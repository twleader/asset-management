package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/** Taiwan-only coordinator. One code is persisted before its canonical row is allowed into Redis. */
@Slf4j
@Component
public class TwLiveQuoteDispatcher {
    static final int FUBON_CODES_PER_ROUND = 40;
    private final MarketClock clock;
    private final PriceFetchClient prices;
    private final ObjectProvider<FubonNormalizedQuoteClient> fubonClient;
    private final StockSourceQuery source;
    private final PriceCacheWriter writer;
    private final TwLiveQuoteOutcomeCounters counters;
    private final ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter;
    private final ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooOrderBookFallbackWriter;
    private final boolean fubonEnabled;
    private final boolean fubonLiveEnabled;
    /** Compatibility seam for pre-370 unit fixtures; production always uses the coordinator path. */
    private final TwLiveQuoteProvider legacyProvider;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private int fubonCursor;
    private volatile long fubonSkipUntilNanos;

    @Autowired
    public TwLiveQuoteDispatcher(MarketClock clock, PriceFetchClient prices,
                                 ObjectProvider<FubonNormalizedQuoteClient> fubonClient,
                                 StockSourceQuery source, PriceCacheWriter writer,
                                 TwLiveQuoteOutcomeCounters counters,
                                 ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter,
                                 ObjectProvider<TwYahooOrderBookFallbackRoundWriter> yahooOrderBookFallbackWriter,
                                 @Value("${fubon.enabled:false}") boolean fubonEnabled,
                                 @Value("${fubon.tw-live-quotes-enabled:false}") boolean fubonLiveEnabled) {
        this.clock = clock; this.prices = prices; this.fubonClient = fubonClient; this.source = source;
        this.writer = writer; this.counters = counters; this.fubonEnabled = fubonEnabled;
        this.orderBookWriter = orderBookWriter;
        this.yahooOrderBookFallbackWriter = yahooOrderBookFallbackWriter;
        this.fubonLiveEnabled = fubonLiveEnabled; this.legacyProvider = null;
    }

    /** Existing production-path test seam; real Spring wiring uses the constructor above. */
    TwLiveQuoteDispatcher(MarketClock clock, PriceFetchClient prices,
                          ObjectProvider<FubonNormalizedQuoteClient> fubonClient,
                          StockSourceQuery source, PriceCacheWriter writer,
                          TwLiveQuoteOutcomeCounters counters,
                          boolean fubonEnabled, boolean fubonLiveEnabled) {
        this(clock, prices, fubonClient, source, writer, counters, null, null, fubonEnabled, fubonLiveEnabled);
    }

    /** Existing test seam for the Fubon writer only. */
    TwLiveQuoteDispatcher(MarketClock clock, PriceFetchClient prices,
                          ObjectProvider<FubonNormalizedQuoteClient> fubonClient,
                          StockSourceQuery source, PriceCacheWriter writer,
                          TwLiveQuoteOutcomeCounters counters,
                          ObjectProvider<TwFubonOrderBookRoundWriter> orderBookWriter,
                          boolean fubonEnabled, boolean fubonLiveEnabled) {
        this(clock, prices, fubonClient, source, writer, counters, orderBookWriter, null, fubonEnabled, fubonLiveEnabled);
    }

    TwLiveQuoteDispatcher(MarketClock clock, TwLiveQuoteProvider provider, TwLiveQuoteOutcomeCounters counters) {
        this.clock = clock; this.prices = null; this.fubonClient = null; this.source = null; this.writer = null;
        this.counters = counters; this.orderBookWriter = null; this.yahooOrderBookFallbackWriter = null;
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
        Set<String> pending = new LinkedHashSet<>(codes);
        int succeeded = 0, written = 0, failed = 0;
        FubonNormalizedQuoteClient fubon = fubonEnabled && fubonLiveEnabled && System.nanoTime() >= fubonSkipUntilNanos
                ? fubonClient.getIfAvailable() : null;
        if (fubon != null && !pending.isEmpty()) {
            List<String> fubonCodes = selectFubonCodes(codes);
            FubonNormalizedQuoteClient.BatchResult batch = fubon.fetch(fubonCodes);
            if (isRateLimited(batch)) {
                fubonSkipUntilNanos = Math.max(fubonSkipUntilNanos,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
                log.warn("tw-live provider=FUBON status=RATE_LIMIT_SKIP windowSeconds=60");
            }
            for (var observation : batch.observations()) {
                String code = observation.result().stockCode();
                ApplyResult outcome = persistThenCache(observation.result().withTiming(observation.tradingDate(), observation.providerUpdatedAt()), true);
                if (outcome.dbFailed) { pending.remove(code); failed++; }
                else if (outcome.dbApplied) { pending.remove(code); succeeded++; if (outcome.redisWritten) written++; }
            }
            // This is a non-queued, isolated sink: its slow DB/Redis work cannot retain this
            // dispatcher's generic inFlight guard or trigger an extra provider request.
            submitFubonOrderBooks(batch.orderBooks());
            submitYahooOrderBookFallback(fubonCodes, batch.orderBooks().keySet());
        }
        for (List<String> chunk : chunks(new ArrayList<>(pending), PriceFetchClient.MAX_REQUESTED_CODES)) {
            PriceFetchClient.TwQuoteBatchSummary summary = prices.fetchTwBatch(chunk);
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

    private static boolean isRateLimited(FubonNormalizedQuoteClient.BatchResult batch) {
        if (batch.status() == FubonNormalizedQuoteClient.BatchStatus.RATE_LIMITED) return true;
        return batch.failureReasons().values().stream().anyMatch(reason ->
                "RATE_LIMITED".equals(reason) || "RATE_LIMIT_CIRCUIT_OPEN".equals(reason)
                        || "RATE_LIMIT_BUDGET_EXHAUSTED".equals(reason));
    }

    private ApplyResult persistThenCache(PriceResult raw, boolean fubon) {
        StockSourceQuery.IntradayPersistenceResult db = source.persistIntradayQuote(raw);
        if (db.status() == StockSourceQuery.IntradayPersistenceResult.Status.FAILED) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.DB_FAILED);
            log.warn("tw-live-persistence dbOutcome=DB_FAILED redisOutcome=NOT_ATTEMPTED");
            return ApplyResult.failed();
        }
        boolean applied = db.status() == StockSourceQuery.IntradayPersistenceResult.Status.APPLIED;
        counters.increment(applied ? TwLiveQuoteOutcomeCounters.Outcome.DB_APPLIED : TwLiveQuoteOutcomeCounters.Outcome.DB_STALE_OR_EQUAL);
        if (!applied) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.CANONICAL_REPAIR);
        PriceResult canonical = db.canonical().asPriceResult();
        PriceCacheWriter.CacheWriteOutcome cache = writer.writeTaiwanLive(canonical, "FUBON_INTRADAY".equals(canonical.source()));
        if (cache == PriceCacheWriter.CacheWriteOutcome.WRITTEN) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.REDIS_WRITTEN);
        else if (cache == PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.REDIS_REJECTED);
        else counters.increment(TwLiveQuoteOutcomeCounters.Outcome.REDIS_FAILED);
        log.info("tw-live-persistence dbOutcome={} redisOutcome={} canonicalRepair={}",
                applied ? "DB_APPLIED" : "DB_STALE_OR_EQUAL", cache, !applied);
        return new ApplyResult(applied, cache == PriceCacheWriter.CacheWriteOutcome.WRITTEN, false);
    }

    private synchronized List<String> selectFubonCodes(List<String> all) {
        if (all.size() <= FUBON_CODES_PER_ROUND) return all;
        List<String> selected = new ArrayList<>(FUBON_CODES_PER_ROUND);
        for (int i = 0; i < FUBON_CODES_PER_ROUND; i++) selected.add(all.get((fubonCursor + i) % all.size()));
        fubonCursor = (fubonCursor + FUBON_CODES_PER_ROUND) % all.size();
        return selected;
    }
    private void submitFubonOrderBooks(
            java.util.Map<String, com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient.QuoteDetailResult> snapshots) {
        if (orderBookWriter == null || snapshots == null || snapshots.isEmpty()) return;
        TwFubonOrderBookRoundWriter writer = orderBookWriter.getIfAvailable();
        if (writer != null) writer.submit(snapshots);
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
        return code != null && code.matches("^[0-9]{4,6}[A-Z]?$") && !"0000".equals(code);
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
    static final class Authorization {
        private final TwLiveQuoteBatchResult.MarketState state;
        private Authorization(TwLiveQuoteBatchResult.MarketState state) { this.state = state; }
        boolean marketOpen() { return state == TwLiveQuoteBatchResult.MarketState.OPEN; }
        boolean marketUnknown() { return state == TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN; }
    }
}
