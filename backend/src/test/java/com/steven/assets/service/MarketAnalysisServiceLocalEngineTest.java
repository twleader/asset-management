package com.steven.assets.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.MarketAnalysisResult;
import com.steven.assets.dto.MarketAnalysisSettingsDto;
import com.steven.assets.model.DailyMarketAnalysis;
import com.steven.assets.model.MarketAnalysisSetting;
import com.steven.assets.model.News;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.TwseInstitutionalDaily;
import com.steven.assets.repository.DailyMarketAnalysisRepository;
import com.steven.assets.repository.MarketAnalysisSettingRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.TwseInstitutionalDailyRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.util.MarketZones;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 337（Requirement 78）{@code MarketAnalysisService} 的引擎分岔測試。
 *
 * <p>涵蓋：本機路徑不檢查金鑰且零 Anthropic 互動、LLM 路徑不回歸、engine 白名單與
 * 「null 表示該欄不變」語意、newsHighlights 欄位對應（未經 {@code sanitizeNews}）、
 * 寄信條件與 LLM 路徑一致，以及<b>價基一致性</b>（MA／KD 不含 Redis 今日合成列）。</p>
 */
class MarketAnalysisServiceLocalEngineTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 14);

    private DailyMarketAnalysisRepository analysisRepo;
    private MarketAnalysisSettingRepository settingRepo;
    private TwseIndexDailyHistoryRepository twseRepo;
    private UsIndexDailyHistoryRepository usRepo;
    private NewsHeadlineRepository newsRepo;
    private MarketAnalysisEmailDispatcher emailDispatcher;
    private MarketDataService marketDataService;
    private MarketAnalysisSendTimeService sendTimeService;
    private TwseInstitutionalDailyRepository institutionalRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        analysisRepo = mock(DailyMarketAnalysisRepository.class);
        settingRepo = mock(MarketAnalysisSettingRepository.class);
        twseRepo = mock(TwseIndexDailyHistoryRepository.class);
        usRepo = mock(UsIndexDailyHistoryRepository.class);
        newsRepo = mock(NewsHeadlineRepository.class);
        emailDispatcher = mock(MarketAnalysisEmailDispatcher.class);
        marketDataService = mock(MarketDataService.class);
        sendTimeService = mock(MarketAnalysisSendTimeService.class);
        institutionalRepo = mock(TwseInstitutionalDailyRepository.class);

        when(analysisRepo.findById(any())).thenReturn(Optional.empty());
        when(analysisRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(twseRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(any())).thenReturn(List.of());
        when(usRepo.findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(anyString(), any()))
                .thenReturn(List.of());
        when(newsRepo.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any())).thenReturn(List.of());
        when(institutionalRepo.findVisibleRange(any(), any(), any())).thenReturn(List.of());
        when(marketDataService.isTwTradingDay(any())).thenReturn(true);
        when(sendTimeService.list()).thenReturn(List.of());
    }

    private MarketAnalysisService newService(TechnicalIndicatorService technical,
                                             LocalMarketAnalysisEngine engine,
                                             String apiKey) {
        MarketAnalysisService svc = new MarketAnalysisService(
                analysisRepo, settingRepo, twseRepo, usRepo, newsRepo, objectMapper,
                emailDispatcher, marketDataService, sendTimeService,
                technical, institutionalRepo, engine);
        ReflectionTestUtils.setField(svc, "apiKey", apiKey);
        ReflectionTestUtils.setField(svc, "defaultModel", "claude-opus-5");
        ReflectionTestUtils.setField(svc, "newsMaxAgeDays", 5);
        ReflectionTestUtils.setField(svc, "newsVerifyPublishedDate", true);
        ReflectionTestUtils.setField(svc, "newsRegionBlockEnabled", true);
        return svc;
    }

    private void engineSetting(String engine) {
        MarketAnalysisSetting s = new MarketAnalysisSetting();
        s.setId(MarketAnalysisSetting.SINGLETON_ID);
        s.setModel("claude-opus-5");
        s.setEffort("medium");
        s.setEnabled(Boolean.TRUE);
        s.setEngine(engine);
        when(settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)).thenReturn(Optional.of(s));
    }

    // ===== (d) engine=local ＋ 空 apiKey → 仍落 OK，且全程零 Anthropic client 互動 =====

    @Test
    void localEngine_succeedsWithoutAnthropicApiKey_andNeverTouchesAnthropicClient() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(DailyMarketAnalysis.STATUS_OK, row.getStatus());
        assertNotEquals(DailyMarketAnalysis.STATUS_NOT_CONFIGURED, row.getStatus());
        assertEquals("local-rule-engine:v1", row.getModel());
        // 本機路徑不設 batch_id、不經 PROCESSING → pollPendingBatches() 永遠撈不到它
        assertNull(row.getBatchId());
        assertNotNull(row.getBias());
        // 替身斷言：Anthropic client 是 lazy 建立的，欄位仍為 null 即證明本次全程未建立、未呼叫
        assertNull(ReflectionTestUtils.getField(svc, "anthropicClient"));
    }

    @Test
    void localEngine_writesRuleVersionAndNonEmptyKeyFactors() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        when(usRepo.findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(eq("SOX"), any()))
                .thenReturn(List.of(us("SOX", DATE.minusDays(1), "7000"), us("SOX", DATE, "7350")));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(LocalMarketAnalysisEngine.RULE_VERSION, row.getModel());
        assertEquals("BULLISH", row.getBias());
        assertTrue(row.getKeyFactors().contains("費城半導體"), row.getKeyFactors());
        assertTrue(row.getSummary().startsWith("【本機規則引擎產生，非 LLM 研判】"), row.getSummary());
    }

    /** 357.4a：本機路徑須把 {@code result.factorGroups()} 序列化落庫；round-trip 後四個分類逐條相同。 */
    @Test
    void localEngine_persistsFactorGroupsAsRoundTrippableJson() throws Exception {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        when(usRepo.findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(eq("SOX"), any()))
                .thenReturn(List.of(us("SOX", DATE.minusDays(1), "7000"), us("SOX", DATE, "7350")));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertNotNull(row.getFactorGroups(), row.getFactorGroups());
        MarketAnalysisResult.FactorGroups parsed = objectMapper.readValue(
                row.getFactorGroups(), MarketAnalysisResult.FactorGroups.class);
        assertTrue(parsed.us().stream().anyMatch(f -> f.contains("費城半導體")), parsed.us().toString());
        assertTrue(parsed.twTechnical().isEmpty() || parsed.twTechnical() != null);
        assertTrue(parsed.twVolume().isEmpty());
        assertTrue(parsed.chip().isEmpty());
    }

    // ===== (e) engine=llm 時既有批次路徑行為不回歸 =====

    @Test
    void llmEngine_keepsNotConfiguredBehaviourAndNeverCallsLocalEngine() {
        engineSetting(MarketAnalysisService.ENGINE_LLM);
        LocalMarketAnalysisEngine engine = mock(LocalMarketAnalysisEngine.class);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class), engine, "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(DailyMarketAnalysis.STATUS_NOT_CONFIGURED, row.getStatus());
        assertEquals("claude-opus-5", row.getModel());
        verifyNoInteractions(engine);
    }

    @Test
    void processingGuard_appliesToBothEngines() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        DailyMarketAnalysis processing = new DailyMarketAnalysis();
        processing.setAnalysisDate(DATE);
        processing.setStatus(DailyMarketAnalysis.STATUS_PROCESSING);
        processing.setBatchId("batch-1");
        when(analysisRepo.findById(DATE)).thenReturn(Optional.of(processing));

        LocalMarketAnalysisEngine engine = mock(LocalMarketAnalysisEngine.class);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class), engine, "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(DailyMarketAnalysis.STATUS_PROCESSING, row.getStatus());
        verifyNoInteractions(engine);
    }

    // ===== (f) engine 白名單與 updateSettings 的「null 不變」語意 =====

    @Test
    void updateSettings_rejectsUnknownEngine() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.updateSettings(null, null, null, "gpt"));
        assertTrue(ex.getMessage().contains("不支援的分析引擎：gpt"), ex.getMessage());
    }

    @Test
    void updateSettings_requiresAtLeastOneField() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.updateSettings(null, null, null, null));
        assertTrue(ex.getMessage().contains("engine"), ex.getMessage());
    }

    @Test
    void updateSettings_nullMeansUnchanged() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        when(settingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        svc.updateSettings(null, null, null, MarketAnalysisService.ENGINE_LLM);

        ArgumentCaptor<MarketAnalysisSetting> captor = ArgumentCaptor.forClass(MarketAnalysisSetting.class);
        verify(settingRepo).save(captor.capture());
        MarketAnalysisSetting saved = captor.getValue();
        assertEquals(MarketAnalysisService.ENGINE_LLM, saved.getEngine());
        assertEquals("claude-opus-5", saved.getModel());   // 未提供 → 不變
        assertEquals("medium", saved.getEffort());           // 未提供 → 不變
        assertEquals(Boolean.TRUE, saved.getEnabled());      // 未提供 → 不變
    }

    @Test
    void getSettings_exposesEngineAndItsWhitelist() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        MarketAnalysisSettingsDto dto = svc.getSettings();

        assertEquals(MarketAnalysisService.ENGINE_LOCAL, dto.engine());
        assertEquals(List.of(MarketAnalysisService.ENGINE_LOCAL, MarketAnalysisService.ENGINE_LLM),
                dto.availableEngines().stream().map(MarketAnalysisSettingsDto.EngineOption::id).toList());
        // 模型下拉的「內容」不回歸（只斷言數量的話，清單一換代就得再改一次數字）
        assertEquals(List.of("claude-fable-5", "claude-opus-5", "claude-sonnet-5"),
                dto.availableModels().stream().map(MarketAnalysisSettingsDto.ModelOption::id).toList(),
                "已存設定的 model 在白名單內 → 不得額外補一筆「目前值」選項");
    }

    @Test
    void getSettings_prependsStoredModelWhenItFellOffTheWhitelist() {
        MarketAnalysisSetting s = new MarketAnalysisSetting();
        s.setId(MarketAnalysisSetting.SINGLETON_ID);
        s.setModel("claude-opus-4-8");   // 已下架的舊 model id（尚未被 v1.109.0 changeset 更新到的環境）
        s.setEffort("medium");
        s.setEnabled(Boolean.TRUE);
        s.setEngine(MarketAnalysisService.ENGINE_LOCAL);
        when(settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)).thenReturn(Optional.of(s));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        MarketAnalysisSettingsDto dto = svc.getSettings();

        assertEquals(List.of("claude-opus-4-8", "claude-fable-5", "claude-opus-5", "claude-sonnet-5"),
                dto.availableModels().stream().map(MarketAnalysisSettingsDto.ModelOption::id).toList(),
                "白名單外的既存值須補在最前面，避免下拉選單靜默改掉使用者目前的設定");
    }

    @Test
    void resolveEngine_fallsBackToLocalForUnknownStoredValue() {
        MarketAnalysisSetting s = new MarketAnalysisSetting();
        s.setId(MarketAnalysisSetting.SINGLETON_ID);
        s.setEngine("bogus");
        when(settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)).thenReturn(Optional.of(s));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        assertEquals(MarketAnalysisService.ENGINE_LOCAL, svc.resolveEngine());
    }

    // ===== (g) newsHighlights 欄位對應，且未經 sanitizeNews 的日期覆寫／整筆剔除 =====

    @Test
    void localNewsHighlights_keepSourceFieldsAndSurviveSanitizeNewsWindow() throws Exception {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        // 發布日刻意落在 sanitizeNews 的時窗（analysisDate − newsMaxAgeDays）之外：
        // 若誤用 applyResult()／sanitizeNews()，這兩筆會被整筆剔除或被 date.toString() 覆寫。
        Instant old = LocalDate.of(2026, 7, 1).atStartOfDay(MarketZones.TW_ZONE).toInstant();
        News quant = news("三大法人買賣超統計", "twse", "https://www.twse.com.tw/q",
                News.CATEGORY_TWSE_INSTITUTIONAL, old);
        // 非白名單網域——sanitizeNews 會對它發 outbound HTTP 回抓原文，本路徑不得如此
        News fx = news("台幣匯率快照", "bot-fx", "https://rate.bot.com.tw/x", "fx", old.plusSeconds(60));
        News article = news("一般新聞", "udn", "https://udn.com/a", News.CATEGORY_NEWS, old.plusSeconds(120));
        when(newsRepo.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any()))
                .thenReturn(List.of(article, fx, quant));

        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        List<MarketAnalysisResult.NewsHighlight> out = objectMapper.readValue(
                row.getNewsHighlights(), new TypeReference<List<MarketAnalysisResult.NewsHighlight>>() {});
        assertEquals(3, out.size(), "sanitizeNews 的時窗過濾不得套用於本機路徑");

        // 量化快照（非 news category）排在一般新聞之前
        assertTrue(List.of("三大法人買賣超統計", "台幣匯率快照").contains(out.get(0).title()));
        assertTrue(List.of("三大法人買賣超統計", "台幣匯率快照").contains(out.get(1).title()));
        assertEquals("一般新聞", out.get(2).title());

        MarketAnalysisResult.NewsHighlight last = out.get(2);
        assertEquals(article.getTitle(), last.title());                       // 逐字相等
        assertEquals(article.getSource(), last.source());                     // 逐字相等
        assertEquals(MarketAnalysisService.safeHttpUrl(article.getUrl()), last.url());
        assertEquals(article.getPublishedAt().atZone(MarketZones.TW_ZONE).toLocalDate().toString(),
                last.publishedAt());
        // 未被 sanitizeNews 以 analysisDate 覆寫
        assertEquals("2026-07-01", last.publishedAt());
        assertNotEquals(DATE.toString(), last.publishedAt());
    }

    // ===== (h) 寄信條件與 LLM 路徑一致 =====

    @Test
    void email_isSentOnceOnTradingDay() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        when(emailDispatcher.dispatchDaily(any())).thenReturn(true);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        verify(emailDispatcher).dispatchDaily(any());
        assertNotNull(row.getEmailSentAt());
    }

    @Test
    void email_isNotSentOnNonTradingDay() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        when(marketDataService.isTwTradingDay(DATE)).thenReturn(false);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(DailyMarketAnalysis.STATUS_OK, row.getStatus());   // 分析仍保留供查閱
        verify(emailDispatcher, never()).dispatchDaily(any());
        assertNull(row.getEmailSentAt());
    }

    @Test
    void email_isNotResentWhenAlreadyMarked() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        DailyMarketAnalysis existing = new DailyMarketAnalysis();
        existing.setAnalysisDate(DATE);
        existing.setStatus(DailyMarketAnalysis.STATUS_OK);
        existing.setEmailSentAt(Instant.parse("2026-08-14T01:00:00Z"));
        when(analysisRepo.findById(DATE)).thenReturn(Optional.of(existing));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        // 手動重跑（skipIfAlreadyOk=false、resetEmailSent=false）→ 冪等記號保留、不重寄
        DailyMarketAnalysis row = svc.generate(DATE, "manual");

        assertEquals(DailyMarketAnalysis.STATUS_OK, row.getStatus());
        verify(emailDispatcher, never()).dispatchDaily(any());
        assertNotNull(row.getEmailSentAt());
    }

    @Test
    void scheduledSendResetsIdempotencyMarkerAndResends() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        DailyMarketAnalysis existing = new DailyMarketAnalysis();
        existing.setAnalysisDate(DATE);
        existing.setStatus(DailyMarketAnalysis.STATUS_OK);
        existing.setEmailSentAt(Instant.parse("2026-08-14T01:00:00Z"));
        when(analysisRepo.findById(DATE)).thenReturn(Optional.of(existing));
        when(emailDispatcher.dispatchDaily(any())).thenReturn(true);
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        svc.generateForSend(DATE, "schedule");

        verify(emailDispatcher).dispatchDaily(any());
    }

    // ===== (i)(j) 價基一致性：MA／KD 走 computeFromSeries，且不含 Redis 今日合成列 =====

    @Test
    void indicators_comeFromComputeFromSeriesOnPureDbSeries() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        List<TwseIndexDailyHistory> rows = taiexRows(30, LocalDate.of(2026, 8, 13));
        when(twseRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(any())).thenReturn(rows);

        PriceQueryService priceQuery = mock(PriceQueryService.class);
        when(priceQuery.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        TechnicalIndicatorService technical = new TechnicalIndicatorService(
                mock(StockPriceHistoryRepository.class), priceQuery, twseRepo,
                mock(UsIndexDailyHistoryRepository.class));

        LocalMarketAnalysisEngine engine = mock(LocalMarketAnalysisEngine.class);
        when(engine.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MarketAnalysisResult("NEUTRAL", 0, "s", List.of(), List.of(), "tw", "us", null));

        MarketAnalysisService svc = newService(technical, engine, "");
        svc.generate(DATE, "test");

        ArgumentCaptor<TechnicalIndicatorService.FullIndicators> captor =
                ArgumentCaptor.forClass(TechnicalIndicatorService.FullIndicators.class);
        ArgumentCaptor<TechnicalIndicatorService.FullIndicators> prevCaptor =
                ArgumentCaptor.forClass(TechnicalIndicatorService.FullIndicators.class);
        verify(engine).evaluate(any(), any(), any(), any(), captor.capture(), prevCaptor.capture(),
                any(), any(), any());

        // 餵入相同（純 DB 降序）序列時，本路徑與 computeFromSeries 輸出相同——證明未複製第二份 MA／KD 實作
        List<StockPriceHistory> desc = descSeries(rows);
        TechnicalIndicatorService.FullIndicators expected = technical.computeFromSeries(desc);
        // 先確認比較對象不是空殼，否則 EMPTY == EMPTY 會讓斷言空過
        assertNotNull(expected.weeklyMa());
        assertNotNull(expected.k());
        assertEquals(expected, captor.getValue());

        // 前一期指標＝同一份序列砍掉最新一筆（desc 為降序，故砍 index 0）後的 computeFromSeries
        TechnicalIndicatorService.FullIndicators expectedPrev =
                technical.computeFromSeries(desc.subList(1, desc.size()));
        assertNotNull(expectedPrev.weeklyMa());
        assertEquals(expectedPrev, prevCaptor.getValue());
        // 兩期確實不同（否則「方向」判斷會恆為持平，斷言等於空過）
        assertNotEquals(expected.weeklyMa(), expectedPrev.weeklyMa());
    }

    @Test
    void indicators_excludeTodaysRedisLivePrice_soPriceBasisMatchesCloses() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        // 情境：最新 DB 日線停在「前一交易日」，而 Redis 已有今日即時價
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        LocalDate lastDbDay = today.minusDays(1);
        List<TwseIndexDailyHistory> rows = taiexRows(30, lastDbDay);
        when(twseRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(any())).thenReturn(rows);
        when(twseRepo.findTopNByOrderByTradingDateDesc(anyInt())).thenReturn(reversed(rows));

        PriceQueryService priceQuery = mock(PriceQueryService.class);
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(livePrice(today, "99999")));
        TechnicalIndicatorService technical = new TechnicalIndicatorService(
                mock(StockPriceHistoryRepository.class), priceQuery, twseRepo,
                mock(UsIndexDailyHistoryRepository.class));

        LocalMarketAnalysisEngine engine = mock(LocalMarketAnalysisEngine.class);
        when(engine.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MarketAnalysisResult("NEUTRAL", 0, "s", List.of(), List.of(), "tw", "us", null));

        MarketAnalysisService svc = newService(technical, engine, "");
        svc.generate(DATE, "test");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> closesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<TechnicalIndicatorService.FullIndicators> indCaptor =
                ArgumentCaptor.forClass(TechnicalIndicatorService.FullIndicators.class);
        verify(engine).evaluate(any(), closesCaptor.capture(), any(), any(), indCaptor.capture(),
                any(), any(), any(), any());

        // 收盤序列止於 DB 的最後一個完成日，沒有今日 live 合成列
        List<double[]> closes = closesCaptor.getValue();
        assertEquals(lastDbDay.toEpochDay(), (long) closes.get(closes.size() - 1)[0]);
        assertNotEquals(99999.0, closes.get(closes.size() - 1)[1]);

        // MA／KD 與收盤同源：等於純 DB 序列的 computeFromSeries，且不等於會併入 live 的 computeAll
        TechnicalIndicatorService.FullIndicators pureDb = technical.computeFromSeries(descSeries(rows));
        TechnicalIndicatorService.FullIndicators withLive = technical.computeAll("0000", "台股");
        // 先釘住兩條路徑的實際數值，確認 computeAll 真的把 99999 那列併進來了——
        // 否則 withLive 若因故回 EMPTY，下面的 assertNotEquals 會空過
        assertEquals(0, new BigDecimal("45337.50").compareTo(pureDb.weeklyMa()), "純 DB MA5");
        // (99999 + 45362.5 + 45350 + 45337.5 + 45325) / 5 = 56274.80
        assertEquals(0, new BigDecimal("56274.80").compareTo(withLive.weeklyMa()), "含今日 live 合成列的 MA5");

        assertEquals(pureDb, indCaptor.getValue());
        assertNotEquals(withLive, indCaptor.getValue());
        assertNotEquals(withLive.weeklyMa(), indCaptor.getValue().weeklyMa());
    }

    // ===== 籌碼面選列：只採 AVAILABLE 完整列，同日取 observedAt 最大者 =====

    @Test
    void institutionalRows_pickLatestObservationAndSkipIncompleteOnes() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        Instant t1 = Instant.parse("2026-08-14T09:00:00Z");
        Instant t2 = Instant.parse("2026-08-14T10:00:00Z");
        TwseInstitutionalDaily early = institutional(DATE, t1, "100000000", "AVAILABLE");
        TwseInstitutionalDaily late = institutional(DATE, t2, "200000000", "AVAILABLE");
        TwseInstitutionalDaily unavailable = institutional(DATE.minusDays(1), t1, "300000000", "UNAVAILABLE");
        when(institutionalRepo.findVisibleRange(any(), any(), any()))
                .thenReturn(List.of(unavailable, early, late));

        LocalMarketAnalysisEngine engine = mock(LocalMarketAnalysisEngine.class);
        when(engine.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MarketAnalysisResult("NEUTRAL", 0, "s", List.of(), List.of(), "tw", "us", null));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class), engine, "");

        svc.generate(DATE, "test");

        ArgumentCaptor<LocalMarketAnalysisEngine.InstitutionalNet> latestCaptor =
                ArgumentCaptor.forClass(LocalMarketAnalysisEngine.InstitutionalNet.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocalMarketAnalysisEngine.InstitutionalNet>> listCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(engine).evaluate(any(), any(), any(), any(), any(), any(),
                latestCaptor.capture(), listCaptor.capture(), any());

        assertEquals(new BigDecimal("200000000"), latestCaptor.getValue().foreignNet());
        assertEquals(1, listCaptor.getValue().size(), "UNAVAILABLE 列不得入選");
    }

    @Test
    void institutionalQueryFailure_doesNotBreakAnalysis() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        when(institutionalRepo.findVisibleRange(any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class),
                new LocalMarketAnalysisEngine(), "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(DailyMarketAnalysis.STATUS_OK, row.getStatus());
    }

    @Test
    void engineFailure_landsFailedWithoutThrowing() {
        engineSetting(MarketAnalysisService.ENGINE_LOCAL);
        LocalMarketAnalysisEngine engine = mock(LocalMarketAnalysisEngine.class);
        when(engine.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        MarketAnalysisService svc = newService(mock(TechnicalIndicatorService.class), engine, "");

        DailyMarketAnalysis row = svc.generate(DATE, "test");

        assertEquals(DailyMarketAnalysis.STATUS_FAILED, row.getStatus());
        assertEquals("boom", row.getErrorMessage());
        assertNull(row.getBias());
        assertFalse(DailyMarketAnalysis.STATUS_OK.equals(row.getStatus()));
    }

    // ===== 測試資料工具 =====

    private static List<TwseIndexDailyHistory> taiexRows(int n, LocalDate lastDay) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            TwseIndexDailyHistory r = new TwseIndexDailyHistory();
            r.setTradingDate(lastDay.minusDays(i));
            double close = 45000 + (n - 1 - i) * 12.5;
            r.setClosePoint(BigDecimal.valueOf(close));
            r.setHighPoint(BigDecimal.valueOf(close + 30));
            r.setLowPoint(BigDecimal.valueOf(close - 30));
            r.setTradeValue(BigDecimal.valueOf(8.0e11));
            rows.add(r);
        }
        return rows;
    }

    /** 與 {@code MarketAnalysisService.taiexSeriesDesc} 相同的映射，供斷言比對。 */
    private static List<StockPriceHistory> descSeries(List<TwseIndexDailyHistory> rows) {
        List<StockPriceHistory> desc = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            desc.add(TechnicalIndicatorService.toRow(rows.get(i), "0000", "台股"));
        }
        return desc;
    }

    private static List<TwseIndexDailyHistory> reversed(List<TwseIndexDailyHistory> rows) {
        List<TwseIndexDailyHistory> out = new ArrayList<>(rows);
        java.util.Collections.reverse(out);
        return out;
    }

    private static PriceQueryService.LivePrice livePrice(LocalDate tradingDate, String price) {
        BigDecimal p = new BigDecimal(price);
        return new PriceQueryService.LivePrice("0000", "台股大盤", "台股", p, p, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, p, p, p, null, tradingDate.toString(),
                null, Boolean.FALSE, "test", null);
    }

    private static com.steven.assets.model.UsIndexDailyHistory us(String code, LocalDate d, String close) {
        com.steven.assets.model.UsIndexDailyHistory r = new com.steven.assets.model.UsIndexDailyHistory();
        r.setIndexCode(code);
        r.setTradingDate(d);
        r.setClosePoint(new BigDecimal(close));
        return r;
    }

    private static TwseInstitutionalDaily institutional(LocalDate d, Instant observedAt,
                                                        String foreignNet, String status) {
        return TwseInstitutionalDaily.builder()
                .tradingDate(d)
                .foreignNet(new BigDecimal(foreignNet))
                .trustNet(new BigDecimal("10000000"))
                .dealerNet(new BigDecimal("5000000"))
                .totalNet(new BigDecimal(foreignNet))
                .provider("twse")
                .sourceUrl("https://www.twse.com.tw/x")
                .observedAt(observedAt)
                .availabilityBasis("COMPLETED_CLOSE")
                .status(status)
                .build();
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
