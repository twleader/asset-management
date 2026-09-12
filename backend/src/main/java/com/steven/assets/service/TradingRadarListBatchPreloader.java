package com.steven.assets.service;

import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.repository.TradingRadarListBatchRepository;
import com.steven.assets.dto.TreasuryYieldDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable request-scoped inputs for the authenticated Radar list.  This is deliberately not
 * a cache: it owns only values read during one request and is passed explicitly to the evaluator.
 */
@Service
@RequiredArgsConstructor
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
            List<EtfNavObservation> premiumObservations) {
        public Entry {
            stock = stock == null ? Optional.empty() : stock;
            prices = prices == null ? List.of() : List.copyOf(prices);
            live = live == null ? Optional.empty() : live;
            liveNav = liveNav == null ? Optional.empty() : liveNav;
            adjustmentEvents = adjustmentEvents == null ? List.of() : List.copyOf(adjustmentEvents);
            dividendEvidence = dividendEvidence == null ? DividendEventEvidenceResolver.Resolution.MISSING : dividendEvidence;
            bondYieldBeta = bondYieldBeta == null ? BondYieldBetaResolver.Result.notApplicable(null) : bondYieldBeta;
            fx = fx == null ? TradingRadarMarketContextService.FxContext.EMPTY : fx;
            premiumObservations = premiumObservations == null ? List.of() : List.copyOf(premiumObservations);
        }
    }

    public record Context(Map<Key, Entry> entries) {
        public Context {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
        }

        public Entry entry(String code, String market) {
            return entries.get(new Key(code, market));
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
        List<Target> targets = rawTargets == null ? List.of() : rawTargets.stream()
                .filter(target -> target != null && target.code() != null && target.market() != null)
                .collect(java.util.stream.Collectors.toMap(target -> new Key(target.code(), target.market()),
                        java.util.function.Function.identity(),
                        (left, right) -> new Target(left.code(), left.market(), left.held() || right.held()),
                        LinkedHashMap::new)).values().stream().toList();
        if (targets.isEmpty()) return new Context(Map.of());
        List<TradingRadarListBatchRepository.Key> readerKeys = targets.stream()
                .map(target -> new TradingRadarListBatchRepository.Key(target.code(), target.market())).toList();
        Map<TradingRadarListBatchRepository.Key, List<StockPriceHistory>> prices;
        try {
            prices = batchRepository.findRecentPrices(readerKeys, seriesLimit);
        } catch (RuntimeException unavailable) {
            prices = Map.of();
        }
        Map<TradingRadarListBatchRepository.Key, List<StockDividendHistory>> adjustments;
        try {
            adjustments = batchRepository.findAdjustmentEvents(readerKeys, prices);
        } catch (RuntimeException unavailable) {
            adjustments = Map.of();
        }
        Set<PriceQueryService.PriceKey> priceKeys = targets.stream()
                .map(target -> new PriceQueryService.PriceKey(target.code(), target.market()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<PriceQueryService.PriceKey, List<StockPriceHistory>> priceMap = new LinkedHashMap<>();
        prices.forEach((key, value) -> priceMap.put(new PriceQueryService.PriceKey(key.code(), key.market()), value));
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> lives =
                priceQueryService.getLiveBatch(priceKeys, priceMap);
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.EtfNav>> navs =
                priceQueryService.getEtfNavBatch(priceKeys);

        Map<Key, Stock> stocks = new LinkedHashMap<>();
        try {
            batchRepository.findStocks(readerKeys)
                    .forEach((key, value) -> stocks.put(new Key(key.code(), key.market()), value));
        } catch (RuntimeException unavailable) {
            // Missing master-data evidence is represented as the target code, matching the old
            // single-row resolver's fail-soft behavior.
        }
        List<DividendEventEvidenceBatch.Query> dividendQueries = targets.stream().map(target ->
                new DividendEventEvidenceBatch.Query(target.code(), target.market(), decisionInstant,
                        futureSessionsByMarket == null ? List.of() : futureSessionsByMarket.getOrDefault(target.market(), List.of())))
                .toList();
        Map<Key, DividendEventEvidenceResolver.Resolution> dividends = new LinkedHashMap<>();
        try {
            for (DividendEventEvidenceBatch.Result result : dividendEvidenceRepository.resolveBatch(dividendQueries)) {
                if (result != null && result.query() != null) {
                    dividends.put(new Key(result.query().code(), result.query().market()), result.resolution());
                }
            }
        } catch (RuntimeException unavailable) {
            // Preserve MISSING evidence for every row instead of failing the whole list.
        }
        List<FundamentalAnalysisService.BatchQuery> fundamentalQueries = new ArrayList<>();
        Map<Key, FundamentalAnalysisService.BatchQuery> fundamentalKeys = new LinkedHashMap<>();
        Map<Key, TradingRadarAssetProfileResolver.AssetProfile> profiles = new LinkedHashMap<>();
        for (Target target : targets) {
            Stock stock = stocks.get(new Key(target.code(), target.market()));
            String name = stock == null || stock.getName() == null || stock.getName().isBlank() ? target.code() : stock.getName();
            TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                    stock, target.code(), target.market(), name, null,
                    stockStyleThresholdProvider == null ? AssetClassifier.defaultDividendThreshold()
                            : stockStyleThresholdProvider.incomeThreshold());
            FundamentalAnalysisService.BatchQuery query = new FundamentalAnalysisService.BatchQuery(
                    target.code(), name, target.market(), profile);
            fundamentalQueries.add(query);
            fundamentalKeys.put(new Key(target.code(), target.market()), query);
            profiles.put(new Key(target.code(), target.market()), profile);
        }
        Map<FundamentalAnalysisService.BatchQuery, FundamentalAnalysisService.Resolved> fundamentals;
        try {
            fundamentals = fundamentalAnalysisService.resolveBatch(fundamentalQueries, decisionInstant);
        } catch (RuntimeException unavailable) {
            fundamentals = Map.of();
        }
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
        Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> betaResults;
        try {
            betaResults = bondYieldBetaEvidencePort == null ? Map.of()
                    : bondYieldBetaEvidencePort.resolveBatch(List.copyOf(betaQueries));
        } catch (RuntimeException unavailable) {
            betaResults = Map.of();
        }
        Set<String> requestedTenors = betaQueries.stream().map(BondYieldBetaResolver.Query::tenor)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, TreasuryYieldDto.RateContext> ratesByTenor;
        try {
            ratesByTenor = requestedTenors.isEmpty() ? Map.of()
                    : treasuryYieldService.resolveRateContexts(decisionInstant, requestedTenors);
        } catch (RuntimeException unavailable) {
            ratesByTenor = Map.of();
        }
        Set<String> currencies = profiles.values().stream().filter(java.util.Objects::nonNull)
                .map(TradingRadarAssetProfileResolver.AssetProfile::underlyingCurrency)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, TradingRadarMarketContextService.FxContext> fxByCurrency =
                marketContextService == null ? Map.of()
                        : marketContextService.resolveFxBatchCachedOnly(currencies, decisionInstant);
        Map<String, TradingRadarMarketFeatureResolver.Evidence> featuresByMarket = new LinkedHashMap<>();
        for (String market : targets.stream().map(Target::market).distinct().toList()) {
            if (marketFeaturePort == null) {
                featuresByMarket.put(market, TradingRadarMarketFeatureResolver.Evidence.empty(
                        market, decisionInstant, "市場 feature port 未注入"));
                continue;
            }
            try {
                TradingRadarMarketFeatureResolver.ExpectedSessions expected = expectedMarketSessions == null
                        ? TradingRadarMarketFeatureResolver.ExpectedSessions.none()
                        : expectedMarketSessions.getOrDefault(market,
                                TradingRadarMarketFeatureResolver.ExpectedSessions.none());
                featuresByMarket.put(market, marketFeaturePort.resolveBatch(
                        market, List.of(decisionInstant), expected).getOrDefault(decisionInstant,
                        TradingRadarMarketFeatureResolver.Evidence.empty(market, decisionInstant,
                                "市場 feature batch 缺少決策時點")));
            } catch (RuntimeException unavailable) {
                featuresByMarket.put(market, TradingRadarMarketFeatureResolver.Evidence.empty(
                        market, decisionInstant, "市場 feature 批次讀取失敗"));
            }
        }
        Map<TradingRadarListBatchRepository.Key, List<EtfNavObservation>> premiumObservations;
        try {
            premiumObservations = batchRepository.findEtfNavObservations(readerKeys, decisionInstant);
        } catch (RuntimeException unavailable) {
            premiumObservations = Map.of();
        }
        Map<Key, Entry> entries = new LinkedHashMap<>();
        for (Target target : targets) {
            Key key = new Key(target.code(), target.market());
            PriceQueryService.PriceKey priceKey = new PriceQueryService.PriceKey(target.code(), target.market());
            TradingRadarListBatchRepository.Key readerKey =
                    new TradingRadarListBatchRepository.Key(target.code(), target.market());
            entries.put(key, new Entry(Optional.ofNullable(stocks.get(key)), prices.getOrDefault(readerKey, List.of()),
                    lives.getOrDefault(priceKey, Optional.empty()), navs.getOrDefault(priceKey, Optional.empty()),
                    adjustments.getOrDefault(readerKey, List.of()), dividends.getOrDefault(key,
                            DividendEventEvidenceResolver.Resolution.MISSING),
                    fundamentals.getOrDefault(fundamentalKeys.get(key), FundamentalAnalysisService.Resolved.unavailable(false)),
                    betaResults.getOrDefault(betaKeys.get(key), BondYieldBetaResolver.Result.notApplicable(null)),
                    betaKeys.containsKey(key) ? ratesByTenor.get(betaKeys.get(key).tenor()) : null,
                    fxByCurrency.getOrDefault(profiles.get(key) == null ? null
                            : profiles.get(key).underlyingCurrency(), TradingRadarMarketContextService.FxContext.EMPTY),
                    featuresByMarket.getOrDefault(target.market(), TradingRadarMarketFeatureResolver.Evidence.empty(
                            target.market(), decisionInstant, "市場 feature batch 缺少市場")),
                    premiumTargetDates == null ? null : premiumTargetDates.get(target.market()),
                    premiumObservations.getOrDefault(readerKey, List.of())));
        }
        return new Context(entries);
    }
}
