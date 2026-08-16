package com.steven.assets.service;

import com.steven.assets.dto.MarketAnalysisResult;
import com.steven.assets.model.News;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 337（Requirement 78）本機規則引擎的純函式測試。
 *
 * <p><b>本測試不啟動 Spring context、不連資料庫</b>——這同時證明 337.6 的可測試性約束真的成立
 * （評分核心沒有把 Repository／IO／時鐘偷渡進去）。</p>
 */
class LocalMarketAnalysisEngineTest {

    private final LocalMarketAnalysisEngine engine = new LocalMarketAnalysisEngine();

    private static final LocalDate DATE = LocalDate.of(2026, 8, 14);

    /**
     * 指標全缺（暖機不足）。MA／KD／MACD／RSI 四個訊號在此一律不計分——
     * 本引擎<b>不自行計算</b>這些指標，全部取自傳入的 {@code FullIndicators}。
     */
    private static final TechnicalIndicatorService.FullIndicators NO_IND =
            TechnicalIndicatorService.FullIndicators.EMPTY;

    // ===== (a) 固定輸入 → 確定的 bias / confidence / keyFactors =====

    @Test
    void singleExtremeSignal_producesDeterministicResult() {
        // 只有 SOX 一項訊號可算：+5% → 方向分吃滿 +2，加權分數為極端值 +100
        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(),
                Map.of("SOX", closes(DATE, 7000.0, 7350.0)),
                NO_IND, NO_IND, null, List.of(), List.of());

        assertEquals("BULLISH", r.bias());
        // 12 項訊號只命中 1 項 → 完整度 1/12 → 信心 round(100 × 1.0 × 1/12) = 8
        assertEquals(8, r.confidence());
        assertEquals(1, r.keyFactors().size());
        assertTrue(r.keyFactors().get(0).contains("費城半導體（SOX）"), r.keyFactors().get(0));
        assertTrue(r.keyFactors().get(0).contains("+5.00%"), r.keyFactors().get(0));
        // 加權分數確實是極端值，但信心很低——證明 confidence 不由總分絕對值換算
        assertTrue(r.summary().contains("加權分數 +100.0"), r.summary());

        // 純函式：同一份輸入重跑逐欄相同
        MarketAnalysisResult again = engine.evaluate(DATE, List.of(), List.of(),
                Map.of("SOX", closes(DATE, 7000.0, 7350.0)),
                NO_IND, NO_IND, null, List.of(), List.of());
        assertEquals(r, again);
    }

    /** (c) 單一極端訊號的 confidence 必須<b>低於</b>多訊號一致的 confidence。 */
    @Test
    void multipleAlignedSignals_outrankSingleExtremeSignalInConfidence() {
        MarketAnalysisResult single = engine.evaluate(DATE, List.of(), List.of(),
                Map.of("SOX", closes(DATE, 7000.0, 7350.0)),
                NO_IND, NO_IND, null, List.of(), List.of());

        MarketAnalysisResult many = engine.evaluate(DATE, List.of(), List.of(),
                allUsUp1Pct(), NO_IND, NO_IND,
                net(DATE, 150, 50, 30), threeDaysOfNet(), List.of());

        assertEquals("BULLISH", single.bias());
        assertEquals("BULLISH", many.bias());
        // 7／12 項訊號全數同向 → round(100 × 1.0 × 7/12) = 58
        assertEquals(58, many.confidence());
        assertEquals(8, single.confidence());
        assertTrue(many.confidence() > single.confidence());
        // 反向證明：單一極端訊號的加權分數（100）反而高於多訊號一致者
        assertTrue(single.summary().contains("加權分數 +100.0"), single.summary());
        assertFalse(many.summary().contains("加權分數 +100.0"), many.summary());
    }

    // ===== (b) 資料不足的訊號：不計分、不進 keyFactors，且 confidence 因完整度折減而下降 =====

    @Test
    void missingSignals_areNeitherScoredNorListed_andCutConfidence() {
        MarketAnalysisResult withUs = engine.evaluate(DATE, List.of(), List.of(),
                allUsUp1Pct(), NO_IND, NO_IND,
                net(DATE, 150, 50, 30), threeDaysOfNet(), List.of());

        // 美股序列整組缺席（不是餵 0，而是根本沒有這幾項訊號）
        MarketAnalysisResult withoutUs = engine.evaluate(DATE, List.of(), List.of(),
                Map.of(), NO_IND, NO_IND,
                net(DATE, 150, 50, 30), threeDaysOfNet(), List.of());

        assertEquals(58, withUs.confidence());          // 7／12
        assertEquals(33, withoutUs.confidence());       // 4／12 → round(100 × 4/12)
        assertTrue(withoutUs.confidence() < withUs.confidence());

        // 缺席訊號不得出現在 keyFactors
        assertTrue(withUs.keyFactors().stream().anyMatch(f -> f.contains("費城半導體")));
        assertFalse(withoutUs.keyFactors().stream().anyMatch(f -> f.contains("費城半導體")));
        assertFalse(withoutUs.keyFactors().stream().anyMatch(f -> f.contains("那斯達克")));
        assertEquals(4, withoutUs.keyFactors().size());
        assertTrue(withoutUs.usContext().contains("資料不足"), withoutUs.usContext());

        // 台股序列與指標皆缺 → 五個台股訊號一項都不得計分或列出
        assertFalse(withUs.keyFactors().stream().anyMatch(f -> f.contains("台股收盤")));
        assertFalse(withUs.keyFactors().stream().anyMatch(f -> f.contains("KD 於")));
        assertTrue(withUs.twContext().contains("資料不足"), withUs.twContext());
    }

    @Test
    void noSignalAtAll_yieldsNeutralZeroConfidenceAndAnHonestKeyFactor() {
        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                NO_IND, NO_IND, null, List.of(), List.of());

        assertEquals("NEUTRAL", r.bias());
        assertEquals(0, r.confidence());
        assertEquals(1, r.keyFactors().size());
        assertTrue(r.keyFactors().get(0).contains("無任何可計算的訊號"), r.keyFactors().get(0));
    }

    // ===== 單位換算：法人買賣超欄位單位為「元」，顯示成億須除以 1e8 =====

    @Test
    void institutionalAmountsAreRenderedInHundredMillions() {
        // 實測值：2026-08-14 foreign_net = 45351330421.00（＝453.5 億）
        LocalMarketAnalysisEngine.InstitutionalNet latest = new LocalMarketAnalysisEngine.InstitutionalNet(
                DATE, new BigDecimal("45351330421.00"), new BigDecimal("1200000000.00"),
                new BigDecimal("-800000000.00"), new BigDecimal("45751330421.00"));

        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                NO_IND, NO_IND, latest, List.of(latest, latest), List.of());

        assertTrue(r.keyFactors().stream().anyMatch(f -> f.contains("外資") && f.contains("買超 453.51 億元")),
                r.keyFactors().toString());
        assertTrue(r.keyFactors().stream().anyMatch(f -> f.contains("投信") && f.contains("買超 12.00 億元")),
                r.keyFactors().toString());
        assertTrue(r.keyFactors().stream().anyMatch(f -> f.contains("自營商") && f.contains("賣超 8.00 億元")),
                r.keyFactors().toString());
    }

    // ===== 台股技術面訊號：MA／KD／MACD／RSI 皆取自 FullIndicators，量能取自 tradeValues =====

    @Test
    void taiwanTechnicalSignals_produceNumericKeyFactorsFromTheSameSeries() {
        List<double[]> closes = acceleratingCloses(60, 45000, 15);
        List<double[]> volumes = flatVolumes(60, 8.0e11);
        // 最後一日爆量：近 20 日均量的 1.5 倍
        volumes.set(volumes.size() - 1, new double[]{volumes.get(volumes.size() - 1)[0], 1.2e12});

        TechnicalIndicatorService.FullIndicators ind = new TechnicalIndicatorService.FullIndicators(
                bd("45600"), bd("45300"), bd("44000"), bd("62.30"), bd("58.10"),
                bd("55.00"), bd("56.00"), bd("45800"),
                extended("18.44", "58.20"));
        // 前一期：OSC 較小 → 動能轉強
        TechnicalIndicatorService.FullIndicators prev = withExtended(extended("12.10", "55.00"));

        MarketAnalysisResult r = engine.evaluate(DATE, closes, volumes, Map.of(), ind, prev,
                null, List.of(), List.of());

        String factors = String.join(" | ", r.keyFactors());
        assertTrue(factors.contains("台股收盤 47,625.50 點"), factors);
        assertTrue(factors.contains("KD 於 K=62.30／D=58.10，形成黃金交叉"), factors);
        // MACD 與 RSI 的數字必須逐字等於傳入的 ExtendedIndicators，不得是引擎自算的第二份
        assertTrue(factors.contains("MACD OSC（DI 價基）18.44（前一交易日 12.10）"), factors);
        assertTrue(factors.contains("且動能轉強"), factors);
        assertTrue(factors.contains("RSI10（Wilder 平滑）58.20"), factors);
        assertFalse(factors.contains("收盤價基"), factors);
        assertTrue(factors.contains("台股成交金額 12,000.00 億元，為近 20 日均量的 1.50 倍"), factors);
        // 五個台股訊號皆有資料 → 全部參與計分
        assertTrue(r.confidence() > 0);
        assertNotNull(r.twContext());
        assertTrue(r.twContext().contains("47,625.50"), r.twContext());
    }

    /**
     * MACD／RSI 的唯一來源是 {@code FullIndicators.extended()}：即使收盤序列長到足以自算，
     * 只要傳入的擴充指標為空，兩個訊號就必須缺席（證明引擎沒有留第二份實作當 fallback）。
     */
    @Test
    void macdAndRsi_areAbsentWhenExtendedIndicatorsAreEmpty_evenWithALongCloseSeries() {
        List<double[]> closes = acceleratingCloses(120, 45000, 15);
        List<double[]> volumes = flatVolumes(120, 8.0e11);

        MarketAnalysisResult r = engine.evaluate(DATE, closes, volumes, Map.of(),
                NO_IND, NO_IND, null, List.of(), List.of());

        String factors = String.join(" | ", r.keyFactors());
        assertFalse(factors.contains("MACD"), factors);
        assertFalse(factors.contains("RSI"), factors);
        assertFalse(r.twContext().contains("MACD"), r.twContext());
        assertFalse(r.twContext().contains("RSI"), r.twContext());
    }

    /** 前一期 OSC 缺席（序列不足以再退一期）時，MACD 訊號整項不計分——不得拿當期值自比。 */
    @Test
    void macdSignal_isAbsentWhenPreviousOscIsMissing() {
        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                withExtended(extended("18.44", "58.20")), NO_IND, null, List.of(), List.of());

        String factors = String.join(" | ", r.keyFactors());
        assertFalse(factors.contains("MACD"), factors);
        // 同一份 extended 的 RSI 不受影響（RSI 只看當期）
        assertTrue(factors.contains("RSI10（Wilder 平滑）58.20"), factors);
    }

    /** RSI 直接採用 {@code extended().rsi10()}：≥70 進超買區、給出反向（偏空）方向分。 */
    @Test
    void rsiSignal_readsRsi10FromExtendedIndicators() {
        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                withExtended(extended(null, "72.50")), NO_IND, null, List.of(), List.of());

        assertEquals("BEARISH", r.bias());
        assertEquals(1, r.keyFactors().size());
        assertTrue(r.keyFactors().get(0).contains("RSI10（Wilder 平滑）72.50"), r.keyFactors().get(0));
        assertTrue(r.keyFactors().get(0).contains("已進入超買區"), r.keyFactors().get(0));
    }

    @Test
    void volumeSignal_isAbsentWhenWindowIsTooShort() {
        List<double[]> closes = acceleratingCloses(60, 45000, 15);
        List<double[]> shortVolumes = flatVolumes(10, 8.0e11);   // < 21 筆 → 量能訊號不計分

        MarketAnalysisResult r = engine.evaluate(DATE, closes, shortVolumes, Map.of(),
                NO_IND, NO_IND, null, List.of(), List.of());

        assertFalse(String.join(" ", r.keyFactors()).contains("成交金額"), r.keyFactors().toString());
    }

    // ===== summary 自我標示為機器產生 =====

    @Test
    void summary_declaresItselfAsLocalRuleEngineOutput() {
        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(),
                allUsUp1Pct(), NO_IND, NO_IND,
                net(DATE, 150, 50, 30), threeDaysOfNet(), List.of());

        assertTrue(r.summary().startsWith("【本機規則引擎產生，非 LLM 研判】"), r.summary());
        assertTrue(r.summary().contains("未經回測驗證"), r.summary());
        assertTrue(r.summary().contains("不是歷史勝率"), r.summary());
    }

    @Test
    void ruleVersion_isTheValueWrittenToModelColumn() {
        assertEquals("local-rule-engine:v1", LocalMarketAnalysisEngine.RULE_VERSION);
    }

    // ===== (g) newsHighlights：量化快照優先、欄位照原樣、url 只過 safeHttpUrl =====

    @Test
    void newsHighlights_rankQuantSnapshotsFirstAndCopyFieldsVerbatim() {
        Instant t = Instant.parse("2026-08-13T16:30:00Z");   // Asia/Taipei = 2026-08-14
        News article = news("一般新聞：某公司改名", "udn", "https://udn.com/a", News.CATEGORY_NEWS, t.plusSeconds(3600));
        News quant = news("三大法人買賣超：外資買超 453 億", "twse",
                "https://www.twse.com.tw/x", News.CATEGORY_TWSE_INSTITUTIONAL, t);

        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                NO_IND, NO_IND, null, List.of(),
                List.of(article, quant));

        List<MarketAnalysisResult.NewsHighlight> out = r.newsHighlights();
        assertEquals(2, out.size());
        // 量化快照排在一般新聞之前（縱使一般新聞較新）
        assertEquals("三大法人買賣超：外資買超 453 億", out.get(0).title());
        assertEquals("一般新聞：某公司改名", out.get(1).title());
        // title／source 逐字相等；url 等於 safeHttpUrl(來源 url)；publishedAt 為 Asia/Taipei 的日期字串
        assertEquals("twse", out.get(0).source());
        assertEquals(MarketAnalysisService.safeHttpUrl("https://www.twse.com.tw/x"), out.get(0).url());
        assertEquals("2026-08-14", out.get(0).publishedAt());
        assertEquals("2026-08-14", out.get(1).publishedAt());
    }

    @Test
    void newsHighlights_dropNonHttpUrlButKeepTheItem() {
        News evil = news("標題", "src", "javascript:alert(1)", News.CATEGORY_NEWS,
                Instant.parse("2026-08-13T16:30:00Z"));

        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                NO_IND, NO_IND, null, List.of(), List.of(evil));

        assertEquals(1, r.newsHighlights().size());
        assertNull(r.newsHighlights().get(0).url());
        assertEquals("標題", r.newsHighlights().get(0).title());
    }

    @Test
    void newsHighlights_capAtSixAndPreferFinanceKeywords() {
        List<News> pool = new ArrayList<>();
        Instant base = Instant.parse("2026-08-13T16:30:00Z");
        for (int i = 0; i < 10; i++) {
            pool.add(news("無關主題 " + i, "src", "https://example.com/" + i,
                    News.CATEGORY_NEWS, base.plusSeconds(i)));
        }
        pool.add(news("台積電外資買超帶動半導體", "udn", "https://udn.com/k",
                News.CATEGORY_NEWS, base));   // 關鍵詞權重高、但發布時間最舊

        MarketAnalysisResult r = engine.evaluate(DATE, List.of(), List.of(), Map.of(),
                NO_IND, NO_IND, null, List.of(), pool);

        assertEquals(6, r.newsHighlights().size());
        assertEquals("台積電外資買超帶動半導體", r.newsHighlights().get(0).title());
    }

    // ===== 測試資料工具 =====

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 只填引擎會讀的兩欄（OSC／RSI10），其餘擴充指標本引擎不使用。 */
    private static TechnicalIndicatorService.ExtendedIndicators extended(String osc, String rsi10) {
        return new TechnicalIndicatorService.ExtendedIndicators(
                null, null, null, null, null, null, null,
                osc == null ? null : bd(osc),
                null,
                rsi10 == null ? null : bd(rsi10),
                null, null, null, null);
    }

    /** MA／KD 全缺、只帶擴充指標的 FullIndicators。 */
    private static TechnicalIndicatorService.FullIndicators withExtended(
            TechnicalIndicatorService.ExtendedIndicators ext) {
        return new TechnicalIndicatorService.FullIndicators(
                null, null, null, null, null, null, null, null, ext);
    }

    private static List<double[]> closes(LocalDate end, double... values) {
        List<double[]> out = new ArrayList<>();
        long epoch = end.toEpochDay() - values.length + 1;
        for (int i = 0; i < values.length; i++) {
            out.add(new double[]{epoch + i, values[i]});
        }
        return out;
    }

    /** 加速上漲的收盤序列：{@code start + i×step + i²×0.5}（供 MA 位置與量價配合訊號取值）。 */
    private static List<double[]> acceleratingCloses(int n, double start, double step) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = start + i * step + i * i * 0.5;
        return closes(DATE, v);
    }

    private static List<double[]> flatVolumes(int n, double value) {
        List<double[]> out = new ArrayList<>();
        long epoch = DATE.toEpochDay() - n + 1;
        for (int i = 0; i < n; i++) out.add(new double[]{epoch + i, value});
        return out;
    }

    /** SOX／IXIC／SPX 各上漲 1.00%（方向分皆 +1）。 */
    private static Map<String, List<double[]>> allUsUp1Pct() {
        Map<String, List<double[]>> m = new LinkedHashMap<>();
        m.put("SOX", closes(DATE, 7000.0, 7070.0));
        m.put("IXIC", closes(DATE, 20000.0, 20200.0));
        m.put("SPX", closes(DATE, 6000.0, 6060.0));
        return m;
    }

    /** 買超金額以「億元」給值，內部轉回「元」——與 twse_institutional_daily 的實際單位一致。 */
    private static LocalMarketAnalysisEngine.InstitutionalNet net(LocalDate d,
                                                                 double foreignYi, double trustYi, double dealerYi) {
        return new LocalMarketAnalysisEngine.InstitutionalNet(d,
                yi(foreignYi), yi(trustYi), yi(dealerYi), yi(foreignYi + trustYi + dealerYi));
    }

    private static BigDecimal yi(double v) {
        return BigDecimal.valueOf(v).multiply(new BigDecimal("100000000"));
    }

    /** 近三個交易日外資各買超 150 億 → 累計 450 億（方向分 450/300 = 1.5）。 */
    private static List<LocalMarketAnalysisEngine.InstitutionalNet> threeDaysOfNet() {
        return List.of(net(DATE.minusDays(2), 150, 50, 30),
                net(DATE.minusDays(1), 150, 50, 30),
                net(DATE, 150, 50, 30));
    }

    private static News news(String title, String source, String url, String category, Instant publishedAt) {
        News n = new News();
        n.setTitle(title);
        n.setSource(source);
        n.setUrl(url);
        n.setCategory(category);
        n.setPublishedAt(publishedAt);
        n.setFetchedAt(publishedAt);
        n.setDedupeKey(title);
        return n;
    }
}
