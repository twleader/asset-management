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

    /** V6：大盤 regime 新鮮度擴充為可即時判斷（Task 228，Requirement 43 修訂）；股票分數公式本身未變。 */
    public static final String RULE_VERSION = "TW_RULES_V6";

    // ─── V5 因子權重（Requirement 43 修訂／Requirement 47）────────────────────────────
    // V4 為「base 50 + 各因子直接加減 + 硬 clamp」，理論值域 −36~+124：均線六項全滿即 +58，
    // base 50 + 58 = 108 已超過上限，使 KD 過熱的 −3 與任何新增因子被 clamp 完全吃掉
    // （實測 00719B 原始分 110、00697B 113，KD 從 (5,10) 掃到 (95,92) 分數恆為 100）。
    // V5 改為分組加權正規化：各因子標準化為 [-1,+1]，score = 50 + 50 × Σ(w×c)/Σw。
    // 因每個 c ∈ [-1,+1] 且權重以有效項重新正規化，score ∈ [0,100] 恆成立，不需截斷。
    private static final double W_MA20_POS = 0.10;
    private static final double W_MA60_POS = 0.12;
    private static final double W_MA240_POS = 0.15;
    private static final double W_MA20_CONF = 0.06;
    private static final double W_MA60_CONF = 0.08;
    private static final double W_MA240_CONF = 0.10;
    private static final double W_KD_MOMENTUM = 0.08;
    private static final double W_KD_POSITION = 0.13;
    private static final double W_MARKET = 0.08;
    private static final double W_DAY_MOVE = 0.05;
    private static final double W_FX = 0.05;

    /** KD 位置達此均值以上視為短線過熱，直接否決買進（不靠扣分，扣分會被分數區間稀釋）。 */
    private static final double KD_OVERHEAT_AVG = 80.0;
    /** 匯率分位達此值以上視為換匯過貴，直接否決買進。 */
    private static final double FX_EXPENSIVE_PCT = 90.0;

    public enum Confirmation { ABOVE, BELOW, MIXED, UNAVAILABLE }
    public enum MarketRegime { RISK_ON, NEUTRAL, RISK_OFF, DATA_INCOMPLETE }
    public enum InstrumentType { EQUITY, BOND }
    public enum CounterTrendState { NONE, OVERSOLD_WATCH, TRIAL_CANDIDATE }
    public enum Action {
        BUY_CANDIDATE,
        ADD_CANDIDATE,
        /**
         * 分批試單候選：長線結構明確向上、短線深度超賣且已轉強。
         *
         * <p>與 {@link #BUY_CANDIDATE} 的風險結構不同（接刀 vs 順勢），刻意為獨立值，
         * 使 UI、通知訂閱與日後回測能分開統計。</p>
         *
         * <p>存在的理由：買進閘門要求 MA20／MA60 兩日確認皆 ABOVE，而深度超賣幾乎必然
         * 發生在跌破均線時——實測十年 103,040 個交易日，K&lt;20 且 D&lt;20 出現 6,552 次、
         * 買進閘門成立 41,808 次，<b>兩者共存 0 次</b>。故「長線佳＋短線超賣轉強」在原本的
         * 結構下永遠不可能產生買進建議，必須另闢平行路徑，而非放寬 buyGate（放寬會讓所有
         * 下跌趨勢中的標的一併變成可買）。</p>
         */
        TRIAL_BUY,
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
     * @param fxPercentile           底層資產幣別對台幣的五年期分位（0–100）；台幣資產為 null。
     *                               台幣計價但持有外幣資產的 ETF（如 00679B/00697B/00719B）其台幣報價
     *                               ≈ 底層外幣價 × 匯率——實測 00719B 與 USD/TWD 近一年相關係數 0.9737、
     *                               剝除匯率後底層僅動 2.05%（台幣價動 10.34%），故「站上均線」量到的
     *                               相當部分是匯率而非標的本身。此欄位讓換匯貴賤能獨立進入評分（Requirement 47）。
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
            boolean marketStale,
            BigDecimal fxPercentile
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
        Accumulator acc = new Accumulator();

        acc.add(W_MA20_POS, positionOf(input.price(), input.indicators().ma20(), "月線", reasons, risks));
        acc.add(W_MA60_POS, positionOf(input.price(), input.indicators().ma60(), "季線", reasons, risks));
        acc.add(W_MA240_POS, positionOf(input.price(), input.indicators().ma240(), "年線", reasons, risks));
        acc.add(W_MA20_CONF, confirmationOf(input.ma20Confirmation(), "月線", reasons, risks));
        acc.add(W_MA60_CONF, confirmationOf(input.ma60Confirmation(), "季線", reasons, risks));
        acc.add(W_MA240_CONF, confirmationOf(input.ma240Confirmation(), "年線", reasons, risks));

        BigDecimal k = input.indicators().k();
        BigDecimal d = input.indicators().d();
        // KD 拆成兩個正交子因子。V4 把兩者混在一組加減分裡，語意上是錯的：
        // K=90.8/D=86.0 與 K=26.1/D=20.7 同樣是 K>D，但前者是「漲多了」、後者是「跌深反彈」。
        acc.add(W_KD_MOMENTUM, kdMomentum(k, d, reasons, risks));
        acc.add(W_KD_POSITION, kdPosition(k, d, reasons, risks));

        if (equityMarketApplies(input)) {
            acc.add(W_MARKET, marketContribution(input, reasons, risks));
        } else {
            // 債券不套台股大盤 regime：該因子回 null，權重由其餘有效因子按比例吸收。
            reasons.add("資產類別為債券，不套用台股大盤 RISK_ON／RISK_OFF 加減分與買進閘門。 ");
        }

        acc.add(W_DAY_MOVE, dayMoveContribution(input.changePercent(), risks));
        acc.add(W_FX, fxContribution(input.fxPercentile(), reasons, risks));

        int score = acc.score();
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

    /**
     * 加權正規化累加器：只收有效（非 null）因子，最後以有效權重總和正規化。
     *
     * <p>除以 {@code sumW} 即為「缺值權重重分配」——某因子不適用時（債券無大盤、
     * 台幣資產無匯率、指標不可得），其權重自動按比例分攤給其餘因子，而非以 0 分
     * 充當中性值把標的拉向 50 分。</p>
     *
     * <p>因每個貢獻 ∈ [-1,+1]，正規化後 sigma ∈ [-1,+1]，故 score ∈ [0,100] 恆成立。</p>
     */
    private static final class Accumulator {
        private double sumW = 0;
        private double sumWC = 0;

        void add(double weight, Double contribution) {
            if (contribution == null) return;
            sumW += weight;
            sumWC += weight * contribution;
        }

        int score() {
            if (sumW <= 0) return 50;
            double sigma = sumWC / sumW;
            return (int) Math.round(50 + 50 * sigma);
        }
    }

    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    private Double positionOf(BigDecimal price, BigDecimal ma, String label,
                              List<String> reasons, List<String> risks) {
        if (price == null || ma == null) return null;
        int cmp = price.compareTo(ma);
        if (cmp > 0) {
            reasons.add("最新價位於" + label + "之上。 ");
            return 1.0;
        }
        if (cmp < 0) {
            risks.add("最新價位於" + label + "之下。 ");
            return -1.0;
        }
        risks.add("最新價貼近" + label + "，方向尚未拉開。 ");
        return 0.0;
    }

    private Double confirmationOf(Confirmation confirmation, String label,
                                  List<String> reasons, List<String> risks) {
        if (confirmation == Confirmation.ABOVE) {
            reasons.add("已連續兩個收盤日站在" + label + "之上。 ");
            return 1.0;
        }
        if (confirmation == Confirmation.BELOW) {
            risks.add("已連續兩個收盤日位於" + label + "之下。 ");
            return -1.0;
        }
        if (confirmation == Confirmation.MIXED) {
            risks.add(label + "尚未形成連續兩日確認。 ");
            return 0.0;
        }
        return null;
    }

    /** KD 動能：K 與 D 的乖離，±10 即飽和。表達短線轉強／轉弱。 */
    private Double kdMomentum(BigDecimal k, BigDecimal d, List<String> reasons, List<String> risks) {
        if (k == null || d == null) return null;
        double v = clampUnit(k.subtract(d).doubleValue() / 10.0);
        if (v > 0) {
            reasons.add("KD 為 K>D，短線動能偏正向。 ");
        } else if (v < 0) {
            risks.add("KD 未呈 K>D，短線動能偏弱。 ");
        }
        return v;
    }

    /**
     * KD 位置：超買為負、超賣為正。這是 V5 新增的維度——V4 只有「K 是否大於 D」，
     * 無法區分「在高檔轉強」（漲多了）與「在低檔轉強」（跌深反彈）。
     */
    private Double kdPosition(BigDecimal k, BigDecimal d, List<String> reasons, List<String> risks) {
        if (k == null || d == null) return null;
        double avg = k.add(d).doubleValue() / 2.0;
        double v = clampUnit(-(avg - 50.0) / 50.0);
        if (avg > KD_OVERHEAT_AVG) {
            risks.add("KD 均值 " + Math.round(avg) + " 已達超買區，短線過熱、回檔機率升高。 ");
        } else if (avg < 25.0) {
            reasons.add("KD 均值 " + Math.round(avg) + " 位於超賣區，短線跌深。 ");
        }
        return v;
    }

    private Double marketContribution(StockInput input, List<String> reasons, List<String> risks) {
        if (input.marketRegime() == MarketRegime.RISK_ON) {
            // stale 時不給多方加分：大盤停在前一交易日，盤中崩跌看不出來（Task 217.2）。
            if (input.marketStale()) {
                risks.add("大盤資料仍停在前一交易日，暫不採計 RISK_ON 的多方加分。 ");
                return 0.0;
            }
            reasons.add("大盤為 RISK_ON，市場環境允許尋找多方機會。 ");
            return 0.5;
        }
        if (input.marketRegime() == MarketRegime.RISK_OFF) {
            // 不對稱是刻意的：新鮮度不足時只收緊、不放寬。
            risks.add("大盤為 RISK_OFF，禁止產生買進或加碼候選。 ");
            return -1.0;
        }
        return 0.0;
    }

    /** 單日異常波動一律視為風險，雙向皆負（沿用 V4 語意：追高與急跌都扣分）。值域 [-1, 0]。 */
    private Double dayMoveContribution(BigDecimal changePercent, List<String> risks) {
        if (changePercent == null) return null;
        if (changePercent.compareTo(BigDecimal.valueOf(5)) >= 0) {
            risks.add("個股單日漲幅達 5% 以上，避免追高。 ");
            return -1.0;
        }
        if (changePercent.compareTo(BigDecimal.valueOf(-5)) <= 0) {
            risks.add("個股單日跌幅達 5% 以上，波動風險升高。 ");
            return -1.0;
        }
        return 0.0;
    }

    /**
     * 換匯估值：底層幣別對台幣的五年期分位，越貴貢獻越負（Requirement 47）。
     * 台幣資產傳 null，權重由其餘因子吸收——台股標的不因匯率被加減分。
     */
    private Double fxContribution(BigDecimal fxPercentile, List<String> reasons, List<String> risks) {
        if (fxPercentile == null) return null;
        double pct = fxPercentile.doubleValue();
        double v = clampUnit(-(pct - 50.0) / 50.0);
        if (pct >= FX_EXPENSIVE_PCT) {
            risks.add("底層外幣對台幣位於五年期第 " + Math.round(pct) + " 百分位，換匯成本偏高，暫不宜進場。 ");
        } else if (pct >= 65.0) {
            risks.add("底層外幣對台幣位於五年期第 " + Math.round(pct) + " 百分位，換匯偏貴，已反映於分數。 ");
        } else if (pct <= 35.0) {
            reasons.add("底層外幣對台幣位於五年期第 " + Math.round(pct) + " 百分位，換匯相對划算。 ");
        }
        return v;
    }

    /** 年線乖離達此比例才算「長線結構明確向上」，排除貼著年線與低波動標的。 */
    private static final double TRIAL_BUY_MIN_ANNUAL_PREMIUM = 0.05;
    /** 分批試單要求的 KD 深度超賣門檻。 */
    private static final double TRIAL_BUY_KD_OVERSOLD = 20.0;

    /**
     * 分批試單：長線結構明確向上、短線深度超賣且剛轉強、且已止跌。
     *
     * <p>六項條件全數滿足才成立，任一不足即回 false。刻意嚴格——這條路徑繞過了
     * 「站上均線」的順勢要求，本質是接刀，寧可錯過也不要誤發。</p>
     */
    private boolean qualifiesForTrialBuy(StockInput input) {
        BigDecimal price = input.price();
        BigDecimal ma240 = input.indicators() == null ? null : input.indicators().ma240();
        BigDecimal k = input.indicators() == null ? null : input.indicators().k();
        BigDecimal d = input.indicators() == null ? null : input.indicators().d();
        if (price == null || ma240 == null || k == null || d == null) return false;
        if (ma240.signum() <= 0) return false;

        // 1. 長線結構明確向上：年線之上且乖離足夠（僅 price > ma240 在多頭市場幾乎全數成立，
        //    無篩選力；加上乖離門檻才能排除貼著年線者與 KD 近乎雜訊的低波動標的）。
        double premium = price.subtract(ma240).doubleValue() / ma240.doubleValue();
        if (premium < TRIAL_BUY_MIN_ANNUAL_PREMIUM) return false;
        // 2. 年線本身已連續兩日站穩，排除剛上穿的假突破。
        if (input.ma240Confirmation() != Confirmation.ABOVE) return false;
        // 3. KD 深度超賣。
        if (k.doubleValue() >= TRIAL_BUY_KD_OVERSOLD || d.doubleValue() >= TRIAL_BUY_KD_OVERSOLD) return false;
        // 4. 已轉強，且前一期確實是 K<=D——沒有這一項，「持續強勢的低檔股」會被誤判成交叉。
        if (k.compareTo(d) <= 0) return false;
        if (input.previousK() == null || input.previousD() == null) return false;
        if (input.previousK().compareTo(input.previousD()) > 0) return false;
        // 5. 止跌：用最近一根完成日 K，不可用盤中漲跌幅（零交叉會讓狀態逐 tick 翻轉）。
        BigDecimal stability = input.completedChangePercent() != null
                ? input.completedChangePercent()
                : input.changePercent();
        if (stability == null || stability.signum() < 0) return false;
        // 6. 大盤閘門：股票在 RISK_OFF 或資料 stale 時不試單；債券沿用既有豁免。
        return !equityMarketApplies(input)
                || (input.marketRegime() != MarketRegime.RISK_OFF && !input.marketStale());
    }

    private Action actionFor(StockInput input, int score) {
        // 分批試單優先於分數映射：這類標的的短線分數必然偏低（剛跌深），
        // 若先走分數映射會被判成減碼／出場，與「長線佳、可分批進場」的判斷自相矛盾。
        if (qualifiesForTrialBuy(input)) {
            return Action.TRIAL_BUY;
        }

        // 大盤 stale 時一律關閉買進閘門（債券不套大盤閘門，故不受影響）。
        boolean marketAllowsBuy = !equityMarketApplies(input)
                || (input.marketRegime() != MarketRegime.RISK_OFF && !input.marketStale());

        // KD 過熱與換匯過貴採「否決」而非「扣分」：兩者是進場時機問題，不是標的品質問題。
        // 一檔長期結構完好的標的不該因為短線過熱就被判減碼，但也不該在過熱時被建議買進。
        BigDecimal k = input.indicators() == null ? null : input.indicators().k();
        BigDecimal d = input.indicators() == null ? null : input.indicators().d();
        boolean kdOverheated = k != null && d != null
                && k.add(d).doubleValue() / 2.0 > KD_OVERHEAT_AVG;
        boolean fxExpensive = input.fxPercentile() != null
                && input.fxPercentile().doubleValue() >= FX_EXPENSIVE_PCT;

        boolean buyGate = marketAllowsBuy
                && input.ma20Confirmation() == Confirmation.ABOVE
                && input.ma60Confirmation() == Confirmation.ABOVE
                && !kdOverheated
                && !fxExpensive;
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
