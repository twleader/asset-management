package com.steven.assets.service;

import com.steven.assets.dto.MarketAnalysisResult;
import com.steven.assets.model.News;
import com.steven.assets.util.MarketZones;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 今日股市分析的本機規則引擎（Requirement 78 / Task 337）——LLM 成本歸零的替代路徑。
 *
 * <p><b>純函式邊界</b>：本類別不注入任何 Repository、不做 IO、不讀時鐘。所有輸入由
 * {@link MarketAnalysisService} 載入後傳入，故評分邏輯可在不啟動 Spring context、不連資料庫的
 * 情況下單元測試（CLAUDE.md 架構規範）。</p>
 *
 * <p><b>⚠ 規則權重是先驗設定，不是回測校準的結果。</b>本引擎的加權分數、門檻與訊號權重均由既有
 * 技術分析慣例直接設定，<b>未經任何樣本外回測驗證</b>。本專案雖已有回測基礎建設
 * （{@code RadarBacktestExecution} / {@code RadarWalkForwardPlan} /
 * {@code TradingRadarCalibrationSelector}），但將其套用於大盤日內方向預測是另一個量級的工作，
 * 明確不在本引擎範圍。</p>
 *
 * <p><b>任何人不得將本引擎的 {@code confidence} 解讀為經驗勝率</b>——它衡量的是「本次各訊號彼此
 * 同向的程度」（再乘上資料完整度折減），<b>不是</b>「歷史上這樣的訊號組合有多常猜對」。</p>
 *
 * <p><b>相對 LLM 的已知退化（明示接受，不在程式碼或畫面上粉飾）</b>：{@code summary} 由模板拼接、
 * {@code newsHighlights} 由關鍵詞權重排序挑選——只回答「哪幾則新聞最可能相關」，
 * <b>不回答「它為什麼影響台股」</b>。跨領域新聞因果推理仍只有 LLM 路徑做得到。</p>
 */
@Service
public class LocalMarketAnalysisEngine {

    /**
     * 規則版本，寫入 {@code daily_market_analysis.model}。
     * <b>規則權重或門檻日後調整時須提升此版本號</b>——理由與 {@code TradingRadarRuleEngine.RULE_VERSION}
     * 相同：否則歷史紀錄無法分辨是哪一版規則產生的判斷。
     */
    public static final String RULE_VERSION = "local-rule-engine:v1";

    // ===== 訊號權重（先驗設定，非回測校準；調整時須提升 RULE_VERSION）=====

    /** 均線位置與排列：中期方向的主軸，故為台股技術面最高權重。 */
    private static final double W_TW_MA = 2.0;
    /** KD 與金叉／死叉：短期轉折訊號。 */
    private static final double W_TW_KD = 1.5;
    /** MACD OSC 方向：中期動能，較 KD 慢但雜訊少。 */
    private static final double W_TW_MACD = 1.2;
    /** RSI 超買超賣：均值回歸的反向訊號，權重低於順勢類。 */
    private static final double W_TW_RSI = 1.0;
    /** 量價配合：只作為方向的確認／背離，不獨立定方向，故權重最低。 */
    private static final double W_TW_VOLUME = 0.8;

    /**
     * 費半 SOX 權重<b>須高於其餘美股指數</b>：台股半導體權值占比高，
     * 既有 LLM prompt 也已將費半列為關鍵輸入（{@code MarketAnalysisService.US_INDEX_CODES}）。
     */
    private static final double W_US_SOX = 1.8;
    private static final double W_US_IXIC = 1.0;
    private static final double W_US_SPX = 0.8;

    /**
     * 外資權重<b>須高於投信與自營</b>：既有 LLM prompt 已將「外資、自營商連日賣超」列為關鍵因素，
     * 而外資在台股的買賣超規模與持股比重都遠高於另兩者。
     */
    private static final double W_FOREIGN_LATEST = 1.6;
    private static final double W_FOREIGN_RECENT3 = 1.2;
    private static final double W_TRUST_LATEST = 0.6;
    private static final double W_DEALER_LATEST = 0.4;

    // ===== 訊號名稱（完整度折減的分母＝本清單長度）=====

    private static final String SIG_TW_MA = "TW_MA";
    private static final String SIG_TW_KD = "TW_KD";
    private static final String SIG_TW_MACD = "TW_MACD";
    private static final String SIG_TW_RSI = "TW_RSI";
    private static final String SIG_TW_VOLUME = "TW_VOLUME";
    private static final String SIG_US_PREFIX = "US_";
    private static final String SIG_FOREIGN_LATEST = "FOREIGN_LATEST";
    private static final String SIG_FOREIGN_RECENT3 = "FOREIGN_RECENT3";
    private static final String SIG_TRUST_LATEST = "TRUST_LATEST";
    private static final String SIG_DEALER_LATEST = "DEALER_LATEST";

    /** 訊號總數（{@code confidence} 的資料完整度折減分母）。 */
    static final List<String> ALL_SIGNAL_KEYS = List.of(
            SIG_TW_MA, SIG_TW_KD, SIG_TW_MACD, SIG_TW_RSI, SIG_TW_VOLUME,
            SIG_US_PREFIX + "SOX", SIG_US_PREFIX + "IXIC", SIG_US_PREFIX + "SPX",
            SIG_FOREIGN_LATEST, SIG_FOREIGN_RECENT3, SIG_TRUST_LATEST, SIG_DEALER_LATEST);

    // ===== 門檻（具名常數，不得散落魔術數字）=====

    /** 正規化分數（[-100,+100]）的對稱多空門檻：|score| < 此值即 NEUTRAL。 */
    private static final double BIAS_THRESHOLD = 15.0;
    /** 單一訊號方向分的絕對值上限。 */
    private static final double MAX_DIRECTION = 2.0;

    /** KD 超買／超賣界。 */
    private static final double KD_OVERBOUGHT = 80.0;
    private static final double KD_OVERSOLD = 20.0;
    /** RSI 超買／超賣界。 */
    private static final double RSI_OVERBOUGHT = 70.0;
    private static final double RSI_OVERSOLD = 30.0;
    /** RSI 非極端區的線性斜率分母：距 50 每 20 點得 1.0 方向分。 */
    private static final double RSI_NEUTRAL_SPAN = 20.0;

    /** 量能比值：≥ 此值視為明顯量增，≤ 其倒數側視為量縮。 */
    private static final double VOLUME_SURGE_RATIO = 1.2;
    private static final double VOLUME_SHRINK_RATIO = 0.8;
    /** 量能均值視窗（交易日）。 */
    private static final int VOLUME_MA_DAYS = 20;

    /** 美股單日漲跌幅換算方向分的分母：每 1.0% 得 1.0 方向分（上限 ±2）。 */
    private static final double US_PCT_PER_POINT = 1.0;

    /** 法人買賣超換算方向分的分母（單位：億元）；規模差異反映在各自的分母上。 */
    private static final double FOREIGN_YI_PER_POINT = 150.0;
    private static final double FOREIGN_RECENT3_YI_PER_POINT = 300.0;
    private static final double TRUST_YI_PER_POINT = 50.0;
    private static final double DEALER_YI_PER_POINT = 30.0;

    /** 一億元（法人買賣超與大盤成交金額的原始單位皆為<b>元</b>，顯示成億元須除以此值）。 */
    private static final BigDecimal ONE_HUNDRED_MILLION = new BigDecimal("100000000");

    /** newsHighlights 取樣上下界（3～6 則）。 */
    private static final int NEWS_MIN = 3;
    private static final int NEWS_MAX = 6;

    /**
     * RSI 期數，<b>僅供敘述文字標示用</b>——數值一律取自
     * {@link TechnicalIndicatorService.ExtendedIndicators#rsi10()}（全站唯一一份 RSI 實作），
     * 本引擎不自行計算 RSI。此值必須與該欄位的期數（10）一致，否則畫面標示會與實際數字不符。
     */
    private static final int RSI_PERIOD = 10;

    /**
     * 標題財經關鍵詞權重（{@code newsHighlights} 第 2 排序鍵）。
     * <b>這是字串比對，不是語意理解</b>——只回答「哪幾則最可能相關」，不回答「為什麼影響台股」。
     */
    private static final Map<String, Integer> NEWS_KEYWORD_WEIGHTS = Map.ofEntries(
            Map.entry("台積電", 3), Map.entry("外資", 3), Map.entry("三大法人", 3),
            Map.entry("半導體", 3), Map.entry("費城半導體", 3), Map.entry("加權指數", 3),
            Map.entry("台股", 3), Map.entry("聯準會", 3), Map.entry("FED", 3),
            Map.entry("升息", 3), Map.entry("降息", 3),
            Map.entry("法人", 2), Map.entry("大盤", 2), Map.entry("成交量", 2),
            Map.entry("成交金額", 2), Map.entry("利率", 2), Map.entry("通膨", 2),
            Map.entry("CPI", 2), Map.entry("關稅", 2), Map.entry("匯率", 2),
            Map.entry("新台幣", 2), Map.entry("那斯達克", 2), Map.entry("道瓊", 2),
            Map.entry("標普", 2), Map.entry("財報", 2), Map.entry("營收", 2),
            Map.entry("輝達", 2), Map.entry("NVIDIA", 2), Map.entry("AI", 2),
            Map.entry("美元", 1), Map.entry("出口", 1), Map.entry("訂單", 1),
            Map.entry("庫存", 1), Map.entry("期貨", 1), Map.entry("櫃買", 1));

    /**
     * 法人買賣超的傳輸用值：金額單位為「元」（非億元），
     * 由 {@link MarketAnalysisService} 自 {@code twse_institutional_daily} 選列後傳入。
     */
    public record InstitutionalNet(
            java.time.LocalDate tradingDate,
            java.math.BigDecimal foreignNet,
            java.math.BigDecimal trustNet,
            java.math.BigDecimal dealerNet,
            java.math.BigDecimal totalNet) {}

    /**
     * 一個實際參與計分的訊號。
     *
     * @param direction [-2,+2] 的方向分；<b>0 代表「有資料但中性」</b>。
     *                  資料不足的訊號根本不會產生 Signal（不計分、不進 keyFactors），
     *                  故「沒有資料」與「訊號中性」在總分上可分辨。
     * @param factor    非零方向分者必附的中文敘述（含實際數值），為 keyFactors 的一條。
     */
    private record Signal(String key, double direction, double weight, String factor) {}

    /**
     * 依既有規則對「今日台股走向」評分，回傳與 LLM 路徑同型的 {@link MarketAnalysisResult}。
     *
     * <p><b>價基一致性（本任務的硬約束）</b>：{@code taiexCloses}／{@code taiexTradeValues}／
     * {@code taiexIndicators}／{@code taiexIndicatorsPrevious} 四者<b>必須來自同一份「純 DB 完成日」
     * 序列</b>。呼叫端（{@link MarketAnalysisService}）以
     * {@link TechnicalIndicatorService#computeFromSeries(List)} 餵入該序列取得 MA／KD／擴充指標，
     * <b>不得</b>改用 {@code computeAll("0000","台股")}——後者會把 Redis 即時價合成一列今日 bar 併入，
     * 盤中觸發時就會產生「今天的指標配昨天的收盤」的混用價基。</p>
     *
     * @param taiexCloses        {@code {epochDay, close}}，由舊到新
     * @param taiexTradeValues   {@code {epochDay, tradeValue}}（單位：元），由舊到新
     * @param usCloses           key = DJI/SPX/IXIC/SOX，值為 {@code {epochDay, close}} 由舊到新
     * @param taiexIndicators    當期大盤指標（MA／KD／擴充指標）
     * @param taiexIndicatorsPrevious 同一份序列<b>砍掉最新一筆</b>後的指標，供需要「方向」的訊號
     *                                （目前只有 MACD OSC）比較當期與前一期；可為 null（資料不足）
     * @param institutionalLatest    最新交易日法人買賣超；可為 null（當日資料尚未產生）
     * @param institutionalRecent3   最近三個交易日（由舊到新）的法人買賣超，供累計訊號
     */
    public MarketAnalysisResult evaluate(
            LocalDate analysisDate,
            List<double[]> taiexCloses,
            List<double[]> taiexTradeValues,
            Map<String, List<double[]>> usCloses,
            TechnicalIndicatorService.FullIndicators taiexIndicators,
            TechnicalIndicatorService.FullIndicators taiexIndicatorsPrevious,
            InstitutionalNet institutionalLatest,
            List<InstitutionalNet> institutionalRecent3,
            List<News> recentNews) {

        List<double[]> closes = taiexCloses == null ? List.of() : taiexCloses;
        List<double[]> volumes = taiexTradeValues == null ? List.of() : taiexTradeValues;
        Map<String, List<double[]>> us = usCloses == null ? Map.of() : usCloses;
        TechnicalIndicatorService.FullIndicators ind = taiexIndicators == null
                ? TechnicalIndicatorService.FullIndicators.EMPTY : taiexIndicators;
        TechnicalIndicatorService.FullIndicators prevInd = taiexIndicatorsPrevious == null
                ? TechnicalIndicatorService.FullIndicators.EMPTY : taiexIndicatorsPrevious;

        List<Signal> signals = new ArrayList<>();
        List<String> twFragments = new ArrayList<>();
        List<String> volumeFragments = new ArrayList<>();
        List<String> usFragments = new ArrayList<>();
        List<String> chipFragments = new ArrayList<>();

        addIfPresent(signals, maSignal(closes, ind, twFragments));
        addIfPresent(signals, kdSignal(ind, twFragments));
        addIfPresent(signals, macdSignal(ind, prevInd, twFragments));
        addIfPresent(signals, rsiSignal(ind, twFragments));
        addIfPresent(signals, volumeSignal(closes, volumes, volumeFragments));

        addIfPresent(signals, usSignal("SOX", "費城半導體", W_US_SOX, us, usFragments));
        addIfPresent(signals, usSignal("IXIC", "那斯達克綜合", W_US_IXIC, us, usFragments));
        addIfPresent(signals, usSignal("SPX", "標普 500", W_US_SPX, us, usFragments));

        addIfPresent(signals, institutionalLatestSignal(institutionalLatest, chipFragments));
        addIfPresent(signals, institutionalRecent3Signal(institutionalRecent3, chipFragments));
        addIfPresent(signals, trustSignal(institutionalLatest, chipFragments));
        addIfPresent(signals, dealerSignal(institutionalLatest, chipFragments));

        double weightedSum = 0;
        double totalWeight = 0;
        for (Signal s : signals) {
            weightedSum += s.direction() * s.weight();
            totalWeight += s.weight();
        }
        double normalized = totalWeight == 0 ? 0
                : clamp(weightedSum / (MAX_DIRECTION * totalWeight) * 100.0, -100.0, 100.0);
        String bias = biasOf(normalized);
        int confidence = confidenceOf(signals, bias);

        List<String> keyFactors = new ArrayList<>();
        for (Signal s : signals) {
            if (s.direction() != 0 && s.factor() != null) {
                keyFactors.add(s.factor());
            }
        }
        if (keyFactors.isEmpty()) {
            keyFactors.add(signals.isEmpty()
                    ? "本次無任何可計算的訊號（台股日線、美股日線與法人買賣超皆資料不足），未產生方向判斷。"
                    : "本次 " + signals.size() + " 項可計算訊號方向分皆為 0（中性），未出現偏多或偏空的具體證據。");
        }

        List<String> twAndVolume = concat(twFragments, volumeFragments);
        String twContext = twAndVolume.isEmpty() ? "（台股日線資料不足，本次未產生技術面敘述）"
                : String.join("；", twAndVolume) + "。";
        String usContext = usFragments.isEmpty() ? "（美股日線資料不足，本次未產生連動敘述）"
                : String.join("；", usFragments) + "。";
        String summary = buildSummary(analysisDate, bias, normalized, confidence,
                signals.size(), twFragments, volumeFragments, usFragments, chipFragments);

        MarketAnalysisResult.FactorGroups factorGroups = new MarketAnalysisResult.FactorGroups(
                List.copyOf(twFragments), List.copyOf(volumeFragments),
                List.copyOf(usFragments), List.copyOf(chipFragments));

        return new MarketAnalysisResult(bias, confidence, summary, keyFactors,
                selectNews(recentNews), twContext, usContext, factorGroups);
    }

    private static void addIfPresent(List<Signal> signals, Signal s) {
        if (s != null) {
            signals.add(s);
        }
    }

    /** 回傳 {@code a} 接 {@code b} 的新 {@code ArrayList}，不修改任一輸入 list。 */
    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    // ===== 台股技術面 =====

    /**
     * (a) 收盤對 MA5／MA20／MA60／MA240 的位置與均線排列。
     * MA 一律取自 {@link TechnicalIndicatorService.FullIndicators}（同一份核心），本引擎不重算均線。
     */
    private Signal maSignal(List<double[]> closes, TechnicalIndicatorService.FullIndicators ind,
                            List<String> twFragments) {
        Double close = lastValue(closes);
        List<String> above = new ArrayList<>();
        List<String> below = new ArrayList<>();
        // 由短到長：週線 MA5、月線 MA20、季線 MA60、年線 MA240
        String[] labels = {"週線(MA5)", "月線(MA20)", "季線(MA60)", "年線(MA240)"};
        BigDecimal[] mas = {ind.weeklyMa(), ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa()};
        List<String> parts = new ArrayList<>();
        int available = 0;
        if (close == null) {
            return null;   // 無收盤 → 資料不足，不計分
        }
        for (int i = 0; i < mas.length; i++) {
            if (mas[i] == null) continue;
            available++;
            double ma = mas[i].doubleValue();
            if (close >= ma) {
                above.add(labels[i]);
            } else {
                below.add(labels[i]);
            }
            parts.add(labels[i] + " " + num(ma));
        }
        if (available == 0) {
            return null;   // 全部均線暖機不足 → 資料不足，不計分
        }
        double direction = MAX_DIRECTION * (above.size() - below.size()) / (double) available;

        // 均線排列加成：MA5>MA20>MA60 為多頭排列，反之為空頭排列
        String alignment = "";
        if (ind.weeklyMa() != null && ind.monthlyMa() != null && ind.quarterlyMa() != null) {
            double ma5 = ind.weeklyMa().doubleValue();
            double ma20 = ind.monthlyMa().doubleValue();
            double ma60 = ind.quarterlyMa().doubleValue();
            if (ma5 > ma20 && ma20 > ma60) {
                direction += 0.5;
                alignment = "、均線呈多頭排列";
            } else if (ma5 < ma20 && ma20 < ma60) {
                direction -= 0.5;
                alignment = "、均線呈空頭排列";
            }
        }
        direction = clamp(direction, -MAX_DIRECTION, MAX_DIRECTION);

        String desc = "台股收盤 " + num(close) + " 點，位於 "
                + (above.isEmpty() ? "" : String.join("、", above) + " 之上")
                + (above.isEmpty() || below.isEmpty() ? "" : "、")
                + (below.isEmpty() ? "" : String.join("、", below) + " 之下")
                + alignment + "（" + String.join("、", parts) + "）";
        twFragments.add(desc);
        return new Signal(SIG_TW_MA, direction, W_TW_MA, desc);
    }

    /** (b) K／D 值與金叉／死叉；K/D 一律取自 {@link TechnicalIndicatorService}，本引擎不重算 KD。 */
    private Signal kdSignal(TechnicalIndicatorService.FullIndicators ind, List<String> twFragments) {
        if (ind.k() == null || ind.d() == null) {
            return null;   // KD 暖機不足 → 不計分
        }
        double k = ind.k().doubleValue();
        double d = ind.d().doubleValue();
        double direction = k > d ? 1.0 : (k < d ? -1.0 : 0.0);

        String cross = "";
        if (ind.previousK() != null && ind.previousD() != null) {
            double pk = ind.previousK().doubleValue();
            double pd = ind.previousD().doubleValue();
            if (pk <= pd && k > d) {
                direction += 0.5;
                cross = "，形成黃金交叉";
            } else if (pk >= pd && k < d) {
                direction -= 0.5;
                cross = "，形成死亡交叉";
            }
        }
        String zone = "";
        if (k >= KD_OVERBOUGHT) {
            direction -= 0.5;
            zone = "，K 值已入超買區";
        } else if (k <= KD_OVERSOLD) {
            direction += 0.5;
            zone = "，K 值已入超賣區";
        }
        direction = clamp(direction, -MAX_DIRECTION, MAX_DIRECTION);

        String desc = "KD 於 K=" + num(k) + "／D=" + num(d) + cross + zone;
        twFragments.add(desc);
        return new Signal(SIG_TW_KD, direction, W_TW_KD, desc);
    }

    /**
     * (c) MACD OSC 方向。
     *
     * <p><b>本引擎不重算 MACD</b>：{@code osc} 一律取自
     * {@link TechnicalIndicatorService.ExtendedIndicators#osc()}——全站唯一一份 MACD 實作
     * （{@code TechnicalIndicatorService.macdSeriesAsc}，12／26／9、SMA seed、價基為台股慣例的
     * DI ＝ (最高＋最低＋2×收盤)/4）。</p>
     *
     * <p>本訊號需要的是「OSC 的方向」（當期 vs 前一期），而 {@link TechnicalIndicatorService.FullIndicators}
     * 只提供單點值；解法是由呼叫端（{@link MarketAnalysisService#generateLocal}）對同一份序列與其
     * 「砍掉最新一筆」的子序列各跑一次 {@code computeFromSeries}，把兩期指標一併傳入。
     * 多算一次的成本由 service 端承擔，引擎維持純函式且不持有第二份 MACD 遞迴。</p>
     */
    private Signal macdSignal(TechnicalIndicatorService.FullIndicators cur,
                              TechnicalIndicatorService.FullIndicators prev,
                              List<String> twFragments) {
        BigDecimal curOsc = oscOf(cur);
        BigDecimal prevOsc = oscOf(prev);
        if (curOsc == null || prevOsc == null) {
            return null;   // 暖機不足（需 26+9 期以上）或未提供前一期 → 不計分
        }
        double current = curOsc.doubleValue();
        double previous = prevOsc.doubleValue();
        double sign = current > 0 ? 1.0 : (current < 0 ? -1.0 : 0.0);
        double momentum = current > previous ? 1.0 : (current < previous ? -1.0 : 0.0);
        double direction = clamp(sign + momentum, -MAX_DIRECTION, MAX_DIRECTION);

        String desc = "MACD OSC（DI 價基）" + num(current) + "（前一交易日 " + num(previous) + "）"
                + (current >= 0 ? "位於零軸之上" : "位於零軸之下")
                + (momentum > 0 ? "且動能轉強" : momentum < 0 ? "且動能轉弱" : "且動能持平");
        twFragments.add(desc);
        return new Signal(SIG_TW_MACD, direction, W_TW_MACD, desc);
    }

    private static BigDecimal oscOf(TechnicalIndicatorService.FullIndicators ind) {
        return (ind == null || ind.extended() == null) ? null : ind.extended().osc();
    }

    /**
     * (d) RSI 超買超賣。
     *
     * <p><b>本引擎不重算 RSI</b>：數值一律取自
     * {@link TechnicalIndicatorService.ExtendedIndicators#rsi10()}——全站唯一一份 RSI 實作
     * （{@code TechnicalIndicatorService.rsiSeriesAsc}，10 期 Wilder 平滑、收盤價基）。
     * {@code null} 代表暖機不足，該訊號不計分、不進 {@code keyFactors}。</p>
     */
    private Signal rsiSignal(TechnicalIndicatorService.FullIndicators ind, List<String> twFragments) {
        BigDecimal rsi10 = (ind == null || ind.extended() == null) ? null : ind.extended().rsi10();
        if (rsi10 == null) {
            return null;   // 暖機不足 → 不計分
        }
        double v = rsi10.doubleValue();
        double direction;
        String zone;
        if (v >= RSI_OVERBOUGHT) {
            direction = -1.5;
            zone = "已進入超買區（≥" + num(RSI_OVERBOUGHT) + "），短線追高風險升高";
        } else if (v <= RSI_OVERSOLD) {
            direction = 1.5;
            zone = "已進入超賣區（≤" + num(RSI_OVERSOLD) + "），短線超跌反彈機會升高";
        } else {
            direction = clamp((v - 50.0) / RSI_NEUTRAL_SPAN, -1.0, 1.0);
            zone = v >= 50 ? "位於中性偏強區" : "位於中性偏弱區";
        }
        String desc = "RSI" + RSI_PERIOD + "（Wilder 平滑）" + num(v) + "，" + zone;
        twFragments.add(desc);
        return new Signal(SIG_TW_RSI, direction, W_TW_RSI, desc);
    }

    /** (e) 量能：當日成交金額對近 {@value #VOLUME_MA_DAYS} 交易日均值的比值，並與當日漲跌方向配對（量價配合）。 */
    private Signal volumeSignal(List<double[]> closes, List<double[]> volumes, List<String> volumeFragments) {
        if (volumes.size() < VOLUME_MA_DAYS + 1 || closes.size() < 2) {
            return null;   // 量能視窗或漲跌幅資料不足 → 不計分
        }
        int n = volumes.size();
        double latest = volumes.get(n - 1)[1];
        double sum = 0;
        for (int i = n - 1 - VOLUME_MA_DAYS; i < n - 1; i++) {
            sum += volumes.get(i)[1];
        }
        double mean = sum / VOLUME_MA_DAYS;
        if (mean <= 0) {
            return null;
        }
        double ratio = latest / mean;

        int cn = closes.size();
        double prevClose = closes.get(cn - 2)[1];
        double lastClose = closes.get(cn - 1)[1];
        if (prevClose == 0) {
            return null;
        }
        double changePct = (lastClose - prevClose) / prevClose * 100.0;
        double priceSign = changePct > 0 ? 1.0 : (changePct < 0 ? -1.0 : 0.0);

        double direction;
        String pattern;
        if (ratio >= VOLUME_SURGE_RATIO) {
            direction = priceSign * 1.5;
            pattern = priceSign > 0 ? "量價齊揚" : priceSign < 0 ? "量增價跌、賣壓沉重" : "量增價平";
        } else if (ratio <= VOLUME_SHRINK_RATIO) {
            direction = -priceSign * 0.5;
            pattern = priceSign > 0 ? "量縮價漲、追價意願不足" : priceSign < 0 ? "量縮價跌、賣壓減輕" : "量縮價平";
        } else {
            direction = priceSign * 0.5;
            pattern = "量能持平";
        }
        direction = clamp(direction, -MAX_DIRECTION, MAX_DIRECTION);

        String desc = "台股成交金額 " + num(latest / ONE_HUNDRED_MILLION.doubleValue()) + " 億元，為近 "
                + VOLUME_MA_DAYS + " 日均量的 " + num(ratio) + " 倍，同日指數 "
                + pct(changePct) + "，" + pattern;
        volumeFragments.add(desc);
        return new Signal(SIG_TW_VOLUME, direction, W_TW_VOLUME, desc);
    }

    // ===== 美股連動 =====

    private Signal usSignal(String code, String name, double weight,
                            Map<String, List<double[]>> usCloses, List<String> usFragments) {
        List<double[]> series = usCloses.get(code);
        if (series == null || series.size() < 2) {
            return null;   // 缺該指數或不足兩個交易日 → 不計分
        }
        int n = series.size();
        double prev = series.get(n - 2)[1];
        double last = series.get(n - 1)[1];
        if (prev == 0) {
            return null;
        }
        double changePct = (last - prev) / prev * 100.0;
        double direction = clamp(changePct / US_PCT_PER_POINT, -MAX_DIRECTION, MAX_DIRECTION);

        String desc = name + "（" + code + "）最新交易日收 " + num(last) + "，較前一交易日 " + pct(changePct);
        usFragments.add(desc);
        return new Signal(SIG_US_PREFIX + code, direction, weight, desc);
    }

    // ===== 籌碼面（金額單位為「元」，顯示成億元須除以 1e8）=====

    private Signal institutionalLatestSignal(InstitutionalNet latest, List<String> chipFragments) {
        if (latest == null || latest.foreignNet() == null) {
            return null;   // 當日法人資料尚未產生 → 不計分
        }
        double foreignYi = toYi(latest.foreignNet());
        double direction = clamp(foreignYi / FOREIGN_YI_PER_POINT, -MAX_DIRECTION, MAX_DIRECTION);
        String desc = "外資於 " + latest.tradingDate() + " " + netWord(foreignYi) + " "
                + num(Math.abs(foreignYi)) + " 億元";
        chipFragments.add(desc);
        return new Signal(SIG_FOREIGN_LATEST, direction, W_FOREIGN_LATEST, desc);
    }

    /**
     * 近三個交易日外資累計買賣超，另附投信／自營當日買賣超訊號。
     * <b>僅一個交易日時不計分</b>——此時累計值與「最新日」訊號完全相同，會變成同一事實灌兩份權重。
     */
    private Signal institutionalRecent3Signal(List<InstitutionalNet> recent3, List<String> chipFragments) {
        if (recent3 == null || recent3.size() < 2) {
            return null;
        }
        double sum = 0;
        int days = 0;
        for (InstitutionalNet n : recent3) {
            if (n == null || n.foreignNet() == null) continue;
            sum += toYi(n.foreignNet());
            days++;
        }
        if (days < 2) {
            return null;
        }
        double direction = clamp(sum / FOREIGN_RECENT3_YI_PER_POINT, -MAX_DIRECTION, MAX_DIRECTION);
        String desc = "外資近 " + days + " 個交易日累計" + netWord(sum) + " " + num(Math.abs(sum)) + " 億元";
        chipFragments.add(desc);
        return new Signal(SIG_FOREIGN_RECENT3, direction, W_FOREIGN_RECENT3, desc);
    }

    /** 投信當日買賣超（權重 {@value #W_TRUST_LATEST} 刻意低於外資的 {@value #W_FOREIGN_LATEST}）。 */
    private Signal trustSignal(InstitutionalNet latest, List<String> chipFragments) {
        if (latest == null || latest.trustNet() == null) {
            return null;
        }
        double yi = toYi(latest.trustNet());
        double direction = clamp(yi / TRUST_YI_PER_POINT, -MAX_DIRECTION, MAX_DIRECTION);
        String desc = "投信於 " + latest.tradingDate() + " " + netWord(yi) + " " + num(Math.abs(yi)) + " 億元";
        chipFragments.add(desc);
        return new Signal(SIG_TRUST_LATEST, direction, W_TRUST_LATEST, desc);
    }

    /** 自營商當日買賣超（權重 {@value #W_DEALER_LATEST} 刻意低於外資的 {@value #W_FOREIGN_LATEST}）。 */
    private Signal dealerSignal(InstitutionalNet latest, List<String> chipFragments) {
        if (latest == null || latest.dealerNet() == null) {
            return null;
        }
        double yi = toYi(latest.dealerNet());
        double direction = clamp(yi / DEALER_YI_PER_POINT, -MAX_DIRECTION, MAX_DIRECTION);
        String desc = "自營商於 " + latest.tradingDate() + " " + netWord(yi) + " " + num(Math.abs(yi)) + " 億元";
        chipFragments.add(desc);
        return new Signal(SIG_DEALER_LATEST, direction, W_DEALER_LATEST, desc);
    }

    private static String netWord(double yi) {
        return yi >= 0 ? "買超" : "賣超";
    }

    /** 元 → 億元。 */
    private static double toYi(BigDecimal amountInDollars) {
        return amountInDollars.divide(ONE_HUNDRED_MILLION, java.math.MathContext.DECIMAL64).doubleValue();
    }

    // ===== bias / confidence =====

    private static String biasOf(double normalized) {
        if (normalized >= BIAS_THRESHOLD) return "BULLISH";
        if (normalized <= -BIAS_THRESHOLD) return "BEARISH";
        return "NEUTRAL";
    }

    /**
     * {@code confidence}＝「參與計分訊號中方向與最終 bias 相同者的加權占比」×「參與計分訊號數 ÷ 訊號總數」。
     *
     * <p><b>刻意不由總分絕對值直接換算</b>：那會讓「單一極端訊號」（|score| 可達 100 但只有一項證據）
     * 與「多訊號一致」得到相同信心。本式的完整度折減使前者的信心明顯低於後者。</p>
     *
     * <p>bias 為 NEUTRAL 時，「同向」定義為方向分恰為 0 的訊號——多空互相抵銷而得到的 NEUTRAL
     * 因此信心偏低，訊號本身就中性而得到的 NEUTRAL 才有高信心。</p>
     */
    private static int confidenceOf(List<Signal> signals, String bias) {
        if (signals.isEmpty()) {
            return 0;
        }
        double agreeing = 0;
        double total = 0;
        for (Signal s : signals) {
            total += s.weight();
            boolean agree = switch (bias) {
                case "BULLISH" -> s.direction() > 0;
                case "BEARISH" -> s.direction() < 0;
                default -> s.direction() == 0;
            };
            if (agree) {
                agreeing += s.weight();
            }
        }
        double agreement = total == 0 ? 0 : agreeing / total;
        double completeness = signals.size() / (double) ALL_SIGNAL_KEYS.size();
        return (int) Math.round(clamp(agreement * completeness * 100.0, 0.0, 100.0));
    }

    // ===== 敘述 =====

    private static String buildSummary(LocalDate analysisDate, String bias, double normalized,
                                       int confidence, int signalCount,
                                       List<String> twFragments, List<String> volumeFragments,
                                       List<String> usFragments, List<String> chipFragments) {
        StringBuilder sb = new StringBuilder();
        // 自我標示：使歷史列表與每日 email 在純文字閱讀時即可分辨兩種來源，不必回查 model 欄位
        sb.append("【本機規則引擎產生，非 LLM 研判】");
        sb.append(analysisDate == null ? "本次" : analysisDate.toString()).append(" 台股走向研判為")
          .append(biasWord(bias)).append("，加權分數 ").append(signed(normalized))
          .append("（值域 -100～+100），信心 ").append(confidence)
          .append("％，實際參與計分訊號 ").append(signalCount).append("／")
          .append(ALL_SIGNAL_KEYS.size()).append(" 項。");
        List<String> twAndVolume = concat(twFragments, volumeFragments);
        if (!twAndVolume.isEmpty()) {
            sb.append("技術面：").append(String.join("；", twAndVolume)).append("。");
        }
        if (!usFragments.isEmpty()) {
            sb.append("美股連動：").append(String.join("；", usFragments)).append("。");
        }
        if (!chipFragments.isEmpty()) {
            sb.append("籌碼面：").append(String.join("；", chipFragments)).append("。");
        }
        sb.append("本結論由本機規則加權得出，未經回測驗證；信心值代表本次各訊號彼此同向的程度與資料完整度，"
                + "不是歷史勝率。本路徑不做跨領域新聞因果推理，下方參考新聞僅為相關性排序結果。");
        return sb.toString();
    }

    private static String biasWord(String bias) {
        return switch (bias) {
            case "BULLISH" -> "偏多";
            case "BEARISH" -> "偏空";
            default -> "中性";
        };
    }

    // ===== newsHighlights：排序挑選，不做語意理解 =====

    /**
     * 從本地 {@code news_headline} 挑 {@value #NEWS_MIN}～{@value #NEWS_MAX} 則，排序鍵依序：
     * <ol>
     *   <li>非 {@code news} category 的量化快照優先（與既有 {@code buildLocalNewsBlock()} 分桶條件相同：
     *       數量少但訊號最強）</li>
     *   <li>標題命中財經關鍵詞的權重</li>
     *   <li>{@code published_at} 由新到舊</li>
     * </ol>
     *
     * <p>{@code title}／{@code source} 照原樣填；{@code url} 只經
     * {@link MarketAnalysisService#safeHttpUrl(String)}（前端 {@code <a href>} XSS 的防禦縱深，
     * 與資料是否可信無關）；{@code publishedAt} 轉 Asia/Taipei 的 {@code LocalDate} 後取
     * {@code toString()}（與既有 {@code appendLocalNews} 一致）。</p>
     *
     * <p><b>刻意不套 {@code sanitizeNews()}</b>：那層的精確日期驗證、回抓原文校正發布日與地區封鎖
     * 針對的是<b>模型自報</b>的不可信輸出；本路徑資料直接來自本地爬蟲、發布日已由 producer 驗證過。
     * 更關鍵的是 {@code sanitizeNews()} 會對非白名單網域<b>發出 outbound HTTP 回抓原文</b>——
     * 那會讓一條號稱「100% 本地 DB、零外部呼叫」的路徑偷偷連外。</p>
     */
    private List<MarketAnalysisResult.NewsHighlight> selectNews(List<News> recentNews) {
        if (recentNews == null || recentNews.isEmpty()) {
            return List.of();
        }
        List<News> sorted = new ArrayList<>(recentNews);
        sorted.sort(Comparator
                .comparingInt(LocalMarketAnalysisEngine::categoryBucket)
                .thenComparing(Comparator.comparingInt(LocalMarketAnalysisEngine::keywordWeight).reversed())
                .thenComparing(Comparator.comparing(
                        (News n) -> n.getPublishedAt() == null ? java.time.Instant.EPOCH : n.getPublishedAt())
                        .reversed())
                .thenComparing(n -> n.getTitle() == null ? "" : n.getTitle()));

        int take = Math.min(NEWS_MAX, sorted.size());
        List<MarketAnalysisResult.NewsHighlight> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            News n = sorted.get(i);
            String publishedAt = n.getPublishedAt() == null ? null
                    : n.getPublishedAt().atZone(MarketZones.TW_ZONE).toLocalDate().toString();
            out.add(new MarketAnalysisResult.NewsHighlight(
                    n.getTitle(), n.getSource(), MarketAnalysisService.safeHttpUrl(n.getUrl()), publishedAt));
        }
        // 少於下界時只能全給——本路徑不得杜撰新聞補足 NEWS_MIN
        return out;
    }

    /** 0 = 量化快照（非 {@code news} category），1 = 一般新聞。與既有 {@code buildLocalNewsBlock()} 同判準。 */
    private static int categoryBucket(News n) {
        String c = n.getCategory();
        return (c != null && !News.CATEGORY_NEWS.equals(c)) ? 0 : 1;
    }

    private static int keywordWeight(News n) {
        String title = n.getTitle();
        if (title == null || title.isBlank()) {
            return 0;
        }
        String upper = title.toUpperCase(Locale.ROOT);
        int w = 0;
        for (Map.Entry<String, Integer> e : NEWS_KEYWORD_WEIGHTS.entrySet()) {
            if (upper.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                w += e.getValue();
            }
        }
        return w;
    }

    // ===== 序列運算工具 =====

    private static Double lastValue(List<double[]> series) {
        return series.isEmpty() ? null : series.get(series.size() - 1)[1];
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%+.2f%%", v);
    }

    private static String signed(double v) {
        return String.format(Locale.ROOT, "%+.1f", v);
    }
}
