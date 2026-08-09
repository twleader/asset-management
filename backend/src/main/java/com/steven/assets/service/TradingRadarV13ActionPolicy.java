package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V13 promotion 後才啟用的賣方覆寫；未 promotion 時完整保留 V12 action。
 */
public final class TradingRadarV13ActionPolicy {

    public static final String HIGH_MOMENTUM_RISK = "HIGH_MOMENTUM_RISK";
    public static final String WEAKENING_DOWNSIDE_CONFIRMED = "WEAKENING_DOWNSIDE_CONFIRMED";

    private TradingRadarV13ActionPolicy() {}

    public record Decision(
            TradingRadarRuleEngine.Action action,
            boolean v13Active,
            boolean highMomentumRisk,
            boolean weakeningConfirmed,
            boolean downsideThresholdMet,
            List<String> disclosures
    ) {
        public Decision {
            Objects.requireNonNull(action, "action");
            disclosures = disclosures == null ? List.of() : List.copyOf(disclosures);
        }
    }

    /**
     * 舊 {@code profitTakingConfirmed} 只映射成 HIGH_MOMENTUM_RISK；在已 promotion 的 V13，
     * REDUCE 必須同時有 weakening 與 calibrated downside。close sensitivity 不得傳入
     * {@code downsideRiskPct}，由呼叫端型別/流程守門。
     */
    public static Decision apply(
            TradingRadarRuleEngine.StockInput input,
            TradingRadarRuleEngine.Action v12Action,
            boolean profitTakingConfirmed,
            boolean kdDeadCross,
            BigDecimal downsideRiskPct,
            RuleParameters parameters,
            boolean promotionActive) {
        Objects.requireNonNull(v12Action, "v12Action");
        Objects.requireNonNull(parameters, "parameters");

        List<String> disclosures = new ArrayList<>();
        if (profitTakingConfirmed) {
            disclosures.add(HIGH_MOMENTUM_RISK
                    + "：高檔與動能風險僅供揭露，不預測後續漲跌，也不單獨觸發減碼。");
        }
        boolean active = promotionActive && RuleParameters.V13_VERSION.equals(parameters.ruleVersion());
        if (!active) {
            // 未通過 holdout 時維持 V12 production；舊 boolean 仍能安全讀取並顯示 disclosure。
            return new Decision(v12Action, false, profitTakingConfirmed,
                    false, false, disclosures);
        }

        boolean weakening = weakeningConfirmed(input, kdDeadCross, parameters.weakeningCondition());
        boolean downsideMet = downsideRiskPct != null
                && downsideRiskPct.compareTo(parameters.downsideActionThresholdPct()) >= 0;
        boolean sellCandidate = v12Action == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || v12Action == TradingRadarRuleEngine.Action.EXIT_CANDIDATE;
        boolean reduceAllowed = input != null && input.held() && sellCandidate
                && weakening && downsideMet;
        if (reduceAllowed) {
            disclosures.add(WEAKENING_DOWNSIDE_CONFIRMED
                    + "：結構與獨立動能/流動性同步轉弱，且主要 next-open holdout 下檔率達門檻。");
            return new Decision(v12Action,
                    true, profitTakingConfirmed, true, true, disclosures);
        }

        TradingRadarRuleEngine.Action action = v12Action;
        if (sellCandidate) {
            action = input != null && input.held()
                    ? TradingRadarRuleEngine.Action.HOLD_CAUTION
                    : TradingRadarRuleEngine.Action.WAIT;
            disclosures.add("V13_SELL_BLOCKED：REDUCE/EXIT 必須同時具備 weakening 與主要樣本 downside 門檻。");
        } else if (profitTakingConfirmed && v12Action == TradingRadarRuleEngine.Action.AVOID) {
            // 非持有者的舊 profit-taking 對映同樣不得因高檔標籤自動變成迴避。
            action = TradingRadarRuleEngine.Action.WATCH;
        }
        return new Decision(action, true, profitTakingConfirmed, weakening, downsideMet, disclosures);
    }

    /**
     * weakening = (MA20 或 MA60 兩日 BELOW) 且 KD death cross 且
     * (OSC<0 或 completedChange<0 且 volumeRatio>=1.5/候選門檻)。
     */
    public static boolean weakeningConfirmed(
            TradingRadarRuleEngine.StockInput input,
            boolean kdDeadCross,
            RuleParameters.WeakeningCondition condition) {
        if (input == null || condition == null) return false;
        boolean structure = input.ma20Confirmation() == TradingRadarRuleEngine.Confirmation.BELOW
                || input.ma60Confirmation() == TradingRadarRuleEngine.Confirmation.BELOW;
        TradingRadarRuleEngine.ExtendedIndicators extended = input.extendedIndicators();
        boolean oscNegative = extended != null && extended.osc() != null && extended.osc().signum() < 0;
        boolean downOnVolume = input.completedChangePercent() != null
                && input.completedChangePercent().signum() < 0
                && input.volumeRatio() != null
                && input.volumeRatio().compareTo(condition.downVolumeRatioFloor()) >= 0;
        return structure && kdDeadCross && (oscNegative || downOnVolume);
    }
}
