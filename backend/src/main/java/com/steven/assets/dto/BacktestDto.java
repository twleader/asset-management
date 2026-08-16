package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交易雷達規則回測的請求／回應契約（Task 273／Requirement 56）。
 *
 * <p><b>本檔即 273.4b.3 所要求的「輸入／輸出契約」單一事實來源</b>——t274／t275／t276／t277
 * 的驗收指令依此撰寫。欄位增刪須同步更新那四份任務檔的驗收段。</p>
 *
 * <p><b>輸出一律表述為「歷史上此條件成立後的報酬分布」</b>。不得出現「預測」「將會」「機率為」
 * 「能降低風險」等語句（Task 273 的 273.8.1，沿用 {@code KD_OVERHEAT_K} Javadoc 已建立的紀律）。
 * 亦刻意<b>不輸出 p 值與信賴區間</b>：單一市場、單一十年期、標的高度重疊，且連續多日同一訊號成立
 * 造成嚴重自相關，古典檢定的前提不成立。</p>
 */
public final class BacktestDto {

    private BacktestDto() {}

    /**
     * @param codes      標的代號清單；null／空＝全部台股（不含 {@code 0000}）。
     * @param from       起始交易日（含）；null＝不設限。
     * @param to         結束交易日（含）；null＝不設限。
     * @param horizons   前瞻交易日數；null／空＝ {@code [5, 20, 60, 120]}。
     * @param thresholds 門檻掃描（273.4b.1）。key 為可調量名稱、value 為候選值清單。
     *                   支援的 key：{@code biasSigma}（季線乖離的 σ 倍數）、{@code sigmaFloor}
     *                   （σ 絕對下限）、{@code kdOverheat}、{@code kdOversold}、{@code volumeRatio}。
     *                   每個候選值各產生一條述詞、各自完整統計。
     * @param predicates 額外的組合述詞（273.4b.2），語法為 {@code 基礎條件+附加條件}，
     *                   例如 {@code EXTREME_OVERSOLD+SCORE_LT_40}。null／空＝只跑內建述詞。
     */
    public record Request(
            List<String> codes,
            String from,
            String to,
            List<Integer> horizons,
            Map<String, List<BigDecimal>> thresholds,
            List<String> predicates,
            /** Task 308：null 代表沿用 Task 273 舊路徑；V13 預設為台股。 */
            Set<String> markets,
            BigDecimal calibrationRatio,
            Integer walkForwardFolds,
            Map<CostKey, CostAssumption> costs,
            Boolean includeCloseFallbackSensitivity
    ) {
        /** Task 273 的六欄建構形狀永久保留，避免既有 Java 呼叫端與測試斷裂。 */
        public Request(
                List<String> codes,
                String from,
                String to,
                List<Integer> horizons,
                Map<String, List<BigDecimal>> thresholds,
                List<String> predicates) {
            this(codes, from, to, horizons, thresholds, predicates,
                    null, null, null, null, null);
        }

        /** 只有明確帶入任一 Task 308 欄位才啟用新報告；舊空物件仍保持原行為。 */
        public boolean v13Requested() {
            return markets != null || calibrationRatio != null || walkForwardFolds != null
                    || costs != null || includeCloseFallbackSensitivity != null;
        }
    }

    /** V13 report 的標的範圍；只有完整市場可成為 production promotion 證據。 */
    public enum UniverseMode { FULL_MARKET, BOUNDED_DIAGNOSTIC }

    public enum InstrumentKind { STOCK, EQUITY_ETF, BOND_ETF }

    public record CostKey(String market, InstrumentKind instrumentKind) {
        /** JSON map key 格式：{@code 台股|STOCK}；供 Jackson key deserializer 使用。 */
        public CostKey(String encoded) {
            this(parseMarket(encoded), parseKind(encoded));
        }

        private static String parseMarket(String encoded) {
            int split = encoded == null ? -1 : encoded.indexOf('|');
            if (split <= 0) throw new IllegalArgumentException("cost key 必須為 market|instrumentKind");
            return encoded.substring(0, split);
        }

        private static InstrumentKind parseKind(String encoded) {
            int split = encoded == null ? -1 : encoded.indexOf('|');
            if (split <= 0 || split == encoded.length() - 1) {
                throw new IllegalArgumentException("cost key 必須為 market|instrumentKind");
            }
            return InstrumentKind.valueOf(encoded.substring(split + 1));
        }

        @Override
        public String toString() {
            return market + "|" + instrumentKind;
        }
    }

    /** 比率欄位皆為百分比；這是可覆寫的回測模型假設，不是券商報價。 */
    public record CostAssumption(
            BigDecimal buyFeePct,
            BigDecimal sellFeePct,
            BigDecimal sellTaxPct,
            BigDecimal slippageEachSidePct,
            String sourceLabel,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {}

    /**
     * @param ruleVersion    產出這份統計的規則版本（本任務不升版，應為既有值）。
     * @param dataFrom       實際涵蓋的最早交易日。
     * @param dataTo         實際涵蓋的最晚交易日。
     * @param codeCount      納入統計的標的數。
     * @param horizons       實際使用的 horizon。
     * @param perCode        逐標的的樣本揭露（273.7.1）。
     * @param results        每個（述詞 × held × horizon）的統計。
     * @param pairedByCode   逐標的配對比較的彙總（273.6.2）。
     * @param failedCodes    因讀取或還原失敗而未納入統計的標的。**必須可見**——否則「N 檔的統計」
     *                       與「本來就只有 N 檔」無法區分，結果不可獨立複驗。
     * @param notes          必須讓讀者看見的限制與揭露。
     */
    public record Response(
            String ruleVersion,
            String dataFrom,
            String dataTo,
            int codeCount,
            List<Integer> horizons,
            List<CodeCoverage> perCode,
            List<PredicateStat> results,
            List<PairedStat> pairedByCode,
            List<String> failedCodes,
            List<String> notes,
            /** null 代表 legacy Task 273 request；非 null 才是 Task 308 可成交報告。 */
            V13Report v13
    ) {
        /** Task 273 的九欄建構形狀保留。 */
        public Response(
                String ruleVersion,
                String dataFrom,
                String dataTo,
                int codeCount,
                List<Integer> horizons,
                List<CodeCoverage> perCode,
                List<PredicateStat> results,
                List<PairedStat> pairedByCode,
                List<String> failedCodes,
                List<String> notes) {
            this(ruleVersion, dataFrom, dataTo, codeCount, horizons, perCode,
                    results, pairedByCode, failedCodes, notes, null);
        }
    }

    /** Task 308 的 next-open／chronological holdout／walk-forward typed report。 */
    public record V13Report(
            String productionRuleVersion,
            boolean productionPromoted,
            UniverseMode universeMode,
            BigDecimal calibrationRatio,
            int walkForwardFolds,
            boolean closeFallbackSensitivityIncluded,
            List<ResolvedCostAssumption> assumptions,
            List<MarketHorizonExecution> marketHorizons,
            List<String> failures,
            List<String> notes,
            /** 固定、可重現的候選 grid（包含 V12 baseline 與 V13 candidates）。 */
            List<String> candidateParameterSetIds,
            /** key 為 market/horizon；只記錄 calibration 選出的 candidate，不代表 runtime 已升版。 */
            Map<String, String> selectedCandidates,
            /** report-only registry 目前通過的 key 數；production runtime 仍由 baseline fallback 保護。 */
            int promotedCandidateCount,
            /** selected key -> complete immutable parameter snapshot. */
            Map<String, RuleParameterSnapshot> selectedParameterSnapshots
    ) {
        public V13Report {
            universeMode = universeMode == null ? UniverseMode.FULL_MARKET : universeMode;
            selectedParameterSnapshots = selectedParameterSnapshots == null
                    ? Map.of() : Map.copyOf(selectedParameterSnapshots);
        }

        /** Compatibility shape before universeMode was exposed. */
        public V13Report(
                String productionRuleVersion,
                boolean productionPromoted,
                BigDecimal calibrationRatio,
                int walkForwardFolds,
                boolean closeFallbackSensitivityIncluded,
                List<ResolvedCostAssumption> assumptions,
                List<MarketHorizonExecution> marketHorizons,
                List<String> failures,
                List<String> notes,
                List<String> candidateParameterSetIds,
                Map<String, String> selectedCandidates,
                int promotedCandidateCount,
                Map<String, RuleParameterSnapshot> selectedParameterSnapshots) {
            this(productionRuleVersion, productionPromoted, UniverseMode.FULL_MARKET,
                    calibrationRatio, walkForwardFolds, closeFallbackSensitivityIncluded,
                    assumptions, marketHorizons, failures, notes, candidateParameterSetIds,
                    selectedCandidates, promotedCandidateCount, selectedParameterSnapshots);
        }

        /** Compatibility shape before selected parameter snapshots were exposed. */
        public V13Report(
                String productionRuleVersion,
                boolean productionPromoted,
                BigDecimal calibrationRatio,
                int walkForwardFolds,
                boolean closeFallbackSensitivityIncluded,
                List<ResolvedCostAssumption> assumptions,
                List<MarketHorizonExecution> marketHorizons,
                List<String> failures,
                List<String> notes,
                List<String> candidateParameterSetIds,
                Map<String, String> selectedCandidates,
                int promotedCandidateCount) {
            this(productionRuleVersion, productionPromoted, UniverseMode.FULL_MARKET,
                    calibrationRatio, walkForwardFolds,
                    closeFallbackSensitivityIncluded, assumptions, marketHorizons, failures, notes,
                    candidateParameterSetIds, selectedCandidates, promotedCandidateCount, Map.of());
        }

        /** 舊 JSON／Java 呼叫端形狀保留；新增 evidence 欄位採空集合。 */
        public V13Report(
                String productionRuleVersion,
                boolean productionPromoted,
                UniverseMode universeMode,
                BigDecimal calibrationRatio,
                int walkForwardFolds,
                boolean closeFallbackSensitivityIncluded,
                List<ResolvedCostAssumption> assumptions,
                List<MarketHorizonExecution> marketHorizons,
                List<String> failures,
                List<String> notes) {
            this(productionRuleVersion, productionPromoted, universeMode,
                    calibrationRatio, walkForwardFolds, closeFallbackSensitivityIncluded,
                    assumptions, marketHorizons, failures, notes,
                    List.of(), Map.of(), 0, Map.of());
        }

        /** 舊 JSON／Java 呼叫端形狀保留；新增 evidence 欄位採空集合。 */
        public V13Report(
                String productionRuleVersion,
                boolean productionPromoted,
                BigDecimal calibrationRatio,
                int walkForwardFolds,
                boolean closeFallbackSensitivityIncluded,
                List<ResolvedCostAssumption> assumptions,
                List<MarketHorizonExecution> marketHorizons,
                List<String> failures,
                List<String> notes) {
            this(productionRuleVersion, productionPromoted, UniverseMode.FULL_MARKET,
                    calibrationRatio, walkForwardFolds,
                    closeFallbackSensitivityIncluded, assumptions, marketHorizons, failures, notes,
                    List.of(), Map.of(), 0, Map.of());
        }
    }

    public record ResolvedCostAssumption(
            CostKey key,
            CostAssumption assumption,
            BigDecimal roundTripCostPct,
            BigDecimal returnPracticalDeltaPct
    ) {}

    /**
     * Immutable, typed echo of every V13 parameter used by a candidate or a
     * selected production key.  The report must be reproducible from this
     * object; consumers should not have to infer thresholds or sigma floors from
     * a parameter-set id.
     */
    public record RuleParameterSnapshot(
            String parameterSetId,
            String ruleVersion,
            boolean normalizedBiasEnabled,
            ThresholdSnapshot shortThresholds,
            ThresholdSnapshot mediumThresholds,
            BigDecimal confidenceThreshold,
            BigDecimal normalizedBiasFloor,
            BigDecimal normalizedBiasSaturationMultiple,
            BigDecimal normalizedBiasUpperMultiple,
            BigDecimal normalizedBiasLowerMultiple,
            BigDecimal downsideActionThresholdPct,
            WeakeningSnapshot weakeningCondition,
            Map<String, BigDecimal> candidateWeightDeltas,
            BondRateSnapshot bondRate,
            SigmaSnapshot sigma
    ) {
        public RuleParameterSnapshot {
            candidateWeightDeltas = candidateWeightDeltas == null
                    ? Map.of() : Map.copyOf(candidateWeightDeltas);
        }
    }

    public record ThresholdSnapshot(int buy, int hold, int caution, int reduce) {}

    public record WeakeningSnapshot(
            boolean requireStructureBelow,
            boolean requireKdDeadCross,
            BigDecimal downVolumeRatioFloor
    ) {}

    public record BondRateSnapshot(
            Map<String, String> tenorByBondTerm,
            BigDecimal curveShapeWeight,
            BigDecimal returnPctAtUnit
    ) {
        public BondRateSnapshot {
            tenorByBondTerm = tenorByBondTerm == null ? Map.of() : Map.copyOf(tenorByBondTerm);
        }
    }

    /**
     * Sigma provenance is separate from the candidate's floor.  raw/effective
     * are nullable at aggregate candidate level because they are per signal
     * observation; when an observation is echoed they must be populated rather
     * than reconstructed from a percentile.
     */
    public record SigmaSnapshot(
            String status,
            BigDecimal rawSigmaRatio,
            BigDecimal effectiveSigmaRatio,
            BigDecimal floorRatio,
            BigDecimal p05SigmaRatio,
            BigDecimal p10SigmaRatio,
            BigDecimal p25SigmaRatio,
            int sampleN,
            LocalDate calibrationCutoff,
            LocalDate asOfFrom,
            LocalDate asOfTo,
            String source
    ) {}

    public record MarketHorizonExecution(
            String market,
            int horizon,
            String cutoff,
            int globalDateCount,
            int calibrationDateCount,
            int holdoutDateCount,
            List<WalkForwardFold> folds,
            SplitExecutionStat calibration,
            SplitExecutionStat holdout,
            int excludedMissingEntryOpen,
            int excludedMissingExitOpen,
            int excludedInsufficientForward,
            int excludedCostOutsideEffectiveRange,
            int closeSensitivityN,
            ExecutionDateExample firstPrimaryExecution,
            String promotionStatus,
            String rejectionReason,
            String selectedCandidateParameterSetId,
            List<CandidateCalibration> candidateCalibration,
            PromotionHorizonEvidence promotionEvidence,
            /** report group identity；混合 instrument/profile 不得靜默合併。 */
            String instrumentKind,
            String productionProfile,
            /** strict AssetProfile identity；各欄缺值必須保留為 UNKNOWN/空白。 */
            String assetClass,
            String stockStyle,
            String bondTerm,
            /** signal evidence confidence decile（D0..D9），不是方向分數。 */
            String confidenceDecile,
            /** production gate scope；stratum metrics are diagnostic-only when pooled. */
            String promotionEvidenceScope,
            /** selected candidate complete immutable parameter echo. */
            RuleParameterSnapshot selectedParameterSnapshot,
            /** calibration/fold purge counts; a signal-date split alone is not sufficient. */
            PurgeEmbargoEvidence purgeEmbargoEvidence
    ) {
        /** 舊 endpoint／測試建構形狀保留；新增候選證據採空值。 */
        public MarketHorizonExecution(
                String market,
                int horizon,
                String cutoff,
                int globalDateCount,
                int calibrationDateCount,
                int holdoutDateCount,
                List<WalkForwardFold> folds,
                SplitExecutionStat calibration,
                SplitExecutionStat holdout,
                int excludedMissingEntryOpen,
                int excludedMissingExitOpen,
                int excludedInsufficientForward,
                int closeSensitivityN,
                ExecutionDateExample firstPrimaryExecution,
                String promotionStatus,
                String rejectionReason) {
            this(market, horizon, cutoff, globalDateCount, calibrationDateCount, holdoutDateCount,
                    folds, calibration, holdout, excludedMissingEntryOpen, excludedMissingExitOpen,
                    excludedInsufficientForward, 0, closeSensitivityN, firstPrimaryExecution,
                    promotionStatus, rejectionReason, null, List.of(), null,
                    null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Evidence that walk-forward boundaries were applied to the realised exit date as well as
     * the signal date.  Counts are deliberately separate from the calibration sample sizes so
     * consumers can distinguish a genuinely small sample from samples removed by embargo.
     */
    public record PurgeEmbargoEvidence(
            String calibrationBoundary,
            int calibrationPurgedN,
            int calibrationPurgedCodes,
            int foldPurgedN,
            int foldPurgedCodes
    ) {}

    /** 單一 action 軌的 coverage 與同樣本比較；entry／held 不可混成一個樣本數。 */
    public record ActionTrackCoverage(
            int candidateN,
            int candidateCodes,
            int baselineN,
            int baselineCodes,
            int intersectionN,
            int intersectionCodes
    ) {}

    /** 單一候選在 calibration 的覆蓋與同樣本比較；selector 只使用 combined 欄位。 */
    public record CandidateCalibration(
            String parameterSetId,
            String ruleVersion,
            int candidateCoverageN,
            int candidateCoverageCodes,
            int baselineCoverageN,
            int baselineCoverageCodes,
            int intersectionN,
            int intersectionCodes,
            BigDecimal downsideRatePct,
            BigDecimal pairedMedianDeltaPct,
            BigDecimal pooledMeanDeltaPct,
            boolean selected,
            /** candidate 在共同 (code,signalDate,horizon) intersection 的 downside 比例。 */
            BigDecimal intersectionCandidateDownsideRatePct,
            /** baseline 在同一共同 intersection 的 downside 比例。 */
            BigDecimal intersectionBaselineDownsideRatePct,
            /** entry 軌（free BUY/ADD/TRIAL）獨立 coverage／intersection。 */
            ActionTrackCoverage entryCoverage,
            /** held 軌（HOLD／REDUCE／EXIT）獨立 coverage／intersection。 */
            ActionTrackCoverage heldCoverage,
            /** 完整 immutable candidate parameter echo，供重現 calibration。 */
            RuleParameterSnapshot parameterSnapshot
    ) {
        public CandidateCalibration {
            entryCoverage = entryCoverage == null ? new ActionTrackCoverage(0, 0, 0, 0, 0, 0)
                    : entryCoverage;
            heldCoverage = heldCoverage == null ? new ActionTrackCoverage(0, 0, 0, 0, 0, 0)
                    : heldCoverage;
    }

        /** Compatibility shape before the typed parameter snapshot was exposed. */
        public CandidateCalibration(
                String parameterSetId,
                String ruleVersion,
                int candidateCoverageN,
                int candidateCoverageCodes,
                int baselineCoverageN,
                int baselineCoverageCodes,
                int intersectionN,
                int intersectionCodes,
                BigDecimal downsideRatePct,
                BigDecimal pairedMedianDeltaPct,
                BigDecimal pooledMeanDeltaPct,
                boolean selected,
                BigDecimal intersectionCandidateDownsideRatePct,
                BigDecimal intersectionBaselineDownsideRatePct,
                ActionTrackCoverage entryCoverage,
                ActionTrackCoverage heldCoverage) {
            this(parameterSetId, ruleVersion, candidateCoverageN, candidateCoverageCodes,
                    baselineCoverageN, baselineCoverageCodes, intersectionN, intersectionCodes,
                    downsideRatePct, pairedMedianDeltaPct, pooledMeanDeltaPct, selected,
                    intersectionCandidateDownsideRatePct, intersectionBaselineDownsideRatePct,
                    entryCoverage, heldCoverage, null);
        }

        /** 舊 JSON／Java 呼叫形狀保留；新增 intersection downside 採缺值。 */
        public CandidateCalibration(
                String parameterSetId,
                String ruleVersion,
                int candidateCoverageN,
                int candidateCoverageCodes,
                int baselineCoverageN,
                int baselineCoverageCodes,
                int intersectionN,
                int intersectionCodes,
                BigDecimal downsideRatePct,
                BigDecimal pairedMedianDeltaPct,
                BigDecimal pooledMeanDeltaPct,
                boolean selected) {
                this(parameterSetId, ruleVersion, candidateCoverageN, candidateCoverageCodes,
                    baselineCoverageN, baselineCoverageCodes, intersectionN, intersectionCodes,
                    downsideRatePct, pairedMedianDeltaPct, pooledMeanDeltaPct, selected, null, null,
                    new ActionTrackCoverage(0, 0, 0, 0, 0, 0),
                    new ActionTrackCoverage(0, 0, 0, 0, 0, 0), null);
        }
    }

    /** selected candidate 在 untouched holdout／fold 的 promotion gate 證據。 */
    public record PromotionHorizonEvidence(
            int horizon,
            String parameterSetId,
            int holdoutN,
            int holdoutCodes,
            BigDecimal pairedMedianDeltaPct,
            BigDecimal pooledMeanDeltaPct,
            BigDecimal downsideImprovementPp,
            BigDecimal returnPracticalDeltaPct,
            int validFolds,
            int passingFolds,
            boolean catastrophicFold,
            boolean promoted,
            String reason,
            /** candidate／baseline comparative metrics 的共同 signal intersection n。 */
            int intersectionN,
            /** candidate／baseline comparative metrics 的共同 signal code count。 */
            int intersectionCodes,
            /** 另行揭露 candidate coverage，避免 coverage 差被誤當成 alpha。 */
            int candidateCoverageN,
            int baselineCoverageN,
            /** entry 軌（free BUY/ADD/TRIAL）獨立 coverage／intersection。 */
            ActionTrackCoverage entryCoverage,
            /** held 軌（HOLD／REDUCE／EXIT）獨立 coverage／intersection。 */
            ActionTrackCoverage heldCoverage,
            /** selected candidate 的完整 immutable parameter echo。 */
            RuleParameterSnapshot parameterSnapshot
    ) {
        public PromotionHorizonEvidence {
            entryCoverage = entryCoverage == null ? new ActionTrackCoverage(0, 0, 0, 0, 0, 0)
                    : entryCoverage;
            heldCoverage = heldCoverage == null ? new ActionTrackCoverage(0, 0, 0, 0, 0, 0)
                    : heldCoverage;
    }

        /** Compatibility shape before the typed parameter snapshot was exposed. */
        public PromotionHorizonEvidence(
                int horizon,
                String parameterSetId,
                int holdoutN,
                int holdoutCodes,
                BigDecimal pairedMedianDeltaPct,
                BigDecimal pooledMeanDeltaPct,
                BigDecimal downsideImprovementPp,
                BigDecimal returnPracticalDeltaPct,
                int validFolds,
                int passingFolds,
                boolean catastrophicFold,
                boolean promoted,
                String reason,
                int intersectionN,
                int intersectionCodes,
                int candidateCoverageN,
                int baselineCoverageN,
                ActionTrackCoverage entryCoverage,
                ActionTrackCoverage heldCoverage) {
            this(horizon, parameterSetId, holdoutN, holdoutCodes, pairedMedianDeltaPct,
                    pooledMeanDeltaPct, downsideImprovementPp, returnPracticalDeltaPct,
                    validFolds, passingFolds, catastrophicFold, promoted, reason,
                    intersectionN, intersectionCodes, candidateCoverageN, baselineCoverageN,
                    entryCoverage, heldCoverage, null);
        }

        /** 舊 JSON／Java 呼叫形狀保留；新增 coverage/intersection 欄位採 0。 */
        public PromotionHorizonEvidence(
                int horizon,
                String parameterSetId,
                int holdoutN,
                int holdoutCodes,
                BigDecimal pairedMedianDeltaPct,
                BigDecimal pooledMeanDeltaPct,
                BigDecimal downsideImprovementPp,
                BigDecimal returnPracticalDeltaPct,
                int validFolds,
                int passingFolds,
                boolean catastrophicFold,
                boolean promoted,
                String reason) {
            this(horizon, parameterSetId, holdoutN, holdoutCodes, pairedMedianDeltaPct,
                    pooledMeanDeltaPct, downsideImprovementPp, returnPracticalDeltaPct,
                    validFolds, passingFolds, catastrophicFold, promoted, reason,
                    holdoutN, holdoutCodes, 0, 0,
                    new ActionTrackCoverage(0, 0, 0, 0, 0, 0),
                    new ActionTrackCoverage(0, 0, 0, 0, 0, 0), null);
        }
    }

    public record WalkForwardFold(
            int fold,
            String trainFrom,
            String trainTo,
            int trainDateCount,
            String evaluationFrom,
            String evaluationTo,
            int evaluationDateCount,
            int jointTrainDateCount,
            LocalDate jointTrainFrom,
            LocalDate jointTrainTo,
            List<Integer> jointRequiredHorizons,
            /** 該 fold train dates 專屬 calibration selector 選出的 candidate。 */
            String selectedCandidateParameterSetId,
            /** same-intersection gross/net distribution and candidate-minus-baseline delta for evaluation block. */
            FoldExecutionEvidence executionEvidence
    ) {
        public WalkForwardFold {
            jointRequiredHorizons = jointRequiredHorizons == null
                    ? List.of() : List.copyOf(jointRequiredHorizons);
        }

        /** 舊 JSON／Java 呼叫形狀保留；fold candidate 尚未揭露時為 null。 */
        public WalkForwardFold(
                int fold,
                String trainFrom,
                String trainTo,
                int trainDateCount,
                String evaluationFrom,
                String evaluationTo,
                int evaluationDateCount) {
            this(fold, trainFrom, trainTo, trainDateCount, evaluationFrom, evaluationTo,
                    evaluationDateCount, 0, null, null, List.of(), null, null);
        }

        /** Compatibility shape before per-fold execution evidence was exposed. */
        public WalkForwardFold(
                int fold,
                String trainFrom,
                String trainTo,
                int trainDateCount,
                String evaluationFrom,
                String evaluationTo,
                int evaluationDateCount,
                String selectedCandidateParameterSetId) {
            this(fold, trainFrom, trainTo, trainDateCount, evaluationFrom, evaluationTo,
                    evaluationDateCount, 0, null, null, List.of(),
                    selectedCandidateParameterSetId, null);
        }
    }

    /**
     * Complete per-fold evaluation evidence.  The nested distributions are computed on one
     * candidate/baseline action-track intersection; no fold aggregate is inferred from coverage
     * totals alone.  Sigma provenance is explicit so a fail-closed fold cannot be mistaken for a
     * calibrated selection.
     */
    public record FoldNormalizedBiasProvenance(
            String code,
            String signalDate,
            int horizon,
            String track,
            boolean enabled,
            BigDecimal rawBiasRatio,
            BigDecimal rawSigmaRatio,
            BigDecimal sigmaFloorRatio,
            BigDecimal effectiveSigmaRatio,
            BigDecimal normalizedBias,
            String asOfDate,
            String reason
    ) {}

    public record FoldExecutionEvidence(
            String sigmaProfileStatus,
            String sigmaProfileCutoff,
            String sigmaProfileSource,
            String status,
            String reason,
            String selectedCandidateParameterSetId,
            int candidateCoverageN,
            int candidateCoverageCodes,
            int baselineCoverageN,
            int baselineCoverageCodes,
            int intersectionN,
            int intersectionCodes,
            ExecutionDistribution candidate,
            ExecutionDistribution baseline,
            ExecutionDelta delta,
            /** selected candidate parameter echo; null when fold selection failed closed. */
            RuleParameterSnapshot parameterSnapshot,
            /** Typed per-signal provenance from the fold-local candidate replay. */
            List<FoldNormalizedBiasProvenance> normalizedBiasRows,
            /** train samples removed because their realised exit reaches the evaluation block. */
            int purgedTrainN,
            int purgedTrainCodes,
            String purgeBoundary
    ) {
        public FoldExecutionEvidence {
            normalizedBiasRows = normalizedBiasRows == null ? List.of() : List.copyOf(normalizedBiasRows);
        }

        /** Compatibility shape before per-fold parameter echo was exposed. */
        public FoldExecutionEvidence(
                String sigmaProfileStatus,
                String sigmaProfileCutoff,
                String sigmaProfileSource,
                String status,
                String reason,
                String selectedCandidateParameterSetId,
                int candidateCoverageN,
                int candidateCoverageCodes,
                int baselineCoverageN,
                int baselineCoverageCodes,
                int intersectionN,
                int intersectionCodes,
                ExecutionDistribution candidate,
                ExecutionDistribution baseline,
                ExecutionDelta delta) {
            this(sigmaProfileStatus, sigmaProfileCutoff, sigmaProfileSource, status, reason,
                    selectedCandidateParameterSetId, candidateCoverageN, candidateCoverageCodes,
                    baselineCoverageN, baselineCoverageCodes, intersectionN, intersectionCodes,
                    candidate, baseline, delta, null, List.of(), 0, 0, null);
        }

        /** Compatibility shape before purge/embargo evidence was exposed. */
        public FoldExecutionEvidence(
                String sigmaProfileStatus,
                String sigmaProfileCutoff,
                String sigmaProfileSource,
                String status,
                String reason,
                String selectedCandidateParameterSetId,
                int candidateCoverageN,
                int candidateCoverageCodes,
                int baselineCoverageN,
                int baselineCoverageCodes,
                int intersectionN,
                int intersectionCodes,
                ExecutionDistribution candidate,
                ExecutionDistribution baseline,
                ExecutionDelta delta,
                RuleParameterSnapshot parameterSnapshot,
                List<FoldNormalizedBiasProvenance> normalizedBiasRows) {
            this(sigmaProfileStatus, sigmaProfileCutoff, sigmaProfileSource, status, reason,
                    selectedCandidateParameterSetId, candidateCoverageN, candidateCoverageCodes,
                    baselineCoverageN, baselineCoverageCodes, intersectionN, intersectionCodes,
                    candidate, baseline, delta, parameterSnapshot, normalizedBiasRows, 0, 0, null);
        }
    }

    /**
     * 同一 action-track intersection 上的完整 gross/net 報酬分布。
     *
     * <p>gross 與 net 必須由同一組 {@code (code, signalDate, horizon)} 樣本計算；
     * {@code costImpactPct=netMeanPct-grossMeanPct} 明確揭露成本影響，不能把成本
     * 吞進 candidate alpha。</p>
     */
    public record ExecutionDistribution(
            int n,
            int codeCount,
            BigDecimal grossMeanPct,
            BigDecimal grossMedianPct,
            BigDecimal grossWinRatePct,
            BigDecimal grossDownsideRiskPct,
            BigDecimal grossP5Pct,
            BigDecimal grossP25Pct,
            BigDecimal grossP75Pct,
            BigDecimal grossP95Pct,
            BigDecimal netMeanPct,
            BigDecimal netMedianPct,
            BigDecimal netWinRatePct,
            BigDecimal netDownsideRiskPct,
            BigDecimal netP5Pct,
            BigDecimal netP25Pct,
            BigDecimal netP75Pct,
            BigDecimal netP95Pct,
            BigDecimal costImpactPct
    ) {}

    /** candidate minus V12 baseline；delta 也維持 gross/net 與成本影響分離。 */
    public record ExecutionDelta(
            BigDecimal grossMeanDeltaPct,
            BigDecimal grossMedianDeltaPct,
            BigDecimal grossWinRateDeltaPp,
            BigDecimal grossDownsideRiskDeltaPp,
            BigDecimal grossP5DeltaPct,
            BigDecimal grossP25DeltaPct,
            BigDecimal grossP75DeltaPct,
            BigDecimal grossP95DeltaPct,
            BigDecimal netMeanDeltaPct,
            BigDecimal netMedianDeltaPct,
            BigDecimal netWinRateDeltaPp,
            BigDecimal netDownsideRiskDeltaPp,
            BigDecimal netP5DeltaPct,
            BigDecimal netP25DeltaPct,
            BigDecimal netP75DeltaPct,
            BigDecimal netP95DeltaPct,
            BigDecimal costImpactDeltaPct
    ) {}

    /**
     * V13 split report。既有扁平欄位保留供舊呼叫端讀取，新的 candidate／baseline
     * 分布與 delta 使用 typed nested records。當沒有 calibration-selected candidate
     * 時，candidate/baseline/delta 必須為 null 且 status/reason 明確說明 insufficient；
     * 不得把整個 attempts list 冒充為 selected candidate 統計。
     */
    public record SplitExecutionStat(
            int n,
            int codeCount,
            BigDecimal grossMeanPct,
            BigDecimal netMeanPct,
            BigDecimal netMedianPct,
            BigDecimal netWinRatePct,
            BigDecimal netDownsideRiskPct,
            BigDecimal netP5Pct,
            BigDecimal netP25Pct,
            BigDecimal netP75Pct,
            BigDecimal netP95Pct,
            int attemptN,
            int attemptCodeCount,
            String selectedCandidateParameterSetId,
            String status,
            String reason,
            int candidateCoverageN,
            int candidateCoverageCodes,
            int baselineCoverageN,
            int baselineCoverageCodes,
            int intersectionN,
            int intersectionCodes,
            ExecutionDistribution candidate,
            ExecutionDistribution baseline,
            ExecutionDelta delta
    ) {
        /** Compatibility shape before V13 distribution percentiles were exposed. */
        public SplitExecutionStat(
                int n,
                int codeCount,
                BigDecimal grossMeanPct,
                BigDecimal netMeanPct,
                BigDecimal netMedianPct,
                BigDecimal netWinRatePct,
                BigDecimal netDownsideRiskPct,
                BigDecimal netP5Pct,
                BigDecimal netP25Pct,
                BigDecimal netP75Pct,
                BigDecimal netP95Pct) {
            this(n, codeCount, grossMeanPct, netMeanPct, netMedianPct, netWinRatePct,
                    netDownsideRiskPct, netP5Pct, netP25Pct, netP75Pct, netP95Pct,
                    n, codeCount, null, "LEGACY", null, n, codeCount, 0, 0, 0, 0,
                    null, null, null);
        }

        /** Compatibility shape before V13 distribution percentiles were exposed. */
        public SplitExecutionStat(
                int n,
                int codeCount,
                BigDecimal grossMeanPct,
                BigDecimal netMeanPct,
                BigDecimal netMedianPct,
                BigDecimal netWinRatePct,
                BigDecimal netDownsideRiskPct) {
            this(n, codeCount, grossMeanPct, netMeanPct, netMedianPct, netWinRatePct,
                    netDownsideRiskPct, null, null, null, null);
        }

        public boolean available() {
            return "AVAILABLE".equals(status) && candidate != null && baseline != null
                    && intersectionN > 0;
        }

        public boolean insufficient() {
            return !available();
        }
    }

    public record ExecutionDateExample(
            String code,
            InstrumentKind instrumentKind,
            String signalDate,
            String entryDate,
            String exitDate,
            BigDecimal entryAdjustedOpen,
            BigDecimal exitAdjustedOpen
    ) {}

    /**
     * @param rows            該標的的原始交易日數。
     * @param nonPositiveRows 被剔除的 {@code close_price <= 0} 髒列數（另立任務處理，此處只揭露）。
     * @param warmupExcluded  因暖機不足 240 筆而排除的天數。
     * @param evaluated       實際進入統計的天數。
     * @param etfPremiumDays  {@code etf_nav_history} 在該標的上實際有值的天數。
     *                        <b>回測期間結構性缺值</b>（全庫僅 11 個交易日），故 ETF 的 buyGate 與
     *                        OVERBOUGHT 統計未含折溢價否決，不得直接當成 production 行為的證據。
     */
    public record CodeCoverage(
            String code,
            String market,
            String instrumentType,
            String dataFrom,
            String dataTo,
            int rows,
            int nonPositiveRows,
            int warmupExcluded,
            int evaluated,
            int etfPremiumDays
    ) {}

    /**
     * 單一（述詞 × held × horizon）的統計。全部欄位皆為「歷史分布」的描述。
     *
     * @param n                    訊號組樣本數。
     * @param sampleInsufficient   {@code n < 30}；該格的數字不可用於決策。
     * @param insufficientForward  因 {@code t+h} 越界而未計入此 horizon 的訊號日數。
     * @param downsideRisk         報酬 {@code <= -10%} 的比例。
     * @param baselineN            同標的同期間所有具備完整前瞻報酬的交易日數。
     * @param diffMean             訊號組平均 − 基準平均。
     * @param caveat               該格特有的揭露（例如 ETF 未含折溢價否決）。
     */
    public record PredicateStat(
            String predicate,
            boolean held,
            int horizon,
            int n,
            boolean sampleInsufficient,
            int insufficientForward,
            BigDecimal mean,
            BigDecimal median,
            BigDecimal winRate,
            BigDecimal downsideRisk,
            BigDecimal p5,
            BigDecimal p25,
            BigDecimal p75,
            BigDecimal p95,
            int baselineN,
            BigDecimal baselineMean,
            BigDecimal baselineMedian,
            BigDecimal baselineWinRate,
            BigDecimal baselineDownsideRisk,
            BigDecimal diffMean,
            String caveat
    ) {}

    /**
     * 逐標的配對比較的彙總（273.6.2）：每檔各自算「訊號組 − 基準」，再對各檔的差額取統計量。
     *
     * <p>與 {@link PredicateStat}（全樣本合併口徑）**必須並列呈現**——合併口徑會被樣本數多的
     * 高波動標的主導，兩者不一致是預期的，必須讓讀者看見。</p>
     *
     * @param codesWithSignal 有訊號的標的數。
     * @param meanOfDiffs     各標的「訊號組平均 − 基準平均」的平均。
     * @param medianOfDiffs   同上的中位數。
     * @param codesPositive   差額為正的標的數。
     */
    public record PairedStat(
            String predicate,
            boolean held,
            int horizon,
            int codesWithSignal,
            BigDecimal meanOfDiffs,
            BigDecimal medianOfDiffs,
            int codesPositive
    ) {}
}
