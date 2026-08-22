package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * t309 第一階段可驗證的 hard gate。
 *
 * <p>Evidence gate 只向保守方向降級；證據不足時，買進候選不會被當成可執行
 * 機會，減碼／出場候選也不會在缺少價格、盤勢或適用資產證據時被誤當成可執行
 * 風控指令。原始 opportunity action 會透過 candidate 欄位保留供 API 揭露。</p>
 */
public final class TradingRadarEvidenceGate {

    /** V12 final-action evidence semantics; independent from the unchanged rule/scoring version. */
    public static final String ACTION_POLICY_VERSION = "EVIDENCE_GATE_V1";

    private TradingRadarEvidenceGate() {}

    /**
     * 三軌 gate 結果（Task 356.9b）。
     *
     * <p>{@code swingAction} 緊接 {@code shortAction}、{@code candidateSwingAction} 緊接
     * {@code candidateShortAction}，命名與既有兩軌對稱（不得寫成 {@code rawSwingAction}）。
     * {@code swingAction} 為 {@code null} 代表呼叫端<b>沒有提供</b> 1周~1月 軌，
     * 此時本 gate 完全不對該軌做任何判定、也不加任何 reasons——既有兩軌的行為逐位不變。</p>
     */
    public record GatedActions(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            TradingRadarRuleEngine.Action swingAction,
            List<String> reasons,
            TradingRadarRuleEngine.Action candidateMediumAction,
            TradingRadarRuleEngine.Action candidateShortAction,
            TradingRadarRuleEngine.Action candidateSwingAction
    ) {
        public GatedActions(TradingRadarRuleEngine.Action mediumAction,
                            TradingRadarRuleEngine.Action shortAction,
                            TradingRadarRuleEngine.Action swingAction,
                            List<String> reasons) {
            this(mediumAction, shortAction, swingAction, reasons,
                    mediumAction, shortAction, swingAction);
        }
    }

    /** 兩軌相容入口；1周~1月 軌未提供（{@code null}），本 gate 不對其做任何判定。 */
    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile) {
        return apply(mediumAction, shortAction, null, held, profile);
    }

    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            TradingRadarRuleEngine.Action swingAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile) {
        if (profile == null || !profile.bond() || profile.currencyDataComplete()) {
            return new GatedActions(mediumAction, shortAction, swingAction, List.of());
        }
        List<String> reasons = new ArrayList<>();
        TradingRadarRuleEngine.Action fallback = held
                ? TradingRadarRuleEngine.Action.HOLD
                : TradingRadarRuleEngine.Action.WATCH;
        TradingRadarRuleEngine.Action gatedMedium = downgradeBuy(mediumAction, fallback, reasons);
        TradingRadarRuleEngine.Action gatedShort = downgradeBuy(shortAction, fallback, reasons);
        TradingRadarRuleEngine.Action gatedSwing = downgradeBuy(swingAction, fallback, reasons);
        gatedMedium = downgradeRiskExit(gatedMedium, fallback, reasons);
        gatedShort = downgradeRiskExit(gatedShort, fallback, reasons);
        gatedSwing = downgradeRiskExit(gatedSwing, fallback, reasons);
        if (!reasons.isEmpty()) {
            reasons.add("底層外幣債券幣別資料不完整，三軌買進／加碼／試單閘門關閉；不以 TWD 猜測。 ");
        }
        return new GatedActions(gatedMedium, gatedShort, gatedSwing, List.copyOf(reasons),
                mediumAction, shortAction, swingAction);
    }

    /** Apply confidence/group coverage after the rule engine has formed candidates. */
    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence) {
        return apply(mediumAction, shortAction, null, held, profile, evidence,
                BigDecimal.valueOf(.70));
    }

    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            TradingRadarRuleEngine.Action swingAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence) {
        return apply(mediumAction, shortAction, swingAction, held, profile, evidence,
                BigDecimal.valueOf(.70));
    }

    /** Candidate path may calibrate confidence threshold; structural coverage stays mandatory. */
    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            BigDecimal confidenceThreshold) {
        return apply(mediumAction, shortAction, null, held, profile, evidence, confidenceThreshold);
    }

    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            TradingRadarRuleEngine.Action swingAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            BigDecimal confidenceThreshold) {
        GatedActions currencyGated = apply(mediumAction, shortAction, swingAction, held, profile);
        List<String> reasons = new ArrayList<>(currencyGated.reasons());
        TradingRadarRuleEngine.Action fallback = held
                ? TradingRadarRuleEngine.Action.HOLD : TradingRadarRuleEngine.Action.WATCH;
        TradingRadarRuleEngine.Action gatedMedium = currencyGated.mediumAction();
        TradingRadarRuleEngine.Action gatedShort = currencyGated.shortAction();
        TradingRadarRuleEngine.Action gatedSwing = currencyGated.swingAction();
        if (evidence == null) {
            reasons.add("證據 confidence 未建立，買進／減碼／出場候選保守降級。 ");
            gatedMedium = downgradeBuy(gatedMedium, fallback, reasons);
            gatedShort = downgradeBuy(gatedShort, fallback, reasons);
            gatedSwing = downgradeBuy(gatedSwing, fallback, reasons);
            gatedMedium = downgradeRiskExit(gatedMedium, fallback, reasons);
            gatedShort = downgradeRiskExit(gatedShort, fallback, reasons);
            gatedSwing = downgradeRiskExit(gatedSwing, fallback, reasons);
        } else {
            if (!evidence.dividendEventComplete()) {
                // Public-event/dividend evidence contributes to risk/disclosure;
                // it is not a universal opportunity hard gate.
                reasons.add("配息事件 evidence 缺漏／過期／不完整，僅列風險揭露；不改變 opportunity action。 ");
            }
            double threshold = confidenceThreshold == null ? .70
                    : Math.max(0.0, Math.min(1.0, confidenceThreshold.doubleValue()));
            boolean mediumEvidenceOpen = mediumOpen(evidence, profile, threshold);
            boolean shortEvidenceOpen = shortOpen(evidence, threshold);
            if (isBuy(gatedMedium) && !mediumEvidenceOpen) {
                reasons.addAll(evidence.reasons());
                reasons.add("中期 evidence gate 未達 PRICE／MARKET／適用基本面完整度或候選 confidence 門檻。 ");
                gatedMedium = fallback;
            }
            if (isBuy(gatedShort) && !shortEvidenceOpen) {
                reasons.addAll(evidence.reasons());
                reasons.add("短期 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 ");
                gatedShort = fallback;
            }
            // Risk-reducing candidates are actionable only when the same mandatory
            // observations are complete.  Keep candidateMedium/ShortAction above
            // unchanged so the API still discloses the rule-engine opportunity.
            if (!mediumEvidenceOpen) {
                gatedMedium = downgradeRiskExit(gatedMedium, fallback, reasons);
            }
            if (!shortEvidenceOpen) {
                gatedShort = downgradeRiskExit(gatedShort, fallback, reasons);
            }
            boolean mediumRiskOpen = riskEvidenceOpen(evidence,
                    TradingRadarEvidenceConfidenceResolver.Horizon.MEDIUM, profile);
            boolean shortRiskOpen = riskEvidenceOpen(evidence,
                    TradingRadarEvidenceConfidenceResolver.Horizon.SHORT, profile);
            if (!mediumRiskOpen) {
                reasons.add("必要下檔風險 evidence 不完整（risk coverage 或 bond beta/riskUnit 未達標），"
                        + "中期減碼／出場僅保留候選揭露。 ");
                gatedMedium = downgradeRiskExit(gatedMedium, fallback, reasons);
            }
            if (!shortRiskOpen) {
                reasons.add("必要下檔風險 evidence 不完整（risk coverage 或 bond beta/riskUnit 未達標），"
                        + "短期減碼／出場僅保留候選揭露。 ");
                gatedShort = downgradeRiskExit(gatedShort, fallback, reasons);
            }
            // 1周~1月 軌：呼叫端沒有提供時完全不判定，既有兩軌的 reasons 逐位不變。
            if (swingAction != null) {
                boolean swingEvidenceOpen = swingOpen(evidence, profile, threshold);
                if (isBuy(gatedSwing) && !swingEvidenceOpen) {
                    reasons.addAll(evidence.reasons());
                    reasons.add("1周~1月 evidence gate 未達 PRICE／MARKET／適用資產完整度或候選 confidence 門檻。 ");
                    gatedSwing = fallback;
                }
                if (!swingEvidenceOpen) {
                    gatedSwing = downgradeRiskExit(gatedSwing, fallback, reasons);
                }
                if (!riskEvidenceOpen(evidence,
                        TradingRadarEvidenceConfidenceResolver.Horizon.SWING, profile)) {
                    reasons.add("必要下檔風險 evidence 不完整（risk coverage 或 bond beta/riskUnit 未達標），"
                            + "1周~1月 減碼／出場僅保留候選揭露。 ");
                    gatedSwing = downgradeRiskExit(gatedSwing, fallback, reasons);
                }
            }
        }
        return new GatedActions(gatedMedium, gatedShort, gatedSwing, dedupe(reasons),
                mediumAction, shortAction, swingAction);
    }

    /**
     * Sell actions require an independently observed downside-risk unit.  A complete Treasury
     * curve context is disclosure evidence only; for bonds the calibrated per-instrument beta
     * and its risk unit must be present before REDUCE/EXIT can pass the execution gate.
     */
    private static boolean riskEvidenceOpen(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            TradingRadarEvidenceConfidenceResolver.Horizon horizon,
            TradingRadarAssetProfileResolver.AssetProfile profile) {
        if (evidence == null || evidence.riskCoverage(horizon) < .70) return false;
        if (profile == null || !profile.bond()) return true;
        // Task 356.9a-2：三軌各自取自己那一份 Risk，禁止以三元運算把 SWING 靜默落到 medium。
        TradingRadarEvidenceConfidenceResolver.Risk risk = evidence.risk(horizon);
        return risk != null && risk.components().stream()
                .filter(component -> "asset_rate".equals(component.name()))
                .anyMatch(component -> component.applicability()
                        == TradingRadarEvidenceConfidenceResolver.Applicability.AVAILABLE
                        && component.unit() != null
                        && Double.isFinite(component.unit()));
    }

    private static boolean shortOpen(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence, double confidenceThreshold) {
        var asset = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC);
        return evidence.gateOpen(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT, confidenceThreshold)
                && (asset == null || !asset.participates()
                || asset.meets(TradingRadarEvidenceConfidenceResolver.Horizon.SHORT, .70));
    }

    /**
     * 1周~1月 軌的結構性 gate（Task 356.9b）。
     *
     * <p>採與 SHORT <b>相同形狀</b>（confidence gate ＋ ASSET_SPECIFIC），而不是 MEDIUM 的
     * 「個股必須有 VALUATION／FINANCIAL」：一個月尺度的決策不應該因為財報季尚未更新而
     * 整軌關閉，那是 1月~6月 軌才需要的要求。SWING 的估值／財務仍以 group 權重
     * （各 {@code .10}）計入 {@code swingConfidence}，並非完全不看。<b>此選擇為判斷性取值、
     * 無回測依據。</b></p>
     */
    private static boolean swingOpen(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            double confidenceThreshold) {
        TradingRadarEvidenceConfidenceResolver.Horizon h =
                TradingRadarEvidenceConfidenceResolver.Horizon.SWING;
        if (!evidence.gateOpen(h, confidenceThreshold)) return false;
        var asset = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC);
        return asset == null || !asset.participates() || asset.meets(h, .70);
    }

    private static boolean mediumOpen(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            double confidenceThreshold) {
        if (!evidence.gateOpen(TradingRadarEvidenceConfidenceResolver.Horizon.MEDIUM, confidenceThreshold)) return false;
        TradingRadarEvidenceConfidenceResolver.Horizon h = TradingRadarEvidenceConfidenceResolver.Horizon.MEDIUM;
        var valuation = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.VALUATION);
        var financial = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.FINANCIAL_OPERATING);
        var asset = evidence.group(TradingRadarEvidenceConfidenceResolver.Group.ASSET_SPECIFIC);
        boolean individual = profile != null && profile.equity()
                && profile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.STOCK;
        if (individual && (valuation == null || !valuation.meets(h, .70)
                || financial == null || !financial.meets(h, .70))) return false;
        return asset == null || !asset.participates() || asset.meets(h, .70);
    }

    private static boolean isBuy(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY;
    }

    private static List<String> dedupe(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }

    private static TradingRadarRuleEngine.Action downgradeBuy(
            TradingRadarRuleEngine.Action action,
            TradingRadarRuleEngine.Action fallback,
            List<String> reasons) {
        if (action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY) {
            reasons.add("幣別資料缺漏：原本的 " + action.name() + " 已降級為觀察／續抱。 ");
            return fallback;
        }
        return action;
    }

    private static TradingRadarRuleEngine.Action downgradeRiskExit(
            TradingRadarRuleEngine.Action action,
            TradingRadarRuleEngine.Action fallback,
            List<String> reasons) {
        if (action == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || action == TradingRadarRuleEngine.Action.EXIT_CANDIDATE) {
            reasons.add("必要證據不完整：原本的 " + action.name()
                    + " 已降級為 " + fallback.name() + "，僅保留候選揭露。 ");
            return fallback;
        }
        return action;
    }
}
