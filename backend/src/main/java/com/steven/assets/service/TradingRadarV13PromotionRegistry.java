package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Task 308 的純 Java promotion gate 與 immutable production registry。
 *
 * <p>registry 只保存通過 untouched holdout 與 walk-forward 的 V13 key；任何 key 缺失、樣本不足、
 * 任一指定 horizon 被拒絕或 candidate 不是 V13，{@link #resolve(ProductionKey)} 都明確回 V12。</p>
 */
public final class TradingRadarV13PromotionRegistry {

    public static final BigDecimal MIN_RETURN_PRACTICAL_DELTA_PCT = new BigDecimal("0.10");
    public static final BigDecimal DOWNSIDE_PRACTICAL_DELTA_PP = new BigDecimal("1.0");
    private static final int GENERAL_HOLDOUT_N = 200;
    private static final int GENERAL_HOLDOUT_CODES = 8;
    private static final int SPECIALIZED_HOLDOUT_N = 150;
    private static final int SPECIALIZED_HOLDOUT_CODES = 3;
    private static final int FOLD_N = 50;
    private static final int FOLD_CODES = 3;
    private static final int MIN_VALID_FOLDS = 3;

    private final RuleParameters fallback;
    private final Map<ProductionKey, RuleParameters> promoted;
    private final Map<ProductionKey, PromotionDecision> decisions;

    public enum Track {
        SHORT(List.of(5, 20)),
        MEDIUM(List.of(60, 120));

        private final List<Integer> requiredHorizons;

        Track(List<Integer> requiredHorizons) {
            this.requiredHorizons = requiredHorizons;
        }

        public List<Integer> requiredHorizons() { return requiredHorizons; }
    }

    /**
     * Exact production identity.  Asset/style/term/confidence are report-only
     * diagnostic strata and deliberately do not participate in equality or
     * promotion lookup; otherwise a sparse decile could silently create a
     * second runtime rule and shrink the promotion sample.
     */
    public record ProductionKey(
            String market,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String profile,
            Track track
    ) {
        public ProductionKey {
            new RadarBacktestExecution.CostKey(market, instrumentKind);
            profile = profile == null || profile.isBlank() ? "GENERAL" : profile.trim().toUpperCase();
            Objects.requireNonNull(track, "track");
        }

        /**
         * Legacy 8-field constructor.  The four diagnostic fields are retained
         * only at the call boundary for binary/source compatibility and are not
         * part of ProductionKey identity.
         */
        @Deprecated
        public ProductionKey(
                String market,
                RadarBacktestExecution.InstrumentKind instrumentKind,
                String profile,
                Track track,
                String assetClass,
                String stockStyle,
                String bondTerm,
                String confidenceDecile) {
            this(market, instrumentKind, profile, track);
        }

        /** Legacy diagnostic accessors; runtime identity is always four fields. */
        @Deprecated public String assetClass() { return "UNKNOWN"; }
        @Deprecated public String stockStyle() { return "UNKNOWN"; }
        @Deprecated public String bondTerm() { return "UNKNOWN"; }
        @Deprecated public String confidenceDecile() { return "ALL"; }

        public boolean specialized() {
            return instrumentKind == RadarBacktestExecution.InstrumentKind.BOND_ETF
                    || !"GENERAL".equals(profile);
        }
    }

    /** positive downsideImprovementPp 代表 candidate 的 <=-10% 比例較 baseline 低。 */
    public record HoldoutMetrics(
            int n,
            int codes,
            BigDecimal pairedMedianDeltaPct,
            BigDecimal pooledMeanDeltaPct,
            BigDecimal downsideImprovementPp,
            BigDecimal roundTripCostPct
    ) {
        public HoldoutMetrics {
            if (n < 0 || codes < 0) throw new IllegalArgumentException("n/codes 不可為負");
        }
    }

    public record FoldMetrics(
            int fold,
            int n,
            int codes,
            BigDecimal pairedMedianDeltaPct,
            BigDecimal pooledMeanDeltaPct,
            BigDecimal downsideImprovementPp
    ) {
        public FoldMetrics {
            if (fold < 1 || n < 0 || codes < 0) {
                throw new IllegalArgumentException("fold 從 1 起算且 n/codes 不可為負");
            }
        }

        boolean validSample() { return n >= FOLD_N && codes >= FOLD_CODES; }
    }

    public record HorizonEvidence(
            int horizon,
            HoldoutMetrics holdout,
            List<FoldMetrics> folds
    ) {
        public HorizonEvidence {
            if (horizon < 1 || horizon > 240) throw new IllegalArgumentException("horizon 必須介於 1..240");
            folds = folds == null ? List.of() : List.copyOf(folds);
        }
    }

    public record CandidatePromotion(
            RuleParameters parameters,
            Map<Integer, HorizonEvidence> evidenceByHorizon
    ) {
        public CandidatePromotion {
            Objects.requireNonNull(parameters, "parameters");
            evidenceByHorizon = evidenceByHorizon == null ? Map.of() : Map.copyOf(evidenceByHorizon);
        }
    }

    public record HorizonDecision(
            int horizon,
            boolean promoted,
            String reason,
            BigDecimal returnPracticalDeltaPct,
            int validFolds,
            int passingFolds,
            boolean catastrophicFold
    ) {}

    public record PromotionDecision(
            boolean promoted,
            String reason,
            Map<Integer, HorizonDecision> horizons
    ) {
        public PromotionDecision {
            horizons = horizons == null ? Map.of() : Map.copyOf(horizons);
        }
    }

    private TradingRadarV13PromotionRegistry(
            RuleParameters fallback,
            Map<ProductionKey, RuleParameters> promoted,
            Map<ProductionKey, PromotionDecision> decisions) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.promoted = Map.copyOf(promoted);
        this.decisions = Map.copyOf(decisions);
    }

    public static TradingRadarV13PromotionRegistry empty() {
        return new TradingRadarV13PromotionRegistry(RuleParameters.v12Default(), Map.of(), Map.of());
    }

    /** 建 registry 時一次完成 gate；之後沒有可變 register 路徑。 */
    public static TradingRadarV13PromotionRegistry build(
            Map<ProductionKey, CandidatePromotion> candidates) {
        RuleParameters fallback = RuleParameters.v12Default();
        if (candidates == null || candidates.isEmpty()) {
            return new TradingRadarV13PromotionRegistry(fallback, Map.of(), Map.of());
        }
        Map<ProductionKey, RuleParameters> promoted = new LinkedHashMap<>();
        Map<ProductionKey, PromotionDecision> decisions = new LinkedHashMap<>();
        for (var entry : candidates.entrySet()) {
            ProductionKey key = Objects.requireNonNull(entry.getKey(), "production key");
            CandidatePromotion candidate = entry.getValue();
            PromotionDecision decision;
            if (candidate == null || !RuleParameters.V13_VERSION.equals(candidate.parameters().ruleVersion())) {
                decision = new PromotionDecision(false, "CANDIDATE_NOT_V13", Map.of());
            } else {
                decision = evaluate(key, candidate.evidenceByHorizon());
            }
            decisions.put(key, decision);
            if (decision.promoted()) promoted.put(key, candidate.parameters());
        }
        return new TradingRadarV13PromotionRegistry(fallback, promoted, decisions);
    }

    /** 未通過或未登錄的 key 永遠回明確的 V12 default。 */
    public RuleParameters resolve(ProductionKey key) {
        Objects.requireNonNull(key, "key");
        return promoted.getOrDefault(key, fallback);
    }

    public RuleParameters resolve(
            String market,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String profile,
            Track track) {
        return resolve(new ProductionKey(market, instrumentKind, profile, track));
    }

    public RuleParameters resolve(
            String market,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String profile,
            Track track,
            String assetClass,
            String stockStyle,
            String bondTerm,
            String confidenceDecile) {
        return resolve(new ProductionKey(market, instrumentKind, profile, track,
                assetClass, stockStyle, bondTerm, confidenceDecile));
    }

    public boolean isPromoted(ProductionKey key) {
        return key != null && promoted.containsKey(key);
    }

    public boolean isPromoted(
            String market,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String profile,
            Track track) {
        return isPromoted(new ProductionKey(market, instrumentKind, profile, track));
    }

    public boolean isPromoted(
            String market,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String profile,
            Track track,
            String assetClass,
            String stockStyle,
            String bondTerm,
            String confidenceDecile) {
        return isPromoted(new ProductionKey(market, instrumentKind, profile, track,
                assetClass, stockStyle, bondTerm, confidenceDecile));
    }

    public PromotionDecision decision(ProductionKey key) {
        if (key == null) return new PromotionDecision(false, "MISSING_KEY", Map.of());
        return decisions.getOrDefault(key,
                new PromotionDecision(false, "NO_PROMOTION_EVIDENCE_RETAIN_V12", Map.of()));
    }

    public Map<ProductionKey, RuleParameters> promotedParameters() { return promoted; }

    public Map<ProductionKey, PromotionDecision> decisions() { return decisions; }

    static PromotionDecision evaluate(
            ProductionKey key, Map<Integer, HorizonEvidence> evidenceByHorizon) {
        Map<Integer, HorizonDecision> horizonDecisions = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (int horizon : key.track().requiredHorizons()) {
            HorizonEvidence evidence = evidenceByHorizon == null ? null : evidenceByHorizon.get(horizon);
            HorizonDecision decision = evaluateHorizon(key, horizon, evidence);
            horizonDecisions.put(horizon, decision);
            if (!decision.promoted()) rejected.add(horizon + ":" + decision.reason());
        }
        if (!rejected.isEmpty()) {
            return new PromotionDecision(false, "RETAIN_V12[" + String.join(",", rejected) + "]",
                    horizonDecisions);
        }
        return new PromotionDecision(true, "PROMOTED_ALL_REQUIRED_HORIZONS", horizonDecisions);
    }

    private static HorizonDecision evaluateHorizon(
            ProductionKey key, int horizon, HorizonEvidence evidence) {
        if (evidence == null || evidence.holdout() == null) {
            return reject(horizon, "MISSING_HOLDOUT", null, 0, 0, false);
        }
        HoldoutMetrics holdout = evidence.holdout();
        if (holdout.roundTripCostPct() == null
                || holdout.pairedMedianDeltaPct() == null
                || holdout.pooledMeanDeltaPct() == null
                || holdout.downsideImprovementPp() == null) {
            return reject(horizon, "MISSING_HOLDOUT_METRICS", null, 0, 0, false);
        }
        int minN = key.specialized() ? SPECIALIZED_HOLDOUT_N : GENERAL_HOLDOUT_N;
        int minCodes = key.specialized() ? SPECIALIZED_HOLDOUT_CODES : GENERAL_HOLDOUT_CODES;
        BigDecimal practical = holdout.roundTripCostPct().max(MIN_RETURN_PRACTICAL_DELTA_PCT);
        if (holdout.n() < minN || holdout.codes() < minCodes) {
            return reject(horizon, "INSUFFICIENT_HOLDOUT_SAMPLE", practical, 0, 0, false);
        }
        if (!passesAdvantage(holdout.pairedMedianDeltaPct(), holdout.pooledMeanDeltaPct(),
                holdout.downsideImprovementPp(), practical)) {
            return reject(horizon, "NO_PRACTICAL_HOLDOUT_ADVANTAGE", practical, 0, 0, false);
        }

        List<FoldMetrics> valid = evidence.folds().stream().filter(FoldMetrics::validSample).toList();
        boolean catastrophic = evidence.folds().stream().anyMatch(fold -> catastrophic(fold, practical));
        int passing = (int) valid.stream().filter(fold -> passesAdvantage(
                fold.pairedMedianDeltaPct(), fold.pooledMeanDeltaPct(),
                fold.downsideImprovementPp(), practical)).count();
        if (valid.size() < MIN_VALID_FOLDS) {
            return reject(horizon, "INSUFFICIENT_VALID_FOLDS", practical, valid.size(), passing, catastrophic);
        }
        if (catastrophic) {
            return reject(horizon, "CATASTROPHIC_FOLD", practical, valid.size(), passing, true);
        }
        if (passing * 10 < valid.size() * 6) {
            return reject(horizon, "FOLD_PASS_RATE_BELOW_60_PERCENT", practical,
                    valid.size(), passing, false);
        }
        return new HorizonDecision(horizon, true, "PASS", practical, valid.size(), passing, false);
    }

    private static boolean passesAdvantage(
            BigDecimal pairedMedian,
            BigDecimal pooledMean,
            BigDecimal downsideImprovement,
            BigDecimal practical) {
        if (pairedMedian == null || pooledMean == null || downsideImprovement == null || practical == null) {
            return false;
        }
        boolean returnPath = pairedMedian.compareTo(practical) >= 0
                && pooledMean.signum() >= 0
                && downsideImprovement.signum() >= 0;
        boolean downsidePath = downsideImprovement.compareTo(DOWNSIDE_PRACTICAL_DELTA_PP) >= 0
                && pairedMedian.compareTo(practical.negate()) >= 0
                && pooledMean.compareTo(practical.negate()) >= 0;
        return returnPath || downsidePath;
    }

    private static boolean catastrophic(FoldMetrics fold, BigDecimal practical) {
        if (fold == null || practical == null) return false;
        BigDecimal doubledLoss = practical.multiply(BigDecimal.valueOf(-2));
        boolean returnsCollapse = fold.pairedMedianDeltaPct() != null
                && fold.pooledMeanDeltaPct() != null
                && fold.pairedMedianDeltaPct().compareTo(doubledLoss) < 0
                && fold.pooledMeanDeltaPct().compareTo(doubledLoss) < 0;
        boolean downsideCollapse = fold.downsideImprovementPp() != null
                && fold.downsideImprovementPp().compareTo(new BigDecimal("-2.0")) < 0;
        return returnsCollapse || downsideCollapse;
    }

    private static HorizonDecision reject(
            int horizon,
            String reason,
            BigDecimal practical,
            int validFolds,
            int passingFolds,
            boolean catastrophic) {
        return new HorizonDecision(horizon, false, reason, practical,
                validFolds, passingFolds, catastrophic);
    }
}
