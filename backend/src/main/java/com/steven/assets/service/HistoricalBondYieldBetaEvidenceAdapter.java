package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.util.MarketZones;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fits per-instrument beta from adjusted closes and immutable Treasury curves.
 * Alignment is by actual NYSE session calendar: a return session accepts only
 * the configured lagged US session, never the merely latest earlier curve row.
 */
@Component
@Transactional(readOnly = true)
public class HistoricalBondYieldBetaEvidenceAdapter implements BondYieldBetaEvidencePort {

    static final String PRICE_BASIS = "CONSERVATIVE_COMPLETED_CLOSE";
    /**
     * Pre-v1.89 rows have no close_source.  This label is an explicit provenance
     * state, not a claim about an exchange/provider; the completed-close timestamp
     * and conservative basis remain the only facts used for as-of gating.
     */
    static final String LEGACY_SOURCE_UNKNOWN = "LEGACY_SOURCE_UNKNOWN";
    static final String LEGACY_PRICE_BASIS = PRICE_BASIS + "+LEGACY_SOURCE_UNKNOWN";
    private static final Set<String> TRUSTED_CLOSE_SOURCES = Set.of(
            "VERIFIED_CLOSE", "TWSE_MI_INDEX", "TPEX_DAILY_CLOSE", "FINMIND_TW_CLOSE",
            "NASDAQ_REDIS_CLOSE", "YAHOO_REDIS_CLOSE");
    static final String FX_BASIS = "CONSERVATIVE_NEXT_MIDNIGHT_TAIPEI";
    static final String FX_PROVIDER = "EXCHANGE_RATE_HISTORY";
    static final String RATE_BASIS_SUFFIX = "+EXACT_US_SESSION_LAG";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime TW_CLOSE_BOUNDARY = LocalTime.of(14, 0);
    private static final LocalTime US_CLOSE_BOUNDARY = LocalTime.of(18, 0);
    private static final int SCALE = 10;

    private record BatchKey(String code, String market) {}

    /** Immutable source snapshot shared by every as-of query in one backtest batch. */
    private record BatchHistory(
            List<StockPriceHistory> prices,
            List<StockDividendHistory> events,
            Map<LocalDate, FxChange> fxChanges,
            List<TreasuryYieldDto.StoredBatch> treasuryBatches) {
        private BatchHistory {
            prices = prices == null ? List.of() : List.copyOf(prices);
            events = events == null ? List.of() : List.copyOf(events);
            fxChanges = fxChanges == null ? Map.of() : Map.copyOf(fxChanges);
            treasuryBatches = treasuryBatches == null ? List.of() : List.copyOf(treasuryBatches);
        }
    }

    private final StockPriceHistoryRepository priceRepository;
    private final StockDividendHistoryRepository dividendRepository;
    private final ExchangeRateHistoryRepository exchangeRateRepository;
    private final DistributionAdjustedPriceService adjustedPriceService;
    private final TreasuryYieldBatchRepository treasuryRepository;
    private final MarketDataService marketDataService;

    public HistoricalBondYieldBetaEvidenceAdapter(
            StockPriceHistoryRepository priceRepository,
            StockDividendHistoryRepository dividendRepository,
            ExchangeRateHistoryRepository exchangeRateRepository,
            DistributionAdjustedPriceService adjustedPriceService,
            TreasuryYieldBatchRepository treasuryRepository,
            MarketDataService marketDataService) {
        this.priceRepository = priceRepository;
        this.dividendRepository = dividendRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.adjustedPriceService = adjustedPriceService;
        this.treasuryRepository = treasuryRepository;
        this.marketDataService = marketDataService;
    }

    @Override
    public List<BondYieldBetaResolver.Sample> load(BondYieldBetaResolver.Query query) {
        return loadEvidence(query).samples();
    }

    /**
     * Backtest hook: source histories are loaded once per (code, market), then
     * each query is truncated to its own decision instant.  The default port
     * implementation resolves one query at a time; this override prevents the
     * historical adapter from re-reading prices, dividends, FX and Treasury for
     * every candidate/horizon cell.
     */
    @Override
    public Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> resolveBatch(
            List<BondYieldBetaResolver.Query> queries) {
        if (queries == null || queries.isEmpty()) return Map.of();
        Map<BatchKey, List<BondYieldBetaResolver.Query>> grouped = new LinkedHashMap<>();
        for (BondYieldBetaResolver.Query query : queries) {
            if (query == null) continue;
            grouped.computeIfAbsent(new BatchKey(query.code(), query.market()), ignored -> new ArrayList<>())
                    .add(query);
        }
        Map<BatchKey, BatchHistory> histories = new LinkedHashMap<>();
        Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> out = new LinkedHashMap<>();
        for (Map.Entry<BatchKey, List<BondYieldBetaResolver.Query>> entry : grouped.entrySet()) {
            BatchKey key = entry.getKey();
            List<BondYieldBetaResolver.Query> group = entry.getValue();
            Instant latestDecision = group.stream().map(BondYieldBetaResolver.Query::decisionInstant)
                    .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
            boolean fxRequired = group.stream()
                    .anyMatch(query -> query.fxControl() == BondYieldBetaResolver.FxControl.REQUIRED);
            BatchHistory history = histories.computeIfAbsent(key,
                    ignored -> loadBatchHistory(key.code(), key.market(), latestDecision, fxRequired));
            for (BondYieldBetaResolver.Query query : group) {
                Evidence evidence = loadEvidence(query, history);
                out.put(query, BondYieldBetaResolver.resolve(query, evidence.samples(), evidence.rateSignal()));
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public Evidence loadEvidence(BondYieldBetaResolver.Query query) {
        if (query == null || query.decisionInstant() == null || query.code() == null
                || query.market() == null || query.tenor() == null || query.signalSpec() == null) {
            return Evidence.empty();
        }
        return loadEvidence(query, loadBatchHistory(query.code(), query.market(),
                query.decisionInstant(), query.fxControl() == BondYieldBetaResolver.FxControl.REQUIRED));
    }

    private Evidence loadEvidence(BondYieldBetaResolver.Query query, BatchHistory history) {
        if (query == null || query.decisionInstant() == null || query.code() == null
                || query.market() == null || query.tenor() == null || query.signalSpec() == null) {
            return Evidence.empty();
        }
        LocalDate decisionDate = query.decisionInstant()
                .atZone(MarketZones.resolve(query.market())).toLocalDate();
        List<StockPriceHistory> prices = history.prices().stream()
                .filter(row -> row != null && row.getTradingDate() != null
                        && !row.getTradingDate().isAfter(decisionDate)
                        && positive(row.getClosePrice()))
                .sorted(Comparator.comparing(StockPriceHistory::getTradingDate))
                .toList();
        if (prices.size() < 2) return Evidence.empty();

        LocalDate from = prices.getFirst().getTradingDate();
        LocalDate to = prices.getLast().getTradingDate();
        List<StockDividendHistory> events = history.events().stream()
                .filter(event -> event != null && event.getExDividendDate() != null
                        && !event.getExDividendDate().isBefore(from)
                        && !event.getExDividendDate().isAfter(to))
                .toList();
        List<StockPriceHistory> desc = new ArrayList<>(prices);
        desc.sort(Comparator.comparing(StockPriceHistory::getTradingDate).reversed());
        List<StockPriceHistory> adjustedDesc = adjustedPriceService
                .adjust(desc, events == null ? List.of() : events).rowsDesc();
        List<StockPriceHistory> adjusted = new ArrayList<>(adjustedDesc);
        adjusted.sort(Comparator.comparing(StockPriceHistory::getTradingDate));

        Map<LocalDate, String> closeProviders = new HashMap<>();
        for (StockPriceHistory row : prices) {
            String source = row.getCloseSource();
            if (!nonBlank(source)) {
                // Historical rows written before close_source migration are still
                // completed closes, but their provider is genuinely unknown.
                closeProviders.put(row.getTradingDate(), LEGACY_SOURCE_UNKNOWN);
            } else if (TRUSTED_CLOSE_SOURCES.contains(source.trim())) {
                // New rows retain the allowlist: an arbitrary label cannot become
                // beta evidence merely by being non-blank.
                closeProviders.put(row.getTradingDate(), source.trim());
            }
        }
        List<TreasuryYieldDto.StoredBatch> batches = history.treasuryBatches();
        List<YieldChange> yieldChanges = yieldChanges(
                batches, query.signalSpec(), query.decisionInstant());
        if (yieldChanges.isEmpty()) return Evidence.empty();
        Map<LocalDate, YieldChange> yieldByDate = new LinkedHashMap<>();
        yieldChanges.forEach(change -> yieldByDate.put(change.date(), change));
        Map<LocalDate, FxChange> fxChanges = query.fxControl() == BondYieldBetaResolver.FxControl.REQUIRED
                ? history.fxChanges() : Map.of();

        List<BondYieldBetaResolver.Sample> samples = new ArrayList<>();
        for (int i = 1; i < adjusted.size(); i++) {
            StockPriceHistory previous = adjusted.get(i - 1);
            StockPriceHistory current = adjusted.get(i);
            LocalDate returnDate = current.getTradingDate();
            LocalDate expectedYieldDate = previousUsTradingSession(returnDate, query.lagSessions());
            YieldChange yield = expectedYieldDate == null ? null : yieldByDate.get(expectedYieldDate);
            if (yield == null || !positive(previous.getClosePrice())
                    || !positive(current.getClosePrice())) continue;
            String currentProvider = closeProviders.get(returnDate);
            String previousProvider = closeProviders.get(previous.getTradingDate());
            if (!nonBlank(currentProvider) || !nonBlank(previousProvider)) continue;
            FxChange fx = query.fxControl() == BondYieldBetaResolver.FxControl.REQUIRED
                    ? fxChanges.get(returnDate) : null;
            if (query.fxControl() == BondYieldBetaResolver.FxControl.REQUIRED && fx == null) continue;

            Instant priceKnownAt = completedCloseInstant(returnDate, query.market());
            if (priceKnownAt.isAfter(query.decisionInstant())) continue;
            BigDecimal returnPct = percentChange(current.getClosePrice(), previous.getClosePrice());
            if (returnPct == null) continue;
            boolean legacyPrice = LEGACY_SOURCE_UNKNOWN.equals(currentProvider)
                    || LEGACY_SOURCE_UNKNOWN.equals(previousProvider);
            BondYieldBetaResolver.NumericObservation priceObservation =
                    new BondYieldBetaResolver.NumericObservation(
                    returnPct, priceKnownAt, priceKnownAt,
                            legacyPrice ? LEGACY_PRICE_BASIS : PRICE_BASIS,
                            legacyPrice ? combinedPriceProvider(previousProvider, currentProvider)
                                    : currentProvider);
            BondYieldBetaResolver.NumericObservation yieldObservation = numeric(
                    yield.effectiveChangePp(), yield);
            BondYieldBetaResolver.NumericObservation fxObservation = fx == null ? null
                    : new BondYieldBetaResolver.NumericObservation(
                            fx.changePct(), fx.knownAt(), fx.knownAt(), FX_BASIS, FX_PROVIDER);
            samples.add(new BondYieldBetaResolver.Sample(
                    returnDate, yield.date(), query.lagSessions(),
                    priceObservation, yieldObservation, fxObservation));
        }

        YieldChange latest = yieldChanges.getLast();
        LocalDate latestCompleteCurveDate = batches == null ? null : batches.stream()
                .filter(HistoricalBondYieldBetaEvidenceAdapter::complete)
                .filter(batch -> !batch.availableAt().isAfter(query.decisionInstant()))
                .map(TreasuryYieldDto.StoredBatch::curveDate)
                .max(Comparator.naturalOrder()).orElse(null);
        // If the newest complete curve cannot form an exact one-session move,
        // do not silently reuse an older shock as today's candidate signal.
        BondYieldBetaResolver.RateSignal signal = !latest.date().equals(latestCompleteCurveDate)
                ? null : new BondYieldBetaResolver.RateSignal(
                latest.date(), numeric(latest.primaryChangePp(), latest),
                latest.shapeChangePp() == null ? null : numeric(latest.shapeChangePp(), latest));
        return new Evidence(List.copyOf(samples), signal);
    }

    private BatchHistory loadBatchHistory(
            String code, String market, Instant latestDecision, boolean fxRequired) {
        if (code == null || market == null || latestDecision == null) return new BatchHistory(
                List.of(), List.of(), Map.of(), List.of());
        List<StockPriceHistory> raw = priceRepository
                .findAllByStockCodeAndMarketOrderByTradingDateAsc(code, market);
        List<StockPriceHistory> prices = raw == null ? List.of() : raw.stream()
                .filter(row -> row != null && row.getTradingDate() != null
                        && positive(row.getClosePrice()))
                .sorted(Comparator.comparing(StockPriceHistory::getTradingDate))
                .toList();
        List<StockDividendHistory> events = List.of();
        if (!prices.isEmpty()) {
            LocalDate from = prices.getFirst().getTradingDate();
            LocalDate to = prices.getLast().getTradingDate();
            List<StockDividendHistory> loaded = dividendRepository.findAdjustmentEvents(
                    code, market, from, to);
            events = loaded == null ? List.of() : loaded.stream()
                    .filter(event -> event != null && event.getExDividendDate() != null)
                    .toList();
        }
        List<TreasuryYieldDto.StoredBatch> batches = treasuryRepository
                .findCompleteSeriesThrough(latestDecision);
        Map<LocalDate, FxChange> fx = fxRequired
                ? fxChanges(exchangeRateRepository.findByCurrencyOrderByRateDateAsc("USD"))
                : Map.of();
        return new BatchHistory(prices, events, fx, batches);
    }

    private List<YieldChange> yieldChanges(
            List<TreasuryYieldDto.StoredBatch> batches,
            BondYieldBetaResolver.RateSignalSpec spec,
            Instant decisionInstant) {
        if (batches == null || batches.size() < 2 || spec == null
                || decisionInstant == null) return List.of();
        List<TreasuryYieldDto.StoredBatch> valid = batches.stream()
                .filter(HistoricalBondYieldBetaEvidenceAdapter::complete)
                // The repository contract is already as-of; keep the adapter
                // defensive so a future revision can never leak through a bad fixture/adapter.
                .filter(batch -> !batch.availableAt().isAfter(decisionInstant))
                .filter(batch -> batch.values().get(spec.primaryTenor()) != null)
                .sorted(Comparator.comparing(TreasuryYieldDto.StoredBatch::curveDate))
                .toList();
        List<YieldChange> out = new ArrayList<>();
        for (int i = 1; i < valid.size(); i++) {
            TreasuryYieldDto.StoredBatch previous = valid.get(i - 1);
            TreasuryYieldDto.StoredBatch current = valid.get(i);
            // A missing middle curve row is not a one-session move. Weekends and
            // statutory holidays are accepted because nextUsTradingSession skips them.
            if (!current.curveDate().equals(nextUsTradingSession(previous.curveDate()))) continue;
            if (!nonBlank(current.provider()) || !current.provider().equals(previous.provider())) continue;
            BigDecimal primary = current.values().get(spec.primaryTenor())
                    .subtract(previous.values().get(spec.primaryTenor()));
            BigDecimal shape = curveShapeChange(previous, current, spec);
            BigDecimal effective = spec.effectiveShock(primary, shape);
            Instant knownAt = later(previous.availableAt(), current.availableAt());
            if (effective == null || knownAt == null || !nonBlank(current.provider())
                    || !nonBlank(current.availabilityBasis())) continue;
            out.add(new YieldChange(current.curveDate(), primary, shape, effective, knownAt,
                    current.provider(), current.availabilityBasis() + RATE_BASIS_SUFFIX));
        }
        return List.copyOf(out);
    }

    private static BigDecimal curveShapeChange(
            TreasuryYieldDto.StoredBatch previous,
            TreasuryYieldDto.StoredBatch current,
            BondYieldBetaResolver.RateSignalSpec spec) {
        if (spec.shapeShortTenor() == null || spec.shapeLongTenor() == null) return null;
        BigDecimal previousShort = previous.values().get(spec.shapeShortTenor());
        BigDecimal previousLong = previous.values().get(spec.shapeLongTenor());
        BigDecimal currentShort = current.values().get(spec.shapeShortTenor());
        BigDecimal currentLong = current.values().get(spec.shapeLongTenor());
        if (previousShort == null || previousLong == null || currentShort == null || currentLong == null) {
            return null;
        }
        return currentLong.subtract(currentShort).subtract(previousLong.subtract(previousShort));
    }

    private BondYieldBetaResolver.NumericObservation numeric(BigDecimal value, YieldChange change) {
        return new BondYieldBetaResolver.NumericObservation(
                value, change.knownAt(), change.knownAt(),
                change.availabilityBasis(), change.provider());
    }

    private LocalDate nextUsTradingSession(LocalDate date) {
        if (date == null) return null;
        LocalDate candidate = date.plusDays(1);
        for (int guard = 0; guard < 14; guard++, candidate = candidate.plusDays(1)) {
            if (marketDataService.isUsTradingDay(candidate)) return candidate;
        }
        return null;
    }

    private LocalDate previousUsTradingSession(LocalDate date, int sessions) {
        if (date == null || sessions < 1) return null;
        LocalDate candidate = date.minusDays(1);
        int remaining = sessions;
        for (int guard = 0; guard < 30; guard++, candidate = candidate.minusDays(1)) {
            if (marketDataService.isUsTradingDay(candidate) && --remaining == 0) return candidate;
        }
        return null;
    }

    private static Map<LocalDate, FxChange> fxChanges(List<ExchangeRateHistory> rows) {
        if (rows == null || rows.size() < 2) return Map.of();
        List<ExchangeRateHistory> ordered = rows.stream()
                .filter(row -> row != null && row.getRateDate() != null
                        && positive(row.getFundValuationRate()))
                .sorted(Comparator.comparing(ExchangeRateHistory::getRateDate))
                .toList();
        Map<LocalDate, FxChange> out = new HashMap<>();
        for (int i = 1; i < ordered.size(); i++) {
            ExchangeRateHistory previous = ordered.get(i - 1);
            ExchangeRateHistory current = ordered.get(i);
            BigDecimal change = percentChange(
                    current.getFundValuationRate(), previous.getFundValuationRate());
            if (change == null) continue;
            Instant knownAt = current.getRateDate().plusDays(1)
                    .atStartOfDay(TAIPEI).toInstant();
            out.put(current.getRateDate(), new FxChange(change, knownAt));
        }
        return Map.copyOf(out);
    }

    private static Instant completedCloseInstant(LocalDate date, String market) {
        if ("美股".equals(market)) return date.atTime(US_CLOSE_BOUNDARY).atZone(NEW_YORK).toInstant();
        return date.atTime(TW_CLOSE_BOUNDARY).atZone(TAIPEI).toInstant();
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (!positive(current) || !positive(previous)) return null;
        return current.divide(previous, SCALE + 4, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static boolean complete(TreasuryYieldDto.StoredBatch batch) {
        if (batch == null || !batch.complete() || batch.curveDate() == null
                || batch.availableAt() == null || batch.values() == null) return false;
        for (String tenor : TreasuryYieldBatchRepository.TENOR_ORDER) {
            BigDecimal value = batch.values().get(tenor);
            if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String combinedPriceProvider(String previousProvider, String currentProvider) {
        if (Objects.equals(previousProvider, currentProvider)) return currentProvider;
        return java.util.stream.Stream.of(previousProvider, currentProvider)
                .filter(HistoricalBondYieldBetaEvidenceAdapter::nonBlank)
                .distinct().sorted().collect(java.util.stream.Collectors.joining("+"));
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private record YieldChange(
            LocalDate date,
            BigDecimal primaryChangePp,
            BigDecimal shapeChangePp,
            BigDecimal effectiveChangePp,
            Instant knownAt,
            String provider,
            String availabilityBasis) {}

    private record FxChange(BigDecimal changePct, Instant knownAt) {}
}
