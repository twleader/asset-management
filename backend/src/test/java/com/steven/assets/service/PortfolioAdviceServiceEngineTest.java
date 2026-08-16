package com.steven.assets.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.dto.InvestmentProfileInput;
import com.steven.assets.dto.PortfolioAdviceResult;
import com.steven.assets.dto.PortfolioAdviceSettingsDto;
import com.steven.assets.dto.RetirementProjectionDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.InvestmentProfile;
import com.steven.assets.model.PortfolioAdvice;
import com.steven.assets.model.PortfolioAdviceSetting;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankDepositRepository;
import com.steven.assets.repository.FundHoldingRepository;
import com.steven.assets.repository.InvestmentPlannedExpenseRepository;
import com.steven.assets.repository.InvestmentProfileRepository;
import com.steven.assets.repository.PortfolioAdviceRepository;
import com.steven.assets.repository.PortfolioAdviceSettingRepository;
import com.steven.assets.repository.StockHoldingRepository;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 339（Requirement 80）{@code PortfolioAdviceService} 的三檔位引擎分岔測試。
 *
 * <p>涵蓋 339.14 的 (b) 三類名稱與 {@code getCurrentAllocation()} 逐字相同、(c) 金額算術三檔共用同一支
 * {@code enrich}、(d) {@code local} 零 Anthropic client 互動且無金鑰仍成功、(e) {@code hybrid} 不掛
 * {@code web_search}／{@code references} 空／模型數字不被採信、(f) {@code llm} 既有行為不回歸、
 * (g) 本機檔位只到類別層級、(h) 風險評估走既有 {@code getProjection()}、(i) engine 白名單與 null 語意、
 * (j) {@code local} 同步落終態不經 {@code PROCESSING}、{@code hybrid} 維持非同步且自癒邏輯三檔共用。</p>
 */
class PortfolioAdviceServiceEngineTest {

    private static final BigDecimal TOTAL_ASSETS = new BigDecimal("10000000");
    private static final long OWNER_ID = 1L;

    private InvestmentProfileRepository profileRepo;
    private InvestmentPlannedExpenseRepository expenseRepo;
    private PortfolioAdviceRepository adviceRepo;
    private PortfolioAdviceSettingRepository settingRepo;
    private AssetSnapshotRepository snapshotRepo;
    private BankDepositRepository depositRepo;
    private FundHoldingRepository fundRepo;
    private StockHoldingRepository stockRepo;
    private RetirementProjectionService projectionService;
    private TenantGuard tenantGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 模擬 portfolio_advice 表：save 指派 id、findById 取回同一筆（背景執行緒收尾用得到）。 */
    private final ConcurrentHashMap<Long, PortfolioAdvice> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final List<String> savedStatuses = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        profileRepo = mock(InvestmentProfileRepository.class);
        expenseRepo = mock(InvestmentPlannedExpenseRepository.class);
        adviceRepo = mock(PortfolioAdviceRepository.class);
        settingRepo = mock(PortfolioAdviceSettingRepository.class);
        snapshotRepo = mock(AssetSnapshotRepository.class);
        depositRepo = mock(BankDepositRepository.class);
        fundRepo = mock(FundHoldingRepository.class);
        stockRepo = mock(StockHoldingRepository.class);
        projectionService = mock(RetirementProjectionService.class);
        tenantGuard = mock(TenantGuard.class);

        when(tenantGuard.requireCurrentUserId()).thenReturn(OWNER_ID);
        when(profileRepo.findByOwnerUserId(OWNER_ID)).thenReturn(Optional.of(profile()));
        when(profileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(expenseRepo.findByOwnerUserIdOrderByExpenseDate(OWNER_ID)).thenReturn(List.of());
        when(snapshotRepo.findLatest()).thenReturn(Optional.of(snapshot()));
        when(depositRepo.findWithBankBySnapshotId(any())).thenReturn(List.of());
        when(fundRepo.findBySnapshotId(any())).thenReturn(List.of());
        when(stockRepo.findBySnapshotId(any())).thenReturn(List.of());
        when(projectionService.project(any(), any(), any())).thenReturn(depletingProjection());
        when(adviceRepo.save(any())).thenAnswer(inv -> {
            PortfolioAdvice row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(seq.incrementAndGet());
            }
            savedStatuses.add(row.getStatus());
            store.put(row.getId(), row);
            return row;
        });
        when(adviceRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    }

    private PortfolioAdviceService newService(LocalPortfolioAllocationEngine engine, String apiKey) {
        PortfolioAdviceService svc = new PortfolioAdviceService(
                profileRepo, expenseRepo, adviceRepo, settingRepo, snapshotRepo,
                depositRepo, fundRepo, stockRepo, projectionService, objectMapper, tenantGuard, engine);
        ReflectionTestUtils.setField(svc, "apiKey", apiKey);
        ReflectionTestUtils.setField(svc, "defaultModel", "claude-opus-4-8");
        return svc;
    }

    private void engineSetting(String engine) {
        PortfolioAdviceSetting s = new PortfolioAdviceSetting();
        s.setId(PortfolioAdviceSetting.SINGLETON_ID);
        s.setEngine(engine);
        s.setModel("claude-opus-4-8");
        s.setEffort("medium");
        s.setWebSearchMaxUses(4);
        when(settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)).thenReturn(Optional.of(s));
    }

    // ===== (d)(j) local：無金鑰仍成功、零 Anthropic client 互動、同步落終態不經 PROCESSING =====

    @Test
    void localEngine_succeedsWithoutApiKey_andNeverBuildsAnthropicClient() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        PortfolioAdvice row = svc.generate(input());

        assertEquals(PortfolioAdvice.STATUS_OK, row.getStatus());
        assertNotEquals(PortfolioAdvice.STATUS_NOT_CONFIGURED, row.getStatus(),
                "engine 分岔必須在金鑰檢查之前，否則無金鑰的 local 會落 NOT_CONFIGURED");
        assertEquals(LocalPortfolioAllocationEngine.ENGINE_VERSION, row.getModel());
        assertEquals("local-allocation:v1", row.getModel());
        assertNotNull(row.getCompletedAt());
        assertNull(row.getRawResponse(), "本機路徑沒有模型原始回覆");
        // 替身斷言：Anthropic client 是 lazy 建立的，欄位仍為 null 即證明本次全程未建立、未呼叫
        assertNull(ReflectionTestUtils.getField(svc, "anthropicClient"));
        // 同步落終態：全程沒有任何一次以 PROCESSING 落庫
        assertFalse(savedStatuses.contains(PortfolioAdvice.STATUS_PROCESSING),
                "local 為毫秒級純計算，不得經過 PROCESSING：" + savedStatuses);
        assertEquals(List.of(PortfolioAdvice.STATUS_OK), savedStatuses);
    }

    // ===== (b) 三類名稱與既有 getCurrentAllocation() 逐字相同 =====

    @Test
    void localAssetClasses_matchGetCurrentAllocationVerbatim() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        List<String> existing = svc.getCurrentAllocation().items().stream()
                .map(CurrentAllocationDto.Item::assetClass).toList();
        PortfolioAdviceResult result = resultOf(svc.generate(input()));
        List<String> produced = result.targetAllocation().stream()
                .map(PortfolioAdviceResult.TargetAllocation::assetClass).toList();

        assertEquals(existing, produced, "本機檔位的三類名稱必須與 getCurrentAllocation() 逐字相同");
        assertEquals(List.of("存款（現金）", "信託基金", "股票"), produced);
    }

    // ===== (c) targetAmount／deltaAmount 由既有 enrich 產生（三檔位同一段程式碼）=====

    @Test
    void amounts_comeFromTheExistingEnrichArithmetic() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        LocalPortfolioAllocationEngine engine = new LocalPortfolioAllocationEngine();
        PortfolioAdviceService svc = newService(engine, "");

        PortfolioAdviceResult produced = resultOf(svc.generate(input()));

        for (PortfolioAdviceResult.TargetAllocation t : produced.targetAllocation()) {
            BigDecimal expectedTarget = TOTAL_ASSETS.multiply(t.targetPct())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            assertEquals(0, expectedTarget.compareTo(t.targetAmount()),
                    t.assetClass() + " 的 targetAmount 應為資產總額 × targetPct");
            assertEquals(0, expectedTarget.subtract(t.currentValue()).compareTo(t.deltaAmount()),
                    t.assetClass() + " 的 deltaAmount 應為 targetAmount − currentValue");
        }

        // 直接餵本機引擎的原始輸出給既有 enrich()，結果必須逐項相同 → 證明走的是同一段算術，沒有第二份實作
        PortfolioAdviceResult base = engine.evaluate(
                "BALANCED",
                LocalPortfolioAllocationEngine.yearsToRetirement(
                        LocalDate.now(), LocalDate.of(2045, 6, 30), LocalDate.of(1980, 1, 1)),
                svc.getCurrentAllocation(), depletingProjection());
        PortfolioAdviceResult viaEnrich = ReflectionTestUtils.invokeMethod(svc, "enrich", base, TOTAL_ASSETS);
        assertNotNull(viaEnrich);
        assertEquals(viaEnrich.targetAllocation(), produced.targetAllocation());
    }

    // ===== (g) 本機檔位的 rebalancePlan 只到類別層級 =====

    @Test
    void localRebalancePlan_isClassLevelOnly() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        PortfolioAdviceResult result = resultOf(svc.generate(input()));

        assertEquals(3, result.rebalancePlan().size());
        for (PortfolioAdviceResult.Rebalance r : result.rebalancePlan()) {
            assertEquals("整體", r.holding(), "不得出現個股代號或基金名稱");
            assertTrue(List.of("BUY", "SELL", "HOLD").contains(r.action()));
            assertTrue(r.estimatedAmount().signum() >= 0, "estimatedAmount 為差額絕對值");
        }
        assertEquals(LocalPortfolioAllocationEngine.TEMPLATE_DISCLAIMER, result.warnings().get(0));
        assertTrue(result.warnings().contains(LocalPortfolioAllocationEngine.NO_HOLDING_LEVEL_WARNING));
        assertTrue(result.references().isEmpty(), "local 的 references 固定為空陣列");
    }

    // ===== (h) riskAssessment 走既有 getProjection()，未複製第二份試算 =====

    @Test
    void riskAssessment_usesExistingRetirementProjectionService() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        PortfolioAdviceResult result = resultOf(svc.generate(input()));

        verify(projectionService, atLeastOnce()).project(any(), any(), any());
        assertTrue(result.riskAssessment().contains("83"), result.riskAssessment());
        assertTrue(result.riskAssessment().contains("2069"), result.riskAssessment());
    }

    // ===== (e) hybrid：不掛 web_search、references 空、模型數字一律以本機值覆蓋 =====

    @Test
    void hybridParams_haveNoWebSearchToolAndMuchLowerMaxTokens() {
        engineSetting(PortfolioAdviceService.ENGINE_HYBRID);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "test-key");

        MessageCreateParams params = svc.buildHybridParams("claude-haiku-4-5", "medium", "sys", "usr");

        assertTrue(params.tools().isEmpty() || params.tools().get().isEmpty(),
                "hybrid 檔位強制停用 web_search，送出的 params 不得含任何 tool");
        assertTrue(params.maxTokens() < 16000,
                "hybrid 只產兩段文字，maxTokens 須顯著低於完整版的 16000（實際 " + params.maxTokens() + "）");
    }

    @Test
    void hybridMerge_takesOnlyTwoTextFieldsAndKeepsLocalNumbers() {
        engineSetting(PortfolioAdviceService.ENGINE_HYBRID);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "test-key");
        PortfolioAdviceResult local = localResult();

        // 模型「多嘴」回了數字欄位與參考來源——一律不得採信
        String raw = """
                這是模型的前言，不該影響解析。
                {"summary":"AI 版總結","riskAssessment":"AI 版風險評估",
                 "targetAllocation":[{"assetClass":"股票","targetPct":99,"targetAmount":99999999,"deltaAmount":88888888}],
                 "rebalancePlan":[{"assetClass":"股票","holding":"2330","action":"BUY","estimatedAmount":1234567}],
                 "references":[{"title":"某新聞","url":"https://example.com"}]}
                """;

        PortfolioAdviceResult out = svc.mergeRefinement(local, raw);

        assertEquals("AI 版總結", out.summary());
        assertEquals("AI 版風險評估", out.riskAssessment());
        assertEquals(local.targetAllocation(), out.targetAllocation(), "數字欄位一律以本機值覆蓋，不採信模型");
        assertEquals(local.rebalancePlan(), out.rebalancePlan(), "不得出現模型自己編的個股層級動作");
        assertEquals(local.warnings(), out.warnings());
        assertTrue(out.references().isEmpty(), "hybrid 的 references 固定為空陣列");
    }

    @Test
    void hybridMerge_fallsBackToLocalTextWhenResponseUnusable() {
        engineSetting(PortfolioAdviceService.ENGINE_HYBRID);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "test-key");
        PortfolioAdviceResult local = localResult();

        PortfolioAdviceResult out = svc.mergeRefinement(local, "抱歉，我無法完成這個要求。");

        assertEquals(local.summary(), out.summary());
        assertEquals(local.riskAssessment(), out.riskAssessment());
        assertEquals(LocalPortfolioAllocationEngine.TEMPLATE_DISCLAIMER, out.warnings().get(0));
        assertTrue(out.warnings().stream().anyMatch(w -> w.contains("AI 潤飾未成功")), out.warnings().toString());
    }

    // ===== (j) hybrid 維持既有非同步形狀：先 PROCESSING，背景收尾 =====

    @Test
    void hybrid_landsProcessingFirstThenCompletesWithLocalNumbers() throws Exception {
        engineSetting(PortfolioAdviceService.ENGINE_HYBRID);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "test-key");
        MessageService messages = stubClient(svc, new IllegalStateException("api down"));

        PortfolioAdvice row = svc.generate(input());

        assertEquals(PortfolioAdvice.STATUS_PROCESSING, row.getStatus(), "hybrid 須維持既有非同步形狀");
        assertTrue(row.getModel().startsWith(PortfolioAdviceService.HYBRID_MODEL_PREFIX), row.getModel());
        assertEquals("hybrid-allocation:v1+claude-opus-4-8", row.getModel());
        assertTrue(row.getModel().length() <= 64);

        PortfolioAdvice done = awaitCompletion(row.getId());
        // 文字潤飾失敗不讓整筆建議失敗：仍為 OK，數字全為本機值
        assertEquals(PortfolioAdvice.STATUS_OK, done.getStatus());
        PortfolioAdviceResult result = resultOf(done);
        assertEquals(3, result.targetAllocation().size());
        assertTrue(result.references().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("AI 潤飾未成功")), result.warnings().toString());

        // 實際送出的 params 不含 web_search tool
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(captor.capture());
        assertTrue(captor.getValue().tools().isEmpty() || captor.getValue().tools().get().isEmpty());
    }

    @Test
    void hybrid_withoutApiKeyLandsNotConfigured() {
        engineSetting(PortfolioAdviceService.ENGINE_HYBRID);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        PortfolioAdvice row = svc.generate(input());

        assertEquals(PortfolioAdvice.STATUS_NOT_CONFIGURED, row.getStatus(),
                "金鑰檢查保留於 hybrid／llm 分支");
        assertNull(ReflectionTestUtils.getField(svc, "anthropicClient"));
    }

    // ===== (f) llm：既有行為不回歸 =====

    @Test
    void llmEngine_keepsNotConfiguredBehaviourAndNeverCallsLocalEngine() {
        engineSetting(PortfolioAdviceService.ENGINE_LLM);
        LocalPortfolioAllocationEngine engine = mock(LocalPortfolioAllocationEngine.class);
        PortfolioAdviceService svc = newService(engine, "");

        PortfolioAdvice row = svc.generate(input());

        assertEquals(PortfolioAdvice.STATUS_NOT_CONFIGURED, row.getStatus());
        assertEquals("claude-opus-4-8", row.getModel());
        verifyNoInteractions(engine);
    }

    @Test
    void llmEngine_stillAttachesWebSearchTool() throws Exception {
        engineSetting(PortfolioAdviceService.ENGINE_LLM);
        LocalPortfolioAllocationEngine engine = mock(LocalPortfolioAllocationEngine.class);
        PortfolioAdviceService svc = newService(engine, "test-key");
        MessageService messages = stubClient(svc, new IllegalStateException("api down"));

        PortfolioAdvice row = svc.generate(input());
        assertEquals(PortfolioAdvice.STATUS_PROCESSING, row.getStatus());
        awaitCompletion(row.getId());

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(captor.capture());
        List<ToolUnion> tools = captor.getValue().tools().orElse(List.of());
        assertEquals(1, tools.size(), "llm 檔位須維持既有 web_search");
        assertTrue(tools.get(0).isWebSearchTool20260209());
        assertEquals(4L, tools.get(0).webSearchTool20260209().orElseThrow().maxUses().orElseThrow());
        assertTrue(captor.getValue().maxTokens() > 4000, "llm 檔位仍用既有的 16000");
        verifyNoInteractions(engine);
    }

    @Test
    void llmEngine_referencesSanitizeStillDropsNonHttpUrls() {
        engineSetting(PortfolioAdviceService.ENGINE_LLM);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "test-key");
        PortfolioAdviceResult raw = new PortfolioAdviceResult("s", "r", List.of(), List.of(), List.of(),
                List.of(), List.of(
                new PortfolioAdviceResult.Reference("危險", "javascript:alert(1)"),
                new PortfolioAdviceResult.Reference("正常", "https://example.com/a")));

        PortfolioAdviceResult out = ReflectionTestUtils.invokeMethod(svc, "sanitize", raw);

        assertNotNull(out);
        assertEquals(2, out.references().size());
        assertNull(out.references().get(0).url(), "非 http(s) 來源須被淨化為 null");
        assertEquals("https://example.com/a", out.references().get(1).url());
    }

    // ===== (i) engine 白名單、null 不變語意、getSettings 曝露清單 =====

    @Test
    void updateSettings_rejectsUnknownEngine() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.updateSettings(null, null, null, "gpt"));
        assertTrue(ex.getMessage().contains("不支援的分析引擎：gpt"), ex.getMessage());
    }

    @Test
    void updateSettings_requiresAtLeastOneFieldAndMentionsEngine() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.updateSettings(null, null, null, null));
        assertTrue(ex.getMessage().contains("engine"), ex.getMessage());
    }

    @Test
    void updateSettings_nullMeansUnchanged() {
        engineSetting(PortfolioAdviceService.ENGINE_LOCAL);
        when(settingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        svc.updateSettings(null, null, null, PortfolioAdviceService.ENGINE_HYBRID);

        ArgumentCaptor<PortfolioAdviceSetting> captor = ArgumentCaptor.forClass(PortfolioAdviceSetting.class);
        verify(settingRepo).save(captor.capture());
        PortfolioAdviceSetting saved = captor.getValue();
        assertEquals(PortfolioAdviceService.ENGINE_HYBRID, saved.getEngine());
        assertEquals("claude-opus-4-8", saved.getModel());   // 未提供 → 不變
        assertEquals("medium", saved.getEffort());           // 未提供 → 不變
        assertEquals(4, saved.getWebSearchMaxUses());        // 未提供 → 不變
    }

    @Test
    void getSettings_exposesEngineAndItsWhitelist() {
        engineSetting(PortfolioAdviceService.ENGINE_HYBRID);
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");

        PortfolioAdviceSettingsDto dto = svc.getSettings();

        assertEquals(PortfolioAdviceService.ENGINE_HYBRID, dto.engine());
        assertEquals(List.of(PortfolioAdviceService.ENGINE_LOCAL, PortfolioAdviceService.ENGINE_HYBRID,
                        PortfolioAdviceService.ENGINE_LLM),
                dto.availableEngines().stream().map(PortfolioAdviceSettingsDto.EngineOption::id).toList());
        // 既有三個下拉的內容不回歸
        assertEquals(3, dto.availableModels().size());
        assertEquals(3, dto.availableEfforts().size());
        assertEquals(4, dto.availableWebSearches().size());
    }

    @Test
    void resolveEngine_fallsBackToLocalForUnknownOrMissingStoredValue() {
        PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");
        when(settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)).thenReturn(Optional.empty());
        assertEquals(PortfolioAdviceService.ENGINE_LOCAL, svc.resolveEngine());

        engineSetting("bogus");
        assertEquals(PortfolioAdviceService.ENGINE_LOCAL, svc.resolveEngine());
    }

    // ===== (j) latest() 的 PROCESSING 自癒邏輯三檔共用（不新增第二個自癒路徑）=====

    @Test
    void staleProcessingSelfHeal_isSharedByAllEngines() {
        for (String engine : List.of(PortfolioAdviceService.ENGINE_LOCAL,
                PortfolioAdviceService.ENGINE_HYBRID, PortfolioAdviceService.ENGINE_LLM)) {
            engineSetting(engine);
            PortfolioAdviceService svc = newService(new LocalPortfolioAllocationEngine(), "");
            PortfolioAdvice stale = new PortfolioAdvice();
            stale.setId(99L);
            stale.setOwnerUserId(OWNER_ID);
            stale.setStatus(PortfolioAdvice.STATUS_PROCESSING);
            stale.setCreatedAt(Instant.now().minusSeconds(3600));
            when(adviceRepo.findFirstByOwnerUserIdOrderByCreatedAtDesc(OWNER_ID))
                    .thenReturn(Optional.of(stale));

            PortfolioAdvice row = svc.latest();

            assertEquals(PortfolioAdvice.STATUS_FAILED, row.getStatus(), "engine=" + engine);
        }
    }

    // ===== 測試工具 =====

    /** 注入替身 client（{@code client()} 是 lazy 欄位），讓背景執行緒不會真的連外。 */
    private MessageService stubClient(PortfolioAdviceService svc, RuntimeException failure) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(MessageCreateParams.class))).thenThrow(failure);
        ReflectionTestUtils.setField(svc, "anthropicClient", client);
        return messages;
    }

    /** 等背景執行緒把該列改成非 PROCESSING（上限 5 秒）。 */
    private PortfolioAdvice awaitCompletion(Long adviceId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            PortfolioAdvice row = store.get(adviceId);
            if (row != null && !PortfolioAdvice.STATUS_PROCESSING.equals(row.getStatus())) {
                return row;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("背景執行緒未在 5 秒內完成（adviceId=" + adviceId + "）");
    }

    private PortfolioAdviceResult resultOf(PortfolioAdvice row) {
        try {
            assertNotNull(row.getResultJson(), "resultJson 不得為空");
            return objectMapper.readValue(row.getResultJson(), PortfolioAdviceResult.class);
        } catch (Exception e) {
            throw new AssertionError("resultJson 解析失敗：" + row.getResultJson(), e);
        }
    }

    /** 一份已 enrich、已帶 rebalancePlan 的本機結果（供 mergeRefinement 測試用）。 */
    private PortfolioAdviceResult localResult() {
        LocalPortfolioAllocationEngine engine = new LocalPortfolioAllocationEngine();
        PortfolioAdviceService svc = newService(engine, "");
        PortfolioAdviceResult base = engine.evaluate("BALANCED", 19,
                svc.getCurrentAllocation(), depletingProjection());
        PortfolioAdviceResult enriched = ReflectionTestUtils.invokeMethod(svc, "enrich", base, TOTAL_ASSETS);
        return engine.withRebalancePlan(enriched);
    }

    private static InvestmentProfileInput input() {
        return new InvestmentProfileInput(
                LocalDate.of(1980, 1, 1), new BigDecimal("1500000"), new BigDecimal("800000"),
                LocalDate.of(2045, 6, 30), null, null, null, null,
                new BigDecimal("2"), new BigDecimal("600000"), null, null, null, null,
                List.of("RETIREMENT"), "BALANCED", "R6_10", List.of());
    }

    private static InvestmentProfile profile() {
        InvestmentProfile p = new InvestmentProfile();
        p.setId(1L);
        p.setOwnerUserId(OWNER_ID);
        p.setBirthDate(LocalDate.of(1980, 1, 1));
        p.setRetirementDate(LocalDate.of(2045, 6, 30));
        p.setRiskTolerance("BALANCED");
        p.setExpectedAnnualReturn("R6_10");
        p.setGoals("RETIREMENT");
        return p;
    }

    private static AssetSnapshot snapshot() {
        return AssetSnapshot.builder()
                .id(7L)
                .ownerUserId(OWNER_ID)
                .snapshotDate(LocalDate.of(2026, 8, 15))
                .totalDeposit(new BigDecimal("4000000"))
                .totalFundValue(new BigDecimal("1000000"))
                .totalStockValue(new BigDecimal("5000000"))
                .totalAssets(TOTAL_ASSETS)
                .build();
    }

    private static RetirementProjectionDto depletingProjection() {
        RetirementProjectionDto.Assumptions assumptions = new RetirementProjectionDto.Assumptions(
                new BigDecimal("8.0"), new BigDecimal("4.5"), new BigDecimal("2"),
                new BigDecimal("600000"), null, null, true, true);
        return new RetirementProjectionDto(true, null, 46, 65, 100, TOTAL_ASSETS,
                assumptions, List.of(), new BigDecimal("20000000"), 83, 2069, false, null);
    }
}
