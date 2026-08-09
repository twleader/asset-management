package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingRadarV13PromotionRegistryTest {

    @Test
    void missingHoldoutOrAnyRequiredHorizonAlwaysResolvesV12() {
        var key = key(RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarV13PromotionRegistry.Track.SHORT, "GENERAL");
        var onlyFiveDay = Map.of(5, passingEvidence(5, 200, 8));
        var registry = registry(key, onlyFiveDay);

        assertThat(registry.isPromoted(key)).isFalse();
        assertThat(registry.resolve(RadarBacktestExecution.TW_MARKET,
                RadarBacktestExecution.InstrumentKind.STOCK, "GENERAL",
                TradingRadarV13PromotionRegistry.Track.SHORT).ruleVersion())
                .isEqualTo(RuleParameters.V12_VERSION);
        assertThat(registry.decision(key).reason()).contains("20:MISSING_HOLDOUT");
    }

    @Test
    void holdoutWithoutPracticalAdvantageCannotPromote() {
        var key = key(RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarV13PromotionRegistry.Track.SHORT, "GENERAL");
        var weak = new TradingRadarV13PromotionRegistry.HorizonEvidence(5,
                holdout(200, 8, "0.49", "0.01", "0", "0.50"), passingFolds());
        var registry = registry(key, Map.of(5, weak, 20, passingEvidence(20, 200, 8)));

        assertThat(registry.resolve(key).ruleVersion()).isEqualTo(RuleParameters.V12_VERSION);
        assertThat(registry.decision(key).horizons().get(5).reason())
                .isEqualTo("NO_PRACTICAL_HOLDOUT_ADVANTAGE");
        assertThat(registry.decision(key).horizons().get(5).returnPracticalDeltaPct())
                .isEqualByComparingTo("0.50");
    }

    @Test
    void generalAndSpecializedSampleFloorsAreAppliedSeparately() {
        var general = key(RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarV13PromotionRegistry.Track.SHORT, "GENERAL");
        var generalRegistry = registry(general, Map.of(
                5, passingEvidence(5, 199, 8),
                20, passingEvidence(20, 199, 8)));
        assertThat(generalRegistry.resolve(general).ruleVersion()).isEqualTo(RuleParameters.V12_VERSION);
        assertThat(generalRegistry.decision(general).horizons().get(5).reason())
                .isEqualTo("INSUFFICIENT_HOLDOUT_SAMPLE");

        var bond = key(RadarBacktestExecution.InstrumentKind.BOND_ETF,
                TradingRadarV13PromotionRegistry.Track.SHORT, "BOND");
        var bondRegistry = registry(bond, Map.of(
                5, passingEvidence(5, 150, 3),
                20, passingEvidence(20, 150, 3)));
        assertThat(bondRegistry.isPromoted(bond)).isTrue();
        assertThat(bondRegistry.resolve(bond).ruleVersion()).isEqualTo(RuleParameters.V13_VERSION);
    }

    @Test
    void atLeastThreeValidFoldsAndSixtyPercentMustPass() {
        var key = key(RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarV13PromotionRegistry.Track.SHORT, "GENERAL");
        List<TradingRadarV13PromotionRegistry.FoldMetrics> twoValid = passingFolds().subList(0, 2);
        var insufficientFolds = evidence(5, holdout(200, 8, "0.6", "0.1", "0", "0.5"), twoValid);
        var registry = registry(key, Map.of(
                5, insufficientFolds,
                20, passingEvidence(20, 200, 8)));
        assertThat(registry.decision(key).horizons().get(5).reason())
                .isEqualTo("INSUFFICIENT_VALID_FOLDS");

        List<TradingRadarV13PromotionRegistry.FoldMetrics> exactlySixty = new ArrayList<>();
        exactlySixty.add(passFold(1));
        exactlySixty.add(passFold(2));
        exactlySixty.add(passFold(3));
        exactlySixty.add(neutralFold(4));
        exactlySixty.add(neutralFold(5));
        var accepted = registry(key, Map.of(
                5, evidence(5, holdout(200, 8, "0.6", "0.1", "0", "0.5"), exactlySixty),
                20, passingEvidence(20, 200, 8)));
        assertThat(accepted.isPromoted(key)).isTrue();
        assertThat(accepted.decision(key).horizons().get(5).passingFolds()).isEqualTo(3);
    }

    @Test
    void anyCatastrophicFoldRejectsTheWholeProductionKey() {
        var key = key(RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarV13PromotionRegistry.Track.MEDIUM, "GENERAL");
        List<TradingRadarV13PromotionRegistry.FoldMetrics> folds = new ArrayList<>(passingFolds());
        folds.set(4, new TradingRadarV13PromotionRegistry.FoldMetrics(
                5, 50, 3, new BigDecimal("-1.01"), new BigDecimal("-1.01"), BigDecimal.ZERO));
        var registry = registry(key, Map.of(
                60, evidence(60, holdout(200, 8, "0.6", "0.1", "0", "0.5"), folds),
                120, passingEvidence(120, 200, 8)));

        assertThat(registry.isPromoted(key)).isFalse();
        assertThat(registry.decision(key).horizons().get(60).reason()).isEqualTo("CATASTROPHIC_FOLD");
        assertThat(registry.resolve(key).parameterSetId()).isEqualTo("V12_DEFAULT");
    }

    @Test
    void allHorizonsCanPromoteThroughDownsideImprovementPath() {
        var key = key(RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarV13PromotionRegistry.Track.MEDIUM, "GENERAL");
        var downsideHoldout = holdout(200, 8, "-0.4", "-0.4", "1.2", "0.5");
        var registry = registry(key, Map.of(
                60, evidence(60, downsideHoldout, downsideFolds()),
                120, evidence(120, downsideHoldout, downsideFolds())));

        assertThat(registry.isPromoted(key)).isTrue();
        assertThat(registry.resolve(key).parameterSetId()).isEqualTo("CANDIDATE_A");
        assertThat(registry.promotedParameters()).containsOnlyKeys(key);
    }

    @Test
    void reportDiagnosticStrataDoNotSplitExactPromotionKey() {
        var incomeD7 = new TradingRadarV13PromotionRegistry.ProductionKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK,
                "INCOME", TradingRadarV13PromotionRegistry.Track.SHORT,
                "STOCK", "INCOME", "UNKNOWN", "D7");
        var incomeD8 = new TradingRadarV13PromotionRegistry.ProductionKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK,
                "INCOME", TradingRadarV13PromotionRegistry.Track.SHORT,
                "STOCK", "INCOME", "UNKNOWN", "D8");
        Map<Integer, TradingRadarV13PromotionRegistry.HorizonEvidence> evidence = Map.of(
                5, passingEvidence(5, 200, 8), 20, passingEvidence(20, 200, 8));
        var registry = TradingRadarV13PromotionRegistry.build(Map.of(
                incomeD7, new TradingRadarV13PromotionRegistry.CandidatePromotion(candidate(), evidence)));

        assertThat(incomeD7).isEqualTo(incomeD8);
        assertThat(registry.promotedParameters()).containsOnlyKeys(incomeD7).hasSize(1);
        assertThat(registry.resolve(incomeD7).ruleVersion()).isEqualTo(RuleParameters.V13_VERSION);
        assertThat(registry.resolve(incomeD8).ruleVersion()).isEqualTo(RuleParameters.V13_VERSION);
        assertThat(registry.isPromoted(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK,
                "INCOME", TradingRadarV13PromotionRegistry.Track.SHORT,
                "STOCK", "INCOME", "UNKNOWN", "D8")).isTrue();
    }

    private static TradingRadarV13PromotionRegistry registry(
            TradingRadarV13PromotionRegistry.ProductionKey key,
            Map<Integer, TradingRadarV13PromotionRegistry.HorizonEvidence> evidence) {
        return TradingRadarV13PromotionRegistry.build(Map.of(key,
                new TradingRadarV13PromotionRegistry.CandidatePromotion(candidate(), evidence)));
    }

    private static TradingRadarV13PromotionRegistry.ProductionKey key(
            RadarBacktestExecution.InstrumentKind kind,
            TradingRadarV13PromotionRegistry.Track track,
            String profile) {
        return new TradingRadarV13PromotionRegistry.ProductionKey(
                RadarBacktestExecution.TW_MARKET, kind, profile, track);
    }

    private static TradingRadarV13PromotionRegistry.HorizonEvidence passingEvidence(
            int horizon, int n, int codes) {
        return evidence(horizon, holdout(n, codes, "0.6", "0.1", "0", "0.5"), passingFolds());
    }

    private static TradingRadarV13PromotionRegistry.HorizonEvidence evidence(
            int horizon,
            TradingRadarV13PromotionRegistry.HoldoutMetrics holdout,
            List<TradingRadarV13PromotionRegistry.FoldMetrics> folds) {
        return new TradingRadarV13PromotionRegistry.HorizonEvidence(horizon, holdout, folds);
    }

    private static TradingRadarV13PromotionRegistry.HoldoutMetrics holdout(
            int n, int codes, String paired, String pooled, String downside, String cost) {
        return new TradingRadarV13PromotionRegistry.HoldoutMetrics(n, codes,
                new BigDecimal(paired), new BigDecimal(pooled),
                new BigDecimal(downside), new BigDecimal(cost));
    }

    private static List<TradingRadarV13PromotionRegistry.FoldMetrics> passingFolds() {
        List<TradingRadarV13PromotionRegistry.FoldMetrics> out = new ArrayList<>();
        for (int i = 1; i <= 5; i++) out.add(passFold(i));
        return List.copyOf(out);
    }

    private static List<TradingRadarV13PromotionRegistry.FoldMetrics> downsideFolds() {
        List<TradingRadarV13PromotionRegistry.FoldMetrics> out = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            out.add(new TradingRadarV13PromotionRegistry.FoldMetrics(i, 50, 3,
                    new BigDecimal("-0.4"), new BigDecimal("-0.4"), new BigDecimal("1.2")));
        }
        return List.copyOf(out);
    }

    private static TradingRadarV13PromotionRegistry.FoldMetrics passFold(int fold) {
        return new TradingRadarV13PromotionRegistry.FoldMetrics(fold, 50, 3,
                new BigDecimal("0.6"), new BigDecimal("0.1"), BigDecimal.ZERO);
    }

    private static TradingRadarV13PromotionRegistry.FoldMetrics neutralFold(int fold) {
        return new TradingRadarV13PromotionRegistry.FoldMetrics(fold, 50, 3,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static RuleParameters candidate() {
        return RuleParameters.v13Candidate("CANDIDATE_A",
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("0.70"), new BigDecimal("0.01"), new BigDecimal("2"),
                new BigDecimal("15"), new LinkedHashMap<>());
    }
}
