package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingRadarCalibrationSelectorTest {

    @Test
    void ruleParametersDefensivelyCopyCandidateWeights() {
        Map<RuleParameters.CandidateWeight, BigDecimal> mutable = new LinkedHashMap<>();
        mutable.put(RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.01"));
        RuleParameters parameters = candidate("C1", new BigDecimal("0.70"), mutable);

        mutable.put(RuleParameters.CandidateWeight.SHORT_TREASURY, new BigDecimal("0.02"));

        assertThat(parameters.candidateWeightDeltas())
                .containsOnlyKeys(RuleParameters.CandidateWeight.SHORT_MARKET);
        assertThatThrownBy(() -> parameters.candidateWeightDeltas().put(
                RuleParameters.CandidateWeight.MEDIUM_MARKET, BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(RuleParameters.v12Default().ruleVersion()).isEqualTo("TW_RULES_V12");
    }

    @Test
    void selectorUsesTheDocumentedTieBreakOrderWithoutHoldoutInput() {
        RuleParameters baseline = RuleParameters.v12Default();
        RuleParameters a = candidate("A", new BigDecimal("0.70"), Map.of());
        RuleParameters b = candidate("B", new BigDecimal("0.70"), Map.of());

        // 1. downside 較低優先，即使 return 較低。
        assertThat(select(baseline,
                score(a, "10", "0.1", "0.1"),
                score(b, "9", "0.0", "0.0"))).isEqualTo(b);
        // 2. downside 同分後看 paired median。
        assertThat(select(baseline,
                score(a, "9", "0.2", "0.1"),
                score(b, "9", "0.3", "0.0"))).isEqualTo(b);
        // 3. paired 同分後看 pooled mean。
        assertThat(select(baseline,
                score(a, "9", "0.3", "0.1"),
                score(b, "9", "0.3", "0.2"))).isEqualTo(b);

        RuleParameters sparse = candidate("SPARSE", new BigDecimal("0.70"),
                Map.of(RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.01")));
        RuleParameters dense = candidate("DENSE", new BigDecimal("0.70"), Map.of(
                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.01"),
                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.01")));
        // 4. 三項績效同分後選較少非零 candidate weights。
        assertThat(select(baseline,
                score(dense, "9", "0.3", "0.2"),
                score(sparse, "9", "0.3", "0.2"))).isEqualTo(sparse);

        RuleParameters near = candidate("NEAR", new BigDecimal("0.70"), Map.of());
        RuleParameters far = candidate("FAR", new BigDecimal("0.80"), Map.of());
        // 5. 非零數也相同後選距 V12/default 較近者。
        assertThat(select(baseline,
                score(far, "9", "0.3", "0.2"),
                score(near, "9", "0.3", "0.2"))).isEqualTo(near);
    }

    @Test
    void nonZeroPairedAndPooledDeltaCanDriveSelection() {
        RuleParameters baseline = RuleParameters.v12Default();
        RuleParameters weak = candidate("WEAK", new BigDecimal("0.70"), Map.of());
        RuleParameters improved = candidate("IMPROVED", new BigDecimal("0.70"), Map.of());

        // 兩候選 downside coverage 相同時，真實 paired／pooled delta 不得被固定零值吞掉。
        assertThat(select(baseline,
                score(weak, "9", "0.05", "0.02"),
                score(improved, "9", "0.20", "0.15")))
                .isEqualTo(improved);
    }

    @Test
    void distanceTieBreakIncludesWeakeningFloorWhileSafetyBooleansRemainInvariant() {
        RuleParameters baseline = RuleParameters.v12Default();
        RuleParameters near = weakeningCandidate("Z_NEAR", "1.50");
        RuleParameters far = weakeningCandidate("A_FAR", "2.00");

        assertThat(select(baseline,
                score(far, "9", "0.3", "0.2"),
                score(near, "9", "0.3", "0.2"))).isEqualTo(near);
        assertThat(near.distanceFrom(baseline)).isLessThan(far.distanceFrom(baseline));
        assertThatThrownBy(() -> new RuleParameters.WeakeningCondition(
                false, true, new BigDecimal("1.50")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleParameters.WeakeningCondition(
                true, false, new BigDecimal("1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Task 446：兩個候選除了 chasedDailyMoveThresholdPct／buyGateOverboughtBiasPct 外
     * 其餘完全相同（同用 v13BuyGateCandidate 工廠，僅這兩個新維度不同），
     * distanceFrom(baseline) 必須為非零，且離 V12 預設值（5／12）較近者距離較小。
     */
    @Test
    void distanceFromIncludesBuyGateVetoThresholdsAndOrdersCandidatesByProximity() {
        RuleParameters baseline = RuleParameters.v12Default();
        var thresholds = new RuleParameters.ActionThresholds(75, 55, 40, 25);
        RuleParameters near = RuleParameters.v13BuyGateCandidate("NEAR_BUYGATE", thresholds, thresholds,
                new BigDecimal("6.0"), new BigDecimal("13.0"));
        RuleParameters far = RuleParameters.v13BuyGateCandidate("FAR_BUYGATE", thresholds, thresholds,
                new BigDecimal("8.0"), new BigDecimal("15.0"));

        assertThat(near.distanceFrom(baseline)).isGreaterThan(BigDecimal.ZERO);
        assertThat(far.distanceFrom(baseline)).isGreaterThan(BigDecimal.ZERO);
        assertThat(near.distanceFrom(baseline)).isLessThan(far.distanceFrom(baseline));
        assertThat(select(baseline,
                score(far, "9", "0.3", "0.2"),
                score(near, "9", "0.3", "0.2"))).isEqualTo(near);
    }

    @Test
    void invalidParameterGridFailsBeforeCalibration() {
        assertThatThrownBy(() -> new RuleParameters.ActionThresholds(55, 75, 40, 25))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleParameters.v13Candidate("BAD",
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("1.01"), new BigDecimal("0.01"), new BigDecimal("2"),
                new BigDecimal("10"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleParameters.v13Candidate("LOW_CONFIDENCE",
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("0.69"), new BigDecimal("0.01"), new BigDecimal("2"),
                new BigDecimal("10"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidenceThreshold");
        assertThatThrownBy(() -> RuleParameters.v13Candidate("ZERO_NORMALIZED_SCALE",
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new BigDecimal("0.70"), new BigDecimal("0.01"), BigDecimal.ZERO,
                new BigDecimal("10"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizedBiasMultiple");
    }

    private static RuleParameters select(
            RuleParameters baseline, TradingRadarCalibrationSelector.CalibrationScore... scores) {
        return TradingRadarCalibrationSelector.select(List.of(scores), baseline).orElseThrow();
    }

    private static TradingRadarCalibrationSelector.CalibrationScore score(
            RuleParameters parameters, String downside, String paired, String pooled) {
        return new TradingRadarCalibrationSelector.CalibrationScore(parameters,
                new BigDecimal(downside), new BigDecimal(paired), new BigDecimal(pooled));
    }

    private static RuleParameters candidate(
            String id,
            BigDecimal confidence,
            Map<RuleParameters.CandidateWeight, BigDecimal> weights) {
        return RuleParameters.v13Candidate(id,
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                new RuleParameters.ActionThresholds(75, 55, 40, 25),
                confidence, new BigDecimal("0.01"), new BigDecimal("2"),
                new BigDecimal("15"), weights);
    }

    private static RuleParameters weakeningCandidate(String id, String volumeFloor) {
        var thresholds = new RuleParameters.ActionThresholds(75, 55, 40, 25);
        return RuleParameters.v13Candidate(id, thresholds, thresholds,
                new BigDecimal("0.70"), new BigDecimal("0.01"), new BigDecimal("2"),
                new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("15"), Map.of(),
                RuleParameters.BondRateCandidate.v12Fallback(),
                RuleParameters.WeakeningCondition.withDownVolumeRatioFloor(
                        new BigDecimal(volumeFloor)));
    }
}
