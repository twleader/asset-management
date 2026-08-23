package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TradingRadarV13ActionPolicyTest {

    private final TradingRadarRuleEngine engine = new TradingRadarRuleEngine();

    @Test
    void profitTakingBecomesDisclosureAndCannotAutomaticallyReduce() {
        var input = input(true,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                "-1", "1.6", TradingRadarRuleEngine.MarketRegime.NEUTRAL);

        var decision = TradingRadarV13ActionPolicy.apply(input,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                true, true, new BigDecimal("30"), candidate(Map.of()), true);

        assertThat(decision.action()).isEqualTo(TradingRadarRuleEngine.Action.HOLD_CAUTION);
        assertThat(decision.highMomentumRisk()).isTrue();
        assertThat(decision.weakeningConfirmed()).isFalse();
        assertThat(decision.disclosures()).anyMatch(text -> text.contains("HIGH_MOMENTUM_RISK"));
    }

    @Test
    void reduceRequiresBothExactWeakeningAndCalibratedDownsideThreshold() {
        var weakening = input(true,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                "-1", "1.6", TradingRadarRuleEngine.MarketRegime.NEUTRAL);
        RuleParameters parameters = candidate(Map.of());

        var lowDownside = TradingRadarV13ActionPolicy.apply(weakening,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                false, true, new BigDecimal("14.99"), parameters, true);
        var enoughDownside = TradingRadarV13ActionPolicy.apply(weakening,
                TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                false, true, new BigDecimal("15"), parameters, true);

        assertThat(TradingRadarV13ActionPolicy.weakeningConfirmed(
                weakening, true, parameters.weakeningCondition())).isTrue();
        assertThat(lowDownside.action()).isEqualTo(TradingRadarRuleEngine.Action.HOLD_CAUTION);
        assertThat(enoughDownside.action()).isEqualTo(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE);
        assertThat(enoughDownside.disclosures())
                .anyMatch(text -> text.contains("WEAKENING_DOWNSIDE_CONFIRMED"));
    }

    @Test
    void exitCandidateCannotBypassWeakeningAndDownsideGate() {
        var weakening = input(true,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                "-1", "1.6", TradingRadarRuleEngine.MarketRegime.NEUTRAL);
        RuleParameters parameters = candidate(Map.of());

        var blocked = TradingRadarV13ActionPolicy.apply(weakening,
                TradingRadarRuleEngine.Action.EXIT_CANDIDATE,
                false, true, null, parameters, true);

        assertThat(blocked.action()).isEqualTo(TradingRadarRuleEngine.Action.HOLD_CAUTION);
        assertThat(blocked.disclosures()).anyMatch(text -> text.contains("V13_SELL_BLOCKED"));
    }

    @Test
    void kdCrossStillNeedsOscOrDownDayWithOnePointFiveVolume() {
        RuleParameters parameters = candidate(Map.of());
        var noIndependentEvidence = input(true,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                "0", "1.49", TradingRadarRuleEngine.MarketRegime.NEUTRAL);
        var downVolume = input(true,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                "0", "1.5", TradingRadarRuleEngine.MarketRegime.NEUTRAL);

        assertThat(TradingRadarV13ActionPolicy.weakeningConfirmed(
                noIndependentEvidence, true, parameters.weakeningCondition())).isFalse();
        assertThat(TradingRadarV13ActionPolicy.weakeningConfirmed(
                downVolume, true, parameters.weakeningCondition())).isTrue();
        assertThat(TradingRadarV13ActionPolicy.weakeningConfirmed(
                downVolume, false, parameters.weakeningCondition())).isFalse();
    }

    @Test
    void unpromotedPolicyAndEmptyRegistryRetainExactBaselineResult() {
        var input = neutralMarketInput(false);
        var baseline = engine.evaluateStock(input);
        var policy = TradingRadarV13ActionPolicy.apply(input, baseline.action(),
                baseline.profitTakingConfirmed(), baseline.kdDeadCross(), new BigDecimal("100"),
                candidate(Map.of()), false);
        var key = new TradingRadarV13PromotionRegistry.ProductionKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK,
                "GENERAL", TradingRadarV13PromotionRegistry.Track.MEDIUM);
        var production = engine.evaluatePromoted(input,
                TradingRadarV13PromotionRegistry.empty(), key, context(null, null));

        assertThat(policy.v13Active()).isFalse();
        assertThat(policy.action()).isEqualTo(baseline.action());
        assertThat(production).isEqualTo(baseline);
        // Task 360：production 版號已升 TW_RULES_V16，不再等於 RuleParameters 的任何一個標籤。
        // RuleParameters 的 V12／V13 是 calibration／candidate 命名空間，production 不得竊用——
        // 尤其 V13_VERSION 是 evaluateCandidate() 的 guard（不符即 throw），
        // 把它當 production 版號會讓「這是不是 candidate」的判別式失效。
        assertThat(TradingRadarRuleEngine.RULE_VERSION)
                .isNotEqualTo(RuleParameters.V12_VERSION)
                .isNotEqualTo(RuleParameters.V13_VERSION);
    }

    @Test
    void trackScopedRuntimeCanPromoteShortWhileMediumFallsBackToV12() {
        var input = neutralMarketInput(false);
        var shortKey = key(TradingRadarV13PromotionRegistry.Track.SHORT);
        var mediumKey = key(TradingRadarV13PromotionRegistry.Track.MEDIUM);
        RuleParameters shortCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.50")));
        TradingRadarV13PromotionRegistry registry = TradingRadarV13PromotionRegistry.build(Map.of(
                shortKey, new TradingRadarV13PromotionRegistry.CandidatePromotion(
                        shortCandidate, passingEvidence())));
        var context = context("0", "0");

        var expectedV12 = engine.evaluateStock(input);
        var expectedShort = engine.evaluateCandidate(input, shortCandidate, context);
        var result = engine.evaluatePromoted(input, registry, shortKey, mediumKey, context);

        assertThat(result.score()).isEqualTo(expectedV12.score());
        assertThat(result.action()).isEqualTo(expectedV12.action());
        assertThat(result.shortScore()).isEqualTo(expectedShort.shortScore());
        assertThat(result.shortAction()).isEqualTo(expectedShort.shortAction());
    }

    @Test
    void trackScopedRuntimeUsesDifferentCandidatesForEachHorizon() {
        var input = neutralMarketInput(false);
        var shortKey = key(TradingRadarV13PromotionRegistry.Track.SHORT);
        var mediumKey = key(TradingRadarV13PromotionRegistry.Track.MEDIUM);
        RuleParameters shortCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.50")));
        RuleParameters mediumCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.50")));
        TradingRadarV13PromotionRegistry registry = TradingRadarV13PromotionRegistry.build(Map.of(
                shortKey, new TradingRadarV13PromotionRegistry.CandidatePromotion(
                        shortCandidate, passingEvidence()),
                mediumKey, new TradingRadarV13PromotionRegistry.CandidatePromotion(
                        mediumCandidate, passingEvidence())));
        var context = context("0", "0");

        var expectedShort = engine.evaluateCandidate(input, shortCandidate, context);
        var expectedMedium = engine.evaluateCandidate(input, mediumCandidate, context);
        var result = engine.evaluatePromoted(input, registry, shortKey, mediumKey, context);

        assertThat(result.score()).isEqualTo(expectedMedium.score());
        assertThat(result.action()).isEqualTo(expectedMedium.action());
        assertThat(result.shortScore()).isEqualTo(expectedShort.shortScore());
        assertThat(result.shortAction()).isEqualTo(expectedShort.shortAction());
    }

    @Test
    void promotedRegistryNeverReachesTheSwingTrack() {
        // Task 356.9c 最容易錯的一支：**兩把 key 都 promoted** 時，現行程式碼根本不會呼叫
        // evaluateStock，若照「從 medium 取 swing」的字面實作，promoted 參數會靜默污染 swing 軌。
        var input = neutralMarketInput(false);
        var shortKey = key(TradingRadarV13PromotionRegistry.Track.SHORT);
        var mediumKey = key(TradingRadarV13PromotionRegistry.Track.MEDIUM);
        RuleParameters shortCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.50")));
        RuleParameters mediumCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.50")));
        TradingRadarV13PromotionRegistry bothPromoted = TradingRadarV13PromotionRegistry.build(Map.of(
                shortKey, new TradingRadarV13PromotionRegistry.CandidatePromotion(
                        shortCandidate, passingEvidence()),
                mediumKey, new TradingRadarV13PromotionRegistry.CandidatePromotion(
                        mediumCandidate, passingEvidence())));
        var context = context("0", "0");

        var baseline = engine.evaluateStock(input);
        var result = engine.evaluatePromoted(input, bothPromoted, shortKey, mediumKey, context);

        assertThat(result.swingScore()).isEqualTo(baseline.swingScore());
        assertThat(result.swingAction()).isEqualTo(baseline.swingAction());
        assertThat(result.swingReasons()).isEqualTo(baseline.swingReasons());
        assertThat(result.swingRisks()).isEqualTo(baseline.swingRisks());
        // 兩軌確實被 promote（否則本測試會因為「根本沒 promote」而假通過）。
        assertThat(result.score())
                .isEqualTo(engine.evaluateCandidate(input, mediumCandidate, context).score());
        assertThat(result.shortScore())
                .isEqualTo(engine.evaluateCandidate(input, shortCandidate, context).shortScore());
    }

    @Test
    void singlePromotedKeyAlsoLeavesTheSwingTrackOnBaseline() {
        var input = neutralMarketInput(false);
        var shortKey = key(TradingRadarV13PromotionRegistry.Track.SHORT);
        var mediumKey = key(TradingRadarV13PromotionRegistry.Track.MEDIUM);
        RuleParameters mediumCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.50")));
        TradingRadarV13PromotionRegistry registry = TradingRadarV13PromotionRegistry.build(Map.of(
                mediumKey, new TradingRadarV13PromotionRegistry.CandidatePromotion(
                        mediumCandidate, passingEvidence())));

        var baseline = engine.evaluateStock(input);
        var result = engine.evaluatePromoted(
                input, registry, shortKey, mediumKey, context("0", "0"));

        assertThat(result.swingScore()).isEqualTo(baseline.swingScore());
        assertThat(result.swingAction()).isEqualTo(baseline.swingAction());
    }

    @Test
    void evaluateCandidateKeepsTheSwingTrackOnBaselineParameters() {
        // Task 356.9d：RuleParameters 不新增任何 swing 的 ActionThresholds／CandidateWeight，
        // 故即使直接呼叫 evaluateCandidate，swing 四欄也必須與純 baseline 相同。
        var input = neutralMarketInput(false);
        RuleParameters bothWeighted = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.50"),
                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.50")));

        var baseline = engine.evaluateStock(input);
        var candidateResult = engine.evaluateCandidate(input, bothWeighted, context("0", "0"));

        assertThat(candidateResult.swingScore()).isEqualTo(baseline.swingScore());
        assertThat(candidateResult.swingAction()).isEqualTo(baseline.swingAction());
        assertThat(candidateResult.swingReasons()).isEqualTo(baseline.swingReasons());
    }

    @Test
    void trackScopedRuntimeWithTwoFallbackKeysIsBitIdenticalToV12() {
        var input = neutralMarketInput(false);
        var shortKey = key(TradingRadarV13PromotionRegistry.Track.SHORT);
        var mediumKey = key(TradingRadarV13PromotionRegistry.Track.MEDIUM);

        var expected = engine.evaluateStock(input);
        var result = engine.evaluatePromoted(
                input, TradingRadarV13PromotionRegistry.empty(), shortKey, mediumKey, context("0", "0"));

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void confidenceThresholdSeventyAllowsBuyButEightyGatesTheSameEvidence() {
        var input = strongCandidateInput(false);
        RuleParameters atSeventy = RuleParameters.v13DisabledCandidate(
                "CONFIDENCE_70", new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25), new BigDecimal("0.70"),
                new BigDecimal("100"), Map.of(), RuleParameters.BondRateCandidate.v12Fallback());
        RuleParameters atEighty = RuleParameters.v13DisabledCandidate(
                "CONFIDENCE_80", atSeventy.shortThresholds(), atSeventy.mediumThresholds(),
                new BigDecimal("0.80"), new BigDecimal("100"), Map.of(),
                RuleParameters.BondRateCandidate.v12Fallback());

        var seventy = engine.evaluateCandidate(input, atSeventy, contextWithConfidence(new BigDecimal("0.75")));
        var eighty = engine.evaluateCandidate(input, atEighty, contextWithConfidence(new BigDecimal("0.75")));

        assertThat(seventy.action()).isEqualTo(TradingRadarRuleEngine.Action.BUY_CANDIDATE);
        assertThat(eighty.action()).isEqualTo(TradingRadarRuleEngine.Action.WATCH);
        assertThat(eighty.risks()).anyMatch(text -> text.contains("V13_CONFIDENCE_GATE"));
    }

    @Test
    void disabledCandidateNeverUsesNormalizedSigmaFallbackOrPercentileReplacement() {
        var input = hotK(withBiasAndPercentile(neutralMarketInput(false), "5", "99"));
        RuleParameters disabled = RuleParameters.v13DisabledCandidate(
                "DISABLED_NORMALIZED", new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25), new BigDecimal("0.70"),
                new BigDecimal("100"), Map.of(), RuleParameters.BondRateCandidate.v12Fallback());

        var baseline = engine.evaluateStock(input);
        var result = engine.evaluateCandidate(input, disabled, context(null, null, null));

        assertThat(disabled.normalizedBiasEnabled()).isFalse();
        assertThat(disabled.normalizedBiasFloor()).isNull();
        assertThat(result.timingState()).isEqualTo(baseline.timingState());
        assertThat(result.normalizedBias()).isNotNull()
                .satisfies(provenance -> {
                    assertThat(provenance.enabled()).isFalse();
                    assertThat(provenance.reason()).isEqualTo("NORMALIZED_PATH_DISABLED");
                    assertThat(provenance.rawBiasRatio()).isNull();
                });
        assertThat(result.risks()).anyMatch(text -> text.contains("V13_SIGMA_PROFILE_GATE"));
        assertThat(result.risks()).noneMatch(text -> text.contains("V13_NORMALIZED_BIAS_FALLBACK_FIXED_THRESHOLD"));
        assertThat(result.risks()).noneMatch(text -> text.contains("V13_NORMALIZED_BIAS_UNAVAILABLE"));
    }

    @Test
    void unavailableSigmaProfileGatesPositiveRowSigmaForBuyAndSell() {
        RuleParameters disabled = RuleParameters.v13DisabledCandidate(
                "PROFILE_UNAVAILABLE_DISABLED", new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25), new BigDecimal("0.70"),
                new BigDecimal("100"), Map.of(), RuleParameters.BondRateCandidate.v12Fallback());
        var unavailable = contextWithConfidence(new BigDecimal("0.75"))
                .withSigmaProfileAvailable(false);

        var buy = engine.evaluateCandidate(strongCandidateInput(false), disabled, unavailable);
        assertThat(buy.action()).isEqualTo(TradingRadarRuleEngine.Action.WATCH);
        assertThat(buy.risks()).anyMatch(text -> text.contains("V13_SIGMA_PROFILE_GATE"));

        var sell = engine.evaluateCandidate(riskInput(true), candidate(Map.of()),
                closedEvidenceContext(new BigDecimal("0.02")).withSigmaProfileAvailable(false));
        assertThat(sell.action()).isEqualTo(TradingRadarRuleEngine.Action.HOLD_CAUTION);
        assertThat(sell.risks()).anyMatch(text -> text.contains("V13_SIGMA_GATE"));
    }

    @Test
    void normalizedCandidateCarriesRawEffectiveAndAsOfProvenancePerRow() {
        var input = withBias(neutralMarketInput(false), "10");
        RuleParameters candidate = normalizedCandidate("PROVENANCE", "0.01", "2");
        LocalDate asOf = LocalDate.of(2026, 8, 8);
        var result = engine.evaluateCandidate(input, candidate,
                new TradingRadarRuleEngine.CandidateContext(
                        true, true, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                        null, null, new BigDecimal("0.02"), BigDecimal.ONE, BigDecimal.ONE,
                        null, null, null, null, null, null, asOf));

        assertThat(result.normalizedBias()).isNotNull()
                .satisfies(provenance -> {
                    assertThat(provenance.enabled()).isTrue();
                    assertThat(provenance.rawBiasRatio()).isEqualByComparingTo("0.10");
                    assertThat(provenance.rawSigmaRatio()).isEqualByComparingTo("0.02");
                    assertThat(provenance.effectiveSigmaRatio()).isEqualByComparingTo("0.02");
                    assertThat(provenance.normalizedBias()).isEqualByComparingTo("5");
                    assertThat(provenance.asOfDate()).isEqualTo(asOf);
                    assertThat(provenance.volatilityFallback()).isFalse();
                });
        assertThat(result.shortNormalizedBias()).isEqualTo(result.normalizedBias());
        TradingRadarDto.NormalizedBiasEvidence dto =
                TradingRadarDto.NormalizedBiasEvidence.from(result.normalizedBias());
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.rawBiasRatio()).isEqualByComparingTo("0.10");
        assertThat(dto.effectiveSigmaRatio()).isEqualByComparingTo("0.02");
        assertThat(dto.asOfDate()).isEqualTo(asOf.toString());
    }

    @Test
    void candidateApiRerunsFullEngineWithImmutableMarketAndTreasuryWeights() {
        var input = neutralMarketInput(false);
        var noDelta = engine.evaluateCandidate(input, candidate(Map.of()), context("0", "0"));
        var marketWeighted = engine.evaluateCandidate(input, candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.50"),
                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.50"))),
                context("0", "0"));
        var treasuryWeighted = engine.evaluateCandidate(input, candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_TREASURY, new BigDecimal("0.50"),
                RuleParameters.CandidateWeight.MEDIUM_TREASURY, new BigDecimal("0.50"))),
                context("1", "1"));

        assertThat(marketWeighted.score()).isGreaterThan(noDelta.score());
        assertThat(marketWeighted.shortScore()).isGreaterThan(noDelta.shortScore());
        assertThat(treasuryWeighted.score()).isGreaterThan(noDelta.score());
        assertThat(treasuryWeighted.shortScore()).isGreaterThan(noDelta.shortScore());
    }

    @Test
    void candidateMarketFeatureContributionChangesScoreButMissingFeaturesDoNot() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        CandidateMarketFeature spx = new CandidateMarketFeature(
                "SPX_RET5", new BigDecimal("0.8"), LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SPX", null, "美股_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        CandidateMarketFeature wti = new CandidateMarketFeature(
                "WTI_RET5", new BigDecimal("-0.2"), LocalDate.of(2026, 8, 7), decision,
                "SOURCE_AVAILABLE_AT", "WTI", null, "美股_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        var known = new TradingRadarMarketFeatureResolver.Evidence(
                "美股", decision, Map.of("SPX_RET5", spx, "WTI_RET5", wti));
        var missing = TradingRadarMarketFeatureResolver.Evidence.empty(
                "美股", decision, "typed source missing");
        RuleParameters featureCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.50"),
                RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.50")));

        var knownResult = engine.evaluateCandidate(neutralMarketInput(false), featureCandidate,
                featureContext(known));
        var missingResult = engine.evaluateCandidate(neutralMarketInput(false), featureCandidate,
                featureContext(missing));
        var noFeatureWeight = engine.evaluateCandidate(neutralMarketInput(false),
                candidate(Map.of()), featureContext(known));
        var fullMap = new LinkedHashMap<String, CandidateMarketFeature>();
        for (String code : List.of("IXIC_RET5", "SOX_RET5", "SPX_RET5", "DJI_RET5",
                "INDEX_VOLUME_RATIO20", "WTI_RET5", "BRENT_RET5", "GOLD_RET5")) {
            fullMap.put(code, availableMarketFeature(code, 1.0, decision));
        }
        fullMap.put("TW_INSTITUTIONAL_NET_TURNOVER", CandidateMarketFeature.notApplicable(
                "TW_INSTITUTIONAL_NET_TURNOVER", "美股市場不適用", "NOT_APPLICABLE"));
        var full = new TradingRadarMarketFeatureResolver.Evidence("美股", decision, fullMap);
        var fullResult = engine.evaluateCandidate(neutralMarketInput(false), featureCandidate,
                featureContext(full));

        // 5-session returns are first converted from percentage points to a
        // dimensionless +/-1 unit (2.5pp saturates), then divided by the full
        // eligible denominator rather than re-scaled to the 2/9 observed rows:
        // (0.8 / 2.5 + -0.2 / 2.5) / 9 = 0.026666....
        assertThat(known.aggregateContribution().value())
                .isCloseTo(0.02666666666666667, org.assertj.core.data.Offset.offset(1e-9));
        // Every return leg is dimensionless before aggregation and the neutral
        // volume ratio contributes zero; the full map therefore remains below
        // one even at 100% coverage.
        assertThat(full.aggregateContribution().value()).isEqualTo(0.35);
        assertThat(full.aggregateContribution().value())
                .as("完整 coverage 的 dimensionless aggregate 應高於 2/9 partial coverage")
                .isGreaterThan(known.aggregateContribution().value());
        assertThat(fullResult.reasons()).anyMatch(text -> text.contains("V13_MARKET_FEATURE_CONTRIBUTION"));
        assertThat(knownResult.reasons()).anyMatch(text -> text.contains("V13_MARKET_FEATURE_CONTRIBUTION"));
        assertThat(missingResult.score()).isEqualTo(noFeatureWeight.score());
        assertThat(missingResult.shortScore()).isEqualTo(noFeatureWeight.shortScore());
        assertThat(missingResult.risks()).anyMatch(text -> text.contains("V13_MARKET_FEATURE_UNAVAILABLE"));
    }

    @Test
    void bondProfileExcludesEquityOnlyMarketFeaturesFromCandidateScore() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        CandidateMarketFeature spx = new CandidateMarketFeature(
                "SPX_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SPX", null, "美股_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        var marketFeatures = new TradingRadarMarketFeatureResolver.Evidence(
                "美股", decision, Map.of("SPX_RET5", spx));
        var equity = TradingRadarAssetProfileResolver.resolve(
                "AAPL", "美股", "Apple", AssetClassifier.STOCK, "STOCK",
                "GROWTH", null, "USD", null);
        var bond = TradingRadarAssetProfileResolver.resolve(
                "TLT", "美股", "20+ Year Treasury Bond ETF", AssetClassifier.BOND, "BOND_ETF",
                null, "LONG", "USD", null);
        var equityAggregate = marketFeatures.aggregateContribution(equity);
        var bondAggregate = marketFeatures.aggregateContribution(bond);
        // One +1pp SPX observation contributes 1/2.5 of one unit; the four
        // applicable global equity legs still divide by the full denominator.
        assertThat(equityAggregate.value()).isEqualTo(0.05);
        assertThat(bondAggregate.value()).isNull();
        assertThat(bondAggregate.availableCount()).isZero();
        assertThat(bondAggregate.eligibleCount())
                .as("全球資產類商品仍適用債券 profile；只有明確 N/A 才能排除 denominator")
                .isEqualTo(3);

        RuleParameters featureCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.50"),
                RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.50")));
        var equityResult = engine.evaluateCandidate(neutralMarketInput(false), featureCandidate,
                featureContext(marketFeatures, equity));
        var bondResult = engine.evaluateCandidate(neutralMarketInput(false), featureCandidate,
                featureContext(marketFeatures, bond));
        // Integer score rounding can make this small +0.05 contribution land
        // on the same displayed score; the aggregate assertion above is the
        // precise exclusion proof, while the full-engine score must not be
        // lower for the equity-only observation.
        assertThat(equityResult.score()).isGreaterThanOrEqualTo(bondResult.score());
        assertThat(bondResult.risks()).anyMatch(text -> text.contains("V13_MARKET_FEATURE_UNAVAILABLE"));
    }

    @Test
    void availableActualBetaMapsToNonNullTreasuryContributionAndMissingFailsClosed() {
        BondYieldBetaResolver.Result available = new BondYieldBetaResolver.Result(
                BondYieldBetaResolver.Status.AVAILABLE, "TLT", "美股", "Y30",
                BondYieldBetaResolver.FxControl.NONE, new BigDecimal("-5"), null, 750,
                List.of(), new BondYieldBetaResolver.Stability(3, true, BigDecimal.ONE, true),
                LocalDate.of(2026, 8, 7), Instant.parse("2026-08-08T04:00:00Z"),
                Set.of("VERIFIED_CLOSE", "US_TREASURY"), null,
                BondYieldBetaResolver.RateSignalSpec.primaryOnly("Y30"),
                LocalDate.of(2026, 8, 7), new BigDecimal("-0.10"), null,
                new BigDecimal("-0.10"), Instant.parse("2026-08-08T04:00:00Z"));
        BondYieldBetaContribution.Contributions actual = BondYieldBetaContribution.from(available);
        BondYieldBetaContribution.Contributions missing = BondYieldBetaContribution.from(
                BondYieldBetaResolver.Result.missing(null, "sample missing"));
        var input = neutralMarketInput(false);
        RuleParameters treasuryCandidate = candidate(Map.of(
                RuleParameters.CandidateWeight.SHORT_TREASURY, new BigDecimal("0.50"),
                RuleParameters.CandidateWeight.MEDIUM_TREASURY, new BigDecimal("0.50")));
        var withActual = engine.evaluateCandidate(input, treasuryCandidate,
                new TradingRadarRuleEngine.CandidateContext(
                        true, true, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                        actual.shortTerm(), actual.mediumTerm()));
        var failClosed = engine.evaluateCandidate(input, treasuryCandidate,
                new TradingRadarRuleEngine.CandidateContext(
                        true, true, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                        missing.shortTerm(), missing.mediumTerm()));

        assertThat(actual.shortTerm()).isEqualTo(0.5);
        assertThat(actual.mediumTerm()).isEqualTo(0.5);
        assertThat(withActual.score()).isGreaterThan(failClosed.score());
        assertThat(withActual.shortScore()).isGreaterThan(failClosed.shortScore());
        assertThat(missing.shortTerm()).isNull();
        assertThat(missing.mediumTerm()).isNull();
    }

    @Test
    void normalizedBiasParametersChangeCandidateOnlyAndMissingSigmaDoesNotInventSignal() {
        var input = withBias(neutralMarketInput(false), "10");
        var v12Before = engine.evaluateStock(input);
        var sigma = context(null, null, new BigDecimal("0.02"));
        var lowMultiple = engine.evaluateCandidate(input,
                normalizedCandidate("LOW_MULTIPLE", "0.01", "1"), sigma);
        var highMultiple = engine.evaluateCandidate(input,
                normalizedCandidate("HIGH_MULTIPLE", "0.01", "10"), sigma);

        assertThat(lowMultiple.score()).isNotEqualTo(highMultiple.score());
        assertThat(engine.evaluateStock(input)).isEqualTo(v12Before);

        var missingSigma = context(null, null, null);
        var missingLow = engine.evaluateCandidate(input,
                normalizedCandidate("MISSING_LOW", "0.01", "1"), missingSigma);
        var missingHigh = engine.evaluateCandidate(input,
                normalizedCandidate("MISSING_HIGH", "0.01", "10"), missingSigma);
        assertThat(missingLow.score()).isEqualTo(missingHigh.score());
        assertThat(missingLow.risks()).anyMatch(text -> text.contains("V13_NORMALIZED_BIAS_UNAVAILABLE"));
    }

    @Test
    void normalizedBiasObservationUsesRatioUnitsAndCalibratedSigmaFloor() {
        var normal = TradingRadarRuleEngine.NormalizedBiasObservation.from(
                new BigDecimal("10"), new BigDecimal("0.02"), new BigDecimal("0.005"),
                LocalDate.of(2026, 8, 7));
        assertThat(normal.rawBiasRatio()).isEqualByComparingTo("0.10");
        assertThat(normal.effectiveSigmaRatio()).isEqualByComparingTo("0.02");
        assertThat(normal.normalizedBias()).isEqualByComparingTo("5");
        assertThat(normal.available()).isTrue();
        assertThat(normal.volatilityFallback()).isFalse();

        var floored = TradingRadarRuleEngine.NormalizedBiasObservation.from(
                new BigDecimal("10"), new BigDecimal("0.001"), new BigDecimal("0.005"),
                LocalDate.of(2026, 8, 7));
        assertThat(floored.effectiveSigmaRatio()).isEqualByComparingTo("0.005");
        assertThat(floored.normalizedBias()).isEqualByComparingTo("20");
        assertThat(floored.floorApplied()).isTrue();
        assertThat(floored.available()).isTrue();
    }

    @Test
    void v13TimingDoesNotUsePercentileOnlyExtremeAndMissingSigmaFallsBackFixed() {
        var input = hotK(withBiasAndPercentile(neutralMarketInput(false), "5", "99"));
        var v12 = engine.evaluateStock(input);
        RuleParameters candidate = RuleParameters.v13Candidate(
                "TIMING_SIDE_TEST", new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25), new BigDecimal("0.70"),
                new BigDecimal("0.005"), new BigDecimal("2"), new BigDecimal("5"),
                new BigDecimal("5"), new BigDecimal("15"), Map.of());

        var normalized = engine.evaluateCandidate(input, candidate,
                context(null, null, new BigDecimal("0.02")));
        var missing = engine.evaluateCandidate(input, candidate, context(null, null, null));

        assertThat(v12.timingState()).as("V12 仍可揭露既有 percentile 路徑")
                .isEqualTo(TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT);
        assertThat(normalized.timingState()).as("normalized=2.5 未達 upper=5，不得被 percentile-only 觸發 extreme")
                .isEqualTo(TradingRadarRuleEngine.TimingState.OVERBOUGHT);
        assertThat(missing.timingState()).as("sigma 缺漏只回退固定 ±20%，不得藉 percentile 繞過")
                .isEqualTo(TradingRadarRuleEngine.TimingState.OVERBOUGHT);
        assertThat(missing.risks()).anyMatch(text -> text.contains("V13_NORMALIZED_BIAS_FALLBACK_FIXED_THRESHOLD"));
    }

    @Test
    void missingSigmaWinsOverClosedEvidenceGateForHeldRiskAction() {
        RuleParameters candidate = normalizedCandidate("SIGMA_RISK_GATE", "0.01", "2");
        var result = engine.evaluateCandidate(riskInput(true), candidate,
                closedEvidenceContext(null));

        assertThat(result.action()).isEqualTo(TradingRadarRuleEngine.Action.HOLD_CAUTION);
        assertThat(result.risks()).anyMatch(text -> text.contains("V13_SIGMA_GATE"));
    }

    private static RuleParameters candidate(Map<RuleParameters.CandidateWeight, BigDecimal> weights) {
        return RuleParameters.v13Candidate("CANDIDATE_ACTION",
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("0.70"), new BigDecimal("0.01"), new BigDecimal("2"),
                new BigDecimal("15"), weights);
    }

    private static TradingRadarRuleEngine.CandidateContext closedEvidenceContext(BigDecimal sigma) {
        return new TradingRadarRuleEngine.CandidateContext(
                true, true, BigDecimal.ONE, new BigDecimal("30"), new BigDecimal("30"),
                null, null, sigma, BigDecimal.ONE, BigDecimal.ONE,
                TradingRadarEvidenceConfidenceResolver.Evidence.EMPTY, null, null, null,
                null, null, null);
    }

    private static TradingRadarRuleEngine.StockInput riskInput(boolean held) {
        return new TradingRadarRuleEngine.StockInput(
                held, bd("80"), bd("0"), bd("0"),
                new TradingRadarRuleEngine.Indicators(
                        bd("100"), bd("110"), bd("120"), bd("70"), bd("80")),
                bd("80"), bd("70"), TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false,
                null, bd("0"), null, bd("0"), bd("0.5"), bd("5"),
                null, null, bd("100"), extended("-1"), bd("1.6"),
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    private static TradingRadarV13PromotionRegistry.ProductionKey key(
            TradingRadarV13PromotionRegistry.Track track) {
        return new TradingRadarV13PromotionRegistry.ProductionKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK,
                "GENERAL", track);
    }

    private static Map<Integer, TradingRadarV13PromotionRegistry.HorizonEvidence> passingEvidence() {
        var holdout = new TradingRadarV13PromotionRegistry.HoldoutMetrics(
                200, 8, new BigDecimal("1.0"), new BigDecimal("1.0"),
                new BigDecimal("2.0"), new BigDecimal("0.10"));
        List<TradingRadarV13PromotionRegistry.FoldMetrics> folds = List.of(
                new TradingRadarV13PromotionRegistry.FoldMetrics(
                        1, 50, 3, new BigDecimal("1.0"), new BigDecimal("1.0"), new BigDecimal("2.0")),
                new TradingRadarV13PromotionRegistry.FoldMetrics(
                        2, 50, 3, new BigDecimal("1.0"), new BigDecimal("1.0"), new BigDecimal("2.0")),
                new TradingRadarV13PromotionRegistry.FoldMetrics(
                        3, 50, 3, new BigDecimal("1.0"), new BigDecimal("1.0"), new BigDecimal("2.0")));
        Map<Integer, TradingRadarV13PromotionRegistry.HorizonEvidence> result = new LinkedHashMap<>();
        for (int horizon : List.of(5, 20, 60, 120)) {
            result.put(horizon, new TradingRadarV13PromotionRegistry.HorizonEvidence(
                    horizon, holdout, folds));
        }
        return result;
    }

    private static TradingRadarRuleEngine.CandidateContext context(
            String shortTreasury, String mediumTreasury) {
        return context(shortTreasury, mediumTreasury, null);
    }

    private static TradingRadarRuleEngine.CandidateContext context(
            String shortTreasury, String mediumTreasury, BigDecimal sigma) {
        return new TradingRadarRuleEngine.CandidateContext(true, true, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO,
                decimalDouble(shortTreasury), decimalDouble(mediumTreasury), sigma);
    }

    private static TradingRadarRuleEngine.CandidateContext contextWithConfidence(BigDecimal confidence) {
        return new TradingRadarRuleEngine.CandidateContext(true, true, confidence,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, new BigDecimal("0.02"));
    }

    private static TradingRadarRuleEngine.CandidateContext featureContext(
            TradingRadarMarketFeatureResolver.Evidence marketFeatures) {
        return featureContext(marketFeatures, null);
    }

    private static TradingRadarRuleEngine.CandidateContext featureContext(
            TradingRadarMarketFeatureResolver.Evidence marketFeatures,
            TradingRadarAssetProfileResolver.AssetProfile profile) {
        var aggregate = marketFeatures.aggregateContribution(profile);
        return new TradingRadarRuleEngine.CandidateContext(
                true, true, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, BigDecimal.ONE, BigDecimal.ONE,
                null, profile, marketFeatures, null,
                aggregate.shortTerm(), aggregate.mediumTerm());
    }

    private static RuleParameters normalizedCandidate(String id, String floor, String multiple) {
        return RuleParameters.v13Candidate(id,
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("0.70"), new BigDecimal(floor), new BigDecimal(multiple),
                new BigDecimal("15"), Map.of());
    }

    private static TradingRadarRuleEngine.StockInput strongCandidateInput(boolean held) {
        return new TradingRadarRuleEngine.StockInput(
                held, bd("120"), bd("1"), bd("1"),
                new TradingRadarRuleEngine.Indicators(
                        bd("110"), bd("100"), bd("90"), bd("60"), bd("40")),
                bd("55"), bd("45"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                null, null, null, null, null, null, null, null, null,
                new TradingRadarRuleEngine.ExtendedIndicators(
                        bd("50"), bd("50"), bd("50"), bd("100"), bd("100"), bd("0"), bd("0"),
                        bd("0"), bd("20"), bd("30"), bd("0"), bd("0"), bd("0"), bd("50")),
                bd("1.5"), TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    private static TradingRadarRuleEngine.StockInput withBias(
            TradingRadarRuleEngine.StockInput input, String bias) {
        return new TradingRadarRuleEngine.StockInput(
                input.held(), input.price(), input.changePercent(), input.completedChangePercent(),
                input.indicators(), input.previousK(), input.previousD(), input.ma20Confirmation(),
                input.ma60Confirmation(), input.ma240Confirmation(), input.instrumentType(),
                input.marketRegime(), input.marketStale(), input.fxPercentile(), new BigDecimal(bias),
                input.ma60BiasPercentile(), input.ma240BiasPercent(), input.week52Position(),
                input.kdBandWidthPercent(), input.etfPremiumPct(), input.etfPremiumPercentile(),
                input.weeklyMa(), input.extendedIndicators(), input.volumeRatio(), input.fundamental());
    }

    private static TradingRadarRuleEngine.StockInput withBiasAndPercentile(
            TradingRadarRuleEngine.StockInput input, String bias, String percentile) {
        return new TradingRadarRuleEngine.StockInput(
                input.held(), input.price(), input.changePercent(), input.completedChangePercent(),
                input.indicators(), input.previousK(), input.previousD(), input.ma20Confirmation(),
                input.ma60Confirmation(), input.ma240Confirmation(), input.instrumentType(),
                input.marketRegime(), input.marketStale(), input.fxPercentile(), new BigDecimal(bias),
                new BigDecimal(percentile), input.ma240BiasPercent(), input.week52Position(),
                input.kdBandWidthPercent(), input.etfPremiumPct(), input.etfPremiumPercentile(),
                input.weeklyMa(), input.extendedIndicators(), input.volumeRatio(), input.fundamental());
    }

    private static TradingRadarRuleEngine.StockInput hotK(TradingRadarRuleEngine.StockInput input) {
        return new TradingRadarRuleEngine.StockInput(
                input.held(), input.price(), input.changePercent(), input.completedChangePercent(),
                new TradingRadarRuleEngine.Indicators(input.indicators().ma20(), input.indicators().ma60(),
                        input.indicators().ma240(), bd("90"), bd("85")),
                bd("80"), bd("70"), input.ma20Confirmation(), input.ma60Confirmation(),
                input.ma240Confirmation(), input.instrumentType(), input.marketRegime(), input.marketStale(),
                input.fxPercentile(), input.ma60BiasPercent(), input.ma60BiasPercentile(),
                input.ma240BiasPercent(), input.week52Position(), input.kdBandWidthPercent(),
                input.etfPremiumPct(), input.etfPremiumPercentile(), input.weeklyMa(), input.extendedIndicators(),
                input.volumeRatio(), input.fundamental());
    }

    private static TradingRadarRuleEngine.StockInput input(
            boolean held,
            TradingRadarRuleEngine.Confirmation ma20,
            TradingRadarRuleEngine.Confirmation ma60,
            String osc,
            String volume,
            TradingRadarRuleEngine.MarketRegime regime) {
        return new TradingRadarRuleEngine.StockInput(
                held, bd("100"), bd("0"), bd("-1"),
                new TradingRadarRuleEngine.Indicators(bd("100"), bd("100"), bd("90"), bd("70"), bd("80")),
                bd("80"), bd("70"), ma20, ma60, TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY, regime, false,
                null, bd("0"), null, bd("11"), bd("0.5"), bd("5"),
                null, null, bd("100"), extended(osc), bd(volume),
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    /** 除 market/Treasury 外盡量中性化，讓 candidate weight 的 full-engine 差異可直接觀察。 */
    private static TradingRadarRuleEngine.StockInput neutralMarketInput(boolean held) {
        return new TradingRadarRuleEngine.StockInput(
                held, bd("100"), bd("0"), bd("0"),
                new TradingRadarRuleEngine.Indicators(bd("100"), bd("100"), bd("100"), bd("50"), bd("50")),
                bd("50"), bd("50"),
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, false,
                null, bd("0"), null, bd("0"), bd("0.5"), bd("5"),
                null, null, bd("100"), extended("0"), bd("1"),
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    private static TradingRadarRuleEngine.ExtendedIndicators extended(String osc) {
        return new TradingRadarRuleEngine.ExtendedIndicators(
                bd("50"), bd("50"), bd("50"), bd("100"), bd("100"), bd("0"), bd("0"), bd(osc),
                bd("50"), bd("50"), bd("0"), bd("0"), bd("0"), bd("50"));
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    private static Double decimalDouble(String value) {
        return value == null ? null : Double.valueOf(value);
    }

    private static CandidateMarketFeature availableMarketFeature(
            String code, double value, Instant decision) {
        String applicability = code.equals("INDEX_VOLUME_RATIO20")
                ? "美股_EQUITY"
                : code.equals("WTI_RET5") || code.equals("BRENT_RET5") || code.equals("GOLD_RET5")
                ? "GLOBAL_ASSET_AGNOSTIC" : "GLOBAL_EQUITY";
        return new CandidateMarketFeature(code, BigDecimal.valueOf(value),
                LocalDate.of(2026, 8, 7), decision, "TEST_AS_OF", "TEST", null,
                applicability, null, CandidateMarketFeature.AVAILABLE, null);
    }
}
