package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 今日交易雷達（Requirement 43）的純規則引擎。
 *
 * <p>不讀資料庫、不碰網路、不依賴時間；同一輸入永遠得到同一輸出，方便測試、回測與稽核。</p>
 */
@Component
public class TradingRadarRuleEngine {

    /**
     * V16：修正 J 值因子的<b>極性錯誤</b>——KD／J 因子（日線）與週線動能因子（週線）的 J 位置分量
     * 改由 {@link #standardJPosition(BigDecimal, BigDecimal)} 以標準 J（{@code 3K − 2D}）現算，
     * 不再直接餵入本專案為對齊券商畫面而採的顯示慣例 {@code j9 = 3D − 2K}
     * （Task 360／Requirement 96）。原式在股票上漲（{@code K > D}）時加分、下跌時扣分，
     * 與該分量自述的「低檔有利於承接、高檔不鼓勵追價」及 Requirement 43／59「不追高殺低」相反。
     *
     * <p><b>本版未調整任何權重、門檻或因子組成</b>，只改這一個分量的算式；
     * {@code j9}／{@code k3d2} 的計算與所有顯示／匯出欄位逐位不變。
     * 但個股 {@code score}／{@code action} 會實質變動（實測 35 檔全部受影響），
     * <b>V15 與 V16 的分數不可直接比較</b>；大盤 {@code score} 不受影響
     * （{@code evaluateMarket()} 全程不吃 {@code j9}）。</p>
     *
     * <p>V15（歷史）：持有期由兩軌拆為<b>三軌</b>——一周（{@link Horizon#SHORT}）、1周~1月
     * （{@link Horizon#SWING}）、1月~6月（{@link Horizon#MEDIUM}），並新增五個因子：
     * 日K 棒、週線趨勢、週線動能、週線乖離、週K 棒與量能（Task 356／Requirement 93）。
     * 因子數由 18 增為 23，<b>23 × 3 個權重全部重新給值</b>（含既有 18 個因子的新值），
     * 三軌各自合計 {@code 1.00}。</p>
     *
     * <p><b>這組權重全部是判斷性取值、無回測依據</b>：改動來源是「為 5 個新因子讓出
     * 0.16–0.19 的權重，並讓每一列跨軌單調」，不是任何量測結果。
     * <b>不得於任何 Javadoc、UI 或匯出文案宣稱這組權重能提高準確度或降低風險。</b>
     * V14 與 V15 的分數不可直接比較。</p>
     *
     * <p>V14（歷史）：延續 V12 的短期／中期雙軌權重架構與全部因子權重（該次未改動任何個股參數值），
     * 只改大盤 regime 的量價環境因子輸入面（Task 342／Requirement 82）——美股大盤自本版起
     * 把既有的 IXIC 完成日量能比與完成日漲跌幅真正接進 {@code MarketInput}，
     * 不再算了卻不用；同時 {@code MarketInput} 新增 {@code crossMarketApplicable}，
     * 讓「跨市場因子不適用（美股）」與「跨市場資料真的缺（台股）」不再共用同一則風險提醒。
     * 美股 regime 分數因此會與 V12 不同，兩版分數不可直接比較。
     *
     * <p>V13 保留給離線 calibration／candidate 參數集（{@link RuleParameters#V13_VERSION}），
     * production 版號故意跳過 13，不得竊用該標籤。</p>
     *
     * <p>V12（歷史）：延續 V11 的短期／中期雙軌權重架構，修正三個因子同源／抖動缺陷並擴大極端保護
     * 涵蓋範圍（Task 298）。W%R 併入 KD/J 因子計分，不再獨立佔權重（W%R9 ≡ 100−RSV9 與 K 同源於
     * 9 日高低帶）；BIAS 因子改為只平均 bias10／bias20 兩分量（b10b20 為代數相依值，不重複計分）；
     * MACD 因子改為 OSC 相對現價的幅度正規化，取代零軸附近逐日硬翻面造成的分數抖動。
     * 另外，極端時機（EXTREME_OVERBOUGHT／EXTREME_OVERSOLD）新增季線乖離自身分位替代路徑，
     * 使低波動標的的保護不再形同虛設（Task 299）。</p>
     */
    public static final String RULE_VERSION = "TW_RULES_V20";

    /**
     * 三軌持有期（Task 356.1a）。
     *
     * <p>{@code SHORT}＝一周（約 5 個交易日、DTO 前綴 {@code shortXxx}）、
     * {@code SWING}＝1周~1月（約 5–20 個交易日、DTO 前綴 {@code swingXxx}）、
     * {@code MEDIUM}＝1月~6月（約 20–120 個交易日、DTO 無前綴，唯一會寄信的一軌）。</p>
     *
     * <p>取代原本貫穿 {@code evaluateHorizon}／{@code applyCandidatePolicy} 的
     * {@code boolean shortTerm}。<b>刻意不保留 boolean overload 與 enum 版本並存</b>——
     * 兩條路徑會隨演進分歧。</p>
     */
    public enum Horizon { SHORT, SWING, MEDIUM }

    /** 現行 production 分層；candidate API 會改讀不可變 RuleParameters，既有入口仍固定這組。 */
    /*
     * 名稱刻意維持 V12_*：它指的是 V12 **參數集**，不是 {@link #RULE_VERSION}。
     * V13／V14／V15 三次升版都沒動過這四個數字（75／55／40／25），三軌共用同一組。
     * 改名為 V15_* 會讓「參數集版本」與「production 規則版號」共用同一組前綴卻指不同東西。
     */
    private static final RuleParameters.ActionThresholds V12_ACTION_THRESHOLDS =
            new RuleParameters.ActionThresholds(75, 55, 40, 25);

    // ─── V15 三軌 × 23 因子權重（Requirement 93，Task 356.7）────────────────────
    //
    // 命名：SW_*（一周）／SWG_*（1周~1月）／MW_*（1月~6月）。三軌各自合計 1.00
    // （由 SHORT_WEIGHT_SUM／SWING_WEIGHT_SUM／MEDIUM_WEIGHT_SUM 以編譯期加總表示並由測試釘住）。
    //
    // **跨軌單調**：每一列的 SW → SWG → MW 必須單調不增或單調不減，不得出現中間軌高於或低於
    // 兩端的鋸齒。理由：中間軌的存在理由就是「介於兩者之間」，非單調等於宣告它是第三套獨立直覺，
    // 那需要各自的回測依據，本任務沒有提供。此不變式由資料驅動測試逐列斷言。
    //
    // **全部 69 個數字都是判斷性取值、無回測依據**，包含既有 18 個因子的新值。
    // 不得於任何 Javadoc、UI 或匯出文案宣稱這組權重能提高準確度或降低風險。
    private static final double SW_MA5 = 0.05;
    private static final double SW_MA20 = 0.05;
    private static final double SW_MA60 = 0.03;
    private static final double SW_MA240 = 0.02;
    /** 含 W%R 分量（Task 298 併入；W%R9 與 K 同源於 9 日高低帶，不再獨立佔權重）。 */
    private static final double SW_KD_J = 0.12;
    private static final double SW_MACD = 0.07;
    private static final double SW_RSI = 0.05;
    private static final double SW_BIAS = 0.06;
    private static final double SW_VOLUME = 0.07;
    private static final double SW_MARKET = 0.08;
    private static final double SW_DAY_MOVE = 0.03;
    private static final double SW_FX = 0.03;
    private static final double SW_ETF_PREMIUM = 0.02;
    private static final double SW_EPS = 0.02;
    private static final double SW_ROE = 0.02;
    private static final double SW_REVENUE = 0.03;
    private static final double SW_PE = 0.02;
    private static final double SW_INDUSTRY = 0.07;
    /** 日K 棒（Task 356.5）：一周軌最重視當天這根 K 棒內部誰主導。 */
    private static final double SW_DAILY_CANDLE = 0.06;
    private static final double SW_WEEKLY_TREND = 0.03;
    private static final double SW_WEEKLY_MOMENTUM = 0.03;
    private static final double SW_WEEKLY_BIAS = 0.02;
    private static final double SW_WEEKLY_CANDLE_VOLUME = 0.02;

    private static final double SWG_MA5 = 0.04;
    private static final double SWG_MA20 = 0.05;
    private static final double SWG_MA60 = 0.05;
    private static final double SWG_MA240 = 0.03;
    /** 含 W%R 分量（理由同 {@link #SW_KD_J}）。 */
    private static final double SWG_KD_J = 0.08;
    private static final double SWG_MACD = 0.06;
    private static final double SWG_RSI = 0.04;
    private static final double SWG_BIAS = 0.06;
    private static final double SWG_VOLUME = 0.06;
    private static final double SWG_MARKET = 0.06;
    private static final double SWG_DAY_MOVE = 0.02;
    private static final double SWG_FX = 0.03;
    private static final double SWG_ETF_PREMIUM = 0.02;
    private static final double SWG_EPS = 0.04;
    private static final double SWG_ROE = 0.04;
    private static final double SWG_REVENUE = 0.03;
    private static final double SWG_PE = 0.03;
    private static final double SWG_INDUSTRY = 0.08;
    private static final double SWG_DAILY_CANDLE = 0.03;
    private static final double SWG_WEEKLY_TREND = 0.06;
    private static final double SWG_WEEKLY_MOMENTUM = 0.05;
    private static final double SWG_WEEKLY_BIAS = 0.02;
    private static final double SWG_WEEKLY_CANDLE_VOLUME = 0.02;

    private static final double MW_MA5 = 0.02;
    private static final double MW_MA20 = 0.04;
    private static final double MW_MA60 = 0.06;
    private static final double MW_MA240 = 0.06;
    /** 含 W%R 分量（Task 298 併入，理由同 {@link #SW_KD_J}）。 */
    private static final double MW_KD_J = 0.05;
    private static final double MW_MACD = 0.04;
    private static final double MW_RSI = 0.03;
    private static final double MW_BIAS = 0.06;
    private static final double MW_VOLUME = 0.04;
    private static final double MW_MARKET = 0.05;
    private static final double MW_DAY_MOVE = 0.01;
    private static final double MW_FX = 0.03;
    private static final double MW_ETF_PREMIUM = 0.02;
    private static final double MW_EPS = 0.06;
    private static final double MW_ROE = 0.06;
    private static final double MW_REVENUE = 0.04;
    private static final double MW_PE = 0.04;
    private static final double MW_INDUSTRY = 0.10;
    private static final double MW_DAILY_CANDLE = 0.01;
    private static final double MW_WEEKLY_TREND = 0.07;
    private static final double MW_WEEKLY_MOMENTUM = 0.05;
    private static final double MW_WEEKLY_BIAS = 0.03;
    private static final double MW_WEEKLY_CANDLE_VOLUME = 0.03;

    static final double SHORT_WEIGHT_SUM = SW_MA5 + SW_MA20 + SW_MA60 + SW_MA240 + SW_KD_J
            + SW_MACD + SW_RSI + SW_BIAS + SW_VOLUME + SW_MARKET + SW_DAY_MOVE + SW_FX
            + SW_ETF_PREMIUM + SW_EPS + SW_ROE + SW_REVENUE + SW_PE + SW_INDUSTRY
            + SW_DAILY_CANDLE + SW_WEEKLY_TREND + SW_WEEKLY_MOMENTUM + SW_WEEKLY_BIAS
            + SW_WEEKLY_CANDLE_VOLUME;
    static final double SWING_WEIGHT_SUM = SWG_MA5 + SWG_MA20 + SWG_MA60 + SWG_MA240 + SWG_KD_J
            + SWG_MACD + SWG_RSI + SWG_BIAS + SWG_VOLUME + SWG_MARKET + SWG_DAY_MOVE + SWG_FX
            + SWG_ETF_PREMIUM + SWG_EPS + SWG_ROE + SWG_REVENUE + SWG_PE + SWG_INDUSTRY
            + SWG_DAILY_CANDLE + SWG_WEEKLY_TREND + SWG_WEEKLY_MOMENTUM + SWG_WEEKLY_BIAS
            + SWG_WEEKLY_CANDLE_VOLUME;
    static final double MEDIUM_WEIGHT_SUM = MW_MA5 + MW_MA20 + MW_MA60 + MW_MA240 + MW_KD_J
            + MW_MACD + MW_RSI + MW_BIAS + MW_VOLUME + MW_MARKET + MW_DAY_MOVE + MW_FX
            + MW_ETF_PREMIUM + MW_EPS + MW_ROE + MW_REVENUE + MW_PE + MW_INDUSTRY
            + MW_DAILY_CANDLE + MW_WEEKLY_TREND + MW_WEEKLY_MOMENTUM + MW_WEEKLY_BIAS
            + MW_WEEKLY_CANDLE_VOLUME;
    /** 舊測試名稱相容；V11 起主欄位是中期分數。 */
    static final double WEIGHT_SUM = MEDIUM_WEIGHT_SUM;

    /**
     * 23 × 3 權重表的<b>唯一可列舉表示</b>，供跨軌單調性測試逐列斷言（Task 356.7c）。
     *
     * <p>每一列為 {@code {一周, 1周~1月, 1月~6月}}，順序與 {@link FactorContributions}
     * 的欄位順序、{@link #evaluateHorizon} 內 {@code acc.add} 的順序一致。</p>
     */
    static final double[][] WEIGHT_TABLE = {
            { SW_MA5, SWG_MA5, MW_MA5 },
            { SW_MA20, SWG_MA20, MW_MA20 },
            { SW_MA60, SWG_MA60, MW_MA60 },
            { SW_MA240, SWG_MA240, MW_MA240 },
            { SW_KD_J, SWG_KD_J, MW_KD_J },
            { SW_MACD, SWG_MACD, MW_MACD },
            { SW_RSI, SWG_RSI, MW_RSI },
            { SW_BIAS, SWG_BIAS, MW_BIAS },
            { SW_VOLUME, SWG_VOLUME, MW_VOLUME },
            { SW_MARKET, SWG_MARKET, MW_MARKET },
            { SW_DAY_MOVE, SWG_DAY_MOVE, MW_DAY_MOVE },
            { SW_FX, SWG_FX, MW_FX },
            { SW_ETF_PREMIUM, SWG_ETF_PREMIUM, MW_ETF_PREMIUM },
            { SW_EPS, SWG_EPS, MW_EPS },
            { SW_ROE, SWG_ROE, MW_ROE },
            { SW_REVENUE, SWG_REVENUE, MW_REVENUE },
            { SW_PE, SWG_PE, MW_PE },
            { SW_INDUSTRY, SWG_INDUSTRY, MW_INDUSTRY },
            { SW_DAILY_CANDLE, SWG_DAILY_CANDLE, MW_DAILY_CANDLE },
            { SW_WEEKLY_TREND, SWG_WEEKLY_TREND, MW_WEEKLY_TREND },
            { SW_WEEKLY_MOMENTUM, SWG_WEEKLY_MOMENTUM, MW_WEEKLY_MOMENTUM },
            { SW_WEEKLY_BIAS, SWG_WEEKLY_BIAS, MW_WEEKLY_BIAS },
            { SW_WEEKLY_CANDLE_VOLUME, SWG_WEEKLY_CANDLE_VOLUME, MW_WEEKLY_CANDLE_VOLUME },
    };

    /** 23 個因子的中文名，僅供測試失敗訊息指出是哪一列（順序同 {@link #WEIGHT_TABLE}）。 */
    static final String[] WEIGHT_TABLE_LABELS = {
            "週線 MA5", "月線 MA20", "季線 MA60", "年線 MA240", "KD／J", "MACD", "RSI",
            "乖離率 BIAS", "個股相對量", "市場環境", "完成日漲跌", "匯率", "ETF 折溢價",
            "EPS 年增", "近似 ROE", "近三月營收年增", "估值（PE／PB／殖利率）", "產業營收年增",
            "日K 棒", "週線趨勢", "週線動能", "週線乖離", "週K 棒與量能",
    };

    /** 極端超買／超賣的季線乖離門檻（%）。 */
    private static final double BIAS_EXTREME_HIGH = 20.0;
    private static final double BIAS_EXTREME_LOW = -20.0;
    /**
     * 極端超買／超賣的季線乖離自身分位門檻（0–100），與 {@link #BIAS_EXTREME_HIGH}／
     * {@link #BIAS_EXTREME_LOW} 二擇一（Task 299）。
     *
     * <p>存在的理由：低波動標的（債券 ETF、大盤型 ETF）的季線乖離終生難以觸及 ±20%，
     * 使極端超買／超賣兩項保護對它們形同不存在。中層 OVERBOUGHT／OVERSOLD（±12%）
     * 刻意不分位化——中層直接參與買進閘門否決，分位化會讓低波動標的每年固定約 10% 的日子
     * 被擋買，與「讓保護可達」的目的相反。</p>
     *
     * <p><b>98／2 與最少樣本 120（見 {@code RadarInputAssembler.BIAS_PCT_MIN_SAMPLES}）
     * 皆無回測量測依據，為判斷性取值</b>（240 樣本下 98 分位≈一年中最極端的前 5 個交易日）。
     * 不得於任何文案宣稱本門檻能降低風險。</p>
     */
    private static final double BIAS_EXTREME_PCT_HIGH = 98.0;
    private static final double BIAS_EXTREME_PCT_LOW = 2.0;
    /** 一般超買／超賣的季線乖離門檻（%）。 */
    private static final double BIAS_HIGH = 12.0;
    private static final double BIAS_LOW = -12.0;

    /**
     * 9 日高低帶寬度低於此比例（%）時，KD 視為在雜訊上飽和而失效（Task 264）。
     *
     * <p>實測 00719B（元大美債1-3）近 60 個交易日的 9 日高低帶平均寬度僅 {@code 1.011%}，
     * 其 {@code K=90.76} 實質只代表「比 9 日低點高 0.37 元」。V10 起把 KD／J／W%R 納入評分與時機判斷，
     * 不做此防護會讓窄幅債券 ETF 的飽和值誤導分數與動作。</p>
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
     * <p><b>V11 延續此對稱性</b>：買方既有「超買否決買進」，賣方就必須有「超賣否決賣出」，
     * 否則系統只在單一方向上保守，等於在低點建議殺低。</p>
     */
    private static final double KD_OVERSOLD_AVG = 20.0;
    private static final double KD_OVERSOLD_K = 15.0;

    /**
     * 52 週相對位置低於此值、且年線已兩日跌破時，標記長期結構偏弱。
     *
     * <p>V11 只把此狀態當作風險揭露與回測觀察欄位；依 t273 實證，它不再取消極端超賣保護，
     * 避免在低檔因長期趨勢弱而追殺。</p>
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

    /** Task 281 已落地的完整技術指標；Task 291 起納入評分，Task 356 起為三軌各自加權。 */
    public record ExtendedIndicators(
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv,
            BigDecimal ema12,
            BigDecimal ema26,
            BigDecimal dif,
            BigDecimal macd,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal b10b20,
            BigDecimal wr9
    ) {}

    /**
     * 一根 K 棒的還原 OHLC（Task 356.5a）。日K 用最新完成日、週K 用最新完成週。
     *
     * <p>本 record 只承載價格，不含日期：日K 的 as-of 日與 {@code RadarInputAssembler
     * .Assembled.volatility60().asOfDate()} 同源（皆為 {@code adjustedRows.get(firstCompleted)}
     * 的交易日），週K 的 as-of 日由 {@link WeeklyInput#weekEndDate()} 承載。</p>
     */
    public record CandleInput(
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {}

    /**
     * 週K 因子輸入（Task 356.6）：由 {@code RadarInputAssembler} 以還原後日K 聚合出的週K 序列
     * 求值，公式一律沿用 {@code TechnicalIndicatorService} 既有的日K 公式（換序列不換公式）。
     *
     * <p>{@code dif}／{@code macd} <b>刻意不在此</b>：進評分的只有 {@code osc}，那兩個是純揭露欄，
     * 由 {@code Assembled} 的週K {@code FullIndicators} 供給，兩者出自同一次
     * {@code computeFromSeries(週K 序列)} 呼叫。</p>
     *
     * @param completedWeeks 完成週根數（已排除進行中週，且已排除 {@code high}／{@code low} 缺值週）。
     *                       不足 60 根時全部指標欄為 {@code null}，四組週K 因子一律缺值。
     */
    public record WeeklyInput(
            CandleInput candle,
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal k,
            BigDecimal d,
            BigDecimal j9,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal volumeRatio,
            BigDecimal changePercent,
            LocalDate weekEndDate,
            int completedWeeks
    ) {}

    // ───────────────────────── K 棒三分量的純函數（Task 356.5c） ─────────────────────────
    //
    // 三支一律為 public static 無狀態純函數（{@code RadarInputAssembler.volumeRatio}／
    // {@code bandWidthPercent} 雖然也是為了測試而抽出，但它們是 @Component 上的 instance 方法，
    // 這裡刻意不照抄那個形狀）。引擎的因子計算與 DTO 的 DailyCandle／WeeklyIndicators 映射
    // 共用同一支，不得各寫一份：三支<b>回傳未經 clamp、未經線性轉換的原值</b>（DTO 要的），
    // 由呼叫端自行轉成 contribution（引擎要的）。
    //
    // 本組分量與 {@code completedChangePercent} <b>正交</b>：後者量「相對昨天走到哪」，
    // 本組量「當天這根 K 棒內部誰主導」。兩者不得合併計分，也不得因為「都跟當日漲跌有關」
    // 而互相取代。

    /** K 棒分量的除法精度；與 {@code RadarInputAssembler.week52Position} 等既有比例欄一致。 */
    private static final int CANDLE_SCALE = 8;

    /**
     * 三支 K 棒純函數<b>共用的全幅判準</b>（Task 356.5b-2）：{@code high − low}，
     * 缺值或<b>非正</b>時回 {@code null}。
     *
     * <p><b>必須是「非正」而不是「相等」</b>：{@code high < low} 的倒置髒列若只判相等，
     * {@code bodyDirection} 會回 {@code ±1}，讓一根確定無效的 K 棒拿到滿貢獻，且完全靜默。
     * 本專案<b>沒有</b>「{@code high >= max(open, close)}、{@code low <= min(open, close)}」
     * 的資料保證：{@code stock_price_history} 的 {@code open_price}／{@code high_price}／
     * {@code low_price} 三欄皆 nullable 且沒有任何 CHECK（唯一的 CHECK 是
     * {@code ck_sph_close_price_positive}），寫入端也只擋非正收盤。BFF 之所以有
     * {@code ChartSeriesAligner.validCandle}，正是因為髒列真的存在。</p>
     *
     * <p><b>一律用 {@code compareTo} 語意、不得用 {@code equals}</b>：還原後 BigDecimal 的
     * scale 會由 4 變 8，{@code equals} 會把數值相同的兩個值判成不相等。</p>
     */
    private static BigDecimal candleRange(BigDecimal high, BigDecimal low) {
        if (high == null || low == null) return null;
        BigDecimal range = high.subtract(low);
        return range.signum() <= 0 ? null : range;
    }

    /**
     * 收盤在當日高低區間的位置，{@code (close − low) / (high − low)}，值域 {@code [0,1]}。
     *
     * <p>收在上緣代表買方主導、下緣代表賣方主導。全幅非正（無波動、漲跌停鎖死或倒置髒列）時
     * 此分量<b>缺值</b>（回 {@code null}），不得以 0.5 冒充中性。</p>
     */
    public static BigDecimal closePosition(BigDecimal high, BigDecimal low, BigDecimal close) {
        BigDecimal range = candleRange(high, low);
        if (range == null || close == null) return null;
        return close.subtract(low).divide(range, CANDLE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 實體方向 {@code sign(close − open)}：{@code +1}／{@code −1}／{@code 0}（十字線）。
     *
     * <p>缺值條件有二：(a) 四個輸入任一缺值；(b) <b>全幅非正</b>（{@code high − low <= 0}，
     * 見 {@link #candleRange}）。</p>
     *
     * <p><b>(b) 是本任務唯一一處「缺值判準不只看該分量自己的輸入」，理由必須留在這裡</b>：
     * 正常來源資料下 {@code high >= max(open, close)}、{@code low <= min(open, close)}，
     * 全幅為零就代表這根 K 棒是一個<b>沒有價格區間的點</b>，不是十字線。若只判「缺 open」，
     * {@code sign(close − open)} 會回 {@code 0}，整個因子得到一個<b>看似有效的中性讀數</b>
     * （{@code 0} 會進 {@code Accumulator} 的 {@code sumW}），使用者與後續稽核都會讀成
     * 「十字線＝多空平衡」，那是完全不同的狀態。台股漲跌停鎖死、極低流動性標的整日只有一筆
     * 成交，都會走到這一條；{@code TechnicalIndicatorService} 的「{@code high}／{@code low}
     * 缺值時 fallback 用 {@code close}」既有慣例更會自己產生 {@code high == low} 的退化 K 棒。</p>
     *
     * <p>真正的十字線是 <b>{@code high > low} 且 {@code close == open}</b>，此時回 {@code 0}。</p>
     */
    public static BigDecimal bodyDirection(
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        if (open == null || close == null) return null;
        if (candleRange(high, low) == null) return null;
        return BigDecimal.valueOf(close.compareTo(open));
    }

    /**
     * 下影線占全幅的比例，{@code (min(open, close) − low) / (high − low)}，值域 {@code [0,1]}。
     *
     * <p>長下影線代表低檔有承接。缺 {@code open}／{@code high}／{@code low}／{@code close}
     * 或全幅非正時缺值。</p>
     *
     * <p><b>轉成貢獻時的中性點 0.25（四分之一全幅）留在呼叫端</b>，且該值為<b>判斷性取值、
     * 無回測依據</b>，不得於任何文案宣稱它能提高準確度。</p>
     */
    public static BigDecimal lowerShadowRatio(
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        BigDecimal range = candleRange(high, low);
        if (range == null || open == null || close == null) return null;
        return open.min(close).subtract(low).divide(range, CANDLE_SCALE, RoundingMode.HALF_UP);
    }

    public record MarketInput(
            BigDecimal price,
            BigDecimal changePercent,
            Indicators indicators,
            Confirmation ma60Confirmation,
            Confirmation ma240Confirmation,
            BigDecimal completedChangePercent,
            BigDecimal marketVolumeRatio,
            BigDecimal marketTurnoverRatio,
            BigDecimal nasdaqChangePercent,
            BigDecimal soxChangePercent,
            BigDecimal usTechCompositePercent,
            boolean usTechAvailable,
            boolean crossMarketApplicable,
            /** 最新完成日的大盤 K 棒（Task 356.5a／356.10a）；缺值時該項計 0 並揭露不採計。 */
            CandleInput dailyCandle,
            /** 大盤週K（Task 356.6／356.10a）；缺值時四項週線加減分一律不採計並揭露。 */
            WeeklyInput weekly
    ) {
        /**
         * V10 前的呼叫形狀；量能與美股資料缺值時不加減分。
         *
         * <p>{@code crossMarketApplicable} 一律填 {@code true}（Task 342）：讓「漏改呼叫端」的
         * 後果是多一則正當的「資料不足」提醒，而不是靜默吞掉一則真提醒。</p>
         */
        public MarketInput(BigDecimal price, BigDecimal changePercent, Indicators indicators,
                           Confirmation ma60Confirmation, Confirmation ma240Confirmation) {
            this(price, changePercent, indicators, ma60Confirmation, ma240Confirmation,
                    changePercent, null, null, null, null, null, false, true, null, null);
        }

        /**
         * Task 356 之前的完整形狀；週K 與日K 棒缺值時該五項一律不採計並於 risks 揭露。
         *
         * <p><b>⚠ 一律不得用於 production 或回測路徑</b>，理由與 {@link StockInput} 的同名警告
         * 相同：少傳引數會靜默把 {@code dailyCandle}／{@code weekly} 填成 {@code null}。
         * 兩條 production 大盤路徑（{@code buildMarket}／{@code buildUsMarket}）與
         * {@code BacktestService} 的兩處大盤 {@code MarketInput} 一律走 15 參數的正式建構式。</p>
         */
        public MarketInput(
                BigDecimal price,
                BigDecimal changePercent,
                Indicators indicators,
                Confirmation ma60Confirmation,
                Confirmation ma240Confirmation,
                BigDecimal completedChangePercent,
                BigDecimal marketVolumeRatio,
                BigDecimal marketTurnoverRatio,
                BigDecimal nasdaqChangePercent,
                BigDecimal soxChangePercent,
                BigDecimal usTechCompositePercent,
                boolean usTechAvailable,
                boolean crossMarketApplicable) {
            this(price, changePercent, indicators, ma60Confirmation, ma240Confirmation,
                    completedChangePercent, marketVolumeRatio, marketTurnoverRatio,
                    nasdaqChangePercent, soxChangePercent, usTechCompositePercent,
                    usTechAvailable, crossMarketApplicable, null, null);
        }
    }

    /**
     * Task 292 已在 as-of resolver 正規化為 [-1,+1] 的基本面／產業輸入。
     *
     * <p>{@code applicable=false} 代表 ETF，五項一律不適用；個股的某項 {@code null}
     * 代表當時資料尚未累積完成，由 Accumulator 重分配權重，不得填 0。</p>
     */
    public record FundamentalInput(
            boolean applicable,
            Double epsContribution,
            Double roeContribution,
            Double revenueContribution,
            Double peContribution,
            Double industryContribution,
            boolean peLoss,
            boolean roeApproximationFallback
    ) {
        public FundamentalInput(boolean applicable, Double epsContribution, Double roeContribution,
                                Double revenueContribution, Double peContribution,
                                Double industryContribution, boolean peLoss) {
            this(applicable, epsContribution, roeContribution, revenueContribution, peContribution,
                    industryContribution, peLoss, false);
        }

        public static final FundamentalInput NOT_APPLICABLE =
                new FundamentalInput(false, null, null, null, null, null, false);
    }

    /**
     * @param changePercent          盤中即時漲跌幅：只供既有相容欄位與顯示使用。
     * @param completedChangePercent 最近一根完成日 K 的漲跌幅：供逆勢「停止續跌」判定，
     *                               盤中為常數，避免零交叉造成逐 tick 翻轉（Task 217.3）。
     * @param marketStale            大盤資料非當前交易日：只收緊不放寬（Task 217.2）。
     * @param fxPercentile           底層資產幣別對台幣的五年期分位（0–100）；台幣資產為 null。
     *                               台幣計價但持有外幣資產的 ETF（如 00679B/00697B/00719B）其台幣報價
     *                               ≈ 底層外幣價 × 匯率——實測 00719B 與 USD/TWD 近一年相關係數 0.9737、
     *                               剝除匯率後底層僅動 2.05%（台幣價動 10.34%），故「站上均線」量到的
     *                               相當部分是匯率而非標的本身。此欄位讓換匯貴賤能獨立進入評分（Requirement 47）。
     * @param ma60BiasPercent        現價對季線的乖離率（%），供 V11 時機狀態與追高／低接閘門使用。
     * @param ma60BiasPercentile     {@code ma60BiasPercent} 在自身近一年分布的分位（值域 0–100）；
     *                               樣本不足 120 筆或 {@code ma60BiasPercent} 為 null 時為 null。
     *                               供極端超買／超賣門檻的分位路徑使用（Task 299），
     *                               使低波動標的（債券 ETF、大盤型 ETF）在絕對乖離終生不可達 ±20%
     *                               的情況下，極端保護仍能以「相對自己」的方式觸發。
     * @param ma240BiasPercent       現價對年線的乖離率（%）。供 TRIAL_BUY 的長線結構門檻與風險文案。
     *                               <b>單位為百分比</b>，故 {@link #TRIAL_BUY_MIN_ANNUAL_PREMIUM} 亦以百分比表示。
     * @param week52Position         52 週相對位置，值域 {@code [0,1]}（呼叫端須 clamp）。長期趨勢品質。
     * @param kdBandWidthPercent     9 日高低帶寬度（%）。低於門檻時 KD 全面失效，見 {@link #KD_BAND_MIN_PERCENT}。
     * @param etfPremiumPct          ETF 折溢價（%，{@code 1.2} = 溢價 1.2%）；非 ETF 為 null。
     * @param etfPremiumPercentile   該 ETF 自身歷史折溢價分位（0–100）；樣本不足或非 ETF 為 null。
     */
    /** Local completed, distribution-adjusted 20-session Bollinger observation; never a provider overlay. */
    public record BollingerInput(
            LocalDate asOfDate, int period, int standardDeviationMultiplier,
            BigDecimal middleBand, BigDecimal upperBand, BigDecimal lowerBand,
            BigDecimal percentB, BigDecimal bandWidthPercent) {}

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
            BigDecimal ma60BiasPercentile,
            BigDecimal ma240BiasPercent,
            BigDecimal week52Position,
            BigDecimal kdBandWidthPercent,
            BigDecimal etfPremiumPct,
            BigDecimal etfPremiumPercentile,
            BigDecimal weeklyMa,
            ExtendedIndicators extendedIndicators,
            BigDecimal volumeRatio,
            FundamentalInput fundamental,
            /**
             * 最新完成日的還原 OHLC（Task 356.5a）。缺值時 {@code DAILY_CANDLE} 因子為 null、
             * 權重重分配，並於 risks 揭露；<b>不得以 0 冒充</b>。
             */
            CandleInput dailyCandle,
            /**
             * 週K 因子輸入（Task 356.6）。{@code null} 或 {@code completedWeeks < 60} 時
             * 四組週K 因子<b>全部</b>缺值、權重重分配，並於 risks 揭露。
             */
            WeeklyInput weekly,
            BollingerInput bollinger
    ) {
        /** Pre-V19 full input: missing completed Bollinger data intentionally preserves original BIAS. */
        public StockInput(boolean held,
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
            BigDecimal ma60BiasPercentile,
            BigDecimal ma240BiasPercent,
            BigDecimal week52Position,
            BigDecimal kdBandWidthPercent,
            BigDecimal etfPremiumPct,
            BigDecimal etfPremiumPercentile,
            BigDecimal weeklyMa,
            ExtendedIndicators extendedIndicators,
            BigDecimal volumeRatio,
            FundamentalInput fundamental,

            CandleInput dailyCandle,

            WeeklyInput weekly) {
            this(held, price, changePercent, completedChangePercent, indicators, previousK, previousD, ma20Confirmation, ma60Confirmation, ma240Confirmation, instrumentType, marketRegime, marketStale, fxPercentile, ma60BiasPercent, ma60BiasPercentile, ma240BiasPercent, week52Position, kdBandWidthPercent, etfPremiumPct, etfPremiumPercentile, weeklyMa, extendedIndicators, volumeRatio, fundamental, dailyCandle, weekly, null);
        }

        /**
         * Task 356 之前的完整形狀；日K 棒與週K 缺值時五個新因子一律不採計。
         *
         * <p><b>⚠ 一律不得用於 production 或回測路徑。</b>本建構式只保留給歷史快照重算與
         * Task 356 之前就存在的測試。少傳兩個引數<b>不會編譯失敗、不會拋例外、不會有任何 log</b>，
         * 於是五個新因子（日K 棒、週線趨勢、週線動能、週線乖離、週K 棒與量能）恆為缺值、
         * 權重被重分配掉——這正是本任務實作期間真的發生過的缺陷（{@code TradingRadarService
         * .buildStock} 只傳 25 個引數，線上五因子全空、回測卻有值，兩邊靜默分岔）。
         * 凡是自己組 {@code Assembled} 的呼叫端，一律走 28 參數的正式建構式並傳入
         * {@code assembled.dailyCandle()}、{@code assembled.weekly()} 與 {@code assembled.bollinger()}；由
         * {@code TradingRadarStockWeeklyWiringTest} 釘住。</p>
         */
        public StockInput(
                boolean held, BigDecimal price, BigDecimal changePercent, BigDecimal completedChangePercent,
                Indicators indicators, BigDecimal previousK, BigDecimal previousD,
                Confirmation ma20Confirmation, Confirmation ma60Confirmation, Confirmation ma240Confirmation,
                InstrumentType instrumentType, MarketRegime marketRegime, boolean marketStale,
                BigDecimal fxPercentile, BigDecimal ma60BiasPercent, BigDecimal ma60BiasPercentile,
                BigDecimal ma240BiasPercent, BigDecimal week52Position, BigDecimal kdBandWidthPercent,
                BigDecimal etfPremiumPct, BigDecimal etfPremiumPercentile, BigDecimal weeklyMa,
                ExtendedIndicators extendedIndicators, BigDecimal volumeRatio,
                FundamentalInput fundamental) {
            this(held, price, changePercent, completedChangePercent, indicators, previousK, previousD,
                    ma20Confirmation, ma60Confirmation, ma240Confirmation, instrumentType, marketRegime,
                    marketStale, fxPercentile, ma60BiasPercent, ma60BiasPercentile, ma240BiasPercent,
                    week52Position, kdBandWidthPercent, etfPremiumPct, etfPremiumPercentile, weeklyMa,
                    extendedIndicators, volumeRatio, fundamental, null, null);
        }

        /** V10 的完整技術面建構式；舊回測／測試沒有基本面 observation。 */
        public StockInput(
                boolean held, BigDecimal price, BigDecimal changePercent, BigDecimal completedChangePercent,
                Indicators indicators, BigDecimal previousK, BigDecimal previousD,
                Confirmation ma20Confirmation, Confirmation ma60Confirmation, Confirmation ma240Confirmation,
                InstrumentType instrumentType, MarketRegime marketRegime, boolean marketStale,
                BigDecimal fxPercentile, BigDecimal ma60BiasPercent, BigDecimal ma240BiasPercent,
                BigDecimal week52Position, BigDecimal kdBandWidthPercent,
                BigDecimal etfPremiumPct, BigDecimal etfPremiumPercentile,
                BigDecimal weeklyMa, ExtendedIndicators extendedIndicators, BigDecimal volumeRatio) {
            this(held, price, changePercent, completedChangePercent, indicators, previousK, previousD,
                    ma20Confirmation, ma60Confirmation, ma240Confirmation, instrumentType, marketRegime,
                    marketStale, fxPercentile, ma60BiasPercent, null, ma240BiasPercent, week52Position,
                    kdBandWidthPercent, etfPremiumPct, etfPremiumPercentile, weeklyMa,
                    extendedIndicators, volumeRatio, FundamentalInput.NOT_APPLICABLE);
        }

        /** Task 291 前的建構形狀；新增 optional 因子缺值時由 Accumulator 重分配。 */
        public StockInput(
                boolean held, BigDecimal price, BigDecimal changePercent, BigDecimal completedChangePercent,
                Indicators indicators, BigDecimal previousK, BigDecimal previousD,
                Confirmation ma20Confirmation, Confirmation ma60Confirmation, Confirmation ma240Confirmation,
                InstrumentType instrumentType, MarketRegime marketRegime, boolean marketStale,
                BigDecimal fxPercentile, BigDecimal ma60BiasPercent, BigDecimal ma240BiasPercent,
                BigDecimal week52Position, BigDecimal kdBandWidthPercent,
                BigDecimal etfPremiumPct, BigDecimal etfPremiumPercentile) {
            this(held, price, changePercent, completedChangePercent, indicators, previousK, previousD,
                    ma20Confirmation, ma60Confirmation, ma240Confirmation, instrumentType, marketRegime,
                    marketStale, fxPercentile, ma60BiasPercent, null, ma240BiasPercent, week52Position,
                    kdBandWidthPercent, etfPremiumPct, etfPremiumPercentile, null, null, null,
                    FundamentalInput.NOT_APPLICABLE);
        }
    }

    /**
     * Immutable volatility-normalized BIAS observation shared by the candidate
     * score and candidate timing paths.  {@code rawBiasRatio} is the percentage
     * BIAS converted to ratio (10% =&gt; 0.10); {@code rawSigmaRatio} remains the
     * realized daily-return ratio.  A positive but tiny sigma may use the
     * calibrated floor; a missing/non-positive sigma is never fabricated by the
     * floor and instead marks the observation as a fixed-threshold fallback.
     */
    public record NormalizedBiasObservation(
            BigDecimal rawBiasRatio,
            BigDecimal rawSigmaRatio,
            BigDecimal sigmaFloorRatio,
            BigDecimal effectiveSigmaRatio,
            BigDecimal normalizedBias,
            LocalDate asOfDate,
            boolean volatilityFallback,
            String reason) {
        public NormalizedBiasObservation {
            sigmaFloorRatio = sigmaFloorRatio == null ? BigDecimal.ZERO : sigmaFloorRatio;
            if (sigmaFloorRatio.signum() < 0) {
                throw new IllegalArgumentException("sigmaFloorRatio 不可為負");
            }
        }

        public boolean available() {
            return normalizedBias != null && effectiveSigmaRatio != null
                    && effectiveSigmaRatio.signum() > 0 && !volatilityFallback;
        }

        public boolean floorApplied() {
            return rawSigmaRatio != null && rawSigmaRatio.signum() > 0
                    && sigmaFloorRatio.signum() > 0
                    && rawSigmaRatio.compareTo(sigmaFloorRatio) < 0;
        }

        /** Build from the user-facing percentage BIAS and ratio sigma. */
        public static NormalizedBiasObservation from(
                BigDecimal biasPercent,
                BigDecimal rawSigmaRatio,
                BigDecimal sigmaFloorRatio,
                LocalDate asOfDate) {
            BigDecimal floor = sigmaFloorRatio == null ? BigDecimal.ZERO : sigmaFloorRatio;
            BigDecimal rawBiasRatio = biasPercent == null
                    ? null : biasPercent.movePointLeft(2);
            if (rawBiasRatio == null) {
                return new NormalizedBiasObservation(rawBiasRatio, rawSigmaRatio, floor, null,
                        null, asOfDate, true, "bias_missing");
            }
            if (rawSigmaRatio == null || rawSigmaRatio.signum() <= 0
                    || !finite(rawSigmaRatio)) {
                return new NormalizedBiasObservation(rawBiasRatio, rawSigmaRatio, floor, null,
                        null, asOfDate, true, "sigma_missing_or_non_positive");
            }
            BigDecimal effective = rawSigmaRatio.max(floor);
            if (effective.signum() <= 0 || !finite(effective)) {
                return new NormalizedBiasObservation(rawBiasRatio, rawSigmaRatio, floor, null,
                        null, asOfDate, true, "sigma_effective_non_positive");
            }
            BigDecimal normalized = rawBiasRatio.divide(effective, 12, RoundingMode.HALF_UP);
            String reason = rawSigmaRatio.compareTo(floor) < 0
                    ? "sigma_floor_applied" : null;
            return new NormalizedBiasObservation(rawBiasRatio, rawSigmaRatio, floor, effective,
                    normalized, asOfDate, false, reason);
        }

        private static boolean finite(BigDecimal value) {
            if (value == null) return false;
            double d = value.doubleValue();
            return !Double.isNaN(d) && !Double.isInfinite(d);
        }
    }

    /**
     * Immutable per-decision provenance for the volatility-normalized BIAS path.  The
     * observation is deliberately echoed even when unavailable: consumers can distinguish a
     * missing sigma from an explicitly disabled candidate instead of inferring either state from
     * a null score or from aggregate calibration metadata.
     */
    public record NormalizedBiasProvenance(
            boolean enabled,
            BigDecimal rawBiasRatio,
            BigDecimal rawSigmaRatio,
            BigDecimal sigmaFloorRatio,
            BigDecimal effectiveSigmaRatio,
            BigDecimal normalizedBias,
            LocalDate asOfDate,
            boolean volatilityFallback,
            boolean floorApplied,
            String reason
    ) {
        public static NormalizedBiasProvenance disabled() {
            return new NormalizedBiasProvenance(false, null, null, null, null, null,
                    null, false, false, "NORMALIZED_PATH_DISABLED");
        }

        public static NormalizedBiasProvenance from(
                boolean enabled, NormalizedBiasObservation observation) {
            if (!enabled) return disabled();
            if (observation == null) {
                return new NormalizedBiasProvenance(true, null, null, null, null, null,
                        null, true, false, "observation_missing");
            }
            return new NormalizedBiasProvenance(true, observation.rawBiasRatio(),
                    observation.rawSigmaRatio(), observation.sigmaFloorRatio(),
                    observation.effectiveSigmaRatio(), observation.normalizedBias(),
                    observation.asOfDate(), observation.volatilityFallback(),
                    observation.floorApplied(), observation.reason());
        }
    }

    /**
     * Task 308 candidate 的樣本日 context。所有欄位都必須來自 signal instant 前可得資料；
     * downside 僅能來自 next-open primary calibration，close sensitivity 不得傳入本欄位。
     * 此型別不被現行 production 入口使用，因此建立 candidate 不會暗中升版。
     */
    public record CandidateContext(
            boolean priceFresh,
            boolean marketFresh,
            BigDecimal confidence,
            BigDecimal shortDownsideRiskPct,
            BigDecimal mediumDownsideRiskPct,
            Double shortTreasuryContribution,
            Double mediumTreasuryContribution,
            /** signal instant 前 60 日報酬 σ ratio（0.02 代表 2%）；缺值時 normalized bias 不採計。 */
            BigDecimal normalizedBiasSigmaRatio,
            /** EvidenceConfidenceResolver 分別計算的短／中期 confidence（0..1）。 */
            BigDecimal shortConfidence,
            BigDecimal mediumConfidence,
            /** full-engine candidate 使用的完整 evidence gate 輸入。 */
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            /** strict asset profile；用於個股基本面／適用資產 gate。 */
            TradingRadarAssetProfileResolver.AssetProfile profile,
            /** typed market candidates；V13 candidate 可依校準權重計分，V12 不受影響。 */
            TradingRadarMarketFeatureResolver.Evidence marketFeatures,
            /** per-instrument Treasury beta evidence；MISSING/非穩定時不得轉成分數。 */
            BondYieldBetaResolver.Result bondYieldBeta,
            /** candidate-only aggregate of non-duplicate AVAILABLE market features. */
            Double shortMarketFeatureContribution,
            /** candidate-only aggregate of non-duplicate AVAILABLE market features. */
            Double mediumMarketFeatureContribution,
            /** volatility observation as-of date; kept separate from accepted-price provenance. */
            LocalDate normalizedBiasAsOfDate,
            /** train/request sigma profile availability; positive row sigma alone is insufficient. */
            boolean sigmaProfileAvailable
    ) {
        /** Compatibility shape before train-fold sigma-profile provenance was explicit. */
        public CandidateContext(
                boolean priceFresh,
                boolean marketFresh,
                BigDecimal confidence,
                BigDecimal shortDownsideRiskPct,
                BigDecimal mediumDownsideRiskPct,
                Double shortTreasuryContribution,
                Double mediumTreasuryContribution,
                BigDecimal normalizedBiasSigmaRatio,
                BigDecimal shortConfidence,
                BigDecimal mediumConfidence,
                TradingRadarEvidenceConfidenceResolver.Evidence evidence,
                TradingRadarAssetProfileResolver.AssetProfile profile,
                TradingRadarMarketFeatureResolver.Evidence marketFeatures,
                BondYieldBetaResolver.Result bondYieldBeta,
                Double shortMarketFeatureContribution,
                Double mediumMarketFeatureContribution,
                LocalDate normalizedBiasAsOfDate) {
            this(priceFresh, marketFresh, confidence, shortDownsideRiskPct, mediumDownsideRiskPct,
                    shortTreasuryContribution, mediumTreasuryContribution, normalizedBiasSigmaRatio,
                    shortConfidence, mediumConfidence, evidence, profile, marketFeatures,
                    bondYieldBeta, shortMarketFeatureContribution, mediumMarketFeatureContribution,
                    normalizedBiasAsOfDate, true);
        }

        /** Immutable copy with fold-local calibration profile availability. */
        public CandidateContext withSigmaProfileAvailable(boolean available) {
            return new CandidateContext(priceFresh, marketFresh, confidence,
                    shortDownsideRiskPct, mediumDownsideRiskPct, shortTreasuryContribution,
                    mediumTreasuryContribution, normalizedBiasSigmaRatio, shortConfidence,
                    mediumConfidence, evidence, profile, marketFeatures, bondYieldBeta,
                    shortMarketFeatureContribution, mediumMarketFeatureContribution,
                    normalizedBiasAsOfDate, available);
        }

        /** Compatibility shape before normalized-bias provenance date was explicit. */
        public CandidateContext(
                boolean priceFresh,
                boolean marketFresh,
                BigDecimal confidence,
                BigDecimal shortDownsideRiskPct,
                BigDecimal mediumDownsideRiskPct,
                Double shortTreasuryContribution,
                Double mediumTreasuryContribution,
                BigDecimal normalizedBiasSigmaRatio,
                BigDecimal shortConfidence,
                BigDecimal mediumConfidence,
                TradingRadarEvidenceConfidenceResolver.Evidence evidence,
                TradingRadarAssetProfileResolver.AssetProfile profile,
                TradingRadarMarketFeatureResolver.Evidence marketFeatures,
                BondYieldBetaResolver.Result bondYieldBeta,
                Double shortMarketFeatureContribution,
                Double mediumMarketFeatureContribution) {
            this(priceFresh, marketFresh, confidence, shortDownsideRiskPct, mediumDownsideRiskPct,
                    shortTreasuryContribution, mediumTreasuryContribution, normalizedBiasSigmaRatio,
                    shortConfidence, mediumConfidence, evidence, profile, marketFeatures,
                    bondYieldBeta, shortMarketFeatureContribution, mediumMarketFeatureContribution, null, true);
        }

        public CandidateContext(
                boolean priceFresh,
                boolean marketFresh,
                BigDecimal confidence,
                BigDecimal shortDownsideRiskPct,
                BigDecimal mediumDownsideRiskPct,
                Double shortTreasuryContribution,
                Double mediumTreasuryContribution) {
            this(priceFresh, marketFresh, confidence, shortDownsideRiskPct, mediumDownsideRiskPct,
                    shortTreasuryContribution, mediumTreasuryContribution, null,
                    confidence, confidence, null, null, null, null, null, null, null, true);
        }

        /** normalized-bias 版本的相容建構式；evidence/profile 未提供時不啟用 evidence gate。 */
        public CandidateContext(
                boolean priceFresh,
                boolean marketFresh,
                BigDecimal confidence,
                BigDecimal shortDownsideRiskPct,
                BigDecimal mediumDownsideRiskPct,
                Double shortTreasuryContribution,
                Double mediumTreasuryContribution,
                BigDecimal normalizedBiasSigmaRatio) {
            this(priceFresh, marketFresh, confidence, shortDownsideRiskPct, mediumDownsideRiskPct,
                    shortTreasuryContribution, mediumTreasuryContribution, normalizedBiasSigmaRatio,
                    confidence, confidence, null, null, null, null, null, null, null, true);
        }

        /** Compatibility shape before candidate market feature contributions were explicit. */
        public CandidateContext(
                boolean priceFresh,
                boolean marketFresh,
                BigDecimal confidence,
                BigDecimal shortDownsideRiskPct,
                BigDecimal mediumDownsideRiskPct,
                Double shortTreasuryContribution,
                Double mediumTreasuryContribution,
                BigDecimal normalizedBiasSigmaRatio,
                BigDecimal shortConfidence,
                BigDecimal mediumConfidence,
                TradingRadarEvidenceConfidenceResolver.Evidence evidence,
                TradingRadarAssetProfileResolver.AssetProfile profile,
                TradingRadarMarketFeatureResolver.Evidence marketFeatures,
                BondYieldBetaResolver.Result bondYieldBeta) {
            this(priceFresh, marketFresh, confidence, shortDownsideRiskPct, mediumDownsideRiskPct,
                    shortTreasuryContribution, mediumTreasuryContribution, normalizedBiasSigmaRatio,
                    shortConfidence, mediumConfidence, evidence, profile, marketFeatures,
                    bondYieldBeta, null, null, null, true);
        }

        public CandidateContext {
            validateCandidateRange(confidence, BigDecimal.ZERO, BigDecimal.ONE, "confidence");
            validateCandidateRange(shortConfidence, BigDecimal.ZERO, BigDecimal.ONE, "shortConfidence");
            validateCandidateRange(mediumConfidence, BigDecimal.ZERO, BigDecimal.ONE, "mediumConfidence");
            validateCandidateRange(shortDownsideRiskPct, BigDecimal.ZERO, BigDecimal.valueOf(100),
                    "shortDownsideRiskPct");
            validateCandidateRange(mediumDownsideRiskPct, BigDecimal.ZERO, BigDecimal.valueOf(100),
                    "mediumDownsideRiskPct");
            validateCandidateRange(normalizedBiasSigmaRatio, BigDecimal.ZERO, BigDecimal.TEN,
                    "normalizedBiasSigmaRatio");
            validateContribution(shortTreasuryContribution, "shortTreasuryContribution");
            validateContribution(mediumTreasuryContribution, "mediumTreasuryContribution");
            marketFeatures = marketFeatures == null
                    ? TradingRadarMarketFeatureResolver.Evidence.empty(null, null,
                    "market feature resolver 未建立") : marketFeatures;
            bondYieldBeta = bondYieldBeta == null
                    ? BondYieldBetaResolver.Result.missing(null, "bond beta evidence 未建立") : bondYieldBeta;
            TradingRadarMarketFeatureResolver.AggregatedContribution aggregate =
                    marketFeatures.aggregateContribution(profile);
            if (shortMarketFeatureContribution == null) {
                shortMarketFeatureContribution = aggregate.shortTerm();
            }
            if (mediumMarketFeatureContribution == null) {
                mediumMarketFeatureContribution = aggregate.mediumTerm();
            }
            validateContribution(shortMarketFeatureContribution, "shortMarketFeatureContribution");
            validateContribution(mediumMarketFeatureContribution, "mediumMarketFeatureContribution");
        }

        private static void validateContribution(Double value, String field) {
            if (value != null && (!Double.isFinite(value) || value < -1.0 || value > 1.0)) {
                throw new IllegalArgumentException(field + " 必須介於 -1..1");
            }
        }

        private static void validateCandidateRange(
                BigDecimal value, BigDecimal min, BigDecimal max, String field) {
            if (value != null && (value.compareTo(min) < 0 || value.compareTo(max) > 0)) {
                throw new IllegalArgumentException(field + " 必須介於 " + min + ".." + max);
            }
        }
    }

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
            /** KD 短線熱度；V11 同時影響 KD/J 因子、買進閘門與畫面揭露。 */
            KdHeat kdHeat,
            /** 進場時機（Task 264）；供畫面在收合列即可辨識，並對動作做雙向覆寫。 */
            TimingState timingState,
            /**
             * KD 高檔死亡交叉（Task 273 新增輸出，Task 291 起為多證據獲利了結條件之一）。
             *
             * <p>不得單獨觸發賣出；必須與 MACD 或下跌爆量至少一項共同成立，
             * 並且已位於極端高檔，才構成 {@code profitTakingConfirmed}。</p>
             *
             * <p>資料不完整而走 {@code NO_TRADE} 早退分支時一律為 {@code false}：那代表
             * <b>無法判定</b>，不得因為「呼叫 kdDeadCross 剛好也回 false」就視為等價——
             * 兩者表面相同但語意不同。</p>
             */
            boolean kdDeadCross,
            /**
             * 長期結構已破壞（年線兩日跌破且 52 週位置 ≤ {@link #WEEK52_BROKEN}；Task 273 新增輸出）。
             *
             * <p>供回測與風險揭露；V11 不再讓此狀態取消極端超賣保護。早退分支一律為 false。</p>
             */
            boolean longTermBroken,
            Integer shortScore,
            Action shortAction,
            List<String> shortReasons,
            List<String> shortRisks,
            /**
             * 三軌的動作分組<b>不全相同</b>（Task 356.1c）。
             *
             * <p>V15 起由「短 vs 中兩軌分組不同」改為三軌比較；分組沿用既有定義
             * （買進／中性／賣出／無法判定）。</p>
             */
            boolean horizonConflict,
            boolean profitTakingConfirmed,
            /** Per-row medium-horizon normalized-BIAS provenance; null only for legacy callers. */
            NormalizedBiasProvenance normalizedBias,
            /** Per-row short-horizon normalized-BIAS provenance; null only for legacy callers. */
            NormalizedBiasProvenance shortNormalizedBias,
            /** 1周~1月 軌（Task 356.1b）；既有 component 的順序與名稱一律不動，新欄位一律追加在後。 */
            Integer swingScore,
            Action swingAction,
            List<String> swingReasons,
            List<String> swingRisks
    ) {
        /** Compatibility constructor for pre-provenance engine/test callers. */
        public StockResult(
                Integer score,
                Action action,
                CounterTrendResult counterTrend,
                List<String> reasons,
                List<String> risks,
                KdHeat kdHeat,
                TimingState timingState,
                boolean kdDeadCross,
                boolean longTermBroken,
                Integer shortScore,
                Action shortAction,
                List<String> shortReasons,
                List<String> shortRisks,
                boolean horizonConflict,
                boolean profitTakingConfirmed) {
            this(score, action, counterTrend, reasons, risks, kdHeat, timingState,
                    kdDeadCross, longTermBroken, shortScore, shortAction, shortReasons,
                    shortRisks, horizonConflict, profitTakingConfirmed, null, null);
        }

        /** Task 356 之前的完整形狀；1周~1月 軌未供給時為 {@code NO_TRADE}／{@code null} 分數。 */
        public StockResult(
                Integer score,
                Action action,
                CounterTrendResult counterTrend,
                List<String> reasons,
                List<String> risks,
                KdHeat kdHeat,
                TimingState timingState,
                boolean kdDeadCross,
                boolean longTermBroken,
                Integer shortScore,
                Action shortAction,
                List<String> shortReasons,
                List<String> shortRisks,
                boolean horizonConflict,
                boolean profitTakingConfirmed,
                NormalizedBiasProvenance normalizedBias,
                NormalizedBiasProvenance shortNormalizedBias) {
            this(score, action, counterTrend, reasons, risks, kdHeat, timingState,
                    kdDeadCross, longTermBroken, shortScore, shortAction, shortReasons,
                    shortRisks, horizonConflict, profitTakingConfirmed,
                    normalizedBias, shortNormalizedBias,
                    null, Action.NO_TRADE, List.of(),
                    List.of("1周~1月 軌未由本呼叫端供給，今日不交易。"));
        }
    }

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

        Double marketActivity = averageAvailable(
                decimal(input.marketVolumeRatio()), decimal(input.marketTurnoverRatio()));
        // Task 342：文案只能列舉本次 marketActivity 實際採用的分量。美股結構性沒有成交金額
        // （us_index_daily_history 無成交值欄），台股也可能因樣本不足而 turnover 為 null；
        // 寫死「量能／成交金額」等於在講一個不存在的值。
        String activityLabel = activityLabel(input.marketVolumeRatio(), input.marketTurnoverRatio());
        if (marketActivity == null || input.completedChangePercent() == null) {
            risks.add("大盤完成日量價資料不足，本日不採計量價環境分數。 ");
        } else if (input.completedChangePercent().signum() > 0 && marketActivity >= 1.1) {
            score += 8;
            reasons.add("大盤完成日上漲且" + activityLabel + "同步放大，需求獲得確認。 ");
        } else if (input.completedChangePercent().signum() < 0 && marketActivity >= 1.1) {
            score -= 10;
            risks.add("大盤完成日下跌且" + activityLabel + "放大，市場賣壓升高。 ");
        } else if (input.completedChangePercent().signum() > 0 && marketActivity <= 0.8) {
            score -= 3;
            risks.add("大盤完成日上漲但量能不足，漲勢確認偏弱。 ");
        } else if (input.completedChangePercent().signum() < 0 && marketActivity <= 0.8) {
            score += 3;
            reasons.add("大盤完成日下跌但量能收斂，賣壓未擴大。 ");
        }

        // Task 342：三段而非兩段。「不適用」（美股大盤即 IXIC，跨市場因子會重複計分，
        // Requirement 64 的排除維持有效）必須沉默，不得謊報成「資料不足」；中間那段是台股的
        // 正當提醒（美股科技共同完成日有 5 個日曆日上限，超過即整組 unavailable），不得一起關掉。
        if (!input.crossMarketApplicable()) {
            // 沉默：不加任何 reasons／risks。
        } else if (!input.usTechAvailable() || input.usTechCompositePercent() == null) {
            risks.add("前一個已完成的美股科技共同交易日資料不足，本日不採計跨市場分數。 ");
        } else {
            double us = input.usTechCompositePercent().doubleValue();
            if (us >= 1.0) {
                score += 6;
                reasons.add("前一美股科技交易日明顯上漲，跨市場風險偏好正向。 ");
            } else if (us > 0) {
                score += 3;
                reasons.add("前一美股科技交易日上漲，跨市場情緒略偏正向。 ");
            } else if (us <= -1.5) {
                score -= 6;
                risks.add("前一美股科技交易日明顯下跌，科技風險偏好轉弱。 ");
            } else if (us < 0) {
                score -= 3;
                risks.add("前一美股科技交易日下跌，跨市場情緒略偏保守。 ");
            }
        }

        score += weeklyMarketScore(input, reasons, risks);
        score += dailyCandleMarketScore(input, reasons, risks);

        score = clamp(score);
        MarketRegime regime = score >= 65
                ? MarketRegime.RISK_ON
                : score >= 40 ? MarketRegime.NEUTRAL : MarketRegime.RISK_OFF;
        return new MarketResult(score, regime, List.copyOf(reasons), List.copyOf(risks));
    }

    // ─── 大盤週K 與日K 棒的加減分（Task 356.10a）─────────────────────────────────
    //
    // 下列全部幅度與門檻**一律為判斷性取值、無回測依據**（比照 OSC_FULL_SCALE_PCT 的既有揭露
    // 慣例）。不得於任何文案宣稱它們能提高準確度或降低風險。
    /** 現價相對週MA10 的加減分。 */
    private static final int MARKET_WEEKLY_MA10_POINTS = 6;
    /** 現價相對週MA20 的加減分。 */
    private static final int MARKET_WEEKLY_MA20_POINTS = 5;
    /** 週KD 方向的加減分。 */
    private static final int MARKET_WEEKLY_KD_POINTS = 4;
    /** 週 OSC 正負的加減分。 */
    private static final int MARKET_WEEKLY_OSC_POINTS = 4;
    /** 最新完成週上漲且放量的加分。 */
    private static final int MARKET_WEEKLY_VOLUME_UP_POINTS = 4;
    /** 最新完成週下跌且放量的扣分（不對稱是刻意的：只收緊不放寬）。 */
    private static final int MARKET_WEEKLY_VOLUME_DOWN_POINTS = 5;
    /** 上漲但量能收斂的扣分。 */
    private static final int MARKET_WEEKLY_THIN_UP_PENALTY = 2;
    /** 下跌但量能收斂的加分。 */
    private static final int MARKET_WEEKLY_THIN_DOWN_BONUS = 2;
    private static final double MARKET_WEEKLY_VOLUME_HIGH = 1.1;
    private static final double MARKET_WEEKLY_VOLUME_LOW = 0.8;
    /** 日K 棒收盤區間位置的加減分。 */
    private static final int MARKET_DAILY_CANDLE_POINTS = 3;
    private static final double MARKET_CANDLE_STRONG_CLOSE = 0.7;
    private static final double MARKET_CANDLE_WEAK_CLOSE = 0.3;

    /**
     * 大盤週線加減分（Task 356.10a）。任一資料缺值即<b>該項計 0 並於 risks 揭露「不採計」</b>，
     * 不得寫成中性結論。
     *
     * <p>週K 缺值<b>不影響</b> {@code complete(MarketInput)}／{@code dataComplete}
     * （Task 356.10a-2）——若把 weekly 併進完整性判定，完成週不足 60 的大盤會整組變
     * {@code DATA_INCOMPLETE}，連帶關掉<b>全部個股</b>的買進閘門。</p>
     */
    private int weeklyMarketScore(MarketInput input, List<String> reasons, List<String> risks) {
        WeeklyInput weekly = input.weekly();
        if (weekly == null) {
            risks.add("大盤週K 尚未建立（完成週不足或序列不可得），本日不採計週線方向與週量價。 ");
            return 0;
        }
        int score = 0;
        score += marketPriceVsWeeklyMa(input.price(), weekly.ma10(), MARKET_WEEKLY_MA10_POINTS,
                "週MA10", reasons, risks);
        score += marketPriceVsWeeklyMa(input.price(), weekly.ma20(), MARKET_WEEKLY_MA20_POINTS,
                "週MA20", reasons, risks);

        if (weekly.k() == null || weekly.d() == null) {
            risks.add("大盤週KD 資料不足，本日不採計週線動能方向。 ");
        } else if (weekly.k().compareTo(weekly.d()) > 0) {
            score += MARKET_WEEKLY_KD_POINTS;
            reasons.add("大盤週KD 為 K>D，週線動能偏正向。 ");
        } else if (weekly.k().compareTo(weekly.d()) < 0) {
            score -= MARKET_WEEKLY_KD_POINTS;
            risks.add("大盤週KD 未呈 K>D，週線動能偏弱。 ");
        }

        if (weekly.osc() == null) {
            risks.add("大盤週 MACD 柱狀體資料不足，本日不採計。 ");
        } else if (weekly.osc().signum() > 0) {
            score += MARKET_WEEKLY_OSC_POINTS;
            reasons.add("大盤週 OSC 為正，週線動能仍在多方。 ");
        } else if (weekly.osc().signum() < 0) {
            score -= MARKET_WEEKLY_OSC_POINTS;
            risks.add("大盤週 OSC 為負，週線動能偏空。 ");
        }

        BigDecimal weekChange = weekly.changePercent();
        BigDecimal weekVolumeRatio = weekly.volumeRatio();
        if (weekChange == null || weekVolumeRatio == null) {
            risks.add("大盤最新完成週的漲跌或量比資料不足，本日不採計週量價確認。 ");
        } else {
            double ratio = weekVolumeRatio.doubleValue();
            if (weekChange.signum() > 0 && ratio >= MARKET_WEEKLY_VOLUME_HIGH) {
                score += MARKET_WEEKLY_VOLUME_UP_POINTS;
                reasons.add("大盤最新完成週上漲且週量能放大，週線需求獲得確認。 ");
            } else if (weekChange.signum() < 0 && ratio >= MARKET_WEEKLY_VOLUME_HIGH) {
                score -= MARKET_WEEKLY_VOLUME_DOWN_POINTS;
                risks.add("大盤最新完成週下跌且週量能放大，週線賣壓升高。 ");
            } else if (weekChange.signum() > 0 && ratio <= MARKET_WEEKLY_VOLUME_LOW) {
                score -= MARKET_WEEKLY_THIN_UP_PENALTY;
                risks.add("大盤最新完成週上漲但週量能不足，漲勢確認偏弱。 ");
            } else if (weekChange.signum() < 0 && ratio <= MARKET_WEEKLY_VOLUME_LOW) {
                score += MARKET_WEEKLY_THIN_DOWN_BONUS;
                reasons.add("大盤最新完成週下跌但週量能收斂，賣壓未擴大。 ");
            }
        }
        return score;
    }

    /** 大盤日K 棒的收盤區間位置加減分（Task 356.10a）；全幅非正或 OHLC 缺漏時不採計。 */
    private int dailyCandleMarketScore(MarketInput input, List<String> reasons, List<String> risks) {
        CandleInput candle = input.dailyCandle();
        BigDecimal position = candle == null ? null
                : closePosition(candle.high(), candle.low(), candle.close());
        if (position == null) {
            risks.add("大盤最新完成日 K 棒沒有價格區間或 OHLC 缺漏，本日不採計日K 棒收盤位置。 ");
            return 0;
        }
        double value = position.doubleValue();
        if (value >= MARKET_CANDLE_STRONG_CLOSE) {
            reasons.add("大盤最新完成日收在當日區間上緣，買方主導。 ");
            return MARKET_DAILY_CANDLE_POINTS;
        }
        if (value <= MARKET_CANDLE_WEAK_CLOSE) {
            risks.add("大盤最新完成日收在當日區間下緣，賣方主導。 ");
            return -MARKET_DAILY_CANDLE_POINTS;
        }
        return 0;
    }

    /** 大盤現價相對某條週均線的加減分；均線缺值時揭露不採計（不得寫成中性）。 */
    private int marketPriceVsWeeklyMa(BigDecimal price, BigDecimal ma, int weight, String label,
                                      List<String> reasons, List<String> risks) {
        if (price == null || ma == null) {
            risks.add("大盤" + label + "資料不足，本日不採計該項週線位置。 ");
            return 0;
        }
        int cmp = price.compareTo(ma);
        if (cmp > 0) {
            reasons.add("大盤最新價位於" + label + "之上。 ");
            return weight;
        }
        if (cmp < 0) {
            risks.add("大盤最新價位於" + label + "之下。 ");
            return -weight;
        }
        risks.add("大盤最新價貼近" + label + "，方向尚未拉開。 ");
        return 0;
    }

    public StockResult evaluateStock(StockInput input) {
        return evaluateStockInternal(input, null, null);
    }

    /**
     * Offline V13 comparison baseline: preserve the exact V12 factor/threshold
     * output, then apply the same evidence gate used by candidate actions.  This
     * is deliberately not the production {@link #evaluateStock} path, so missing
     * evidence cannot alter today's V12 runtime semantics.
     */
    public StockResult evaluateBaseline(
            StockInput input, CandidateContext context) {
        StockResult raw = evaluateStock(input);
        if (context == null || context.evidence() == null) return raw;
        TradingRadarEvidenceGate.GatedActions gated = TradingRadarEvidenceGate.apply(
                raw.action(), raw.shortAction(), raw.swingAction(), input.held(),
                context.profile(), context.evidence());
        List<String> mediumReasons = new ArrayList<>(raw.reasons());
        List<String> mediumRisks = new ArrayList<>(raw.risks());
        List<String> shortReasons = new ArrayList<>(raw.shortReasons());
        List<String> shortRisks = new ArrayList<>(raw.shortRisks());
        List<String> swingReasons = new ArrayList<>(raw.swingReasons());
        List<String> swingRisks = new ArrayList<>(raw.swingRisks());
        mediumRisks.addAll(gated.mediumDiagnostics());
        shortRisks.addAll(gated.shortDiagnostics());
        swingRisks.addAll(gated.swingDiagnostics());
        return new StockResult(
                raw.score(), gated.mediumAction(), raw.counterTrend(),
                List.copyOf(mediumReasons), List.copyOf(mediumRisks), raw.kdHeat(), raw.timingState(),
                raw.kdDeadCross(), raw.longTermBroken(), raw.shortScore(), gated.shortAction(),
                List.copyOf(shortReasons), List.copyOf(shortRisks),
                horizonConflict(gated.mediumAction(), gated.swingAction(), gated.shortAction()),
                raw.profitTakingConfirmed(), raw.normalizedBias(), raw.shortNormalizedBias(),
                raw.swingScore(), gated.swingAction(),
                List.copyOf(swingReasons), List.copyOf(swingRisks));
    }

    /**
     * 離線 calibration/holdout 專用：以 candidate 參數重跑完整因子、score、action 與 gate。
     * production 不得直接呼叫此方法，必須經 {@link #evaluatePromoted} 的 registry fallback。
     */
    public StockResult evaluateCandidate(
            StockInput input, RuleParameters parameters, CandidateContext context) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(context, "context");
        if (!RuleParameters.V13_VERSION.equals(parameters.ruleVersion())) {
            throw new IllegalArgumentException("candidate evaluation 必須使用 TW_RULES_V13 參數");
        }
        return evaluateStockInternal(input, parameters, context);
    }

    /**
     * production 唯一的 V13 入口：registry 未通過或缺 evidence 時 resolve 成 V12，直接走既有引擎。
     */
    /**
     * @deprecated A single production key cannot safely identify both horizons.  This overload is
     * retained for source compatibility and deliberately delegates the same key to both tracks;
     * callers must migrate to the five-argument track-scoped overload to prevent cross-horizon
     * promotion.
     */
    @Deprecated(forRemoval = false)
    public StockResult evaluatePromoted(
            StockInput input,
            TradingRadarV13PromotionRegistry registry,
            TradingRadarV13PromotionRegistry.ProductionKey key,
            CandidateContext context) {
        return evaluatePromoted(input, registry, key, key, context);
    }

    /**
     * Track-scoped production entry point.  Short and medium promotion keys are
     * resolved independently: a rejected/missing key falls back to the exact V12
     * horizon while the other horizon may still use its promoted V13 candidate.
     * This prevents a medium-track promotion from silently changing short actions
     * (or vice versa).  When both keys fall back, the original V12 object is
     * returned directly so all fields remain bit-identical to {@link #evaluateStock}.
     */
    public StockResult evaluatePromoted(
            StockInput input,
            TradingRadarV13PromotionRegistry registry,
            TradingRadarV13PromotionRegistry.ProductionKey shortKey,
            TradingRadarV13PromotionRegistry.ProductionKey mediumKey,
            CandidateContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(shortKey, "shortKey");
        Objects.requireNonNull(mediumKey, "mediumKey");

        boolean shortPromoted = registry.isPromoted(shortKey)
                && RuleParameters.V13_VERSION.equals(registry.resolve(shortKey).ruleVersion());
        boolean mediumPromoted = registry.isPromoted(mediumKey)
                && RuleParameters.V13_VERSION.equals(registry.resolve(mediumKey).ruleVersion());
        if (!shortPromoted && !mediumPromoted) {
            return evaluateStock(input);
        }

        CandidateContext requiredContext = Objects.requireNonNull(context, "context");
        // Task 356.9c：swing 軌一律走 V15 baseline，故只要**任一** key promoted 就必須額外跑一次
        // baseline。不能省：現行程式在**兩把 key 都 promoted** 時完全不會呼叫 evaluateStock，
        // 若照「從 medium 取 swing」的字面實作，promoted 參數會靜默污染 swing 軌。
        StockResult baseline = evaluateStock(input);
        StockResult medium = mediumPromoted
                ? evaluateCandidate(input, registry.resolve(mediumKey), requiredContext)
                : baseline;
        StockResult shortTerm = shortPromoted
                ? evaluateCandidate(input, registry.resolve(shortKey), requiredContext)
                : baseline;
        return composeTrackScopedResult(medium, shortTerm, baseline);
    }

    /**
     * 三軌組合（Task 356.9c）。
     *
     * <p><b>1周~1月 軌不納入 V13 candidate／promotion 機制</b>：{@code evaluateCandidate}／
     * {@code evaluatePromoted} 只解析 short／medium 兩把 key，swing 四欄與三軌
     * {@code horizonConflict} 一律由 {@code baseline}（純 {@link #evaluateStock}）供給。
     * 理由：promotion registry 的候選參數是以 {@code 5／20／60／120} 對兩軌做樣本外校準選出的，
     * 沒有針對 swing 軌的 holdout 證據，硬套等於未經校準就上線。</p>
     */
    private StockResult composeTrackScopedResult(
            StockResult medium, StockResult shortTerm, StockResult baseline) {
        return new StockResult(
                medium.score(), medium.action(), medium.counterTrend(), medium.reasons(), medium.risks(),
                medium.kdHeat(), medium.timingState(), medium.kdDeadCross(), medium.longTermBroken(),
                shortTerm.shortScore(), shortTerm.shortAction(), shortTerm.shortReasons(), shortTerm.shortRisks(),
                horizonConflict(medium.action(), baseline.swingAction(), shortTerm.shortAction()),
                medium.profitTakingConfirmed(), medium.normalizedBias(), shortTerm.shortNormalizedBias(),
                baseline.swingScore(), baseline.swingAction(),
                baseline.swingReasons(), baseline.swingRisks());
    }

    private StockResult evaluateStockInternal(
            StockInput input, RuleParameters candidate, CandidateContext context) {
        if (!complete(input)) {
            // kdDeadCross／longTermBroken 一律填 false：資料不完整代表「無法判定」，
            // 不得改呼叫 kdDeadCross(input)——那在 indicators 為 null 時會回到「偶然的 false」，
            // 表面相同但語意不同（Task 273）。
            // Task 356.1e：三軌都必須填成 NO_TRADE／score=null，不得只填兩軌而讓 swing 欄位
            // 為 null 物件——消費端會分不出「無法判定」與「這一軌不存在」。
            return new StockResult(null, Action.NO_TRADE,
                    new CounterTrendResult(CounterTrendState.NONE, List.of(), List.of()), List.of(),
                    List.of("個股必要的 MA20／60／240、KD、241 根完成日 K 或大盤資料不足，今日不交易。"),
                    KdHeat.NORMAL, TimingState.NEUTRAL, false, false,
                null, Action.NO_TRADE, List.of(),
                    List.of("必要資料不足，短期軌今日不交易。"), false, false,
                    candidate == null || !candidate.normalizedBiasEnabled()
                            ? NormalizedBiasProvenance.disabled()
                            : NormalizedBiasProvenance.from(true, null),
                    candidate == null || !candidate.normalizedBiasEnabled()
                            ? NormalizedBiasProvenance.disabled()
                            : NormalizedBiasProvenance.from(true, null),
                    null, Action.NO_TRADE, List.of(),
                    List.of("必要資料不足，1周~1月 軌今日不交易。"));
        }

        boolean narrowBand = narrowKdBand(input);
        KdHeat kdHeat = kdHeatOf(input);
        boolean normalizedEnabled = candidate != null && candidate.normalizedBiasEnabled();
        NormalizedBiasObservation normalizedBias = !normalizedEnabled
                ? null : normalizedBiasObservation(input, context, candidate);
        TimingState timing = !normalizedEnabled
                ? timingOf(input)
                : timingOf(input, normalizedBias, candidate);
        boolean profitTaking = profitTakingConfirmed(input, timing);
        boolean deadCross = kdDeadCross(input);

        // 因子貢獻與其文案只算一次，三軌各自加權累加（Task 305／356.7a）：避免逐檔重算三遍，
        // 更避免日後只改其中一軌路徑上的貢獻函數呼叫，導致三軌靜默分岔。
        FactorContributions factors = computeFactors(input, narrowBand, kdHeat, candidate == null);
        HorizonScore medium = evaluateHorizon(input, Horizon.MEDIUM, factors, kdHeat, timing,
                profitTaking, candidate,
                context == null ? null : context.mediumTreasuryContribution(), context,
                normalizedBias);
        HorizonScore shortTerm = evaluateHorizon(input, Horizon.SHORT, factors, kdHeat, timing,
                profitTaking, candidate,
                context == null ? null : context.shortTreasuryContribution(), context,
                normalizedBias);
        // Task 356.9c／356.9d：swing 軌一律走 V15 baseline，連 candidate 參數集都不套用
        // （RuleParameters 沒有、也不得新增 swing 的 ActionThresholds／CandidateWeight）。
        // 因此 candidate 路徑下也要用 baseline 的 timing 與 profitTaking 重算這一軌。
        TimingState baselineTiming = normalizedEnabled ? timingOf(input) : timing;
        boolean baselineProfitTaking = normalizedEnabled
                ? profitTakingConfirmed(input, baselineTiming) : profitTaking;
        HorizonScore swing = evaluateHorizon(input, Horizon.SWING, factors, kdHeat, baselineTiming,
                baselineProfitTaking, null, null, null, null);
        if (candidate != null) {
            medium = applyCandidatePolicy(input, medium, candidate, context,
                    context.mediumDownsideRiskPct(), deadCross, profitTaking, Horizon.MEDIUM);
            shortTerm = applyCandidatePolicy(input, shortTerm, candidate, context,
                    context.shortDownsideRiskPct(), deadCross, profitTaking, Horizon.SHORT);
        }
        CounterTrendResult counterTrend = evaluateCounterTrend(input);
        return new StockResult(
                medium.score(), medium.action(), counterTrend,
                medium.reasons(), medium.risks(), kdHeat, timing,
                deadCross, longTermBroken(input),
                shortTerm.score(), shortTerm.action(), shortTerm.reasons(), shortTerm.risks(),
                horizonConflict(medium.action(), swing.action(), shortTerm.action()), profitTaking,
                NormalizedBiasProvenance.from(normalizedEnabled, normalizedBias),
                NormalizedBiasProvenance.from(normalizedEnabled, normalizedBias),
                swing.score(), swing.action(), swing.reasons(), swing.risks());
    }

    private record HorizonScore(
            int score,
            Action action,
            List<String> reasons,
            List<String> risks
    ) {}

    /**
     * 23 因子貢獻值＋共用文案的求值結果（Task 305／356.7d）；不適用的因子為 {@code null}。
     *
     * <p>欄位順序與 {@link #computeFactors} 內的求值順序一致，也與既有
     * {@link #evaluateHorizon} 內 {@code acc.add} 的既有順序一致。Task 356 新增的五欄
     * 一律追加在 {@code industry} 之後、{@code reasons}／{@code risks} 之前。</p>
     */
    private record FactorContributions(
            Double ma5,
            Double ma20,
            Double ma60,
            Double ma240,
            Double kdJ,
            Double macd,
            Double rsi,
            Double bias,
            Double volume,
            Double market,
            Double dayMove,
            Double fx,
            Double etfPremium,
            Double eps,
            Double roe,
            Double revenue,
            Double pe,
            Double industry,
            Double dailyCandle,
            Double weeklyTrend,
            Double weeklyMomentum,
            Double weeklyBias,
            Double weeklyCandleVolume,
            List<String> reasons,
            List<String> risks
    ) {}

    /**
     * 因子貢獻與其文案的共用求值：三軌輸入相同、只有權重不同，故只算一次，
     * 交由 {@link #evaluateHorizon} 各自加權（Task 305／356.7d）。
     *
     * <p><b>呼叫順序刻意與重構前 {@code evaluateHorizon} 內 {@code acc.add} 的既有順序逐一對應</b>，
     * 既有貢獻函數（{@code positionOf}／{@code maWithConfirmation}／{@code kdJContribution}／
     * {@code macdContribution}／…／{@code fundamentalContribution}）本體不動——這是輸出
     * reasons／risks 逐位不變的前提。債券的「不套用台股大盤」reasons 句、{@code narrowBand}
     * risks 句、基本面缺值 risks 句皆屬此共用段；{@code describeHeat} 與時機分位揭露仍在
     * {@link #evaluateHorizon} 內逐軌呼叫，不屬共用段。</p>
     *
     * <p>{@code kdHeat} 目前不影響任何共用貢獻值或文案，接收它只是與 {@code evaluateStock}
     * 既有的一次性求值（{@code kdHeatOf(input)} 只呼叫一次）對齊，避免呼叫端另外分開傳遞。</p>
     */
    private FactorContributions computeFactors(StockInput input, boolean narrowBand, KdHeat kdHeat, boolean production) {
        List<String> reasons = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        // Task 356.6a：標籤由「週線」改為「日線 MA5」。input.weeklyMa() 實際上是**日K 收盤序列的
        // 5 日 SMA**，不是週K；不改的話同一份 reasons 會同時出現「最新價位於週線之上」與
        // 「最新價位於週MA5 之上」兩則、卻是兩個不同的量，而那個措辭錯誤正是 Requirement 93 的
        // 立案理由之一。欄位名 weeklyMa 依 356.14c 不得更動（改名會同時改變既有快照與匯出語意）。
        Double ma5 = positionOf(input.price(), input.weeklyMa(), "日線 MA5", reasons, risks);
        Double ma20 = maWithConfirmation(input.price(), input.indicators().ma20(), input.ma20Confirmation(),
                "月線", reasons, risks);
        Double ma60 = maWithConfirmation(input.price(), input.indicators().ma60(), input.ma60Confirmation(),
                "季線", reasons, risks);
        Double ma240 = maWithConfirmation(input.price(), input.indicators().ma240(), input.ma240Confirmation(),
                "年線", reasons, risks);

        if (narrowBand) {
            risks.add("近 9 個交易日高低帶過窄，KD／J／W%R 在雜訊上飽和，本日不採計。 ");
        }
        Double kdJ = narrowBand ? null : kdJContribution(input, reasons, risks);
        Double macd = macdContribution(input.extendedIndicators(), input.price(), reasons, risks);
        Double rsi = rsiContribution(input.extendedIndicators(), reasons, risks);
        Double bias = biasContribution(input.extendedIndicators(), reasons, risks);
        // Candidate factors and their narratives remain frozen; production alone uses this safety preference.
        if (production) bias = bollingerAdjustedBias(bias, input.bollinger(), risks);
        Double volume = volumeContribution(input.completedChangePercent(), input.volumeRatio(), reasons, risks);

        Double market = null;
        if (equityMarketApplies(input)) {
            market = marketContribution(input, reasons, risks);
        } else {
            reasons.add("資產類別為債券，不套用台股大盤 RISK_ON／RISK_OFF 加減分與買進閘門。 ");
        }
        Double dayMove = completedDayContribution(input.completedChangePercent(), reasons, risks);
        Double fx = fxContribution(input.fxPercentile(), reasons, risks);
        Double etfPremium = etfPremiumContribution(input, reasons, risks);

        Double eps = null;
        Double roe = null;
        Double revenue = null;
        Double pe = null;
        Double industry = null;
        FundamentalInput fundamental = input.fundamental();
        if (fundamental != null && fundamental.applicable()) {
            eps = fundamentalContribution(fundamental.epsContribution(), "EPS 年增", reasons, risks);
            roe = fundamentalContribution(fundamental.roeContribution(), "近似 ROE", reasons, risks);
            revenue = fundamentalContribution(
                    fundamental.revenueContribution(), "近三月營收年增", reasons, risks);
            pe = fundamentalContribution(fundamental.peContribution(),
                    fundamental.peLoss() ? "PE（可信來源顯示虧損）" : "估值（PE／PB／殖利率）",
                    reasons, risks);
            industry = fundamentalContribution(
                    fundamental.industryContribution(), "產業營收年增", reasons, risks);
            int available = availableFundamentalCount(fundamental);
            if (available == 0) {
                risks.add("個股基本面與產業歷史尚在累積，本日缺值權重已重分配，不宣稱已完整評估。 ");
            } else if (available < 5) {
                risks.add("部分個股基本面／產業資料尚在累積，缺值權重已重分配。 ");
            }
            if (fundamental.roeApproximationFallback()) {
                risks.add("近似 ROE 缺少期初權益，僅以最新期末權益估算；FINANCIAL 證據信心降低。 ");
            }
        }

        Double dailyCandle = dailyCandleContribution(input.dailyCandle(), risks);
        WeeklyInput weekly = usableWeekly(input.weekly(), risks);
        Double weeklyTrend = weeklyTrendContribution(weekly, input.price(), reasons, risks);
        Double weeklyMomentum = weeklyMomentumContribution(weekly, input.price(), reasons, risks);
        Double weeklyBias = weeklyBiasContribution(weekly, reasons, risks);
        Double weeklyCandleVolume = weeklyCandleVolumeContribution(weekly, reasons, risks);

        return new FactorContributions(ma5, ma20, ma60, ma240, kdJ, macd, rsi, bias, volume, market,
                dayMove, fx, etfPremium, eps, roe, revenue, pe, industry,
                dailyCandle, weeklyTrend, weeklyMomentum, weeklyBias, weeklyCandleVolume,
                List.copyOf(reasons), List.copyOf(risks));
    }

    // ───────────────────────── Task 356.5／356.6 的五個新因子 ─────────────────────────
    //
    // 五者**只進分數，不進 TimingState／KdHeat／profitTakingConfirmed／kdDeadCross／
    // longTermBroken 五個判定**（Task 356.8c）：把新指標接進極端態判定會同時改動保護門檻，
    // 本任務沒有回測依據支持那麼做。

    /** 收盤區間位置的中性點：收在正中央為 0 貢獻，上緣 +1、下緣 −1。 */
    private static final double CLOSE_POSITION_NEUTRAL = 0.5;
    /**
     * 下影線比例的中性點（四分之一全幅）。<b>判斷性取值、無回測依據</b>，
     * 不得於任何文案宣稱它能提高準確度。
     */
    private static final double LOWER_SHADOW_NEUTRAL = 0.25;

    /**
     * 缺值原因 (B)：{@code open}／{@code high}／{@code low}／{@code close} 任一缺漏
     * （四欄在 {@code stock_price_history} 皆 nullable，歷史列常見）。
     */
    private static final String DAILY_CANDLE_OHLC_MISSING_RISK =
            "最新完成日 K 棒的 OHLC 資料缺漏，日K 棒因子不採計，缺值權重已重分配。 ";

    /**
     * 缺值原因 (A)：四欄俱全但<b>全幅非正</b>（漲跌停鎖死、整日單一成交價，或
     * {@code high < low} 的倒置髒列）。
     */
    private static final String DAILY_CANDLE_NO_RANGE_RISK =
            "最新完成日 K 棒沒有價格區間（漲跌停鎖死或整日單一成交價），"
                    + "日K 棒因子不採計，缺值權重已重分配。 ";

    /**
     * 日K 棒因子（Task 356.5b）：收盤區間位置、實體方向、下影線比例三個分量各自 clamp 在
     * {@code [-1,1]} 後<b>取可用值的平均</b>（缺值不計入平均、不以 0 冒充）。
     *
     * <p><b>與 {@code completedChangePercent} 正交</b>：後者量「相對昨天走到哪」，本因子量
     * 「當天這根 K 棒內部誰主導」。兩者不得合併計分，也不得因為「都跟當日漲跌有關」而互相取代。</p>
     *
     * <p>三個分量全部缺值時整個因子為 {@code null}、權重由 {@link Accumulator} 重分配，
     * 並在 {@code risks} 加一則揭露——<b>不得沉默</b>。全幅非正在台股是每天都可能命中的實際路徑
     * （漲跌停鎖死、極低流動性標的整日單一成交價），不是理論案例。</p>
     *
     * <p><b>兩種缺值原因的文案不得互相冒充（Task 356.5d）</b>：{@code candle == null} 不是
     * 「OHLC 缺漏」的唯一入口——{@code RadarInputAssembler.candleAt} 只在索引越界時回
     * {@code null}，「該列存在但 {@code high}／{@code low} 為 null」拿到的是一個<b>非 null</b>
     * 的 {@link CandleInput}，三個分量同樣全缺。若一律歸因為「沒有價格區間」，使用者會讀到
     * 一句<b>可查證為假</b>的話（那天既沒有漲跌停鎖死，也不是只成交一筆）。故判準是：四欄
     * 任一為 null → {@link #DAILY_CANDLE_OHLC_MISSING_RISK}；四欄俱全而全幅非正 →
     * {@link #DAILY_CANDLE_NO_RANGE_RISK}。</p>
     */
    private Double dailyCandleContribution(CandleInput candle, List<String> risks) {
        if (candle == null) {
            risks.add(DAILY_CANDLE_OHLC_MISSING_RISK);
            return null;
        }
        Double value = candleComponents(candle);
        if (value == null) {
            risks.add(hasFullOhlc(candle)
                    ? DAILY_CANDLE_NO_RANGE_RISK
                    : DAILY_CANDLE_OHLC_MISSING_RISK);
        }
        return value;
    }

    /** 四欄是否俱全；缺值原因分岔的唯一判準（見 {@link #dailyCandleContribution}）。 */
    private static boolean hasFullOhlc(CandleInput candle) {
        return candle.open() != null && candle.high() != null
                && candle.low() != null && candle.close() != null;
    }

    /** 一根 K 棒的三分量平均；日K 與週K 共用同一支，不得各寫一份。 */
    private Double candleComponents(CandleInput candle) {
        BigDecimal position = closePosition(candle.high(), candle.low(), candle.close());
        BigDecimal body = bodyDirection(candle.open(), candle.high(), candle.low(), candle.close());
        BigDecimal shadow = lowerShadowRatio(
                candle.open(), candle.high(), candle.low(), candle.close());
        return averageAvailable(
                position == null ? null
                        : clampUnit((position.doubleValue() - CLOSE_POSITION_NEUTRAL) * 2.0),
                body == null ? null : clampUnit(body.doubleValue()),
                shadow == null ? null
                        : clampUnit((shadow.doubleValue() - LOWER_SHADOW_NEUTRAL) * 4.0));
    }

    /**
     * 週K 因子的共用前置（Task 356.6）：{@code weekly == null} 或完成週不足
     * {@code MIN_COMPLETED_WEEKS}（由 assembler 以「指標欄全 null」表示）時，四組週K 因子
     * <b>全部</b>缺值並在 {@code risks} 揭露一次——不得沉默，也不得寫成「週線中性」。
     */
    private WeeklyInput usableWeekly(WeeklyInput weekly, List<String> risks) {
        int completedWeeks = weekly == null ? 0 : weekly.completedWeeks();
        if (weekly == null || completedWeeks < WEEKLY_MIN_COMPLETED_WEEKS) {
            risks.add("週K 完成週不足 " + WEEKLY_MIN_COMPLETED_WEEKS + " 根（目前 " + completedWeeks
                    + " 根），本日不採計週線因子，缺值權重已重分配。 ");
            return null;
        }
        return weekly;
    }

    /**
     * 週K 指標成立的最少完成週根數（Task 356.3d／356.6）。
     *
     * <p><b>必須與 {@code RadarInputAssembler.MIN_COMPLETED_WEEKS} 保持同值</b>（由測試釘住）：
     * assembler 以它決定要不要把指標欄填成 {@code null}，引擎以它決定要不要採計四組週K 因子。
     * 引擎刻意不直接引用 assembler 的常數——引擎是純函數、不得反向依賴 {@code @Component}。</p>
     *
     * <p>下界來自週MACD：OSC 要到第 34 根完成週才有第一個值，60 根留了 26 根的 EMA 收斂餘裕。
     * <b>此數字為判斷性取值、無回測依據。</b></p>
     */
    static final int WEEKLY_MIN_COMPLETED_WEEKS = 60;

    /**
     * 週線趨勢（Task 356.6a）：現價相對週 {@code ma5}／{@code ma10}／{@code ma20} 的位置，
     * 沿用既有 {@link #positionOf} 的語意與文案，不另立規則。
     */
    private Double weeklyTrendContribution(
            WeeklyInput weekly, BigDecimal price, List<String> reasons, List<String> risks) {
        if (weekly == null) return null;
        return averageAvailable(
                positionOf(price, weekly.ma5(), "週MA5", reasons, risks),
                positionOf(price, weekly.ma10(), "週MA10", reasons, risks),
                positionOf(price, weekly.ma20(), "週MA20", reasons, risks));
    }

    /**
     * 週 OSC 幅度正規化的全幅比例（現價 %），與日K 的 {@link #OSC_FULL_SCALE_PCT}
     * <b>刻意是兩個獨立常數</b>（Task 356.6b）。
     *
     * <p>週 OSC 的幅度本來就大於日 OSC，沿用日K 的 {@code 0.5} 會讓幾乎每一根週K 都打到
     * clamp 邊界而失去解析度。<b>{@code 2.0} 為判斷性取值、無回測依據</b>，
     * 不得於任何文案宣稱它能提高準確度或降低風險。</p>
     */
    private static final double WEEKLY_OSC_FULL_SCALE_PCT = 2.0;

    /**
     * 週線動能（Task 356.6b）：週KD、週MACD、週RSI 三個分量取可用值平均。
     *
     * <p>週MACD 沿用 V12 建立的「OSC 相對現價幅度正規化」而非零軸硬翻面，避免零軸附近逐週抖動。</p>
     */
    private Double weeklyMomentumContribution(
            WeeklyInput weekly, BigDecimal price, List<String> reasons, List<String> risks) {
        if (weekly == null) return null;
        Double direction = null;
        Double position = null;
        if (weekly.k() != null && weekly.d() != null) {
            direction = (double) Integer.signum(weekly.k().compareTo(weekly.d()));
            double average = weekly.k().add(weekly.d()).doubleValue() / 2.0;
            position = clampUnit((50.0 - average) / 50.0);
            if (direction > 0) reasons.add("週KD 為 K>D，週線動能轉強。 ");
            if (direction < 0) risks.add("週KD 為 K<D，週線動能轉弱。 ");
        }
        Double jPosition = standardJPosition(weekly.k(), weekly.d());
        Double kd = averageAvailable(direction, position, jPosition);

        Double macd = null;
        if (weekly.osc() != null && price != null && price.signum() > 0) {
            double oscPct = weekly.osc().doubleValue() / price.doubleValue() * 100.0;
            macd = clampUnit(oscPct / WEEKLY_OSC_FULL_SCALE_PCT);
            if (oscPct >= OSC_NARRATIVE_PCT) reasons.add("週 MACD 柱狀體 OSC 為正，週線動能偏多。 ");
            if (oscPct <= -OSC_NARRATIVE_PCT) risks.add("週 MACD 柱狀體 OSC 為負，週線動能偏弱。 ");
        }

        Double rsiAverage = averageAvailable(decimal(weekly.rsi5()), decimal(weekly.rsi10()));
        Double rsi = null;
        if (rsiAverage != null) {
            if (rsiAverage <= 30) reasons.add("週RSI 位於低檔，週線位置有利逢低觀察。 ");
            if (rsiAverage >= 70) risks.add("週RSI 位於高檔，週線位置不利追價。 ");
            rsi = clampUnit((50.0 - rsiAverage) / 50.0);
        }
        return averageAvailable(kd, macd, rsi);
    }

    /**
     * 週MA10 乖離的全幅分母（%）。日K 的 {@code bias10} 用 {@code 10}，此處放寬為
     * {@code 15}——週MA10 ≈ 一季尺度，乖離量級大於日MA10。<b>判斷性取值、無回測依據。</b>
     */
    private static final double WEEKLY_BIAS10_FULL_SCALE_PCT = 15.0;
    /**
     * 週MA20 乖離的全幅分母（%）。<b>維持 {@code 20} 不變、與日K 相同</b>——週MA20 ≈ 半年尺度，
     * 實測乖離量級與日MA20 相近，刻意不放寬（<b>這不是筆誤</b>）。
     * 同為判斷性取值、無回測依據。
     */
    private static final double WEEKLY_BIAS20_FULL_SCALE_PCT = 20.0;

    /**
     * 週線乖離（Task 356.6c）：負乖離（現價在週均線下方）為正貢獻＝低接位置，
     * 正乖離為負貢獻＝追價成本。
     */
    private Double weeklyBiasContribution(
            WeeklyInput weekly, List<String> reasons, List<String> risks) {
        if (weekly == null) return null;
        Double value = averageAvailable(
                weekly.bias10() == null ? null
                        : clampUnit(-weekly.bias10().doubleValue() / WEEKLY_BIAS10_FULL_SCALE_PCT),
                weekly.bias20() == null ? null
                        : clampUnit(-weekly.bias20().doubleValue() / WEEKLY_BIAS20_FULL_SCALE_PCT));
        if (value != null && value > 0.4) reasons.add("週線乖離偏低，週尺度具均值回歸與逢低承接空間。 ");
        if (value != null && value < -0.4) risks.add("週線乖離偏高，週尺度追價成本升高。 ");
        return value;
    }

    /** 週量價確認的放量門檻，沿用日K 四象限的同一組數字。 */
    private static final double WEEKLY_VOLUME_HIGH_RATIO = 1.2;
    /** 週量價確認的量縮門檻，沿用日K 四象限的同一組數字。 */
    private static final double WEEKLY_VOLUME_LOW_RATIO = 0.7;

    /**
     * 週K 棒與量能（Task 356.6d）：週收盤區間位置、週實體方向、週量價確認三個分量取可用值平均。
     *
     * <p>前兩者的缺值判準與日K <b>完全相同</b>（見 {@link #bodyDirection}）：週全幅非正代表
     * 整週沒有任何價格區間，不是十字線。</p>
     */
    private Double weeklyCandleVolumeContribution(
            WeeklyInput weekly, List<String> reasons, List<String> risks) {
        if (weekly == null) return null;
        CandleInput candle = weekly.candle();
        Double position = null;
        Double body = null;
        if (candle != null) {
            BigDecimal rawPosition = closePosition(candle.high(), candle.low(), candle.close());
            position = rawPosition == null ? null
                    : clampUnit((rawPosition.doubleValue() - CLOSE_POSITION_NEUTRAL) * 2.0);
            BigDecimal rawBody = bodyDirection(
                    candle.open(), candle.high(), candle.low(), candle.close());
            body = rawBody == null ? null : clampUnit(rawBody.doubleValue());
        }
        Double confirmation = weeklyVolumeConfirmation(
                weekly.changePercent(), weekly.volumeRatio(), reasons, risks);
        return averageAvailable(position, body, confirmation);
    }

    /** 週量價四象限；與日K 的 {@link #volumeContribution} 同式，只有文案改為週尺度。 */
    private Double weeklyVolumeConfirmation(BigDecimal changePercent, BigDecimal volumeRatio,
                                            List<String> reasons, List<String> risks) {
        if (changePercent == null || volumeRatio == null) return null;
        int direction = changePercent.signum();
        double ratio = volumeRatio.doubleValue();
        if (direction > 0 && ratio >= WEEKLY_VOLUME_HIGH_RATIO) {
            reasons.add("最新完成週帶量上漲，週線需求獲得成交量確認。 ");
            return 1.0;
        }
        if (direction < 0 && ratio >= WEEKLY_VOLUME_HIGH_RATIO) {
            risks.add("最新完成週爆量下跌，週線籌碼賣壓升高。 ");
            return -1.0;
        }
        if (direction > 0 && ratio <= WEEKLY_VOLUME_LOW_RATIO) {
            risks.add("最新完成週上漲但週量能不足，漲勢確認偏弱。 ");
            return -0.4;
        }
        if (direction < 0 && ratio <= WEEKLY_VOLUME_LOW_RATIO) {
            reasons.add("最新完成週下跌但週量能收斂，賣壓未擴大。 ");
            return 0.4;
        }
        return 0.0;
    }

    /**
     * 單一持有期的動作映射；<b>三軌各自建立 {@link Accumulator} 並各自加權累加</b>，
     * 不得以「同一個分數套三組門檻」實作（Task 356.7a）。缺值因子不進 {@code sumW}，
     * 其權重由可用因子重新正規化，<b>不得以 0 冒充缺值</b>。
     *
     * <p>因子貢獻與其文案改由呼叫端算好一次傳入（{@link #computeFactors}，Task 305），
     * 本方法只負責依權重加總與 horizon 專屬文案（{@code describeHeat}、時機分位揭露、
     * {@code actionFor} 內產生的句子）——這些仍逐軌各自執行，會分別出現在三軌各自的清單。</p>
     */
    private HorizonScore evaluateHorizon(
            StockInput input,
            Horizon horizon,
            FactorContributions factors,
            KdHeat kdHeat,
            TimingState timing,
            boolean profitTaking,
            RuleParameters candidate,
            Double treasuryContribution,
            CandidateContext context,
            NormalizedBiasObservation normalizedBias) {
        List<String> reasons = new ArrayList<>(factors.reasons());
        List<String> risks = new ArrayList<>(factors.risks());
        Accumulator acc = new Accumulator();
        boolean shortTerm = horizon == Horizon.SHORT;
        // Task 356.9c／356.9d 的不變式，寫成硬失敗而不是註解：SWING 沒有任何 candidate 校準依據，
        // 若日後有人把 candidate 傳進 SWING，下面的 CandidateWeight 分派會靜默把它當成 MEDIUM。
        if (horizon == Horizon.SWING && candidate != null) {
            throw new IllegalArgumentException("1周~1月 軌不得套用 V13 candidate 參數集");
        }

        acc.add(weightOf(horizon, SW_MA5, SWG_MA5, MW_MA5), factors.ma5());
        acc.add(weightOf(horizon, SW_MA20, SWG_MA20, MW_MA20), factors.ma20());
        acc.add(weightOf(horizon, SW_MA60, SWG_MA60, MW_MA60), factors.ma60());
        acc.add(weightOf(horizon, SW_MA240, SWG_MA240, MW_MA240), factors.ma240());
        acc.add(weightOf(horizon, SW_KD_J, SWG_KD_J, MW_KD_J), factors.kdJ());
        acc.add(weightOf(horizon, SW_MACD, SWG_MACD, MW_MACD), factors.macd());
        acc.add(weightOf(horizon, SW_RSI, SWG_RSI, MW_RSI), factors.rsi());
        Double bias = factors.bias();
        if (candidate != null && candidate.normalizedBiasEnabled()) {
            // V13 candidate only：同一筆 immutable observation 同時供 timing 與
            // BIAS；缺 sigma 不偷偷以分位或固定值偽造 normalized score。
            bias = normalizedBiasCandidate(normalizedBias, candidate, reasons, risks);
        }
        acc.add(weightOf(horizon, SW_BIAS, SWG_BIAS, MW_BIAS), bias);
        acc.add(weightOf(horizon, SW_VOLUME, SWG_VOLUME, MW_VOLUME), factors.volume());
        // SWING 一律以 candidate == null 呼叫（Task 356.9c／356.9d），故永遠拿 baseline 權重；
        // RuleParameters 沒有、也不得新增 SWING_MARKET 這類 candidate 權重 key。
        double marketWeight = candidateWeight(candidate,
                shortTerm ? RuleParameters.CandidateWeight.SHORT_MARKET
                        : RuleParameters.CandidateWeight.MEDIUM_MARKET,
                weightOf(horizon, SW_MARKET, SWG_MARKET, MW_MARKET));
        acc.add(marketWeight, factors.market());
        // V13 candidate-only typed numeric market features are an additional
        // factor beside the existing regime market factor.  Baseline/V12 keeps
        // this path completely inert (candidate == null); missing and
        // disclosure-only aggregates remain null and therefore do not become a
        // guessed neutral score.
        if (candidate != null && context != null) {
            boolean shortHorizon = shortTerm;
            Double featureContribution = shortHorizon
                    ? context.shortMarketFeatureContribution()
                    : context.mediumMarketFeatureContribution();
            RuleParameters.CandidateWeight featureKey = shortHorizon
                    ? RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE
                    : RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE;
            double featureWeight = candidateWeight(candidate, featureKey, 0.0);
            TradingRadarMarketFeatureResolver.AggregatedContribution aggregate =
                    context.marketFeatures().aggregateContribution(context.profile());
            if (featureContribution == null) {
                risks.add("V13_MARKET_FEATURE_UNAVAILABLE：typed numeric market feature coverage "
                        + percent(aggregate.coverage())
                        + "，缺值／disclosure-only 不進 candidate score。 ");
                if (!aggregate.unavailableReasons().isEmpty()) {
                    risks.add("V13_MARKET_FEATURE_REASON："
                            + String.join("；", aggregate.unavailableReasons()) + "。 ");
                }
            } else {
                acc.add(featureWeight, featureContribution);
                if (featureWeight > 0) {
                    reasons.add("V13_MARKET_FEATURE_CONTRIBUTION：coverage "
                            + percent(aggregate.coverage()) + "，aggregate="
                            + formatContribution(featureContribution) + "，權重="
                            + formatContribution(featureWeight) + "。 ");
                    if (aggregate.coverage() < 1.0 && !aggregate.unavailableReasons().isEmpty()) {
                        risks.add("V13_MARKET_FEATURE_PARTIAL_COVERAGE："
                                + String.join("；", aggregate.unavailableReasons()) + "。 ");
                    }
                } else {
                    risks.add("V13_MARKET_FEATURE_DISCLOSURE_ONLY：typed numeric aggregate 可得但本 candidate 未配置分數權重。 ");
                }
            }
        }
        acc.add(weightOf(horizon, SW_DAY_MOVE, SWG_DAY_MOVE, MW_DAY_MOVE), factors.dayMove());
        acc.add(weightOf(horizon, SW_FX, SWG_FX, MW_FX), factors.fx());
        acc.add(weightOf(horizon, SW_ETF_PREMIUM, SWG_ETF_PREMIUM, MW_ETF_PREMIUM),
                factors.etfPremium());
        acc.add(weightOf(horizon, SW_EPS, SWG_EPS, MW_EPS), factors.eps());
        acc.add(weightOf(horizon, SW_ROE, SWG_ROE, MW_ROE), factors.roe());
        acc.add(weightOf(horizon, SW_REVENUE, SWG_REVENUE, MW_REVENUE), factors.revenue());
        acc.add(weightOf(horizon, SW_PE, SWG_PE, MW_PE), factors.pe());
        acc.add(weightOf(horizon, SW_INDUSTRY, SWG_INDUSTRY, MW_INDUSTRY), factors.industry());
        acc.add(weightOf(horizon, SW_DAILY_CANDLE, SWG_DAILY_CANDLE, MW_DAILY_CANDLE),
                factors.dailyCandle());
        acc.add(weightOf(horizon, SW_WEEKLY_TREND, SWG_WEEKLY_TREND, MW_WEEKLY_TREND),
                factors.weeklyTrend());
        acc.add(weightOf(horizon, SW_WEEKLY_MOMENTUM, SWG_WEEKLY_MOMENTUM, MW_WEEKLY_MOMENTUM),
                factors.weeklyMomentum());
        acc.add(weightOf(horizon, SW_WEEKLY_BIAS, SWG_WEEKLY_BIAS, MW_WEEKLY_BIAS),
                factors.weeklyBias());
        acc.add(weightOf(horizon, SW_WEEKLY_CANDLE_VOLUME, SWG_WEEKLY_CANDLE_VOLUME,
                MW_WEEKLY_CANDLE_VOLUME), factors.weeklyCandleVolume());
        double treasuryWeight = candidateWeight(candidate,
                shortTerm ? RuleParameters.CandidateWeight.SHORT_TREASURY
                        : RuleParameters.CandidateWeight.MEDIUM_TREASURY,
                0.0);
        acc.add(treasuryWeight, treasuryContribution);

        describeHeat(kdHeat, input.indicators().k(), input.indicators().d(), risks);
        // V12 keeps the historical percentile route.  V13 uses it only as a
        // disclosure field; it must not silently participate in candidate action.
        if (candidate == null) describeBiasPercentileExtreme(timing, input, reasons, risks);
        int score = acc.score();
        // Task 356.8a：三軌共用同一組 V12_ACTION_THRESHOLDS（75／55／40／25）。
        RuleParameters.ActionThresholds thresholds = candidate == null
                ? V12_ACTION_THRESHOLDS
                : shortTerm ? candidate.shortThresholds() : candidate.mediumThresholds();
        Action action = actionFor(input, score, timing, profitTaking, risks, reasons, thresholds);
        return new HorizonScore(score, action, List.copyOf(reasons), List.copyOf(risks));
    }

    /** 三軌權重選擇的<b>唯一分派點</b>；窮盡 switch，新增第四個 horizon 時必須編譯失敗。 */
    private static double weightOf(Horizon horizon, double shortWeight, double swingWeight,
                                  double mediumWeight) {
        return switch (horizon) {
            case SHORT -> shortWeight;
            case SWING -> swingWeight;
            case MEDIUM -> mediumWeight;
        };
    }

    private NormalizedBiasObservation normalizedBiasObservation(
            StockInput input, CandidateContext context, RuleParameters candidate) {
        BigDecimal rawSigma = context == null ? null : context.normalizedBiasSigmaRatio();
        LocalDate asOf = context == null ? null : context.normalizedBiasAsOfDate();
        return NormalizedBiasObservation.from(
                input == null ? null : input.ma60BiasPercent(), rawSigma,
                candidate == null ? BigDecimal.ZERO : candidate.sigmaFloorRatio(), asOf);
    }

    private Double normalizedBiasCandidate(
            NormalizedBiasObservation observation,
            RuleParameters candidate,
            List<String> reasons,
            List<String> risks) {
        if (observation == null || !observation.available()) {
            String reason = observation == null || observation.reason() == null
                    ? "observation_missing" : observation.reason();
            risks.add("V13_NORMALIZED_BIAS_UNAVAILABLE：" + reason
                    + "；本次不以 sigma floor／分位數偽造 normalized score，confidence 應由呼叫端降低。 ");
            return null;
        }
        if (observation.floorApplied()) {
            risks.add("V13_NORMALIZED_BIAS_SIGMA_FLOOR：raw sigma 低於校準下限，已使用 effective sigma floor；"
                    + "normalized 值仍保留並揭露。 ");
        }
        BigDecimal normalized = observation.normalizedBias();
        double contribution = -normalized
                .divide(candidate.normalizedBiasMultiple(), 12, RoundingMode.HALF_UP)
                .doubleValue();
        contribution = Math.max(-1.0, Math.min(1.0, contribution));
        if (contribution > 0) reasons.add("V13 normalized bias 顯示季線下方且波動調整後具承接空間。 ");
        if (contribution < 0) risks.add("V13 normalized bias 顯示季線上方且波動調整後追價成本偏高。 ");
        return contribution;
    }

    private HorizonScore applyCandidatePolicy(
            StockInput input,
            HorizonScore horizon,
            RuleParameters parameters,
            CandidateContext context,
            BigDecimal downsideRiskPct,
            boolean deadCross,
            boolean profitTaking,
            Horizon track) {
        if (track == Horizon.SWING) {
            throw new IllegalArgumentException("1周~1月 軌不納入 V13 candidate／promotion 機制");
        }
        boolean shortTerm = track == Horizon.SHORT;
        TradingRadarV13ActionPolicy.Decision decision = TradingRadarV13ActionPolicy.apply(
                input, horizon.action(), profitTaking, deadCross, downsideRiskPct, parameters, true);
        // Keep the action produced by the immutable rule policy separate from any
        // evidence-gate fallback.  Sigma safety is a hard matrix over the original
        // REDUCE/EXIT opportunity; inspecting only the already-gated HOLD/WATCH
        // action would lose that fact and could return the wrong sigma fallback.
        Action policyAction = decision.action();
        Action action = policyAction;
        List<String> risks = new ArrayList<>(horizon.risks());
        risks.addAll(decision.disclosures());

        // V13 candidate 的 action 必須先通過完整 evidence resolver/gate；legacy
        // 7/8-field CandidateContext 沒有 evidence 時保留既有純規則測試形狀，
        // BacktestService 的正式 V13 路徑一律傳入非 null evidence。
        if (context.evidence() != null) {
            TradingRadarEvidenceGate.GatedActions gated = TradingRadarEvidenceGate.apply(
                    action, action, input.held(), context.profile(), context.evidence(),
                    parameters.confidenceThreshold());
            Action evidenceAction = shortTerm ? gated.shortAction() : gated.mediumAction();
            if (evidenceAction != action) {
                risks.addAll(shortTerm ? gated.shortDiagnostics() : gated.mediumDiagnostics());
            }
            action = evidenceAction;
        }

        BigDecimal confidence = candidateConfidence(context, shortTerm);
        // Every V13 candidate (including candidates that disable the normalized-bias
        // score) needs a train-fold volatility profile.  Otherwise a disabled path
        // could bypass the same confidence/safety fallback merely by omitting the
        // normalized factor while still being promoted as V13.
        boolean sigmaUnavailable = RuleParameters.V13_VERSION.equals(parameters.ruleVersion())
                && (context == null || !context.sigmaProfileAvailable()
                || context.normalizedBiasSigmaRatio() == null
                || context.normalizedBiasSigmaRatio().signum() <= 0);
        if (sigmaUnavailable) {
            // Missing realized sigma falls back to fixed absolute timing only;
            // cap candidate confidence so this fallback cannot masquerade as a
            // fully observed normalized-BIAS decision.
            confidence = confidence == null ? null : confidence.min(new BigDecimal("0.50"));
            risks.add(parameters.normalizedBiasEnabled()
                    ? "V13_NORMALIZED_BIAS_FALLBACK_FIXED_THRESHOLD：sigma 缺漏，時機改用固定乖離門檻；"
                    + "candidate confidence 已保守下修。 "
                    : "V13_SIGMA_PROFILE_GATE：V13 fold sigma profile 缺漏；normalized path 雖停用，"
                    + "candidate confidence 仍保守下修。 ");
            // The same missing sigma that closes the buy confidence gate must also
            // close REDUCE/EXIT.  Otherwise weakening/downside evidence could still
            // turn a fixed-threshold fallback into an executable sell instruction.
            if (isRiskAction(policyAction)) {
                action = input != null && input.held() ? Action.HOLD_CAUTION : Action.WAIT;
                risks.add("V13_SIGMA_GATE：sigma 缺漏／fold profile 不可用，REDUCE/EXIT 僅保留候選揭露。 ");
            }
        }
        boolean confidenceAllowsBuy = context.priceFresh()
                && context.marketFresh()
                && confidence != null
                && confidence.compareTo(parameters.confidenceThreshold()) >= 0;
        if (isBuyAction(action) && !confidenceAllowsBuy) {
            action = input.held() ? Action.HOLD : Action.WATCH;
            risks.add("V13_CONFIDENCE_GATE：PRICE/MARKET freshness 或 calibrated confidence 不足，"
                    + "買進動作只向 HOLD/WATCH 降級。 ");
        }
        return new HorizonScore(horizon.score(), action, horizon.reasons(), List.copyOf(risks));
    }

    private BigDecimal candidateConfidence(CandidateContext context, boolean shortTerm) {
        BigDecimal horizon = shortTerm ? context.shortConfidence() : context.mediumConfidence();
        return horizon == null ? context.confidence() : horizon;
    }

    private double candidateWeight(
            RuleParameters candidate, RuleParameters.CandidateWeight key, double baseline) {
        if (candidate == null) return baseline;
        double adjusted = baseline + candidate.candidateWeightDeltas()
                .getOrDefault(key, BigDecimal.ZERO).doubleValue();
        if (adjusted < 0.0 || adjusted > 1.0) {
            throw new IllegalArgumentException(key + " 調整後權重必須介於 0..1");
        }
        return adjusted;
    }

    private boolean isBuyAction(Action action) {
        return action == Action.BUY_CANDIDATE
                || action == Action.ADD_CANDIDATE
                || action == Action.TRIAL_BUY;
    }

    private boolean isRiskAction(Action action) {
        return action == Action.REDUCE_CANDIDATE || action == Action.EXIT_CANDIDATE;
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
        BigDecimal stabilityBasis = input.completedChangePercent();
        boolean stabilized = stabilityBasis != null && stabilityBasis.compareTo(BigDecimal.ZERO) >= 0;

        if (lowKd && goldenCross && stabilized && !fundamentalDeteriorating(input)) {
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

        if (lowKd && goldenCross && stabilized && fundamentalDeteriorating(input)) {
            risks.add("基本面多項惡化，本次只保留超跌觀察，不升級為逆勢試單候選。 ");
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
            return Math.max(0, Math.min(100, (int) Math.round(50 + 50 * sigma)));
        }
    }

    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /**
     * 標準 J（{@code 3K − 2D}）的位置分量：低檔為正、高檔為負。
     *
     * <p><b>刻意不讀 {@code j9}。</b>本專案的 {@code j9 = 3D − 2K} 是為了與使用者券商畫面相符
     * 而採的顯示慣例，方向與坊間標準 {@code J = 3K − 2D} 相反。令 {@code avg = (K+D)/2}、
     * {@code m = K − D}，則 {@code 3D − 2K = avg − 2.5m}、{@code 3K − 2D = avg + 2.5m}，
     * 代入本式的 {@code (50 − value)/50}（語意為「低檔為正」）分別得到
     * {@code (50−avg)/50 + m/20}（動能越強分數越高＝追漲）與 {@code (50−avg)/50 − m/20}
     * （動能越強分數越低＝不追價）。後者才符合 Requirement 43／59「不追高殺低」與
     * {@code t291:59} 自述的「低檔有利於承接、高檔不鼓勵追價」。Task 291（日線）與
     * Task 356.6b（週線）都直接接入 {@code j9} 而未檢視慣例方向，由 Requirement 96／Task 360 修正。</p>
     *
     * <p>改寫成 {@code (j9 − 50)/50} <b>不是</b>修法：那會連位置項一起反轉，變成高檔加分。</p>
     *
     * <p><b>為什麼由 {@code k}／{@code d} 現算而不讀 {@code k3d2}：因為 {@link WeeklyInput}
     * 沒有 {@code k3d2} 欄位</b>，為此新增 record 欄位會踩到本專案已知的「相容建構式吃掉新欄位
     * → production 少傳引數、新欄位靜默 null、既有測試全綠」陷阱。<b>理由不是精度，精度其實相反</b>：
     * {@code k}／{@code d}／{@code j9}／{@code k3d2} 是在 {@code TechnicalIndicatorService:435}
     * 的同一行一起 {@code scale2(...)} 捨入到 2 位小數的，故由 2 位 {@code k}／{@code d} 現算
     * {@code 3k − 2d} 的誤差上界為 {@code 0.025}，直接讀 {@code k3d2} 只有 {@code 0.005}——
     * 讀 {@code k3d2} 反而較精確，只是兩者代入 {@code /50} 後分別為 {@code 5e-4} 與 {@code 1e-4}，
     * 皆可忽略。</p>
     */
    static Double standardJPosition(BigDecimal k, BigDecimal d) {
        if (k == null || d == null) return null;
        double j = 3.0 * k.doubleValue() - 2.0 * d.doubleValue();
        return clampUnit((50.0 - j) / 50.0);
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value * 100.0).setScale(1, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    private static String formatContribution(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString();
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

    /** MA20／60／240 將即時位置與兩個完成日的確認等權平均。 */
    private Double maWithConfirmation(BigDecimal price, BigDecimal ma, Confirmation confirmation,
                                      String label, List<String> reasons, List<String> risks) {
        return averageAvailable(
                positionOf(price, ma, label, reasons, risks),
                confirmationOf(confirmation, label, reasons, risks));
    }

    /**
     * KD 方向、KD 均值位置、J9 位置與 W%R 位置四者的可用值平均。
     *
     * <p>W%R 於 Task 298 併入本因子：{@code W%R9 ≡ 100 − RSV9} 為代數恆等式，K 又是 RSV 的平滑，
     * 兩者獨立計權重等於同一個 9 日高低帶訊號算兩次分。窄幅防護（呼叫端 {@code narrowBand}）
     * 一併涵蓋新併入的分量，理由相同。</p>
     */
    private Double kdJContribution(StockInput input, List<String> reasons, List<String> risks) {
        BigDecimal k = input.indicators() == null ? null : input.indicators().k();
        BigDecimal d = input.indicators() == null ? null : input.indicators().d();
        Double direction = null;
        Double position = null;
        if (k != null && d != null) {
            direction = (double) Integer.signum(k.compareTo(d));
            double average = k.add(d).doubleValue() / 2.0;
            position = clampUnit((50.0 - average) / 50.0);
            if (direction > 0) reasons.add("KD 為 K>D，短線動能轉強。 ");
            if (direction < 0) risks.add("KD 為 K<D，短線動能轉弱。 ");
            if (average < 25) reasons.add("KD 位於低檔，具跌深承接條件。 ");
            if (average > 75) risks.add("KD 位於高檔，不鼓勵追價。 ");
        }
        Double jPosition = standardJPosition(k, d);
        // 本系統 W%R 值域為 0（高檔）至 100（低檔），故低檔為正貢獻，與被併入前的 wrContribution 同式。
        BigDecimal wr9 = input.extendedIndicators() == null ? null : input.extendedIndicators().wr9();
        Double wrPosition = wr9 == null ? null : clampUnit((wr9.doubleValue() - 50.0) / 50.0);
        if (wr9 != null && wr9.doubleValue() >= 80) reasons.add("威廉指標位於低檔，具跌深承接條件。 ");
        if (wr9 != null && wr9.doubleValue() <= 20) risks.add("威廉指標位於高檔，避免追價。 ");
        return averageAvailable(direction, position, jPosition, wrPosition);
    }

    /**
     * OSC 幅度正規化的全幅比例（現價 %）：{@code |OSC|} 達現價此比例即視為飽和 ±1（Task 298）。
     *
     * <p><b>沒有回測依據，是判斷性取值</b>，比照 {@link #KD_OVERHEAT_K} 的既有揭露寫法。
     * 不得於任何文案宣稱本門檻能降低風險。</p>
     */
    private static final double OSC_FULL_SCALE_PCT = 0.5;
    /**
     * OSC 幅度（現價 %）達此值以上才輸出動能文案，取代舊版「非零即宣稱方向」（Task 298）。
     *
     * <p>目的是避免零軸附近的噪音級 OSC 也被描述為「動能偏多／偏弱」——那正是硬翻轉抖動的成因。
     * 同 {@link #OSC_FULL_SCALE_PCT}，沒有回測依據，是判斷性取值。</p>
     */
    private static final double OSC_NARRATIVE_PCT = 0.1;

    /**
     * MACD 只取 OSC，避免 DIF、MACD、OSC 的代數相依值重複灌權重；貢獻改採 OSC 相對現價的
     * 幅度正規化，取代舊版 {@code signum(osc)} 硬翻轉（Task 298）——OSC 在零軸附近逐日翻面時，
     * 硬翻轉版本會讓中期分數擺動約 ±5 分、短期約 ±8 分，直接造成動作門檻邊界抖動。
     */
    private Double macdContribution(ExtendedIndicators indicators, BigDecimal price,
                                    List<String> reasons, List<String> risks) {
        BigDecimal osc = indicators == null ? null : indicators.osc();
        if (osc == null) return null;
        if (price == null || price.signum() <= 0) return null;
        double oscPct = osc.doubleValue() / price.doubleValue() * 100.0;
        if (oscPct >= OSC_NARRATIVE_PCT) reasons.add("MACD 柱狀體 OSC 為正，動能偏多。 ");
        if (oscPct <= -OSC_NARRATIVE_PCT) risks.add("MACD 柱狀體 OSC 為負，動能偏弱。 ");
        return clampUnit(oscPct / OSC_FULL_SCALE_PCT);
    }

    /** RSI5／RSI10 可用值平均，以 50 為中性；低檔有利承接、高檔抑制追價。 */
    private Double rsiContribution(ExtendedIndicators indicators,
                                   List<String> reasons, List<String> risks) {
        if (indicators == null) return null;
        Double average = averageAvailable(decimal(indicators.rsi5()), decimal(indicators.rsi10()));
        if (average == null) return null;
        if (average <= 30) reasons.add("RSI 位於低檔，價格位置有利逢低觀察。 ");
        if (average >= 70) risks.add("RSI 位於高檔，避免追價。 ");
        return clampUnit((50.0 - average) / 50.0);
    }

    /**
     * BIAS10、BIAS20 分別依固定尺度正規化後取可用值平均（Task 298）。
     *
     * <p>{@code b10b20 ≡ bias10 − bias20} 為代數相依值，不再納入平均——同一乖離訊號用兩個代數
     * 相依分量計權重會使實質權重偏向 bias10，與 MACD 因子「只取 OSC」的既有原則矛盾。
     * {@code b10b20} 在 {@code ExtendedIndicators}／DTO／畫面／匯出仍維持純揭露，不受影響。</p>
     */
    private Double biasContribution(ExtendedIndicators indicators,
                                    List<String> reasons, List<String> risks) {
        if (indicators == null) return null;
        Double value = averageAvailable(
                indicators.bias10() == null ? null : clampUnit(-indicators.bias10().doubleValue() / 10.0),
                indicators.bias20() == null ? null : clampUnit(-indicators.bias20().doubleValue() / 20.0));
        if (value != null && value > 0.4) reasons.add("乖離率偏低，具均值回歸與逢低承接空間。 ");
        if (value != null && value < -0.4) risks.add("乖離率偏高，追價成本升高。 ");
        return value;
    }

    /** Judgmental extension penalty inside the existing BIAS weight, not a stand-alone buy/sell signal. */
    static Double bollingerAdjustedBias(Double bias, BollingerInput observation, List<String> risks) {
        if (bias == null) return null;
        if (observation == null || observation.bandWidthPercent() == null) {
            risks.add("布林完成日資料不足，本次沿用原乖離因子。 ");
            return bias;
        }
        if (observation.bandWidthPercent().signum() == 0) {
            risks.add("布林區間無寬度，本次不採計延伸扣分。 ");
            return bias;
        }
        double penalty = bollingerExtensionPenalty(observation);
        if (penalty > 0) {
            risks.add("布林完成日價格位於中軌上方，已在乖離因子採計上方延伸扣分；觸及上軌不構成單獨賣訊。 ");
        }
        return clampUnit(bias - penalty);
    }

    static double bollingerExtensionPenalty(BollingerInput observation) {
        if (observation == null || observation.percentB() == null || observation.bandWidthPercent() == null) return 0;
        double width = observation.bandWidthPercent().doubleValue();
        double position = observation.percentB().doubleValue();
        if (!Double.isFinite(width) || width <= 0 || !Double.isFinite(position)) return 0;
        double extension = Math.max(0, Math.min(1, 2 * (position - 0.5)));
        double reliability = Math.min(1, width / 2) * Math.min(1, 20 / width);
        return 0.25 * extension * reliability;
    }

    /** 個股完成日量價確認；相對量使用調整後成交股數計算。 */
    private Double volumeContribution(BigDecimal completedChangePercent, BigDecimal volumeRatio,
                                      List<String> reasons, List<String> risks) {
        if (completedChangePercent == null || volumeRatio == null) return null;
        int direction = completedChangePercent.signum();
        double ratio = volumeRatio.doubleValue();
        if (direction > 0 && ratio >= 1.2) {
            reasons.add("完成日帶量上漲，需求獲得成交量確認。 ");
            return 1.0;
        }
        if (direction < 0 && ratio >= 1.2) {
            risks.add("完成日爆量下跌，籌碼賣壓升高。 ");
            return -1.0;
        }
        if (direction > 0 && ratio <= 0.7) {
            risks.add("完成日上漲但量能不足，漲勢確認偏弱。 ");
            return -0.4;
        }
        if (direction < 0 && ratio <= 0.7) {
            reasons.add("完成日下跌但成交量收斂，賣壓未擴大。 ");
            return 0.4;
        }
        return 0.0;
    }

    /** 完成日漲跌採均值回歸方向，上漲延伸為負、下跌回落為正。 */
    private Double completedDayContribution(BigDecimal completedChangePercent,
                                            List<String> reasons, List<String> risks) {
        if (completedChangePercent == null) return null;
        double value = clampUnit(-completedChangePercent.doubleValue() / 5.0);
        if (completedChangePercent.compareTo(BigDecimal.valueOf(5)) >= 0) {
            risks.add("完成日漲幅達 5% 以上，避免追高。 ");
        } else if (completedChangePercent.signum() < 0) {
            reasons.add("完成日價格回落，位置成本相對降低；仍須等待止跌才可買進。 ");
        }
        return value;
    }

    /** 數值已由 as-of resolver 映射至 [-1,+1]；本引擎只負責具名揭露與加權。 */
    private Double fundamentalContribution(
            Double contribution, String label, List<String> reasons, List<String> risks) {
        if (contribution == null) return null;
        double value = clampUnit(contribution);
        if (value >= 0.5) reasons.add(label + "表現正向，已納入評分。 ");
        else if (value <= -0.5) risks.add(label + "表現偏弱，已納入評分。 ");
        return value;
    }

    private int availableFundamentalCount(FundamentalInput input) {
        int count = 0;
        if (input.epsContribution() != null) count++;
        if (input.roeContribution() != null) count++;
        if (input.revenueContribution() != null) count++;
        if (input.peContribution() != null) count++;
        if (input.industryContribution() != null) count++;
        return count;
    }

    /**
     * 多項惡化只是買方硬閘門，不直接製造賣出訊號，避免在財報公布後追殺。
     */
    private boolean fundamentalDeteriorating(StockInput input) {
        FundamentalInput f = input == null ? null : input.fundamental();
        if (f == null || !f.applicable()) return false;
        Double[] basic = { f.epsContribution(), f.roeContribution(), f.revenueContribution(), f.peContribution() };
        int severe = 0;
        for (Double value : basic) if (value != null && value <= -0.8) severe++;
        if (severe >= 2) return true;
        if (!f.peLoss()) return false;
        return (f.epsContribution() != null && f.epsContribution() <= -0.5)
                || (f.roeContribution() != null && f.roeContribution() <= -0.5)
                || (f.revenueContribution() != null && f.revenueContribution() <= -0.5);
    }

    /** 極端高檔時，KD、MACD、下跌爆量三種獨立轉弱證據至少兩項才確認獲利了結。 */
    private boolean profitTakingConfirmed(StockInput input, TimingState timing) {
        if (timing != TimingState.EXTREME_OVERBOUGHT) return false;
        int evidence = kdDeadCross(input) ? 1 : 0;
        ExtendedIndicators extended = input.extendedIndicators();
        if (extended != null && extended.osc() != null && extended.osc().signum() < 0) evidence++;
        if (input.completedChangePercent() != null && input.completedChangePercent().signum() < 0
                && input.volumeRatio() != null && input.volumeRatio().compareTo(BigDecimal.valueOf(1.2)) >= 0) {
            evidence++;
        }
        return evidence >= 2;
    }

    /**
     * 持有期分歧（Task 356.1c）：<b>三軌的動作分組不全相同</b>即為分歧。
     *
     * <p>V15 之前是「短 vs 中兩軌分組不同」；分組定義沿用 {@link #actionGroup}。</p>
     */
    private boolean horizonConflict(Action medium, Action swing, Action shortTerm) {
        int mediumGroup = actionGroup(medium);
        return mediumGroup != actionGroup(swing) || mediumGroup != actionGroup(shortTerm);
    }

    /** 持有期分歧只比較買進／中性／賣出／無法判定四個方向群組。 */
    private int actionGroup(Action action) {
        if (action == null || action == Action.NO_TRADE) return 4;
        return switch (action) {
            case BUY_CANDIDATE, ADD_CANDIDATE, TRIAL_BUY -> 1;
            case HOLD, WATCH, HOLD_CAUTION, WAIT -> 2;
            case REDUCE_CANDIDATE, EXIT_CANDIDATE, AVOID -> 3;
            case NO_TRADE -> 4;
        };
    }

    private static Double decimal(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /**
     * 大盤量價文案的分量標籤（Task 342）——依本次 {@code marketActivity} 實際由哪幾個 ratio
     * 構成內插，不得列舉不存在的欄位。
     *
     * <p><b>必須是輸入的純函數</b>：不讀任何外部狀態、不新增第二個旗標欄位。引擎的
     * 「同一輸入永遠同一輸出」契約是回測可重現性的基礎。</p>
     *
     * <p>兩者皆 null 時回傳的標籤不會被使用（該情況下 {@code marketActivity} 為 null，
     * 走的是「資料不足」那一段），此處仍回傳中性字串以維持全函數。</p>
     */
    private static String activityLabel(BigDecimal volumeRatio, BigDecimal turnoverRatio) {
        if (volumeRatio != null && turnoverRatio != null) return "量能與成交金額";
        if (volumeRatio != null) return "量能";
        if (turnoverRatio != null) return "成交金額";
        return "量價";
    }

    private static Double averageAvailable(Double... values) {
        double sum = 0;
        int count = 0;
        if (values != null) {
            for (Double value : values) {
                if (value == null) continue;
                sum += value;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
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
     * 一併引用；V11 也把 J 與 W%R 同組停用，避免同一窄幅震盪被重複計分。</p>
     *
     * <p>資料不足（{@code null}）時視同未觸發，不得因缺值而關閉保護。</p>
     */
    private boolean narrowKdBand(StockInput input) {
        BigDecimal band = input == null ? null : input.kdBandWidthPercent();
        return band != null && band.doubleValue() < KD_BAND_MIN_PERCENT;
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
     *
     * <p>Task 299：極端門檻改為「絕對 ±20% 或自身分位 ≥98／≤2」二擇一，讓低波動標的的
     * 極端保護有機會觸發。中層 OVERBOUGHT／OVERSOLD 維持絕對門檻，不套用分位。</p>
     */
    private TimingState timingOf(StockInput input) {
        boolean overheated = kdHeatOf(input) == KdHeat.OVERHEATED;
        boolean oversold = kdOversold(input);
        Double bias = input.ma60BiasPercent() == null ? null : input.ma60BiasPercent().doubleValue();
        Double pct = input.ma60BiasPercentile() == null ? null : input.ma60BiasPercentile().doubleValue();
        boolean premiumExpensive = input.etfPremiumPct() != null
                && input.etfPremiumPct().doubleValue() >= ETF_PREMIUM_EXPENSIVE;

        // 乖離本身（bias）為 null 時分位不可能有值，extremeHigh/Low 自然為 false，維持不判極端。
        boolean extremeHigh = bias != null && (bias >= BIAS_EXTREME_HIGH || (pct != null && pct >= BIAS_EXTREME_PCT_HIGH));
        boolean extremeLow = bias != null && (bias <= BIAS_EXTREME_LOW || (pct != null && pct <= BIAS_EXTREME_PCT_LOW));

        // 折溢價刻意不納入 EXTREME_OVERBOUGHT：溢價會在一天內收斂，而減碼是不可逆的建議。
        if (overheated && extremeHigh) return TimingState.EXTREME_OVERBOUGHT;
        if (oversold && extremeLow) return TimingState.EXTREME_OVERSOLD;
        if (overheated || premiumExpensive || (bias != null && bias >= BIAS_HIGH)) return TimingState.OVERBOUGHT;
        if (oversold || (bias != null && bias <= BIAS_LOW)) return TimingState.OVERSOLD;
        return TimingState.NEUTRAL;
    }

    /**
     * Candidate timing path.  When normalized volatility is available, only its
     * calibrated magnitude decides the extreme BIAS side; the V12 percentile is
     * deliberately disclosure-only.  Missing sigma falls back to the fixed
     * absolute thresholds, never to percentile action, and is disclosed by the
     * shared observation.
     */
    private TimingState timingOf(
            StockInput input, NormalizedBiasObservation observation, RuleParameters candidate) {
        boolean overheated = kdHeatOf(input) == KdHeat.OVERHEATED;
        boolean oversold = kdOversold(input);
        BigDecimal biasValue = input.ma60BiasPercent();
        double bias = biasValue == null ? 0.0 : biasValue.doubleValue();
        boolean premiumExpensive = input.etfPremiumPct() != null
                && input.etfPremiumPct().doubleValue() >= ETF_PREMIUM_EXPENSIVE;

        boolean extremeHigh;
        boolean extremeLow;
        if (observation != null && observation.available()) {
            double normalized = observation.normalizedBias().doubleValue();
            double upper = candidate.normalizedBiasUpperMultiple().doubleValue();
            double lower = candidate.normalizedBiasLowerMultiple().doubleValue();
            extremeHigh = normalized >= upper;
            extremeLow = normalized <= -lower;
        } else {
            // Explicit fixed-threshold fallback; ma60BiasPercentile is never
            // consulted here because sigma evidence is unavailable.
            extremeHigh = biasValue != null && bias >= BIAS_EXTREME_HIGH;
            extremeLow = biasValue != null && bias <= BIAS_EXTREME_LOW;
        }

        if (overheated && extremeHigh) return TimingState.EXTREME_OVERBOUGHT;
        if (oversold && extremeLow) return TimingState.EXTREME_OVERSOLD;
        if (overheated || premiumExpensive || (biasValue != null && bias >= BIAS_HIGH)) {
            return TimingState.OVERBOUGHT;
        }
        if (oversold || (biasValue != null && bias <= BIAS_LOW)) return TimingState.OVERSOLD;
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

    /**
     * 極端時機由分位路徑（而非絕對門檻）觸發時的專屬揭露（Task 299）。
     *
     * <p>只在「絕對門檻未達」時補這句：絕對門檻已達時 {@link #describeHeat} 與既有的
     * {@code actionFor}／{@code profitTakingConfirmed} 文案已足以說明極端狀態，重複標註
     * 分位反而模糊「這次是相對自己貴，不是真的乖離 20%」的重點。</p>
     */
    private void describeBiasPercentileExtreme(
            TimingState timing, StockInput input, List<String> reasons, List<String> risks) {
        BigDecimal bias = input.ma60BiasPercent();
        BigDecimal percentile = input.ma60BiasPercentile();
        if (bias == null || percentile == null) return;
        if (timing == TimingState.EXTREME_OVERBOUGHT && bias.doubleValue() < BIAS_EXTREME_HIGH) {
            risks.add("季線乖離 " + fmt1(bias) + "% 已位於自身近一年分布的第 "
                    + Math.round(percentile.doubleValue()) + " 百分位，極端超買以自身分布判定。 ");
        } else if (timing == TimingState.EXTREME_OVERSOLD && bias.doubleValue() > BIAS_EXTREME_LOW) {
            reasons.add("季線乖離 " + fmt1(bias) + "% 已位於自身近一年分布的第 "
                    + Math.round(percentile.doubleValue()) + " 百分位，極端超賣以自身分布判定。 ");
        }
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
        // 基本面多項惡化時不主動接刀；這只擋買，不引發賣出。
        if (fundamentalDeteriorating(input)) return false;
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
        BigDecimal stability = input.completedChangePercent();
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
     * 動作映射：<b>三軌各自</b>以自己的分數分層，再套止跌／追高／基本面閘門與對稱高低檔保護
     * （Task 356.8a／356.8b）。四項保護在三軌各自成立，不得只在其中兩軌實作。
     *
     * <p>{@code TimingState}／{@code KdHeat}／{@code profitTakingConfirmed}／
     * {@code kdDeadCross}／{@code longTermBroken} 五個判定<b>維持每檔只算一次、三軌共用</b>，
     * 公式與門檻一律不改；Task 356 新增的日K 棒與週K 因子<b>只進分數，不進這五個判定</b>
     * ——把新指標接進極端態判定會同時改動保護門檻，本任務沒有回測依據支持那麼做（356.8c）。</p>
     */
    private Action actionFor(StockInput input, int score, TimingState timing,
                             boolean profitTaking,
                             List<String> risks, List<String> reasons,
                             RuleParameters.ActionThresholds thresholds) {
        // 分批試單優先於分數映射：這類標的的短線分數必然偏低（剛跌深），
        // 若先走分數映射會被判成減碼／出場，與「長線佳、可分批進場」的判斷自相矛盾。
        if (qualifiesForTrialBuy(input)) {
            risks.add("逆勢試單的本質是接刀，僅適合小額分批；長線判斷失準時虧損可能持續擴大。 ");
            if (equityMarketApplies(input) && input.marketRegime() == MarketRegime.RISK_OFF) {
                risks.add("大盤為 RISK_OFF，本訊號只限小額試單，不得視為一般買進或加碼候選。 ");
            }
            return Action.TRIAL_BUY;
        }

        // 高檔獲利了結須 KD／MACD／下跌爆量三類至少兩類轉弱；KD 死叉單獨不得再賣。
        if (profitTaking) {
            reasons.add("已達極端高檔，且 KD／MACD／量價三類轉弱證據至少兩項成立，獲利了結獲得確認。 ");
            risks.add("本訊號只描述高檔與多項轉弱狀態，不預測後續漲跌；分批處理可降低單點判斷風險。 ");
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
        boolean stillFalling = input.completedChangePercent() == null
                || input.completedChangePercent().signum() < 0;
        boolean chasedDailyMove = input.completedChangePercent() != null
                && input.completedChangePercent().compareTo(BigDecimal.valueOf(5)) >= 0;
        boolean overbought = timing == TimingState.OVERBOUGHT
                || timing == TimingState.EXTREME_OVERBOUGHT;
        boolean fundamentalsBlockBuy = fundamentalDeteriorating(input);
        if (fundamentalsBlockBuy) {
            risks.add("基本面多項惡化，三軌的買進／加碼／試單閘門關閉；本條件不直接產生賣出。 ");
        }

        boolean buyGate = marketAllowsBuy
                && input.ma20Confirmation() == Confirmation.ABOVE
                && input.ma60Confirmation() == Confirmation.ABOVE
                && !kdOverheated
                && !fxExpensive
                && !premiumExpensive
                && !stillFalling
                && !chasedDailyMove
                && !overbought
                && !fundamentalsBlockBuy;
        if (score >= thresholds.buy() && buyGate) {
            return input.held() ? Action.ADD_CANDIDATE : Action.BUY_CANDIDATE;
        }
        if (score >= thresholds.hold()) return input.held() ? Action.HOLD : Action.WATCH;
        if (score >= thresholds.caution()) return input.held() ? Action.HOLD_CAUTION : Action.WAIT;

        // ── 第 3 步：不得殺低（需求 3）。對稱於買方的「超買否決買進」。
        Action mapped = score >= thresholds.reduce()
                ? (input.held() ? Action.REDUCE_CANDIDATE : Action.AVOID)
                : (input.held() ? Action.EXIT_CANDIDATE : Action.AVOID);
        if (timing == TimingState.EXTREME_OVERSOLD) {
            reasons.add("已達極端超賣（KD 深度超賣且明顯低於季線），此位置不建議追殺出場。 ");
            if (longTermBroken(input)) {
                risks.add("長期結構仍偏弱，但 V11 的極端超賣保護不再因年線破壞而失效，避免低點殺出。 ");
            }
            risks.add("本訊號只描述當前位置，不預測反彈時點；後續條件改變仍應重新評估。 ");
            return input.held() ? Action.HOLD_CAUTION : Action.WAIT;
        }
        return mapped;
    }

    /**
     * 長期結構已完全破壞：年線兩日跌破<b>且</b>位於 52 週區間最低段。
     *
     * <p>V11 僅供風險揭露與回測，不再取消極端超賣保護；判準仍與年線結構要求同源。</p>
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
