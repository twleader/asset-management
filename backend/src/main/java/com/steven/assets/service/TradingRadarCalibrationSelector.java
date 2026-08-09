package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * 只讀 calibration 統計的 deterministic selector；holdout 欄位刻意不存在於輸入型別。
 */
public final class TradingRadarCalibrationSelector {

    private TradingRadarCalibrationSelector() {}

    public record CalibrationScore(
            RuleParameters parameters,
            BigDecimal downsideRatePct,
            BigDecimal pairedMedianDeltaPct,
            BigDecimal pooledMeanDeltaPct
    ) {
        public CalibrationScore {
            Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(downsideRatePct, "downsideRatePct");
            Objects.requireNonNull(pairedMedianDeltaPct, "pairedMedianDeltaPct");
            Objects.requireNonNull(pooledMeanDeltaPct, "pooledMeanDeltaPct");
        }
    }

    /**
     * 固定 tie-break：downside 較低、paired median 較高、pooled mean 較高、非零 candidate
     * weights 較少、距 V12 較近。完全同分時以 id 收斂成可重現結果。
     */
    public static Optional<RuleParameters> select(
            Collection<CalibrationScore> candidates, RuleParameters baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        Comparator<CalibrationScore> order = Comparator
                .comparing(CalibrationScore::downsideRatePct)
                .thenComparing(CalibrationScore::pairedMedianDeltaPct, Comparator.reverseOrder())
                .thenComparing(CalibrationScore::pooledMeanDeltaPct, Comparator.reverseOrder())
                .thenComparingInt(score -> score.parameters().nonZeroCandidateWeightCount())
                .thenComparing(score -> score.parameters().distanceFrom(baseline))
                .thenComparing(score -> score.parameters().parameterSetId());
        return candidates.stream()
                .filter(Objects::nonNull)
                .sorted(order)
                .map(CalibrationScore::parameters)
                .findFirst();
    }
}
