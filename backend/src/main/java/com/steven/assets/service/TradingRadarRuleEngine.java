package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）的純規則引擎。
 *
 * <p>不讀資料庫、不碰網路、不依賴時間；同一輸入永遠得到同一輸出，方便測試、回測與稽核。</p>
 */
@Component
public class TradingRadarRuleEngine {

    public static final String RULE_VERSION = "TW_RULES_V6";

    public enum Confirmation { ABOVE, BELOW, MIXED, UNAVAILABLE }
    public enum MarketRegime { RISK_ON, NEUTRAL, RISK_OFF, DATA_INCOMPLETE }
    public enum InstrumentType { EQUITY, BOND }
    public enum CounterTrendState { NONE, OVERSOLD_WATCH, TRIAL_CANDIDATE }
    public enum Action {
        BUY_CANDIDATE,
        ADD_CANDIDATE,
        HOLD,
        WATCH,
        HOLD_CAUTION,
        WAIT,
        REDUCE_CANDIDATE,
        EXIT_CANDIDATE,
        AVOID,
        NO_TRADE
    }

    public record Indicators(
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma240,
            BigDecimal k,
            BigDecimal d
    ) {}

    public record MarketInput(
            BigDecimal price,
            BigDecimal changePercent,
            Indicators indicators,
            Confirmation ma60Confirmation,
            Confirmation ma240Confirmation
    ) {}

    /**
     * @param changePercent          盤中即時漲跌幅：供顯示與「單日漲跌幅扣分」使用。
     * @param completedChangePercent 最近一根完成日 K 的漲跌幅：供逆勢「停止續跌」判定，
     *                               盤中為常數，避免零交叉造成逐 tick 翻轉（Task 217.3）。
     * @param marketStale            大盤資料非當前交易日：只收緊不放寬（Task 217.2）。
     */
    public record StockInput(
            boolean held,
            BigDecimal price,
            BigDecimal changePercent,
            BigDecimal completedChangePercent,
            Indicators indicators,
            BigDecimal previousK,
            BigDecimal previousD,
            Confirmation ma20Confirmation,
            Confirmation ma60Confirmation,
            Confirmation ma240Confirmation,
            InstrumentType instrumentType,
            MarketRegime marketRegime,
            boolean marketStale
    ) {}

    public record MarketResult(
            Integer score,
            MarketRegime regime,
            List<String> reasons,
            List<String> risks
    ) {}

    public record StockResult(
            Integer score,
            Action action,
            CounterTrendResult counterTrend,
            List<String> reasons,
            List<String> risks
    ) {}

    public record CounterTrendResult(
            CounterTrendState state,
            List<String> reasons,
            List<String> risks
    ) {}

    /**
     * 以最近完成日 K（新到舊）判斷連續兩個收盤日相對各自當日 SMA 的位置。
     */
    public Confirmation confirm(List<BigDecimal> closesDesc, int period) {
        if (closesDesc == null || period <= 0 || closesDesc.size() < period + 1) {
            return Confirmation.UNAVAILABLE;
        }
        for (int i = 0; i <= period; i++) {
            if (closesDesc.get(i) == null) return Confirmation.UNAVAILABLE;
        }

        BigDecimal latestMa = average(closesDesc, 0, period);
        BigDecimal previousMa = average(closesDesc, 1, period);
        int latest = closesDesc.get(0).compareTo(latestMa);
        int previous = closesDesc.get(1).compareTo(previousMa);

        if (latest > 0 && previous > 0) return Confirmation.ABOVE;
        if (latest < 0 && previous < 0) return Confirmation.BELOW;
        return Confirmation.MIXED;
    }

    public MarketResult evaluateMarket(MarketInput input) {
        if (!complete(input)) {
            return new MarketResult(null, MarketRegime.DATA_INCOMPLETE, List.of(),
                    List.of("大盤必要的 MA20／60／240、KD 或 241 根完成日 K 不足，今日不建立風險方向。"));
        }

        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        int score = 50;
        score += priceVsMa(input.price(), input.indicators().ma20(), 8, "月線", reasons, risks);
        score += priceVsMa(input.price(), input.indicators().ma60(), 12, "季線", reasons, risks);
        score += priceVsMa(input.price(), input.indicators().ma240(), 15, "年線", reasons, risks);
        score += confirmationScore(input.ma60Confirmation(), 5, "季線", reasons, risks);
        score += confirmationScore(input.ma240Confirmation(), 5, "年線", reasons, risks);

        if (input.indicators().k().compareTo(input.indicators().d()) > 0) {
            score += 5;
            reasons.add("大盤 KD 為 K>D，短線動能偏正向。 ");
        } else {
            score -= 5;
            risks.add("大盤 KD 未呈 K>D，短線動能偏弱。 ");
        }

        if (input.changePercent().compareTo(BigDecimal.valueOf(-3)) <= 0) {
            score -= 10;
            risks.add("大盤單日跌幅達 3% 以上，觸發市場壓力扣分。 ");
        } else if (input.changePercent().compareTo(BigDecimal.valueOf(3)) >= 0) {
            score += 3;
            reasons.add("大盤單日漲幅達 3% 以上，但仍需留意追價風險。 ");
        }

        score = clamp(score);
        MarketRegime regime = score >= 65
                ? MarketRegime.RISK_ON
                : score >= 40 ? MarketRegime.NEUTRAL : MarketRegime.RISK_OFF;
        return new MarketResult(score, regime, List.copyOf(reasons), List.copyOf(risks));
    }

    public StockResult evaluateStock(StockInput input) {
        if (!complete(input)) {
            return new StockResult(null, Action.NO_TRADE,
                    new CounterTrendResult(CounterTrendState.NONE, List.of(), List.of()), List.of(),
                    List.of("個股必要的 MA20／60／240、KD、241 根完成日 K 或大盤資料不足，今日不交易。"));
        }

        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        int score = 50;
        score += priceVsMa(input.price(), input.indicators().ma20(), 8, "月線", reasons, risks);
        score += priceVsMa(input.price(), input.indicators().ma60(), 12, "季線", reasons, risks);
        score += priceVsMa(input.price(), input.indicators().ma240(), 15, "年線", reasons, risks);
        score += confirmationScore(input.ma20Confirmation(), 5, "月線", reasons, risks);
        score += confirmationScore(input.ma60Confirmation(), 8, "季線", reasons, risks);
        score += confirmationScore(input.ma240Confirmation(), 10, "年線", reasons, risks);

        BigDecimal k = input.indicators().k();
        BigDecimal d = input.indicators().d();
        if (k.compareTo(d) > 0) {
            score += 5;
            reasons.add("KD 為 K>D，短線動能偏正向。 ");
            if (k.compareTo(BigDecimal.valueOf(20)) < 0 && d.compareTo(BigDecimal.valueOf(20)) < 0) {
                score += 3;
                reasons.add("KD 位於 20 以下且 K>D，出現低檔轉強訊號。 ");
            }
        } else {
            score -= 5;
            risks.add("KD 未呈 K>D，短線動能偏弱。 ");
        }
        if (k.compareTo(BigDecimal.valueOf(80)) > 0 && d.compareTo(BigDecimal.valueOf(80)) > 0) {
            score -= 3;
            risks.add("KD 同在 80 以上，短線可能過熱。 ");
        }

        if (equityMarketApplies(input)) {
            if (input.marketRegime() == MarketRegime.RISK_ON) {
                // stale 時不給多方加分：大盤停在前一交易日，盤中崩跌看不出來（Task 217.2）。
                if (input.marketStale()) {
                    risks.add("大盤資料仍停在前一交易日，暫不採計 RISK_ON 的多方加分。 ");
                } else {
                    score += 8;
                    reasons.add("大盤為 RISK_ON，市場環境允許尋找多方機會。 ");
                }
            } else if (input.marketRegime() == MarketRegime.RISK_OFF) {
                // 扣分與 veto 不因 stale 放寬：新鮮度不足時只收緊。
                score -= 15;
                risks.add("大盤為 RISK_OFF，禁止產生買進或加碼候選。 ");
            }
        } else {
            reasons.add("資產類別為債券，不套用台股大盤 RISK_ON／RISK_OFF 加減分與買進閘門。 ");
        }

        if (input.changePercent().compareTo(BigDecimal.valueOf(5)) >= 0) {
            score -= 3;
            risks.add("個股單日漲幅達 5% 以上，避免追高。 ");
        } else if (input.changePercent().compareTo(BigDecimal.valueOf(-5)) <= 0) {
            score -= 5;
            risks.add("個股單日跌幅達 5% 以上，波動風險升高。 ");
        }

        score = clamp(score);
        Action action = actionFor(input, score);
        CounterTrendResult counterTrend = evaluateCounterTrend(input);
        return new StockResult(score, action, counterTrend, List.copyOf(reasons), List.copyOf(risks));
    }

    private CounterTrendResult evaluateCounterTrend(StockInput input) {
        BigDecimal k = input.indicators().k();
        BigDecimal d = input.indicators().d();
        boolean longTermIntact = input.price().compareTo(input.indicators().ma240()) > 0
                && input.ma240Confirmation() == Confirmation.ABOVE;
        boolean shortTermPullback = input.ma20Confirmation() == Confirmation.BELOW
                && input.ma60Confirmation() == Confirmation.BELOW;
        boolean kOversold = k.compareTo(BigDecimal.valueOf(20)) < 0;

        if (!longTermIntact || !shortTermPullback || !kOversold) {
            return new CounterTrendResult(CounterTrendState.NONE, List.of(), List.of());
        }

        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        reasons.add("最新價與連續兩個收盤日仍在年線之上，長期結構尚未失守。 ");
        reasons.add("月線、季線已連續兩日跌破且 K 低於 20，進入短線超跌觀察區。 ");

        boolean lowKd = d.compareTo(BigDecimal.valueOf(20)) < 0;
        boolean goldenCross = input.previousK() != null
                && input.previousD() != null
                && input.previousK().compareTo(input.previousD()) <= 0
                && k.compareTo(d) > 0;
        // 「停止續跌」以最近一根完成日 K 判定：盤中漲跌幅在 0% 附近零交叉會讓狀態逐 tick 翻轉（Task 217.3）。
        BigDecimal stabilityBasis = input.completedChangePercent() != null
                ? input.completedChangePercent()
                : input.changePercent();
        boolean stabilized = stabilityBasis.compareTo(BigDecimal.ZERO) >= 0;

        if (lowKd && goldenCross && stabilized) {
            reasons.add("K、D 皆低於 20，且 K 由前一期不高於 D 轉為 K>D，形成低檔黃金交叉。 ");
            reasons.add("本日價格已停止續跌，可列為小額分批的逆勢試單候選。 ");
            risks.add("逆勢試單不取代原本的趨勢分數與主規則建議，必須限制部位並分批。 ");
            if (equityMarketApplies(input) && input.marketRegime() == MarketRegime.RISK_OFF) {
                risks.add("大盤仍為 RISK_OFF，只限小額試單，不得視為一般買進或加碼候選。 ");
            }
            return new CounterTrendResult(
                    CounterTrendState.TRIAL_CANDIDATE,
                    List.copyOf(reasons),
                    List.copyOf(risks));
        }

        if (!lowKd) risks.add("D 尚未低於 20，KD 尚未完整進入低檔。 ");
        if (input.previousK() == null || input.previousD() == null) {
            risks.add("前一期 KD 資料不足，不能判定低檔黃金交叉。 ");
        } else if (!goldenCross) {
            risks.add("KD 尚未由 K<=D 轉為 K>D，低檔轉強仍未確認。 ");
        }
        if (!stabilized) risks.add("本日價格仍在下跌，尚未出現停止續跌訊號。 ");
        if (equityMarketApplies(input) && input.marketRegime() == MarketRegime.RISK_OFF) {
            risks.add("大盤為 RISK_OFF，僅可觀察；若後續升級也只限小額分批，主規則建議仍優先。 ");
        }
        return new CounterTrendResult(
                CounterTrendState.OVERSOLD_WATCH,
                List.copyOf(reasons),
                List.copyOf(risks));
    }

    private Action actionFor(StockInput input, int score) {
        // 大盤 stale 時一律關閉買進閘門（債券不套大盤閘門，故不受影響）。
        boolean marketAllowsBuy = !equityMarketApplies(input)
                || (input.marketRegime() != MarketRegime.RISK_OFF && !input.marketStale());
        boolean buyGate = marketAllowsBuy
                && input.ma20Confirmation() == Confirmation.ABOVE
                && input.ma60Confirmation() == Confirmation.ABOVE;
        if (score >= 75 && buyGate) {
            return input.held() ? Action.ADD_CANDIDATE : Action.BUY_CANDIDATE;
        }
        if (score >= 55) return input.held() ? Action.HOLD : Action.WATCH;
        if (score >= 40) return input.held() ? Action.HOLD_CAUTION : Action.WAIT;
        if (score >= 25) return input.held() ? Action.REDUCE_CANDIDATE : Action.AVOID;
        return input.held() ? Action.EXIT_CANDIDATE : Action.AVOID;
    }

    private boolean complete(MarketInput input) {
        return input != null
                && input.price() != null
                && input.changePercent() != null
                && complete(input.indicators())
                && available(input.ma60Confirmation())
                && available(input.ma240Confirmation());
    }

    private boolean complete(StockInput input) {
        return input != null
                && input.price() != null
                && input.changePercent() != null
                && complete(input.indicators())
                && available(input.ma20Confirmation())
                && available(input.ma60Confirmation())
                && available(input.ma240Confirmation())
                && input.instrumentType() != null
                && input.marketRegime() != null
                && (!equityMarketApplies(input)
                    || input.marketRegime() != MarketRegime.DATA_INCOMPLETE);
    }

    private boolean complete(Indicators indicators) {
        return indicators != null
                && indicators.ma20() != null
                && indicators.ma60() != null
                && indicators.ma240() != null
                && indicators.k() != null
                && indicators.d() != null;
    }

    private boolean available(Confirmation confirmation) {
        return confirmation != null && confirmation != Confirmation.UNAVAILABLE;
    }

    private boolean equityMarketApplies(StockInput input) {
        return input.instrumentType() != InstrumentType.BOND;
    }

    private int priceVsMa(BigDecimal price, BigDecimal ma, int weight, String label,
                          List<String> reasons, List<String> risks) {
        int cmp = price.compareTo(ma);
        if (cmp > 0) {
            reasons.add("最新價位於" + label + "之上。 ");
            return weight;
        }
        if (cmp < 0) {
            risks.add("最新價位於" + label + "之下。 ");
            return -weight;
        }
        risks.add("最新價貼近" + label + "，方向尚未拉開。 ");
        return 0;
    }

    private int confirmationScore(Confirmation confirmation, int weight, String label,
                                  List<String> reasons, List<String> risks) {
        if (confirmation == Confirmation.ABOVE) {
            reasons.add("已連續兩個收盤日站在" + label + "之上。 ");
            return weight;
        }
        if (confirmation == Confirmation.BELOW) {
            risks.add("已連續兩個收盤日位於" + label + "之下。 ");
            return -weight;
        }
        risks.add(label + "尚未形成連續兩日確認。 ");
        return 0;
    }

    private BigDecimal average(List<BigDecimal> values, int start, int count) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i < start + count; i++) sum = sum.add(values.get(i));
        return sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
