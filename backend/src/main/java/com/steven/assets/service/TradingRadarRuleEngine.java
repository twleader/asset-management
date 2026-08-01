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

    /**
     * V9：動作映射由「分數單一維度」改為「趨勢品質 × 進場時機」二維（Task 264）。
     *
     * <p>新增季線乖離、52 週相對位置、ETF 折溢價三個因子並重配全部權重；新增 {@link TimingState}
     * 對動作做雙向覆寫（極端超買＋高檔死叉 → 減碼；極端超賣 → 阻擋出場）；移除 {@code TRIAL_BUY}
     * 的 {@code RISK_OFF} 封鎖。<b>V9 與 V8 的分數不可直接比較</b>——與 Task 228／232／263 三次
     * 「因子組成、權重與正規化方式完全相同」的升版不同，本次改了因子、權重與映射結構。</p>
     */
    public static final String RULE_VERSION = "TW_RULES_V9";

    // ─── V9 因子權重（Requirement 43 修訂／Task 264）────────────────────────────
    // V5~V8 為 11 個因子，尺度佔比「短期 0.37／中期 0.20／長期 0.25／環境 0.18」——短期壓過長期，
    // 與「獲利期間數周至兩年」的需求相反。V9 增為 14 個，改為「短期 0.23／中期 0.29／長期 0.27／
    // 環境 0.15／估值 0.06」，中期（數周至數月）成為最大組。
    // 權重表以 Requirement 43 修訂（TW_RULES_V9）的 Acceptance Criteria 為唯一契約。
    private static final double W_MA20_POS = 0.06;
    private static final double W_MA20_CONF = 0.05;
    private static final double W_KD_MOMENTUM = 0.05;
    private static final double W_KD_POSITION = 0.07;
    private static final double W_MA60_POS = 0.10;
    private static final double W_MA60_CONF = 0.07;
    /** 季線乖離（均值回歸）：V9 對「不追高殺低」的評分層修正，與動作層的 TimingState 覆寫互補。 */
    private static final double W_EXTENSION = 0.12;
    private static final double W_MA240_POS = 0.11;
    private static final double W_MA240_CONF = 0.09;
    /** 52 週相對位置：長期趨勢品質。刻意保持正向——追高的抑制由季線乖離與時機覆寫負責。 */
    private static final double W_52W_POS = 0.07;
    private static final double W_MARKET = 0.06;
    private static final double W_DAY_MOVE = 0.04;
    private static final double W_FX = 0.05;
    /** ETF 折溢價自身歷史分位：溢價買進等於為同一籃資產多付錢。 */
    private static final double W_ETF_PREMIUM = 0.06;

    /** 全部權重的合計，供測試以斷言釘住（不得靠人工加總）。 */
    static final double WEIGHT_SUM = W_MA20_POS + W_MA20_CONF + W_KD_MOMENTUM + W_KD_POSITION
            + W_MA60_POS + W_MA60_CONF + W_EXTENSION + W_MA240_POS + W_MA240_CONF + W_52W_POS
            + W_MARKET + W_DAY_MOVE + W_FX + W_ETF_PREMIUM;

    /** 季線乖離的飽和點（%）：正負 25% 即達 -1／+1。 */
    private static final double BIAS_SATURATION = 25.0;
    /** 極端超買／超賣的季線乖離門檻（%）。 */
    private static final double BIAS_EXTREME_HIGH = 20.0;
    private static final double BIAS_EXTREME_LOW = -20.0;
    /** 一般超買／超賣的季線乖離門檻（%）。 */
    private static final double BIAS_HIGH = 12.0;
    private static final double BIAS_LOW = -12.0;

    /**
     * 9 日高低帶寬度低於此比例（%）時，KD 視為在雜訊上飽和而失效（Task 264）。
     *
     * <p>實測 00719B（元大美債1-3）近 60 個交易日的 9 日高低帶平均寬度僅 {@code 1.011%}，
     * 其 {@code K=90.76} 實質只代表「比 9 日低點高 0.37 元」。V9 新增兩條由 KD 驅動的<b>動作覆寫</b>，
     * 不做此防護會讓債券 ETF 大量誤發減碼與試單建議——那比現況更糟。</p>
     */
    private static final double KD_BAND_MIN_PERCENT = 2.0;

    /** KD 位置達此均值以上視為短線過熱，直接否決買進（不靠扣分，扣分會被分數區間稀釋）。 */
    private static final double KD_OVERHEAT_AVG = 80.0;
    /**
     * K 單獨達此值亦視為短線過熱（Task 232）。
     *
     * <p>存在的理由：原本只看 {@code avg(K,D)}，而 K 剛突破 80 時正是 K 大幅領先 D 之際，
     * 均值被 D 拉低而不觸發——實測 00882（K 82.3／D 75.2）avg 僅 78.75，被判加碼候選。</p>
     *
     * <p><b>此門檻沒有回測依據，是刻意的風險偏好取捨。</b>十年台股 37,720 個買進閘門成立樣本
     * 顯示，過熱組的後續下檔風險反而低於正常放行組（20 日內跌逾 10% 的比例：K&gt;85 組 10.0%、
     * 正常放行組 15.9%）。採納理由是使用者不願在單一指標極端超買時收到加碼建議。
     * 取 85 而非 80 的依據是受影響樣本量（0.63% vs 7.10%），即盡量縮小影響面。
     * <b>不得於任何文案宣稱本門檻能降低回檔風險。</b></p>
     */
    private static final double KD_OVERHEAT_K = 85.0;
    /** K 單獨達此值視為短線偏熱：純揭露，不影響分數與動作（Task 232）。 */
    private static final double KD_ELEVATED_K = 80.0;
    /** KD 均值達此值視為短線偏熱：純揭露，不影響分數與動作（Task 232）。 */
    private static final double KD_ELEVATED_AVG = 70.0;

    /**
     * 極端超賣的門檻，對稱於 {@link #KD_OVERHEAT_AVG}／{@link #KD_OVERHEAT_K}（Task 264）。
     *
     * <p><b>對稱性是 V9 的核心論證</b>：買方既有「超買否決買進」，賣方就必須有「超賣否決賣出」，
     * 否則系統只在單一方向上保守，等於在低點建議殺低。</p>
     */
    private static final double KD_OVERSOLD_AVG = 20.0;
    private static final double KD_OVERSOLD_K = 15.0;

    /**
     * 52 週相對位置低於此值、且年線已兩日跌破時，視為長期結構已完全破壞。
     *
     * <p>這是「極端超賣不殺低」的必要安全閥：沒有它，一檔持續崩壞的標的會因為 KD 永遠釘在低檔而
     * <b>永遠拿不到出場訊號</b>。有了它，「大盤急跌造成的錯殺」被保護、「真實崩壞」仍會出場。</p>
     */
    private static final double WEEK52_BROKEN = 0.10;

    /** 匯率分位達此值以上視為換匯過貴，直接否決買進。 */
    private static final double FX_EXPENSIVE_PCT = 90.0;

    /**
     * ETF 溢價達此比例（%）即否決買進，與自身歷史分位無關。
     *
     * <p>絕對門檻與分位門檻並存是刻意的：分位管「相對自己貴不貴」，絕對值管「無論歷史如何，
     * 溢價 3% 買進就是為同一籃資產多付 3%」。只用分位會讓一檔長期高溢價的 ETF 因為「現在只是
     * 它的中位數」而放行。</p>
     */
    private static final double ETF_PREMIUM_EXPENSIVE = 3.0;

    public enum Confirmation { ABOVE, BELOW, MIXED, UNAVAILABLE }
    public enum MarketRegime { RISK_ON, NEUTRAL, RISK_OFF, DATA_INCOMPLETE }
    public enum InstrumentType { EQUITY, BOND }
    public enum CounterTrendState { NONE, OVERSOLD_WATCH, TRIAL_CANDIDATE }
    /**
     * KD 短線熱度三態（Task 232）。
     *
     * <p>{@code OVERHEATED} 關閉買進閘門（降級為 HOLD／WATCH，不扣分）；
     * {@code ELEVATED} <b>純為揭露，不影響分數與動作</b>——它存在的理由是收合列看不到
     * reasons／risks，使用者無從得知 K 已偏高。</p>
     */
    public enum KdHeat { OVERHEATED, ELEVATED, NORMAL }

    /**
     * 進場時機（Task 264）——與 {@code score} 正交的第二個維度。
     *
     * <p>V8 之前動作完全由 {@code score} 單調決定，而 {@code score} 量的是順勢強度，
     * 造成兩個方向的封閉：<b>六項均線全為 {@code +1} 時分數下限為 65，永遠達不到減碼／出場</b>；
     * 跌破均線又必然落入出場區。即「均線之上永遠不賣、跌破均線必然賣」＝追高殺低。
     * 單維度下「高分」與「該賣」無法同時表達，故必須另立此維度。</p>
     */
    public enum TimingState {
        EXTREME_OVERBOUGHT, OVERBOUGHT, NEUTRAL, OVERSOLD, EXTREME_OVERSOLD
    }
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
     * @param ma60BiasPercent        現價對季線的乖離率（%）。數周至數月尺度，是 V9 的均值回歸主因子。
     * @param ma240BiasPercent       現價對年線的乖離率（%）。供 TRIAL_BUY 的長線結構門檻與風險文案。
     *                               <b>單位為百分比</b>，故 {@link #TRIAL_BUY_MIN_ANNUAL_PREMIUM} 亦以百分比表示。
     * @param week52Position         52 週相對位置，值域 {@code [0,1]}（呼叫端須 clamp）。長期趨勢品質。
     * @param kdBandWidthPercent     9 日高低帶寬度（%）。低於門檻時 KD 全面失效，見 {@link #KD_BAND_MIN_PERCENT}。
     * @param etfPremiumPct          ETF 折溢價（%，{@code 1.2} = 溢價 1.2%）；非 ETF 為 null。
     * @param etfPremiumPercentile   該 ETF 自身歷史折溢價分位（0–100）；樣本不足或非 ETF 為 null。
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
            BigDecimal fxPercentile,
            BigDecimal ma60BiasPercent,
            BigDecimal ma240BiasPercent,
            BigDecimal week52Position,
            BigDecimal kdBandWidthPercent,
            BigDecimal etfPremiumPct,
            BigDecimal etfPremiumPercentile
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
            List<String> risks,
            /** KD 短線熱度（Task 232）；供畫面在收合列即可辨識，不影響 score 與 action。 */
            KdHeat kdHeat,
            /** 進場時機（Task 264）；供畫面在收合列即可辨識，並對動作做雙向覆寫。 */
            TimingState timingState
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
            // Task 264：不再加分。獎勵大盤暴漲與「不追高」直接衝突；單日跌逾 3% 的扣分保留不動
            // （只收緊不放寬的既有原則）。
            risks.add("大盤單日漲幅達 3% 以上，追價風險升高。 ");
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
                    List.of("個股必要的 MA20／60／240、KD、241 根完成日 K 或大盤資料不足，今日不交易。"),
                    KdHeat.NORMAL, TimingState.NEUTRAL);
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

        acc.add(W_EXTENSION, extensionOf(input.ma60BiasPercent(), reasons, risks));
        acc.add(W_52W_POS, week52Of(input.week52Position(), reasons, risks));

        BigDecimal k = input.indicators().k();
        BigDecimal d = input.indicators().d();
        boolean narrowBand = narrowKdBand(input);
        if (narrowBand) {
            risks.add("近 9 個交易日的高低帶過窄，KD 在雜訊上飽和，本日不以 KD 判斷位置與時機。 ");
        }
        // KD 拆成兩個正交子因子。V4 把兩者混在一組加減分裡，語意上是錯的：
        // K=90.8/D=86.0 與 K=26.1/D=20.7 同樣是 K>D，但前者是「漲多了」、後者是「跌深反彈」。
        acc.add(W_KD_MOMENTUM, kdMomentum(k, d, reasons, risks));
        // 窄幅時 KD 位置回 null，權重由 Accumulator 重分配（不得以 0 充當中性值）。
        acc.add(W_KD_POSITION, narrowBand ? null : kdPosition(k, d, reasons));
        // 熱度文案與買進閘門共用 kdHeatOf()，不在此另行比較門檻（Task 232）。
        KdHeat kdHeat = kdHeatOf(input);
        describeHeat(kdHeat, k, d, risks);

        if (equityMarketApplies(input)) {
            acc.add(W_MARKET, marketContribution(input, reasons, risks));
        } else {
            // 債券不套台股大盤 regime：該因子回 null，權重由其餘有效因子按比例吸收。
            reasons.add("資產類別為債券，不套用台股大盤 RISK_ON／RISK_OFF 加減分與買進閘門。 ");
        }

        acc.add(W_DAY_MOVE, dayMoveContribution(input.changePercent(), risks));
        acc.add(W_FX, fxContribution(input.fxPercentile(), reasons, risks));
        acc.add(W_ETF_PREMIUM, etfPremiumContribution(input, reasons, risks));

        int score = acc.score();
        TimingState timing = timingOf(input);
        Action action = actionFor(input, score, timing, risks, reasons);
        CounterTrendResult counterTrend = evaluateCounterTrend(input);
        return new StockResult(score, action, counterTrend,
                List.copyOf(reasons), List.copyOf(risks), kdHeat, timing);
    }

    private CounterTrendResult evaluateCounterTrend(StockInput input) {
        // 窄幅標的的 KD 在雜訊上飽和，低檔判定與高檔同樣失真（Task 264）。
        if (narrowKdBand(input)) {
            return new CounterTrendResult(CounterTrendState.NONE, List.of(), List.of());
        }
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
    private Double kdPosition(BigDecimal k, BigDecimal d, List<String> reasons) {
        if (k == null || d == null) return null;
        double avg = k.add(d).doubleValue() / 2.0;
        double v = clampUnit(-(avg - 50.0) / 50.0);
        if (avg < 25.0) {
            reasons.add("KD 均值 " + Math.round(avg) + " 位於超賣區，短線跌深。 ");
        }
        // 超買側的文案改由 describeHeat() 統一輸出，與買進閘門共用同一門檻求值處（Task 232）。
        return v;
    }

    /**
     * KD 熱度三態的<b>唯一求值處</b>——買進閘門、風險文案、DTO 皆由此取得。
     *
     * <p>刻意集中：門檻若散在閘門與文案各判一次，日後任一處被改就會出現
     * 「畫面標示過熱、動作卻仍是加碼候選」的矛盾，而那正是 Task 232 要消滅的缺陷。</p>
     */
    private KdHeat kdHeatOf(StockInput input) {
        if (narrowKdBand(input)) return KdHeat.NORMAL;
        BigDecimal k = input.indicators() == null ? null : input.indicators().k();
        BigDecimal d = input.indicators() == null ? null : input.indicators().d();
        if (k == null || d == null) return KdHeat.NORMAL;
        double avg = k.add(d).doubleValue() / 2.0;
        double kv = k.doubleValue();
        if (avg > KD_OVERHEAT_AVG || kv > KD_OVERHEAT_K) return KdHeat.OVERHEATED;
        if (kv > KD_ELEVATED_K || avg > KD_ELEVATED_AVG) return KdHeat.ELEVATED;
        return KdHeat.NORMAL;
    }

    /**
     * 極端超賣的<b>唯一求值處</b>，對稱於 {@link #kdHeatOf(StockInput)}。
     *
     * <p>集中的理由同 Task 232：門檻若散在覆寫與文案各判一次，日後任一處被改就會出現
     * 「畫面標示極端超賣、動作卻是出場候選」的矛盾。</p>
     */
    private boolean kdOversold(StockInput input) {
        if (narrowKdBand(input)) return false;
        BigDecimal k = input.indicators() == null ? null : input.indicators().k();
        BigDecimal d = input.indicators() == null ? null : input.indicators().d();
        if (k == null || d == null) return false;
        double avg = k.add(d).doubleValue() / 2.0;
        return avg < KD_OVERSOLD_AVG || k.doubleValue() < KD_OVERSOLD_K;
    }

    /**
     * 9 日高低帶過窄 → KD 全面失效（Task 264）。
     *
     * <p><b>低檔側同樣要防護，不可只防高檔。</b>{@code qualifiesForTrialBuy()} 的深度超賣條件與
     * {@code evaluateCounterTrend()} 直接吃原始 K／D、不經 {@code kdHeatOf()}，故本方法必須被那兩處
     * 一併引用；而 V9 正在移除 {@code TRIAL_BUY} 的 {@code RISK_OFF} 封鎖，等於放大該路徑的觸發面。</p>
     *
     * <p>資料不足（{@code null}）時視同未觸發，不得因缺值而關閉保護。</p>
     */
    private boolean narrowKdBand(StockInput input) {
        BigDecimal band = input == null ? null : input.kdBandWidthPercent();
        return band != null && band.doubleValue() < KD_BAND_MIN_PERCENT;
    }

    /**
     * 季線乖離（均值回歸）：過度延伸為負、過度超跌為正。
     *
     * <p>這是 V9 對「不追高殺低」的<b>評分層</b>修正，與 {@link #timingOf} 的動作層覆寫互補——
     * 評分層讓拉伸的標的分數自然下降，動作層在極端時直接改變動作。V8 的 {@code positionOf()}
     * 只回二元 ±1，價格高於年線 0.01% 與 33% 貢獻完全相同，量不到「貴」這件事。</p>
     */
    private Double extensionOf(BigDecimal ma60BiasPercent, List<String> reasons, List<String> risks) {
        if (ma60BiasPercent == null) return null;
        double bias = ma60BiasPercent.doubleValue();
        double v = clampUnit(-bias / BIAS_SATURATION);
        if (bias >= BIAS_HIGH) {
            risks.add("最新價已明顯偏離季線（乖離 " + Math.round(bias) + "%），追高風險升高。 ");
        } else if (bias <= BIAS_LOW) {
            reasons.add("最新價已明顯低於季線（乖離 " + Math.round(bias) + "%），具均值回歸空間。 ");
        }
        return v;
    }

    /**
     * 52 週相對位置：長期趨勢品質，刻意保持正向。
     *
     * <p>與季線乖離<b>正交</b>：本因子量的是「長期趨勢好不好」，季線乖離量的是「現在進場貴不貴」。
     * 追高的抑制由後者與時機覆寫負責，不由本因子承擔——3 到 12 個月尺度的動能是長期趨勢的正向訊號。</p>
     */
    private Double week52Of(BigDecimal week52Position, List<String> reasons, List<String> risks) {
        if (week52Position == null) return null;
        double pos = Math.max(0.0, Math.min(1.0, week52Position.doubleValue()));
        if (pos >= 0.9) {
            reasons.add("價格位於 52 週區間的高位（第 " + Math.round(pos * 100) + " 百分位），長期趨勢強勢。 ");
        } else if (pos <= 0.1) {
            risks.add("價格位於 52 週區間的低位（第 " + Math.round(pos * 100) + " 百分位），長期趨勢疲弱。 ");
        }
        return clampUnit((pos - 0.5) * 2);
    }

    /**
     * ETF 折溢價自身歷史分位：越貴貢獻越負，與 {@link #fxContribution} 同形。
     *
     * <p><b>必須用自身歷史分位而非絕對值評分</b>：不同 ETF 的常態折溢價水準差異極大（台灣債券 ETF
     * 長期存在結構性溢價），用同一組絕對門檻套全部 ETF 會系統性誤判。絕對門檻另以硬否決處理。</p>
     */
    private Double etfPremiumContribution(StockInput input, List<String> reasons, List<String> risks) {
        BigDecimal pct = input.etfPremiumPct();
        if (pct != null && pct.doubleValue() >= ETF_PREMIUM_EXPENSIVE) {
            risks.add("市價高於淨值 " + fmt1(pct) + "%，等於為同一籃資產多付溢價，本日不列入買進／加碼候選。 ");
        } else if (pct != null && pct.signum() < 0) {
            reasons.add("市價低於淨值 " + fmt1(pct.abs()) + "%，進場成本相對划算。 ");
        }
        BigDecimal percentile = input.etfPremiumPercentile();
        if (percentile == null) return null;
        double p = percentile.doubleValue();
        if (p >= 80.0) {
            risks.add("折溢價位於自身歷史第 " + Math.round(p) + " 百分位，相對自己偏貴。 ");
        } else if (p <= 20.0) {
            reasons.add("折溢價位於自身歷史第 " + Math.round(p) + " 百分位，相對自己偏便宜。 ");
        }
        return clampUnit(-(p - 50.0) / 50.0);
    }

    /**
     * 進場時機（Task 264）。由極端往中性依序判斷，先命中先返回；區間互斥且窮盡。
     */
    private TimingState timingOf(StockInput input) {
        boolean overheated = kdHeatOf(input) == KdHeat.OVERHEATED;
        boolean oversold = kdOversold(input);
        Double bias = input.ma60BiasPercent() == null ? null : input.ma60BiasPercent().doubleValue();
        boolean premiumExpensive = input.etfPremiumPct() != null
                && input.etfPremiumPct().doubleValue() >= ETF_PREMIUM_EXPENSIVE;

        // 折溢價刻意不納入 EXTREME_OVERBOUGHT：溢價會在一天內收斂，而減碼是不可逆的建議。
        if (overheated && bias != null && bias >= BIAS_EXTREME_HIGH) return TimingState.EXTREME_OVERBOUGHT;
        if (oversold && bias != null && bias <= BIAS_EXTREME_LOW) return TimingState.EXTREME_OVERSOLD;
        if (overheated || premiumExpensive || (bias != null && bias >= BIAS_HIGH)) return TimingState.OVERBOUGHT;
        if (oversold || (bias != null && bias <= BIAS_LOW)) return TimingState.OVERSOLD;
        return TimingState.NEUTRAL;
    }

    /** 高檔轉弱確認：KD 高檔死亡交叉。**必須要求轉弱，不得只憑超買就賣**——只要超買就出場會在主升段初期砍掉部位。 */
    private boolean kdDeadCross(StockInput input) {
        BigDecimal k = input.indicators() == null ? null : input.indicators().k();
        BigDecimal d = input.indicators() == null ? null : input.indicators().d();
        return k != null && d != null
                && input.previousK() != null && input.previousD() != null
                && input.previousK().compareTo(input.previousD()) > 0
                && k.compareTo(d) <= 0;
    }

    /**
     * 依熱度輸出風險文案。
     *
     * <p>兩個約束：(1) <b>不得宣稱「回檔機率升高」</b>——本專案十年回測顯示過熱組
     * 20 日內跌逾 10% 的比例（11.1%）低於正常放行組（15.9%），該說法與自身資料矛盾；
     * (2) <b>不得把門檻數字寫進句子</b>——條件為嚴格大於，而 K=85.02 顯示為 85.0，
     * 「已高於 85」會變成自我否定的句子。</p>
     */
    private void describeHeat(KdHeat heat, BigDecimal k, BigDecimal d, List<String> risks) {
        if (heat == KdHeat.NORMAL || k == null || d == null) return;
        double avg = k.add(d).doubleValue() / 2.0;
        if (heat == KdHeat.OVERHEATED) {
            // avg 與 K 同時過熱時只輸出一條，avg 版優先。
            risks.add(avg > KD_OVERHEAT_AVG
                    ? "KD 均值 " + Math.round(avg) + " 已達超買區，短線位置偏高；本日不列入買進／加碼候選。 "
                    : "K 值 " + fmt1(k) + " 已達過熱區，短線位置偏高；本日不列入買進／加碼候選。 ");
        } else {
            // 兩分支同時成立時只輸出一條，K 版優先；僅 avg 觸發時不得述 K 值
            //（K=70／D=75 會使 avg=72.5 觸發偏熱，而 K 並未偏高）。
            risks.add(k.doubleValue() > KD_ELEVATED_K
                    ? "K 值 " + fmt1(k) + " 偏高，短線偏熱；未達過熱門檻，動作維持。 "
                    : "KD 均值 " + Math.round(avg) + " 偏高，短線偏熱；未達過熱門檻，動作維持。 ");
        }
    }

    /** 一位小數，與畫面 fmtNumber(kValue, 1) 同精度。 */
    private String fmt1(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
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

    /**
     * 年線乖離達此比例（<b>%</b>）才算「長線結構明確向上」，排除貼著年線與低波動標的。
     *
     * <p><b>Task 264 起單位由小數改為百分比</b>（{@code 0.05} → {@code 5.0}），與
     * {@code StockInput.ma240BiasPercent} 一致。單位不同步會使門檻由「≥ 5%」變成「≥ 0.05%」，
     * 疊加同任務移除的 {@code RISK_OFF} 封鎖後，等同在大盤急跌時對幾乎所有站上年線的標的
     * 大量誤發 {@code TRIAL_BUY}，且用「乖離 +8%」的測試案例測不出來。</p>
     */
    private static final double TRIAL_BUY_MIN_ANNUAL_PREMIUM = 5.0;
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
        // 0. 窄幅標的的 KD 在雜訊上飽和，深度超賣條件失去意義（Task 264）。
        if (narrowKdBand(input)) return false;
        // 0b. ETF 溢價過高時不進場——這是「為同一籃資產多付錢」，與追高同性質。
        if (input.etfPremiumPct() != null
                && input.etfPremiumPct().doubleValue() >= ETF_PREMIUM_EXPENSIVE) return false;

        // 1. 長線結構明確向上：年線之上且乖離足夠（僅 price > ma240 在多頭市場幾乎全數成立，
        //    無篩選力；加上乖離門檻才能排除貼著年線者與 KD 近乎雜訊的低波動標的）。
        //    改讀 StockInput.ma240BiasPercent（單位為百分比），不在此另算一份——兩份等價計算
        //    正是漂移源：其中一份被改時另一份不會跟著改，且不會有任何測試失敗。
        if (input.ma240BiasPercent() == null) return false;
        if (input.ma240BiasPercent().doubleValue() < TRIAL_BUY_MIN_ANNUAL_PREMIUM) return false;
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
        // 6. 大盤閘門：只擋 stale，不再擋 RISK_OFF（Task 264；債券沿用既有豁免）。
        //    推翻 Task 226 的理由：使用者明確要求「股市急跌時應建議買入」，而急跌時 regime 必為
        //    RISK_OFF，原封鎖使該需求在結構上不可能滿足；且它與 evaluateCounterTrend() 既有的
        //    「大盤仍為 RISK_OFF，只限小額試單」文案自相矛盾。
        //    保留 stale 封鎖：那是「資料不新鮮」的技術狀態、不是市場判斷，看不見大盤真實狀況時不得放行。
        //    安全邊界由條件 1 的年線乖離承擔：大盤急跌＋個股長線完好＝錯殺＝買點；
        //    個股自身跌破年線＝長線已壞＝不主動接刀。
        return !equityMarketApplies(input) || !input.marketStale();
    }

    /**
     * 二維動作映射（Task 264）：{@code score}（趨勢品質）× {@link TimingState}（進場時機）。
     *
     * <p>順序為 1) 分批試單 → 2) 極端超買＋高檔死叉的<b>減碼覆寫</b> → 3) 極端超賣的<b>阻擋出場</b>
     * → 4) 既有分數分層。第 2、3 步依 {@link #timingOf} 的判定順序不可能同時成立。</p>
     */
    private Action actionFor(StockInput input, int score, TimingState timing,
                             List<String> risks, List<String> reasons) {
        // 分批試單優先於分數映射：這類標的的短線分數必然偏低（剛跌深），
        // 若先走分數映射會被判成減碼／出場，與「長線佳、可分批進場」的判斷自相矛盾。
        if (qualifiesForTrialBuy(input)) {
            risks.add("逆勢試單的本質是接刀，僅適合小額分批；長線判斷失準時虧損可能持續擴大。 ");
            if (equityMarketApplies(input) && input.marketRegime() == MarketRegime.RISK_OFF) {
                risks.add("大盤為 RISK_OFF，本訊號只限小額試單，不得視為一般買進或加碼候選。 ");
            }
            return Action.TRIAL_BUY;
        }

        // ── 第 2 步：高點應建議賣出（需求 6）。此覆寫凌駕分數——高檔時分數必高，不凌駕就永遠觸發不到。
        if (timing == TimingState.EXTREME_OVERBOUGHT && kdDeadCross(input)) {
            reasons.add("已達極端超買（KD 過熱且明顯偏離季線），且 KD 於高檔形成死亡交叉、短線轉弱。 ");
            risks.add("本訊號描述的是當前位置與轉弱狀態，不預測隔日漲跌；分批減碼優於一次出清。 ");
            return input.held() ? Action.REDUCE_CANDIDATE : Action.AVOID;
        }

        // 大盤 stale 時一律關閉買進閘門（債券不套大盤閘門，故不受影響）。
        boolean marketAllowsBuy = !equityMarketApplies(input)
                || (input.marketRegime() != MarketRegime.RISK_OFF && !input.marketStale());

        // KD 過熱、換匯過貴、ETF 溢價過高採「否決」而非「扣分」：三者都是進場時機問題，
        // 不是標的品質問題。一檔長期結構完好的標的不該因為短線過熱就被判減碼，但也不該在過熱時被建議買進。
        boolean kdOverheated = kdHeatOf(input) == KdHeat.OVERHEATED;
        boolean fxExpensive = input.fxPercentile() != null
                && input.fxPercentile().doubleValue() >= FX_EXPENSIVE_PCT;
        boolean premiumExpensive = input.etfPremiumPct() != null
                && input.etfPremiumPct().doubleValue() >= ETF_PREMIUM_EXPENSIVE;

        boolean buyGate = marketAllowsBuy
                && input.ma20Confirmation() == Confirmation.ABOVE
                && input.ma60Confirmation() == Confirmation.ABOVE
                && !kdOverheated
                && !fxExpensive
                && !premiumExpensive;
        if (score >= 75 && buyGate) {
            return input.held() ? Action.ADD_CANDIDATE : Action.BUY_CANDIDATE;
        }
        if (score >= 55) return input.held() ? Action.HOLD : Action.WATCH;
        if (score >= 40) return input.held() ? Action.HOLD_CAUTION : Action.WAIT;

        // ── 第 3 步：不得殺低（需求 3）。對稱於買方的「超買否決買進」。
        Action mapped = score >= 25
                ? (input.held() ? Action.REDUCE_CANDIDATE : Action.AVOID)
                : (input.held() ? Action.EXIT_CANDIDATE : Action.AVOID);
        if (timing == TimingState.EXTREME_OVERSOLD) {
            if (longTermBroken(input)) {
                risks.add("雖已深度超跌，但年線已連續兩日跌破且價格位於 52 週區間最低段，"
                        + "長期結構視為破壞，不套用「超跌不出場」的保護。 ");
                return mapped;
            }
            reasons.add("已達極端超賣（KD 深度超賣且明顯低於季線），此位置不建議追殺出場。 ");
            risks.add("本訊號只描述當前位置，不預測反彈時點；長期結構若進一步轉壞仍應重新評估。 ");
            return input.held() ? Action.HOLD_CAUTION : Action.WAIT;
        }
        return mapped;
    }

    /**
     * 長期結構已完全破壞：年線兩日跌破<b>且</b>位於 52 週區間最低段。
     *
     * <p>這是「極端超賣不殺低」的必要安全閥——沒有它，持續崩壞的標的會因 KD 永遠釘在低檔而
     * 永遠拿不到出場訊號。判準與 {@code TRIAL_BUY} 的年線結構要求同源。</p>
     */
    private boolean longTermBroken(StockInput input) {
        return input.ma240Confirmation() == Confirmation.BELOW
                && input.week52Position() != null
                && input.week52Position().doubleValue() <= WEEK52_BROKEN;
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
