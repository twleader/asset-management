package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Task 308 可校準規則參數的不可變快照。
 *
 * <p>每一個 candidate 都必須帶著完整快照重跑 engine；這裡不提供 setter，也會 defensive-copy
 * 權重差異。production registry 的 fallback 固定是 {@link #v12Default()}，不會因建立了 V13
 * candidate 就改變現行規則。</p>
 */
public record RuleParameters(
        String parameterSetId,
        String ruleVersion,
        ActionThresholds shortThresholds,
        ActionThresholds mediumThresholds,
        BigDecimal confidenceThreshold,
        BigDecimal normalizedBiasFloor,
        BigDecimal normalizedBiasMultiple,
        /** Candidate timing extreme threshold above zero (normalized sigma units). */
        BigDecimal normalizedBiasUpperMultiple,
        /** Candidate timing extreme threshold below zero (normalized sigma units). */
        BigDecimal normalizedBiasLowerMultiple,
        /** Explicitly enable the volatility-normalized BIAS/timing path. */
        boolean normalizedBiasEnabled,
        BigDecimal downsideActionThresholdPct,
        WeakeningCondition weakeningCondition,
        Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
        BondRateCandidate bondRateCandidate
) {

    public static final String V12_VERSION = "TW_RULES_V12";
    public static final String V13_VERSION = "TW_RULES_V13";

    public RuleParameters {
        if (parameterSetId == null || parameterSetId.isBlank()) {
            throw new IllegalArgumentException("parameterSetId 不可空白");
        }
        if (!V12_VERSION.equals(ruleVersion) && !V13_VERSION.equals(ruleVersion)) {
            throw new IllegalArgumentException("ruleVersion 僅允許 TW_RULES_V12/TW_RULES_V13");
        }
        Objects.requireNonNull(shortThresholds, "shortThresholds");
        Objects.requireNonNull(mediumThresholds, "mediumThresholds");
        requireRange(confidenceThreshold, BigDecimal.ZERO, BigDecimal.ONE, "confidenceThreshold");
        if (V13_VERSION.equals(ruleVersion)
                && confidenceThreshold.compareTo(new BigDecimal("0.70")) < 0) {
            throw new IllegalArgumentException("V13 confidenceThreshold 不得低於 0.70");
        }
        if (normalizedBiasEnabled) {
            requireRange(normalizedBiasFloor, BigDecimal.ZERO, BigDecimal.valueOf(100),
                    "normalizedBiasFloor");
            requirePositive(normalizedBiasMultiple, "normalizedBiasMultiple");
            requirePositive(normalizedBiasUpperMultiple, "normalizedBiasUpperMultiple");
            requirePositive(normalizedBiasLowerMultiple, "normalizedBiasLowerMultiple");
        } else {
            normalizedBiasFloor = null;
            normalizedBiasMultiple = null;
            normalizedBiasUpperMultiple = null;
            normalizedBiasLowerMultiple = null;
        }
        requireRange(downsideActionThresholdPct, BigDecimal.ZERO, BigDecimal.valueOf(100),
                "downsideActionThresholdPct");
        Objects.requireNonNull(weakeningCondition, "weakeningCondition");

        Map<CandidateWeight, BigDecimal> copied = new LinkedHashMap<>();
        if (candidateWeightDeltas != null) {
            for (var entry : candidateWeightDeltas.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "candidate weight key");
                BigDecimal value = Objects.requireNonNull(entry.getValue(), "candidate weight value");
                requireRange(value, BigDecimal.valueOf(-1), BigDecimal.ONE,
                        "candidateWeightDeltas[" + entry.getKey() + "]");
                copied.put(entry.getKey(), value);
            }
        }
        candidateWeightDeltas = Map.copyOf(copied);
        bondRateCandidate = bondRateCandidate == null
                ? BondRateCandidate.v12Fallback() : bondRateCandidate;
    }

    /** Compatibility constructor before candidate timing had separate sides. */
    public RuleParameters(
            String parameterSetId,
            String ruleVersion,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal normalizedBiasFloor,
            BigDecimal normalizedBiasMultiple,
            BigDecimal downsideActionThresholdPct,
            WeakeningCondition weakeningCondition,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate) {
        this(parameterSetId, ruleVersion, shortThresholds, mediumThresholds,
                confidenceThreshold, normalizedBiasFloor, normalizedBiasMultiple,
                normalizedBiasMultiple, normalizedBiasMultiple,
                true, downsideActionThresholdPct, weakeningCondition, candidateWeightDeltas,
                bondRateCandidate);
    }

    /** Compatibility constructor before normalizedBiasEnabled was explicit. */
    public RuleParameters(
            String parameterSetId,
            String ruleVersion,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal normalizedBiasFloor,
            BigDecimal normalizedBiasMultiple,
            BigDecimal normalizedBiasUpperMultiple,
            BigDecimal normalizedBiasLowerMultiple,
            BigDecimal downsideActionThresholdPct,
            WeakeningCondition weakeningCondition,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate) {
        this(parameterSetId, ruleVersion, shortThresholds, mediumThresholds, confidenceThreshold,
                normalizedBiasFloor, normalizedBiasMultiple, normalizedBiasUpperMultiple,
                normalizedBiasLowerMultiple, true, downsideActionThresholdPct, weakeningCondition,
                candidateWeightDeltas, bondRateCandidate);
    }

    public enum CandidateWeight {
        SHORT_MARKET,
        MEDIUM_MARKET,
        /** Candidate-only typed market numeric contribution; V12 baseline is zero. */
        SHORT_MARKET_FEATURE,
        /** Candidate-only typed market numeric contribution; V12 baseline is zero. */
        MEDIUM_MARKET_FEATURE,
        SHORT_TREASURY,
        MEDIUM_TREASURY
    }

    /** 分數區間由高到低必須嚴格遞減。 */
    public record ActionThresholds(int buy, int hold, int caution, int reduce) {
        public ActionThresholds {
            if (buy > 100 || reduce < 0 || !(buy > hold && hold > caution && caution > reduce)) {
                throw new IllegalArgumentException("action thresholds 必須為 100>=buy>hold>caution>reduce>=0");
            }
        }
    }

    /** V13 的 weakening 固定要求結構與獨立動能/流動性兩類證據。 */
    public record WeakeningCondition(
            boolean requireStructureBelow,
            boolean requireKdDeadCross,
            BigDecimal downVolumeRatioFloor
    ) {
        public WeakeningCondition {
            if (!requireStructureBelow || !requireKdDeadCross) {
                throw new IllegalArgumentException("V13 weakening 不得移除結構轉弱或 KD 死叉任一類證據");
            }
            requireRange(downVolumeRatioFloor, BigDecimal.ONE, BigDecimal.valueOf(10),
                    "downVolumeRatioFloor");
        }

        public static WeakeningCondition conservativeV13() {
            return new WeakeningCondition(true, true, new BigDecimal("1.5"));
        }

        public static WeakeningCondition withDownVolumeRatioFloor(BigDecimal floor) {
            return new WeakeningCondition(true, true, floor);
        }
    }

    /**
     * Immutable bond-rate calibration dimension. Each term must choose only from
     * the t275.8 eligible tenor pair. The curve-shape signal is the change in the
     * pair's long-minus-short spread; zero weight preserves the V12 level-only fallback.
     */
    public record BondRateCandidate(
            Map<String, String> tenorByBondTerm,
            BigDecimal curveShapeWeight,
            BigDecimal returnPctAtUnit) {

        private static final Map<String, List<String>> ELIGIBLE = Map.of(
                AssetClassifier.SHORT, List.of("M3", "Y5"),
                AssetClassifier.MID, List.of("Y5", "Y10"),
                AssetClassifier.LONG, List.of("Y10", "Y30"));

        public BondRateCandidate {
            Map<String, String> copied = new LinkedHashMap<>();
            if (tenorByBondTerm == null) {
                throw new IllegalArgumentException("bond tenor candidate 不可缺漏");
            }
            for (var entry : ELIGIBLE.entrySet()) {
                String tenor = tenorByBondTerm.get(entry.getKey());
                if (tenor == null || !entry.getValue().contains(tenor)) {
                    throw new IllegalArgumentException(entry.getKey() + " tenor 只允許 " + entry.getValue());
                }
                copied.put(entry.getKey(), tenor);
            }
            if (tenorByBondTerm.size() != ELIGIBLE.size()) {
                throw new IllegalArgumentException("bond tenor candidate 只能包含 SHORT/MID/LONG");
            }
            requireRange(curveShapeWeight, BigDecimal.valueOf(-1), BigDecimal.ONE,
                    "curveShapeWeight");
            requireRange(returnPctAtUnit, new BigDecimal("0.01"), BigDecimal.valueOf(100),
                    "returnPctAtUnit");
            tenorByBondTerm = Map.copyOf(copied);
        }

        public String tenorFor(String bondTerm) {
            return bondTerm == null ? null : tenorByBondTerm.get(bondTerm.trim().toUpperCase());
        }

        public List<String> eligibleTenors(String bondTerm) {
            return bondTerm == null ? List.of()
                    : ELIGIBLE.getOrDefault(bondTerm.trim().toUpperCase(), List.of());
        }

        public String shapeShortTenor(String bondTerm) {
            List<String> pair = eligibleTenors(bondTerm);
            return pair.size() == 2 ? pair.get(0) : null;
        }

        public String shapeLongTenor(String bondTerm) {
            List<String> pair = eligibleTenors(bondTerm);
            return pair.size() == 2 ? pair.get(1) : null;
        }

        public static BondRateCandidate v12Fallback() {
            return of("M3", "Y10", "Y30", BigDecimal.ZERO, BigDecimal.ONE);
        }

        public static BondRateCandidate of(
                String shortTenor,
                String midTenor,
                String longTenor,
                BigDecimal curveShapeWeight,
                BigDecimal returnPctAtUnit) {
            return new BondRateCandidate(Map.of(
                    AssetClassifier.SHORT, shortTenor,
                    AssetClassifier.MID, midTenor,
                    AssetClassifier.LONG, longTenor),
                    curveShapeWeight, returnPctAtUnit);
        }
    }

    /** 現行 production baseline；建立 candidate 不會修改此物件。 */
    public static RuleParameters v12Default() {
        ActionThresholds thresholds = new ActionThresholds(75, 55, 40, 25);
        return new RuleParameters("V12_DEFAULT", V12_VERSION, thresholds, thresholds,
                BigDecimal.ZERO, null, null,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), false,
                BigDecimal.valueOf(100), WeakeningCondition.conservativeV13(), Map.of(),
                BondRateCandidate.v12Fallback());
    }

    /** 便於固定 calibration grid 建立 V13 candidate；所有值仍經 canonical constructor 驗證。 */
    public static RuleParameters v13Candidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal normalizedBiasFloor,
            BigDecimal normalizedBiasMultiple,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas) {
        return new RuleParameters(parameterSetId, V13_VERSION, shortThresholds, mediumThresholds,
                confidenceThreshold, normalizedBiasFloor, normalizedBiasMultiple,
                downsideActionThresholdPct, WeakeningCondition.conservativeV13(), candidateWeightDeltas,
                BondRateCandidate.v12Fallback());
    }

    public static RuleParameters v13Candidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal normalizedBiasFloor,
            BigDecimal normalizedBiasMultiple,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate) {
        return new RuleParameters(parameterSetId, V13_VERSION, shortThresholds, mediumThresholds,
                confidenceThreshold, normalizedBiasFloor, normalizedBiasMultiple,
                downsideActionThresholdPct, WeakeningCondition.conservativeV13(), candidateWeightDeltas,
                bondRateCandidate);
    }

    /** Candidate factory with independently calibrated upper/lower timing multiples. */
    public static RuleParameters v13Candidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal sigmaFloorRatio,
            BigDecimal normalizedBiasMultiple,
            BigDecimal normalizedBiasUpperMultiple,
            BigDecimal normalizedBiasLowerMultiple,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas) {
        return v13Candidate(parameterSetId, shortThresholds, mediumThresholds,
                confidenceThreshold, sigmaFloorRatio, normalizedBiasMultiple,
                normalizedBiasUpperMultiple, normalizedBiasLowerMultiple,
                downsideActionThresholdPct, candidateWeightDeltas,
                BondRateCandidate.v12Fallback());
    }

    /** Full candidate factory with independent timing sides and bond-rate dimension. */
    public static RuleParameters v13Candidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal sigmaFloorRatio,
            BigDecimal normalizedBiasMultiple,
            BigDecimal normalizedBiasUpperMultiple,
            BigDecimal normalizedBiasLowerMultiple,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate) {
        return new RuleParameters(parameterSetId, V13_VERSION, shortThresholds, mediumThresholds,
                confidenceThreshold, sigmaFloorRatio, normalizedBiasMultiple,
                normalizedBiasUpperMultiple, normalizedBiasLowerMultiple,
                true, downsideActionThresholdPct, WeakeningCondition.conservativeV13(),
                candidateWeightDeltas, bondRateCandidate);
    }

    /** Full candidate factory with an independently calibrated weakening condition. */
    public static RuleParameters v13Candidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal sigmaFloorRatio,
            BigDecimal normalizedBiasMultiple,
            BigDecimal normalizedBiasUpperMultiple,
            BigDecimal normalizedBiasLowerMultiple,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate,
            WeakeningCondition weakeningCondition) {
        return new RuleParameters(parameterSetId, V13_VERSION, shortThresholds, mediumThresholds,
                confidenceThreshold, sigmaFloorRatio, normalizedBiasMultiple,
                normalizedBiasUpperMultiple, normalizedBiasLowerMultiple,
                true, downsideActionThresholdPct, weakeningCondition,
                candidateWeightDeltas, bondRateCandidate);
    }

    /** V13 candidate with normalized BIAS explicitly disabled; null normalized values are intentional. */
    public static RuleParameters v13DisabledCandidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate) {
        return new RuleParameters(parameterSetId, V13_VERSION, shortThresholds, mediumThresholds,
                confidenceThreshold, null, null, null, null, false,
                downsideActionThresholdPct, WeakeningCondition.conservativeV13(),
                candidateWeightDeltas, bondRateCandidate);
    }

    /** Disabled-normalized candidate with an independent weakening calibration dimension. */
    public static RuleParameters v13DisabledCandidate(
            String parameterSetId,
            ActionThresholds shortThresholds,
            ActionThresholds mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal downsideActionThresholdPct,
            Map<CandidateWeight, BigDecimal> candidateWeightDeltas,
            BondRateCandidate bondRateCandidate,
            WeakeningCondition weakeningCondition) {
        return new RuleParameters(parameterSetId, V13_VERSION, shortThresholds, mediumThresholds,
                confidenceThreshold, null, null, null, null, false,
                downsideActionThresholdPct, weakeningCondition,
                candidateWeightDeltas, bondRateCandidate);
    }

    public int nonZeroCandidateWeightCount() {
        return (int) candidateWeightDeltas.values().stream()
                .filter(value -> value.signum() != 0)
                .count();
    }

    /** V13 normalized-BIAS sigma floor ratio (legacy field name kept for wire compatibility). */
    public BigDecimal sigmaFloorRatio() {
        return normalizedBiasFloor;
    }

    /** selector 最後一層 tie-break 使用；數值越小越接近 V12/default。 */
    public BigDecimal distanceFrom(RuleParameters baseline) {
        Objects.requireNonNull(baseline, "baseline");
        BigDecimal distance = BigDecimal.ZERO
                .add(confidenceThreshold.subtract(baseline.confidenceThreshold).abs())
                .add(nullableDistance(normalizedBiasFloor, baseline.normalizedBiasFloor))
                .add(nullableDistance(normalizedBiasMultiple, baseline.normalizedBiasMultiple))
                .add(nullableDistance(normalizedBiasUpperMultiple, baseline.normalizedBiasUpperMultiple))
                .add(nullableDistance(normalizedBiasLowerMultiple, baseline.normalizedBiasLowerMultiple))
                .add(downsideActionThresholdPct.subtract(baseline.downsideActionThresholdPct).abs())
                .add(thresholdDistance(shortThresholds, baseline.shortThresholds))
                .add(thresholdDistance(mediumThresholds, baseline.mediumThresholds));
        for (CandidateWeight key : CandidateWeight.values()) {
            BigDecimal left = candidateWeightDeltas.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal right = baseline.candidateWeightDeltas.getOrDefault(key, BigDecimal.ZERO);
            distance = distance.add(left.subtract(right).abs());
        }
        distance = distance
                .add(curveShapeDistance(bondRateCandidate, baseline.bondRateCandidate))
                .add(bondRateCandidate.returnPctAtUnit()
                        .subtract(baseline.bondRateCandidate.returnPctAtUnit()).abs());
        return distance;
    }

    private static BigDecimal nullableDistance(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) return BigDecimal.ZERO;
        if (left == null || right == null) return BigDecimal.ONE;
        return left.subtract(right).abs();
    }

    private static BigDecimal curveShapeDistance(BondRateCandidate left, BondRateCandidate right) {
        BigDecimal distance = left.curveShapeWeight().subtract(right.curveShapeWeight()).abs();
        for (String term : List.of(AssetClassifier.SHORT, AssetClassifier.MID, AssetClassifier.LONG)) {
            if (!Objects.equals(left.tenorFor(term), right.tenorFor(term))) {
                distance = distance.add(BigDecimal.ONE);
            }
        }
        return distance;
    }

    private static BigDecimal thresholdDistance(ActionThresholds a, ActionThresholds b) {
        return BigDecimal.valueOf(Math.abs(a.buy() - b.buy())
                + Math.abs(a.hold() - b.hold())
                + Math.abs(a.caution() - b.caution())
                + Math.abs(a.reduce() - b.reduce()));
    }

    private static void requireRange(BigDecimal value, BigDecimal min, BigDecimal max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " 必須介於 " + min + ".." + max);
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException(name + " 必須介於 (0,100]");
        }
    }
}
