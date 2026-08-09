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

    private TradingRadarEvidenceGate() {}

    public record GatedActions(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            List<String> reasons,
            TradingRadarRuleEngine.Action candidateMediumAction,
            TradingRadarRuleEngine.Action candidateShortAction
    ) {
        public GatedActions(TradingRadarRuleEngine.Action mediumAction,
                            TradingRadarRuleEngine.Action shortAction,
                            List<String> reasons) {
            this(mediumAction, shortAction, reasons, mediumAction, shortAction);
        }
    }

    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile) {
        if (profile == null || !profile.bond() || profile.currencyDataComplete()) {
            return new GatedActions(mediumAction, shortAction, List.of());
        }
        List<String> reasons = new ArrayList<>();
        TradingRadarRuleEngine.Action fallback = held
                ? TradingRadarRuleEngine.Action.HOLD
                : TradingRadarRuleEngine.Action.WATCH;
        TradingRadarRuleEngine.Action gatedMedium = downgradeBuy(mediumAction, fallback, reasons);
        TradingRadarRuleEngine.Action gatedShort = downgradeBuy(shortAction, fallback, reasons);
        gatedMedium = downgradeRiskExit(gatedMedium, fallback, reasons);
        gatedShort = downgradeRiskExit(gatedShort, fallback, reasons);
        if (!reasons.isEmpty()) {
            reasons.add("底層外幣債券幣別資料不完整，兩軌買進／加碼／試單閘門關閉；不以 TWD 猜測。 ");
        }
        return new GatedActions(gatedMedium, gatedShort, List.copyOf(reasons), mediumAction, shortAction);
    }

    /** Apply confidence/group coverage after the rule engine has formed candidates. */
    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence) {
        return apply(mediumAction, shortAction, held, profile, evidence, BigDecimal.valueOf(.70));
    }

    /** Candidate path may calibrate confidence threshold; structural coverage stays mandatory. */
    public static GatedActions apply(
            TradingRadarRuleEngine.Action mediumAction,
            TradingRadarRuleEngine.Action shortAction,
            boolean held,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            BigDecimal confidenceThreshold) {
        GatedActions currencyGated = apply(mediumAction, shortAction, held, profile);
        List<String> reasons = new ArrayList<>(currencyGated.reasons());
        TradingRadarRuleEngine.Action fallback = held
                ? TradingRadarRuleEngine.Action.HOLD : TradingRadarRuleEngine.Action.WATCH;
        TradingRadarRuleEngine.Action gatedMedium = currencyGated.mediumAction();
        TradingRadarRuleEngine.Action gatedShort = currencyGated.shortAction();
        if (evidence == null) {
            reasons.add("證據 confidence 未建立，買進／減碼／出場候選保守降級。 ");
            gatedMedium = downgradeBuy(gatedMedium, fallback, reasons);
            gatedShort = downgradeBuy(gatedShort, fallback, reasons);
            gatedMedium = downgradeRiskExit(gatedMedium, fallback, reasons);
            gatedShort = downgradeRiskExit(gatedShort, fallback, reasons);
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
        }
        return new GatedActions(gatedMedium, gatedShort, dedupe(reasons), mediumAction, shortAction);
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
        TradingRadarEvidenceConfidenceResolver.Risk risk = horizon
                == TradingRadarEvidenceConfidenceResolver.Horizon.SHORT
                ? evidence.shortRisk() : evidence.mediumRisk();
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
