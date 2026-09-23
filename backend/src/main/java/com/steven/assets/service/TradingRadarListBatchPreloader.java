package com.steven.assets.service;

import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.repository.TradingRadarListBatchRepository;
import com.steven.assets.dto.TreasuryYieldDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Immutable request-scoped inputs for the authenticated Radar list.  This is deliberately not
 * a cache: it owns only values read during one request and is passed explicitly to the evaluator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingRadarListBatchPreloader {

    public record Target(String code, String market, boolean held) {}

    public record Key(String code, String market) {}

    public record Entry(
            Optional<Stock> stock,
            List<StockPriceHistory> prices,
            Optional<PriceQueryService.LivePrice> live,
            Optional<PriceQueryService.EtfNav> liveNav,
            List<StockDividendHistory> adjustmentEvents,
            DividendEventEvidenceResolver.Resolution dividendEvidence,
            FundamentalAnalysisService.Resolved fundamental,
            BondYieldBetaResolver.Result bondYieldBeta,
            TreasuryYieldDto.RateContext rateContext,
            TradingRadarMarketContextService.FxContext fx,
            TradingRadarMarketFeatureResolver.Evidence marketFeatures,
            LocalDate premiumTargetDate,
            List<EtfNavObservation> premiumObservations,
            java.math.BigDecimal incomeThreshold) {
        /** Compatibility shape for existing adapters; the catalog fallback is immutable. */
        public Entry(Optional<Stock> stock, List<StockPriceHistory> prices,
                     Optional<PriceQueryService.LivePrice> live, Optional<PriceQueryService.EtfNav> liveNav,
                     List<StockDividendHistory> adjustmentEvents,
                     DividendEventEvidenceResolver.Resolution dividendEvidence,
                     FundamentalAnalysisService.Resolved fundamental, BondYieldBetaResolver.Result bondYieldBeta,
                     TreasuryYieldDto.RateContext rateContext, TradingRadarMarketContextService.FxContext fx,
                     TradingRadarMarketFeatureResolver.Evidence marketFeatures, LocalDate premiumTargetDate,
                     List<EtfNavObservation> premiumObservations) {
            this(stock, prices, live, liveNav, adjustmentEvents, dividendEvidence, fundamental, bondYieldBeta,
                    rateContext, fx, marketFeatures, premiumTargetDate, premiumObservations,
                    AssetClassifier.defaultDividendThreshold());
        }

        public Entry {
            stock = stock == null ? Optional.empty() : stock;
            prices = prices == null ? List.of() : List.copyOf(prices);
            live = live == null ? Optional.empty() : live;
            liveNav = liveNav == null ? Optional.empty() : liveNav;
            adjustmentEvents = adjustmentEvents == null ? List.of() : List.copyOf(adjustmentEvents);
            dividendEvidence = dividendEvidence == null ? DividendEventEvidenceResolver.Resolution.MISSING : dividendEvidence;
            fundamental = fundamental == null ? FundamentalAnalysisService.Resolved.unavailable(false) : fundamental;
            bondYieldBeta = bondYieldBeta == null ? BondYieldBetaResolver.Result.notApplicable(null) : bondYieldBeta;
            fx = fx == null ? TradingRadarMarketContextService.FxContext.EMPTY : fx;
            marketFeatures = marketFeatures == null ? TradingRadarMarketFeatureResolver.Evidence.empty(
                    null, null, "清單批次資料不可得") : marketFeatures;
            premiumObservations = premiumObservations == null ? List.of() : List.copyOf(premiumObservations);
            incomeThreshold = Objects.requireNonNullElse(incomeThreshold, AssetClassifier.defaultDividendThreshold());
        }

        static Entry unavailable(String market, Instant decisionInstant) {
            return unavailable(market, decisionInstant, AssetClassifier.defaultDividendThreshold());
        }

        static Entry unavailable(String market, Instant decisionInstant, java.math.BigDecimal incomeThreshold) {
            return new Entry(Optional.empty(), List.of(), Optional.empty(), Optional.empty(), List.of(),
                    DividendEventEvidenceResolver.Resolution.MISSING,
                    FundamentalAnalysisService.Resolved.unavailable(false),
                    BondYieldBetaResolver.Result.notApplicable(null), null,
                    TradingRadarMarketContextService.FxContext.EMPTY,
                    TradingRadarMarketFeatureResolver.Evidence.empty(
                            market, decisionInstant, "清單批次資料不可得"),
                    null, List.of(), incomeThreshold);
        }
    }

    public record Context(Map<Key, Entry> entries, java.math.BigDecimal incomeThreshold) {
        public Context(Map<Key, Entry> entries) {
            this(entries, AssetClassifier.defaultDividendThreshold());
        }

        public Context {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
            incomeThreshold = Objects.requireNonNullElse(incomeThreshold, AssetClassifier.defaultDividendThreshold());
        }

        public Entry entry(String code, String market) {
            return entries.get(new Key(code, market));
        }

        /** Materialize every requested exact pair even when an upstream batch was unavailable. */
        static Context complete(Collection<Target> rawTargets, Context source, Instant decisionInstant) {
            Map<Key, Entry> sourceEntries = source == null ? Map.of() : source.entries();
            java.math.BigDecimal threshold = source == null
                    ? AssetClassifier.defaultDividendThreshold() : source.incomeThreshold();
            Map<Key, Entry> complete = new LinkedHashMap<>();
            for (Target target : canonicalTargets(rawTargets)) {
                Key key = new Key(target.code(), target.market());
                complete.put(key, sourceEntries.getOrDefault(key,
                        Entry.unavailable(target.market(), decisionInstant, threshold)));
            }
            return new Context(complete, threshold);
        }
    }

    private final TradingRadarListBatchRepository batchRepository;
    private final PriceQueryService priceQueryService;
    private final DividendEventEvidenceRepository dividendEvidenceRepository;
    private final FundamentalAnalysisService fundamentalAnalysisService;
    private final StockStyleThresholdProvider stockStyleThresholdProvider;
    private final BondYieldBetaEvidencePort bondYieldBetaEvidencePort;
    private final TreasuryYieldService treasuryYieldService;
    private final TradingRadarMarketContextService marketContextService;
    private final TradingRadarMarketFeaturePort marketFeaturePort;

    public Context preload(Collection<Target> rawTargets, Instant decisionInstant,
                           Map<String, List<LocalDate>> futureSessionsByMarket,
                           Map<String, TradingRadarMarketFeatureResolver.ExpectedSessions> expectedMarketSessions,
                           Map<String, LocalDate> premiumTargetDates,
                           int seriesLimit) {
        List<Target> targets = canonicalTargets(rawTargets);
        if (targets.isEmpty()) return new Context(Map.of());
        List<TradingRadarListBatchRepository.Key> readerKeys = targets.stream()
                .map(target -> new TradingRadarListBatchRepository.Key(target.code(), target.market())).toList();
        Set<TradingRadarListBatchRepository.Key> requestedReaderKeys = Set.copyOf(readerKeys);
        Map<TradingRadarListBatchRepository.Key, List<StockPriceHistory>> prices = exactRows(
                readMap("price-history", () -> batchRepository.findRecentPrices(readerKeys, seriesLimit)),
                requestedReaderKeys,
                (key, row) -> samePair(key, row.getStockCode(), row.getMarket()));
        Map<TradingRadarListBatchRepository.Key, List<StockDividendHistory>> adjustments = exactRows(
                readMap("adjustment-events", () -> batchRepository.findAdjustmentEvents(readerKeys, prices)),
                requestedReaderKeys,
                (key, row) -> samePair(key, row.getStockCode(), row.getMarket()));
        Set<PriceQueryService.PriceKey> priceKeys = targets.stream()
                .map(target -> new PriceQueryService.PriceKey(target.code(), target.market()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<PriceQueryService.PriceKey, List<StockPriceHistory>> priceMap = new LinkedHashMap<>();
        prices.forEach((key, value) -> priceMap.put(new PriceQueryService.PriceKey(key.code(), key.market()), value));
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> lives = exactLives(
                readMap("live-quotes", () -> priceQueryService.getLiveBatch(priceKeys, priceMap)), priceKeys);
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.EtfNav>> navs = exactNavs(
                readMap("live-nav", () -> priceQueryService.getEtfNavBatch(priceKeys)), priceKeys);

        Map<Key, Stock> stocks = exactStocks(
                readMap("stock-master", () -> batchRepository.findStocks(readerKeys)), requestedReaderKeys);
        List<DividendEventEvidenceBatch.Query> dividendQueries = targets.stream().map(target ->
                new DividendEventEvidenceBatch.Query(target.code(), target.market(), decisionInstant,
                        futureSessionsByMarket == null ? List.of()
                                : nullSafeList(futureSessionsByMarket.get(target.market()))))
                .toList();
        Map<Key, DividendEventEvidenceResolver.Resolution> dividends = new LinkedHashMap<>();
        Set<Key> requestedKeys = targets.stream().map(target -> new Key(target.code(), target.market()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (DividendEventEvidenceBatch.Result result : readList("dividend-evidence",
                () -> dividendEvidenceRepository.resolveBatch(dividendQueries))) {
            if (result != null && result.query() != null) {
                Key key = new Key(result.query().code(), result.query().market());
                if (requestedKeys.contains(key) && result.resolution() != null) {
                    dividends.put(key, result.resolution());
                }
            }
        }
        List<FundamentalAnalysisService.BatchQuery> fundamentalQueries = new ArrayList<>();
        Map<Key, FundamentalAnalysisService.BatchQuery> fundamentalKeys = new LinkedHashMap<>();
        Map<Key, TradingRadarAssetProfileResolver.AssetProfile> profiles = new LinkedHashMap<>();
        java.math.BigDecimal incomeThreshold = safeIncomeThreshold();
        for (Target target : targets) {
            Stock stock = stocks.get(new Key(target.code(), target.market()));
            String name = stock == null || stock.getName() == null || stock.getName().isBlank() ? target.code() : stock.getName();
            TradingRadarAssetProfileResolver.AssetProfile profile = safeProfile(
                    stock, target.code(), target.market(), name, incomeThreshold);
            FundamentalAnalysisService.BatchQuery query = new FundamentalAnalysisService.BatchQuery(
                    target.code(), name, target.market(), profile);
            fundamentalQueries.add(query);
            fundamentalKeys.put(new Key(target.code(), target.market()), query);
            profiles.put(new Key(target.code(), target.market()), profile);
        }
        Map<FundamentalAnalysisService.BatchQuery, FundamentalAnalysisService.Resolved> fundamentals = exactValues(
                readMap("fundamental", () -> fundamentalAnalysisService == null ? Map.of()
                        : fundamentalAnalysisService.resolveBatch(fundamentalQueries, decisionInstant)),
                Set.copyOf(fundamentalQueries));
        List<BondYieldBetaResolver.Query> betaQueries = new ArrayList<>();
        Map<Key, BondYieldBetaResolver.Query> betaKeys = new LinkedHashMap<>();
        for (Target target : targets) {
            FundamentalAnalysisService.BatchQuery fundamentalQuery = fundamentalKeys.get(new Key(target.code(), target.market()));
            TradingRadarAssetProfileResolver.AssetProfile profile = fundamentalQuery == null ? null : fundamentalQuery.profile();
            if (profile == null || !profile.bond() || !profile.profileComplete()) continue;
            BondYieldBetaResolver.Query query = BondRateQueryResolver.query(target.code(), target.market(), profile,
                    decisionInstant, RuleParameters.v12Default());
            if (query != null && query.tenor() != null) {
                betaQueries.add(query);
                betaKeys.put(new Key(target.code(), target.market()), query);
            }
        }
        Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> betaResults = exactValues(
                readMap("bond-yield-beta", () -> bondYieldBetaEvidencePort == null ? Map.of()
                        : bondYieldBetaEvidencePort.resolveBatch(List.copyOf(betaQueries))), Set.copyOf(betaQueries));
        Set<String> requestedTenors = betaQueries.stream().map(BondYieldBetaResolver.Query::tenor)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, TreasuryYieldDto.RateContext> ratesByTenor = exactValues(
                readMap("treasury-rates", () -> requestedTenors.isEmpty() || treasuryYieldService == null ? Map.of()
                        : treasuryYieldService.resolveRateContexts(decisionInstant, requestedTenors)), requestedTenors);
        Set<String> currencies = profiles.values().stream().filter(java.util.Objects::nonNull)
                .map(TradingRadarAssetProfileResolver.AssetProfile::underlyingCurrency)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, TradingRadarMarketContextService.FxContext> fxByCurrency = exactValues(
                readMap("fx", () -> marketContextService == null ? Map.of()
                        : marketContextService.resolveFxBatchCachedOnly(currencies, decisionInstant)), currencies);
        Map<String, TradingRadarMarketFeatureResolver.Evidence> featuresByMarket = new LinkedHashMap<>();
        for (String market : targets.stream().map(Target::market).distinct().toList()) {
            if (marketFeaturePort == null) {
                featuresByMarket.put(market, TradingRadarMarketFeatureResolver.Evidence.empty(
                        market, decisionInstant, "市場 feature port 未注入"));
                continue;
            }
            TradingRadarMarketFeatureResolver.ExpectedSessions expected = expectedMarketSessions == null
                    ? TradingRadarMarketFeatureResolver.ExpectedSessions.none()
                    : Objects.requireNonNullElse(expectedMarketSessions.get(market),
                            TradingRadarMarketFeatureResolver.ExpectedSessions.none());
            Map<Instant, TradingRadarMarketFeatureResolver.Evidence> values = readMap(
                    "market-feature", () -> marketFeaturePort.resolveBatch(market, List.of(decisionInstant), expected));
            featuresByMarket.put(market, Objects.requireNonNullElse(values.get(decisionInstant),
                    TradingRadarMarketFeatureResolver.Evidence.empty(
                            market, decisionInstant, "市場 feature 批次讀取失敗")));
        }
        Map<TradingRadarListBatchRepository.Key, List<EtfNavObservation>> premiumObservations = exactRows(
                readMap("etf-nav-history", () -> batchRepository.findEtfNavObservations(readerKeys, decisionInstant)),
                requestedReaderKeys,
                (key, row) -> samePair(key, row.getStockCode(), row.getMarket()));
        Map<Key, Entry> entries = new LinkedHashMap<>();
        for (Target target : targets) {
            Key key = new Key(target.code(), target.market());
            PriceQueryService.PriceKey priceKey = new PriceQueryService.PriceKey(target.code(), target.market());
            TradingRadarListBatchRepository.Key readerKey =
                    new TradingRadarListBatchRepository.Key(target.code(), target.market());
            try {
                TradingRadarAssetProfileResolver.AssetProfile profile = profiles.get(key);
                boolean fundamentalApplicable = profile != null && profile.equity()
                        && profile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.STOCK;
                BondYieldBetaResolver.Query betaKey = betaKeys.get(key);
                String underlyingCurrency = profile == null ? null : profile.underlyingCurrency();
                entries.put(key, new Entry(Optional.ofNullable(stocks.get(key)), prices.getOrDefault(readerKey, List.of()),
                        lives.getOrDefault(priceKey, Optional.empty()), navs.getOrDefault(priceKey, Optional.empty()),
                        adjustments.getOrDefault(readerKey, List.of()), dividends.getOrDefault(key,
                                DividendEventEvidenceResolver.Resolution.MISSING),
                        safeFundamental(fundamentals.get(fundamentalKeys.get(key)), fundamentalApplicable),
                        betaKey == null ? BondYieldBetaResolver.Result.notApplicable(null)
                                : betaResults.getOrDefault(betaKey, BondYieldBetaResolver.Result.notApplicable(null)),
                        betaKey == null ? null : ratesByTenor.get(betaKey.tenor()),
                        underlyingCurrency == null ? TradingRadarMarketContextService.FxContext.EMPTY
                                : fxByCurrency.getOrDefault(underlyingCurrency,
                                        TradingRadarMarketContextService.FxContext.EMPTY),
                        featuresByMarket.getOrDefault(target.market(), TradingRadarMarketFeatureResolver.Evidence.empty(
                                target.market(), decisionInstant, "市場 feature batch 缺少市場")),
                        premiumTargetDates == null ? null : premiumTargetDates.get(target.market()),
                        premiumObservations.getOrDefault(readerKey, List.of()), incomeThreshold));
            } catch (RuntimeException unavailable) {
                logBatchUnavailable("entry-materialization", unavailable);
                entries.put(key, Entry.unavailable(target.market(), decisionInstant, incomeThreshold));
            }
        }
        return Context.complete(targets, new Context(entries, incomeThreshold), decisionInstant);
    }

    private static List<Target> canonicalTargets(Collection<Target> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) return List.of();
        Map<Key, Target> canonical = new LinkedHashMap<>();
        for (Target target : rawTargets) {
            if (target == null || target.code() == null || target.market() == null) continue;
            Key key = new Key(target.code(), target.market());
            Target existing = canonical.get(key);
            canonical.put(key, new Target(target.code(), target.market(),
                    target.held() || existing != null && existing.held()));
        }
        return List.copyOf(canonical.values());
    }

    private <K, V> Map<K, V> readMap(String phase, Supplier<Map<K, V>> reader) {
        try {
            Map<K, V> values = reader.get();
            if (values != null) return values;
            log.warn("交易雷達清單批次不可得：phase={}, result=null", phase);
        } catch (RuntimeException unavailable) {
            logBatchUnavailable(phase, unavailable);
        }
        return Map.of();
    }

    private <T> List<T> readList(String phase, Supplier<List<T>> reader) {
        try {
            List<T> values = reader.get();
            if (values != null) return values;
            log.warn("交易雷達清單批次不可得：phase={}, result=null", phase);
        } catch (RuntimeException unavailable) {
            logBatchUnavailable(phase, unavailable);
        }
        return List.of();
    }

    private static <T> Map<TradingRadarListBatchRepository.Key, List<T>> exactRows(
            Map<TradingRadarListBatchRepository.Key, List<T>> raw,
            Set<TradingRadarListBatchRepository.Key> requested,
            BiPredicate<TradingRadarListBatchRepository.Key, T> matchesIdentity) {
        if (raw == null || raw.isEmpty() || requested == null || requested.isEmpty()) return Map.of();
        Map<TradingRadarListBatchRepository.Key, List<T>> safe = new LinkedHashMap<>();
        for (Map.Entry<TradingRadarListBatchRepository.Key, List<T>> entry : raw.entrySet()) {
            TradingRadarListBatchRepository.Key key = entry.getKey();
            if (key == null || !requested.contains(key)) continue;
            List<T> rows = new ArrayList<>();
            for (T row : nullSafeList(entry.getValue())) {
                if (matchesIdentity.test(key, row)) rows.add(row);
            }
            safe.put(key, List.copyOf(rows));
        }
        return Map.copyOf(safe);
    }

    private static Map<Key, Stock> exactStocks(
            Map<TradingRadarListBatchRepository.Key, Stock> raw,
            Set<TradingRadarListBatchRepository.Key> requested) {
        if (raw == null || raw.isEmpty() || requested == null || requested.isEmpty()) return Map.of();
        Map<Key, Stock> safe = new LinkedHashMap<>();
        for (Map.Entry<TradingRadarListBatchRepository.Key, Stock> entry : raw.entrySet()) {
            TradingRadarListBatchRepository.Key key = entry.getKey();
            Stock stock = entry.getValue();
            if (key != null && stock != null && requested.contains(key)
                    && samePair(key, stock.getCode(), stock.getMarket())) {
                safe.put(new Key(key.code(), key.market()), stock);
            }
        }
        return Map.copyOf(safe);
    }

    private static Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> exactLives(
            Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> raw,
            Set<PriceQueryService.PriceKey> requested) {
        if (raw == null || raw.isEmpty() || requested == null || requested.isEmpty()) return Map.of();
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> safe = new LinkedHashMap<>();
        for (Map.Entry<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> entry : raw.entrySet()) {
            PriceQueryService.PriceKey key = entry.getKey();
            if (key == null || !requested.contains(key)) continue;
            Optional<PriceQueryService.LivePrice> value = entry.getValue();
            safe.put(key, value == null ? Optional.empty() : value.filter(live -> live != null
                    && Objects.equals(key.stockCode(), live.stockCode())
                    && Objects.equals(key.market(), live.market())));
        }
        return Map.copyOf(safe);
    }

    private static Map<PriceQueryService.PriceKey, Optional<PriceQueryService.EtfNav>> exactNavs(
            Map<PriceQueryService.PriceKey, Optional<PriceQueryService.EtfNav>> raw,
            Set<PriceQueryService.PriceKey> requested) {
        if (raw == null || raw.isEmpty() || requested == null || requested.isEmpty()) return Map.of();
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.EtfNav>> safe = new LinkedHashMap<>();
        for (Map.Entry<PriceQueryService.PriceKey, Optional<PriceQueryService.EtfNav>> entry : raw.entrySet()) {
            PriceQueryService.PriceKey key = entry.getKey();
            if (key == null || !requested.contains(key)) continue;
            Optional<PriceQueryService.EtfNav> value = entry.getValue();
            safe.put(key, value == null ? Optional.empty() : value.filter(nav -> nav != null
                    && Objects.equals(key.stockCode(), nav.stockCode())
                    && Objects.equals(key.market(), nav.market())));
        }
        return Map.copyOf(safe);
    }

    private static <K, V> Map<K, V> exactValues(Map<K, V> raw, Set<K> requested) {
        if (raw == null || raw.isEmpty() || requested == null || requested.isEmpty()) return Map.of();
        Map<K, V> safe = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : raw.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && requested.contains(entry.getKey())) {
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(safe);
    }

    private static boolean samePair(TradingRadarListBatchRepository.Key key, String code, String market) {
        return Objects.equals(key.code(), code) && Objects.equals(key.market(), market);
    }

    private java.math.BigDecimal safeIncomeThreshold() {
        try {
            if (stockStyleThresholdProvider == null) return AssetClassifier.defaultDividendThreshold();
            return Objects.requireNonNullElse(stockStyleThresholdProvider.incomeThreshold(),
                    AssetClassifier.defaultDividendThreshold());
        } catch (RuntimeException unavailable) {
            logBatchUnavailable("stock-style-threshold", unavailable);
            return AssetClassifier.defaultDividendThreshold();
        }
    }

    private TradingRadarAssetProfileResolver.AssetProfile safeProfile(
            Stock stock, String code, String market, String name, java.math.BigDecimal incomeThreshold) {
        try {
            TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                    stock, code, market, name, null, incomeThreshold);
            return profile == null ? TradingRadarAssetProfileResolver.AssetProfile.unknown("profile unavailable") : profile;
        } catch (RuntimeException unavailable) {
            logBatchUnavailable("asset-profile", unavailable);
            return TradingRadarAssetProfileResolver.AssetProfile.unknown("profile unavailable");
        }
    }

    private static FundamentalAnalysisService.Resolved safeFundamental(
            FundamentalAnalysisService.Resolved value, boolean applicable) {
        return value == null || value.input() == null || value.snapshot() == null
                ? FundamentalAnalysisService.Resolved.unavailable(applicable) : value;
    }

    private static <T> List<T> nullSafeList(List<T> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(Objects::nonNull).toList();
    }

    private static void logBatchUnavailable(String phase, RuntimeException unavailable) {
        log.warn("交易雷達清單批次不可得：phase={}, error={}", phase,
                unavailable.getClass().getSimpleName());
    }
}
