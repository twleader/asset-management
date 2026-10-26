package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.FubonMarketData;
import com.steven.assets.externalmaterials.model.marketdata.FubonResponseData;
import com.steven.assets.externalmaterials.model.marketdata.NormalizedQuoteData;
import com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult;
import com.steven.assets.externalmaterials.model.marketdata.PriceData;
import com.steven.assets.externalmaterials.model.marketdata.PriceProjectionData;
import com.steven.assets.externalmaterials.model.marketdata.ProviderTimedPriceObservation;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;
import com.steven.assets.externalmaterials.model.marketdata.StockMarketData;
import com.steven.assets.externalmaterials.model.marketdata.TaiwanRadarRules;
import com.steven.assets.externalmaterials.service.port.FubonNormalizedQuotePort;
import com.steven.assets.externalmaterials.service.port.FubonResponseCachePort;
import com.steven.assets.externalmaterials.service.port.FubonResponseRepository;
import com.steven.assets.externalmaterials.service.port.LivePriceFetchPort;
import com.steven.assets.externalmaterials.service.port.PriceProjectionPort;
import com.steven.assets.externalmaterials.service.port.StockMarketRepository;
import com.steven.assets.externalmaterials.service.port.StockUniversePort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Taiwan-only coordinator. One code is persisted before its canonical row is allowed into Redis. */
@Slf4j
@Component
public class TwLiveQuoteDispatcher {
    static final int FUBON_CODES_PER_ROUND = 40;
    private final MarketClock clock;
    private final LivePriceFetchPort prices;
    private final ObjectProvider<FubonNormalizedQuotePort> fubonClient;
    private final StockMarketRepository source;
    private final StockUniversePort universe;
    private final PriceProjectionPort writer;
    private final TwLiveQuoteOutcomeCounters counters;
    private final ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter;
    private final ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooOrderBookFallbackWriter;
    private final FubonResponseRepository responseStore;
    private final FubonResponseCachePort responseCache;
    private final boolean fubonEnabled;
    private final boolean fubonLiveEnabled;
    /** Compatibility seam for pre-370 unit fixtures; production always uses the coordinator path. */
    private final TwLiveQuoteProvider legacyProvider;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private int fubonCursor;
    private int snapshotCursor;
    private FubonStockPushSubscriptionManager pushManager;
    private java.util.function.LongSupplier monotonic = System::nanoTime;
    private final java.util.Map<String, Long> snapshotAttemptedAt = new java.util.HashMap<>();
    private final java.util.Map<String, String> snapshotRecoveryIdentity = new java.util.HashMap<>();
    @Autowired
    void configurePushPrimary(FubonStockPushSubscriptionManager pushManager) { this.pushManager = pushManager; }

    void useMonotonic(java.util.function.LongSupplier monotonic) { this.monotonic = monotonic; }
    private volatile long fubonSkipUntilNanos;

    @Autowired
    public TwLiveQuoteDispatcher(MarketClock clock, LivePriceFetchPort prices,
                                 ObjectProvider<FubonNormalizedQuotePort> fubonClient,
                                 StockMarketRepository source, StockUniversePort universe, PriceProjectionPort writer,
                                 TwLiveQuoteOutcomeCounters counters,
                                 ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter,
                                 ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooOrderBookFallbackWriter,
                                 FubonResponseRepository responseStore,
                                 FubonResponseCachePort responseCache,
                                 @Value("${fubon.enabled:false}") boolean fubonEnabled,
                                 @Value("${fubon.tw-live-quotes-enabled:false}") boolean fubonLiveEnabled) {
        this.clock = clock; this.prices = prices; this.fubonClient = fubonClient; this.source = source;
        this.universe = universe;
        this.writer = writer; this.counters = counters; this.fubonEnabled = fubonEnabled;
        this.orderBookWriter = orderBookWriter;
        this.yahooOrderBookFallbackWriter = yahooOrderBookFallbackWriter;
        this.responseStore = responseStore;
        this.responseCache = responseCache;
        this.fubonLiveEnabled = fubonLiveEnabled; this.legacyProvider = null;
    }

    /** Existing production-path test seam; real Spring wiring uses the constructor above. */
    TwLiveQuoteDispatcher(MarketClock clock, LivePriceFetchPort prices,
                          ObjectProvider<FubonNormalizedQuotePort> fubonClient,
                          StockMarketRepository source, StockUniversePort universe, PriceProjectionPort writer,
                          TwLiveQuoteOutcomeCounters counters,
                          boolean fubonEnabled, boolean fubonLiveEnabled) {
        this(clock, prices, fubonClient, source, universe, writer, counters, null, null, null, null,
                fubonEnabled, fubonLiveEnabled);
    }

    /** Existing test seam for the Fubon writer only. */
    TwLiveQuoteDispatcher(MarketClock clock, LivePriceFetchPort prices,
                          ObjectProvider<FubonNormalizedQuotePort> fubonClient,
                          StockMarketRepository source, StockUniversePort universe, PriceProjectionPort writer,
                          TwLiveQuoteOutcomeCounters counters,
                          ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter,
                          boolean fubonEnabled, boolean fubonLiveEnabled) {
        this(clock, prices, fubonClient, source, universe, writer, counters, orderBookWriter, null, null, null,
                fubonEnabled, fubonLiveEnabled);
    }

    /**
     * Pre-Task-380 test seam.  Spring always selects the injected
     * coordinator constructor above; this overload deliberately carries no response store.
     */
    TwLiveQuoteDispatcher(MarketClock clock, LivePriceFetchPort prices,
                          ObjectProvider<FubonNormalizedQuotePort> fubonClient,
                          StockMarketRepository source, StockUniversePort universe, PriceProjectionPort writer,
                          TwLiveQuoteOutcomeCounters counters,
                          ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter,
                          ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooOrderBookFallbackWriter,
                          boolean fubonEnabled, boolean fubonLiveEnabled) {
        this(clock, prices, fubonClient, source, universe, writer, counters, orderBookWriter,
                yahooOrderBookFallbackWriter, null, null, fubonEnabled, fubonLiveEnabled);
    }

    /** Task 380 test seam with the response store/cache enabled but no background book workers. */
    TwLiveQuoteDispatcher(MarketClock clock, LivePriceFetchPort prices,
                          ObjectProvider<FubonNormalizedQuotePort> fubonClient,
                          StockMarketRepository source, StockUniversePort universe, PriceProjectionPort writer,
                          TwLiveQuoteOutcomeCounters counters,
                          FubonResponseRepository responseStore, FubonResponseCachePort responseCache,
                          boolean fubonEnabled, boolean fubonLiveEnabled) {
        this(clock, prices, fubonClient, source, universe, writer, counters, null, null, responseStore, responseCache,
                fubonEnabled, fubonLiveEnabled);
    }

    TwLiveQuoteDispatcher(MarketClock clock, TwLiveQuoteProvider provider, TwLiveQuoteOutcomeCounters counters) {
        this.clock = clock; this.prices = null; this.fubonClient = null; this.source = null; this.universe = null; this.writer = null;
        this.counters = counters; this.orderBookWriter = null; this.yahooOrderBookFallbackWriter = null;
        this.responseStore = null; this.responseCache = null;
        this.fubonEnabled = false; this.fubonLiveEnabled = false; this.legacyProvider = provider;
    }

    public TwLiveQuoteBatchResult refresh(Set<String> rawCodes) { return refresh(rawCodes, authorize()); }

    Authorization authorize() {
        try {
            Optional<Boolean> known = clock.isTwMarketOpenKnown();
            if (known.isEmpty()) { counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_UNKNOWN); return new Authorization(TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN); }
            if (!known.get()) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_CLOSED);
            return new Authorization(known.get() ? TwLiveQuoteBatchResult.MarketState.OPEN : TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED);
        } catch (Exception ex) { counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_UNKNOWN); return new Authorization(TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN); }
    }

    TwLiveQuoteBatchResult refresh(Set<String> rawCodes, Authorization authorization) {
        List<String> codes = normalizedCodes(rawCodes);
        if (authorization.state != TwLiveQuoteBatchResult.MarketState.OPEN) {
            return authorization.state == TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED
                    ? TwLiveQuoteBatchResult.closed(codes.size()) : TwLiveQuoteBatchResult.unknown(codes.size());
        }
        if (!inFlight.compareAndSet(false, true)) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.IN_FLIGHT_SKIPPED);
            log.info("tw-live-round outcome=IN_FLIGHT_SKIPPED requested={} providerCalls=0", codes.size());
            return TwLiveQuoteBatchResult.inFlightSkipped(codes.size());
        }
        try { return legacyProvider != null ? legacyProvider.refresh(new LinkedHashSet<>(codes), true) : run(codes); }
        finally { inFlight.set(false); }
    }

    private TwLiveQuoteBatchResult run(List<String> codes) {
        List<String> effectiveCodes = effectiveRadarCodes(codes);
        if (effectiveCodes.isEmpty()) {
            // A collector failure, no radar rows, or no intersection is deliberately terminal for
            // this LIVE round.  Do not resurrect a generic MIS/Yahoo universe from any other source.
            log.info("tw-live-round outcome=RADAR_EMPTY_OR_UNAVAILABLE requested={} providerCalls=0", codes.size());
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.SUCCESS);
            return TwLiveQuoteBatchResult.open(codes.size(), 0, 0, 0);
        }
        boolean pushPrimary = pushManager != null && pushManager.pushEnabled();
        snapshotAttemptedAt.keySet().retainAll(effectiveCodes);
        snapshotRecoveryIdentity.keySet().retainAll(effectiveCodes);
        List<String> bookCandidates = pushPrimary ? selectFubonCodes(effectiveCodes) : List.of();
        List<String> priceCandidates = pushPrimary ? selectSnapshotCodes(effectiveCodes) : effectiveCodes;
        Set<String> pending = new LinkedHashSet<>(priceCandidates);
        Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult> validRoundBooks = Map.of();
        int succeeded = 0, written = 0, failed = 0;
        boolean bookRoundAllowed = true;
        FubonNormalizedQuotePort fubon = fubonEnabled && fubonLiveEnabled && System.nanoTime() >= fubonSkipUntilNanos
                ? fubonClient.getIfAvailable() : null;
        if (fubon != null && !pending.isEmpty()) {
            List<String> fubonCodes = pushPrimary ? priceCandidates : selectFubonCodes(effectiveCodes);
            NormalizedQuoteData.BatchResult batch = fubon.fetch(fubonCodes);
            if (isRateLimited(batch)) {
                fubonSkipUntilNanos = Math.max(fubonSkipUntilNanos,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
                log.warn("tw-live provider=FUBON status=RATE_LIMIT_SKIP windowSeconds=60");
            }
            FubonEnvelopeOutcome envelopeOutcome = persistFubonEnvelope(batch);
            if (envelopeOutcome == FubonEnvelopeOutcome.STORE_FAILED) bookRoundAllowed = false;
            // Production only accepts Fubon prices/books after its full normalized response became
            // DB-canonical.  The null-store branch is retained strictly for pre-Task-380 unit seams.
            boolean processFubon = responseStore == null || envelopeOutcome == FubonEnvelopeOutcome.STORED;
            if (processFubon) {
                if (batch != null) {
                    for (var observation : batch.observations() == null
                            ? List.<ProviderTimedPriceObservation>of() : batch.observations()) {
                        String code = observation.result().stockCode();
                        ApplyResult outcome = persistThenCache(observation.result().withTiming(observation.tradingDate(), observation.providerUpdatedAt()), true);
                        if (outcome.dbFailed) { pending.remove(code); failed++; }
                        else if (outcome.dbApplied) { pending.remove(code); succeeded++; if (outcome.redisWritten) written++; }
                    }
                    // This is a non-queued, isolated sink: its slow DB/Redis work cannot retain this
                    // dispatcher's generic inFlight guard or trigger an extra provider request.
                    Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult> books =
                            batch.orderBooks() == null ? Map.of() : batch.orderBooks();
                    Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult> selectedBooks = onlySelectedBooks(books, fubonCodes);
                    validRoundBooks = selectedBooks;
                    submitFubonOrderBooks(selectedBooks);
                    if (!pushPrimary) submitYahooOrderBookFallback(fubonCodes, selectedBooks.keySet());
                }
            } else if (envelopeOutcome == FubonEnvelopeOutcome.NO_ENVELOPE) {
                // A transport/shape failure has no usable Fubon data but does not make Yahoo's
                // independent, same-round book fallback unsafe.  A DB transaction failure is
                // intentionally different: it suppresses both book workers for this batch.
                if (!pushPrimary) submitYahooOrderBookFallback(fubonCodes, Set.of());
            }
        }
        // Books remain independently updated while healthy push suppresses *all* price providers.
        if (pushPrimary && bookRoundAllowed) submitYahooOrderBookFallback(bookCandidates, validRoundBooks.keySet());
        for (List<String> chunk : chunks(new ArrayList<>(pending), LivePriceFetchPort.MAX_REQUESTED_CODES)) {
            PriceData.TwQuoteBatchSummary summary = prices.fetchTwBatch(chunk);
            for (var entry : summary.resolved().entrySet()) {
                ApplyResult outcome = persistThenCache(entry.getValue(), false);
                if (outcome.dbFailed) { pending.remove(entry.getKey()); failed++; }
                else if (outcome.dbApplied) { pending.remove(entry.getKey()); succeeded++; if (outcome.redisWritten) written++; }
            }
        }
        for (String code : List.copyOf(pending)) {
            Optional<PriceResult> yahoo = prices.getYahooTwLivePrice(code);
            if (yahoo.isEmpty()) { failed++; continue; }
            ApplyResult outcome = persistThenCache(yahoo.get(), false);
            if (outcome.dbApplied) { succeeded++; if (outcome.redisWritten) written++; }
            else failed++;
        }
        counters.increment(failed == 0 ? TwLiveQuoteOutcomeCounters.Outcome.SUCCESS : TwLiveQuoteOutcomeCounters.Outcome.PARTIAL_FAILURE);
        return TwLiveQuoteBatchResult.open(codes.size(), succeeded, written, failed);
    }

    /** The sole producer universe: input ∩ per-owner current Taiwan trading radar, excluding 0000. */
    private List<String> effectiveRadarCodes(List<String> input) {
        if (source == null) return List.of();
        try {
            Set<String> radarRaw = new LinkedHashSet<>();
            universe.collectTwRadarCodes(radarRaw);
            Set<String> radar = new HashSet<>(normalizedCodes(radarRaw));
            return input.stream().filter(TwLiveQuoteDispatcher::isTaiwanCode)
                    .filter(radar::contains).toList();
        } catch (Exception failure) {
            log.warn("tw-live-round outcome=RADAR_COLLECT_FAILED providerCalls=0");
            return List.of();
        }
    }

    /** One full validated batch must be durable before it can influence canonical price or book paths. */
    private FubonEnvelopeOutcome persistFubonEnvelope(NormalizedQuoteData.BatchResult batch) {
        if (responseStore == null) return FubonEnvelopeOutcome.STORED;
        if (batch == null || batch.envelope() == null) return FubonEnvelopeOutcome.NO_ENVELOPE;
        FubonResponseData.PersistResult persisted = responseStore.persist(batch.envelope(), clock.instant());
        if (persisted.status() != FubonResponseData.WriteStatus.STORED) {
            log.warn("tw-live provider=FUBON responseStore=FAILED priceAndBook=SKIPPED");
            return FubonEnvelopeOutcome.STORE_FAILED;
        }
        if (responseCache != null) {
            for (FubonResponseData.CanonicalResponse row : persisted.canonicalRows()) {
                responseCache.writeStrictNewer(row); // DB remains canonical if this best-effort mirror fails.
            }
        }
        return FubonEnvelopeOutcome.STORED;
    }

    private static boolean isRateLimited(NormalizedQuoteData.BatchResult batch) {
        if (batch == null) return false;
        if (batch.status() == NormalizedQuoteData.BatchStatus.RATE_LIMITED) return true;
        return (batch.failureReasons() == null ? Map.<String, String>of() : batch.failureReasons()).values().stream().anyMatch(reason ->
                "RATE_LIMITED".equals(reason) || "RATE_LIMIT_CIRCUIT_OPEN".equals(reason)
                        || "RATE_LIMIT_BUDGET_EXHAUSTED".equals(reason));
    }

    private ApplyResult persistThenCache(PriceResult raw, boolean fubon) {
        StockMarketData.IntradayPersistenceResult db = source.persistIntradayQuote(raw);
        if (db.status() == StockMarketData.IntradayPersistenceResult.Status.FAILED) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.DB_FAILED);
            log.warn("tw-live-persistence dbOutcome=DB_FAILED redisOutcome=NOT_ATTEMPTED");
            return ApplyResult.failed();
        }
        boolean applied = db.status() == StockMarketData.IntradayPersistenceResult.Status.APPLIED;
        counters.increment(applied ? TwLiveQuoteOutcomeCounters.Outcome.DB_APPLIED : TwLiveQuoteOutcomeCounters.Outcome.DB_STALE_OR_EQUAL);
        if (!applied) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.CANONICAL_REPAIR);
        PriceResult canonical = db.canonical().asPriceResult();
        PriceProjectionData.CacheWriteOutcome cache = writer.writeTaiwanLive(canonical, "FUBON_INTRADAY".equals(canonical.source()) || FubonMarketData.STOCK_SOURCE.equals(canonical.source()));
        if (cache == PriceProjectionData.CacheWriteOutcome.WRITTEN) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.REDIS_WRITTEN);
        else if (cache == PriceProjectionData.CacheWriteOutcome.REJECTED_STALE) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.REDIS_REJECTED);
        else counters.increment(TwLiveQuoteOutcomeCounters.Outcome.REDIS_FAILED);
        log.info("tw-live-persistence dbOutcome={} redisOutcome={} canonicalRepair={}",
                applied ? "DB_APPLIED" : "DB_STALE_OR_EQUAL", cache, !applied);
        return new ApplyResult(applied, cache == PriceProjectionData.CacheWriteOutcome.WRITTEN, false);
    }

    /** Fair bounded recovery; failed requests also debit the per-symbol monotonic cooldown. */
    private List<String> selectSnapshotCodes(List<String> all) {
        List<String> selected = new ArrayList<>(FUBON_CODES_PER_ROUND);
        long now = monotonic.getAsLong();
        int last = snapshotCursor;
        for (int visited = 0; visited < all.size() && selected.size() < FUBON_CODES_PER_ROUND; visited++) {
            int index = (snapshotCursor + visited) % all.size();
            last = index + 1;
            String code = all.get(index);
            String recoveryIdentity = pushManager.recoveryIdentity();
            boolean initialized = snapshotRecoveryIdentity.containsKey(code)
                    && java.util.Objects.equals(recoveryIdentity, snapshotRecoveryIdentity.get(code));
            if (pushManager.isPricePushHealthy(code) && initialized) continue;
            Long attemptedAt = snapshotAttemptedAt.get(code);
            if (attemptedAt != null && now - attemptedAt < TimeUnit.SECONDS.toNanos(60)) continue;
            snapshotAttemptedAt.put(code, now);
            snapshotRecoveryIdentity.put(code, recoveryIdentity);
            selected.add(code);
        }
        snapshotCursor = last % all.size();
        return List.copyOf(selected);
    }

    private synchronized List<String> selectFubonCodes(List<String> all) {
        if (all.size() <= FUBON_CODES_PER_ROUND) return all;
        List<String> selected = new ArrayList<>(FUBON_CODES_PER_ROUND);
        for (int i = 0; i < FUBON_CODES_PER_ROUND; i++) selected.add(all.get((fubonCursor + i) % all.size()));
        fubonCursor = (fubonCursor + FUBON_CODES_PER_ROUND) % all.size();
        return selected;
    }
    private void submitFubonOrderBooks(
            java.util.Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult> snapshots) {
        if (orderBookWriter == null || snapshots == null || snapshots.isEmpty()) return;
        TwFubonOrderBookRoundWriter writer = orderBookWriter.getIfAvailable();
        if (writer != null) writer.submit(snapshots);
    }

    private static Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult>
    onlySelectedBooks(Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult> snapshots,
                      List<String> selected) {
        if (snapshots == null || snapshots.isEmpty() || selected == null || selected.isEmpty()) return Map.of();
        Map<String, com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData.QuoteDetailResult> result =
                new java.util.LinkedHashMap<>();
        for (String code : selected) {
            var snapshot = snapshots.get(code);
            if (snapshot != null) result.put(code, snapshot);
        }
        return Map.copyOf(result);
    }
    /** A candidate is decided only by this round's selected Fubon result, never by API/DB/Redis state. */
    private void submitYahooOrderBookFallback(List<String> selectedFubonCodes, Set<String> validFubonBookCodes) {
        if (yahooOrderBookFallbackWriter == null || selectedFubonCodes == null || selectedFubonCodes.isEmpty()) return;
        List<String> candidates = selectedFubonCodes.stream()
                .filter(TwLiveQuoteDispatcher::isTaiwanCode)
                .filter(code -> validFubonBookCodes == null || !validFubonBookCodes.contains(code))
                .toList();
        if (candidates.isEmpty()) return;
        TwYahooOrderBookFallbackRoundWriter fallback = yahooOrderBookFallbackWriter.getIfAvailable();
        if (fallback != null) fallback.submit(candidates);
    }
    private static boolean isTaiwanCode(String code) {
        return TaiwanRadarRules.isTaiwanRadarCode(code);
    }
    private static List<String> normalizedCodes(Collection<String> raw) {
        TreeSet<String> sorted = new TreeSet<>();
        if (raw != null) for (String code : raw) {
            if (code == null) continue;
            String normalized = code.trim().toUpperCase();
            if (!normalized.isBlank() && !"0000".equals(normalized)) sorted.add(normalized);
        }
        return List.copyOf(sorted);
    }
    private static <T> List<List<T>> chunks(List<T> values, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i += size) result.add(values.subList(i, Math.min(values.size(), i + size)));
        return result;
    }
    private record ApplyResult(boolean dbApplied, boolean redisWritten, boolean dbFailed) { static ApplyResult failed() { return new ApplyResult(false, false, true); } }
    private enum FubonEnvelopeOutcome { STORED, NO_ENVELOPE, STORE_FAILED }
    static final class Authorization {
        private final TwLiveQuoteBatchResult.MarketState state;
        private Authorization(TwLiveQuoteBatchResult.MarketState state) { this.state = state; }
        boolean marketOpen() { return state == TwLiveQuoteBatchResult.MarketState.OPEN; }
        boolean marketUnknown() { return state == TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN; }
    }
}
