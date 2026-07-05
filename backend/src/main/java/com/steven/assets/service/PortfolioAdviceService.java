package com.steven.assets.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.dto.InvestmentProfileDto;
import com.steven.assets.dto.PortfolioAdviceResult;
import com.steven.assets.dto.PortfolioAdviceSettingsDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.model.FundHolding;
import com.steven.assets.model.InvestmentProfile;
import com.steven.assets.model.PortfolioAdvice;
import com.steven.assets.model.PortfolioAdviceSetting;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankDepositRepository;
import com.steven.assets.repository.FundHoldingRepository;
import com.steven.assets.repository.InvestmentProfileRepository;
import com.steven.assets.repository.PortfolioAdviceRepository;
import com.steven.assets.repository.PortfolioAdviceSettingRepository;
import com.steven.assets.repository.StockHoldingRepository;
import com.steven.assets.security.TenantGuard;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 資產配置建議（Requirement 32）。
 *
 * <p>使用者先在頁面填理財條件（年齡／投資年限／每月可投入／理財目標／風險承受度／獲利預期，存於
 * {@code investment_profile}），再按「產生建議」：本服務讀該使用者最新 {@code asset_snapshot} 的現況配置與持有明細，
 * 結合條件組提示詞，**同步**呼叫 Claude（adaptive thinking + 可選 {@code web_search} 納入當前市場）產出結構化建議
 * （整體評析／風險評估／建議目標配置／調整動作／風險提醒／參考來源），解析後存 {@code portfolio_advice}（歷次保存）。
 *
 * <p>與「今日股市分析」的差異：分析是每日排程、走 Batch API（省 50%、可等）；本功能是互動式即時需求，故走
 * 同步 Messages API（點按鈕即等結果）。同步在 request 執行緒內 owner context 已綁定，{@code @Filter}／
 * {@link TenantGuard} 正常運作。金鑰未設定或呼叫／解析失敗 → 落 {@code NOT_CONFIGURED}／{@code FAILED}，不拋出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioAdviceService {

    private static final int MAX_TOKENS = 16000;
    private static final int HISTORY_MAX = 100;
    /** PROCESSING 逾時：送出超過此時間仍未完成（背景執行緒異常／服務重啟中斷）→ 讀取時自癒判 FAILED，避免卡住轉圈。 */
    private static final Duration PROCESSING_STALE = Duration.ofMinutes(10);
    /** 提示詞內每類持有明細最多列出的檔數（個人資產通常遠少於此，僅防極端過長）。 */
    private static final int MAX_HOLDINGS_PER_CLASS = 40;

    // ===== 成本控管白名單（技術白名單，非業務分類，不入 /api/settings；比照 MarketAnalysisService）=====

    private static final List<PortfolioAdviceSettingsDto.ModelOption> AVAILABLE_MODELS = List.of(
            new PortfolioAdviceSettingsDto.ModelOption("claude-opus-4-8", "Opus 4.8（最佳品質）"),
            new PortfolioAdviceSettingsDto.ModelOption("claude-sonnet-5", "Sonnet 5（品質接近、較省）"),
            new PortfolioAdviceSettingsDto.ModelOption("claude-haiku-4-5", "Haiku 4.5（最省、較粗略）")
    );

    private static final List<PortfolioAdviceSettingsDto.EffortOption> AVAILABLE_EFFORTS = List.of(
            new PortfolioAdviceSettingsDto.EffortOption("low", "Low（最省、較快）"),
            new PortfolioAdviceSettingsDto.EffortOption("medium", "Medium（平衡，建議）"),
            new PortfolioAdviceSettingsDto.EffortOption("high", "High（最深入、最貴）")
    );

    private static final String DEFAULT_EFFORT = "medium";

    private static final List<PortfolioAdviceSettingsDto.WebSearchOption> AVAILABLE_WEB_SEARCHES = List.of(
            new PortfolioAdviceSettingsDto.WebSearchOption(0, "關閉（僅依個人資產與條件，最省）"),
            new PortfolioAdviceSettingsDto.WebSearchOption(3, "3 次（精簡）"),
            new PortfolioAdviceSettingsDto.WebSearchOption(4, "4 次（平衡，預設）"),
            new PortfolioAdviceSettingsDto.WebSearchOption(6, "6 次（完整）")
    );

    private static final int DEFAULT_WEB_SEARCH = 4;

    // ===== 表單詞彙白名單（理財目標／風險／獲利預期；本頁表單選項，非跨域分類）=====

    private static final List<InvestmentProfileDto.Option> GOAL_OPTIONS = List.of(
            new InvestmentProfileDto.Option("RETIREMENT", "退休準備"),
            new InvestmentProfileDto.Option("WEALTH_GROWTH", "資產增值"),
            new InvestmentProfileDto.Option("PASSIVE_INCOME", "被動收入（存股／領息）"),
            new InvestmentProfileDto.Option("CHILD_EDUCATION", "子女教育金"),
            new InvestmentProfileDto.Option("HOME_PURCHASE", "購屋置產"),
            new InvestmentProfileDto.Option("SHORT_TERM", "短期資金週轉"),
            new InvestmentProfileDto.Option("WEALTH_PRESERVATION", "財富保值／傳承")
    );

    private static final List<InvestmentProfileDto.Option> RISK_OPTIONS = List.of(
            new InvestmentProfileDto.Option("CONSERVATIVE", "保守（不能忍受本金明顯虧損）"),
            new InvestmentProfileDto.Option("BALANCED", "穩健（可承受中度波動）"),
            new InvestmentProfileDto.Option("AGGRESSIVE", "積極（可承受較大波動追求高報酬）")
    );

    private static final List<InvestmentProfileDto.Option> RETURN_OPTIONS = List.of(
            new InvestmentProfileDto.Option("LT3", "保守：年化報酬 < 3%"),
            new InvestmentProfileDto.Option("R3_6", "中低：年化報酬 3–6%"),
            new InvestmentProfileDto.Option("R6_10", "中高：年化報酬 6–10%"),
            new InvestmentProfileDto.Option("GT10", "積極：年化報酬 > 10%")
    );

    private final InvestmentProfileRepository profileRepo;
    private final PortfolioAdviceRepository adviceRepo;
    private final PortfolioAdviceSettingRepository settingRepo;
    private final AssetSnapshotRepository snapshotRepo;
    private final BankDepositRepository depositRepo;
    private final FundHoldingRepository fundRepo;
    private final StockHoldingRepository stockRepo;
    private final ObjectMapper objectMapper;
    private final TenantGuard tenantGuard;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-opus-4-8}")
    private String defaultModel;

    private volatile AnthropicClient anthropicClient;

    /**
     * 產生建議的背景執行緒池（非同步）：/generate 送出後立即回 PROCESSING、由此池跑 Claude 呼叫（數十秒），
     * 前端輪詢至 OK/FAILED。避免同步長連線撞 nginx/proxy 逾時（見 Requirement 32 設計）。daemon、小型固定池。
     */
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "portfolio-advice-gen");
        t.setDaemon(true);
        return t;
    });

    // ===== Profile（理財條件）=====

    /** 目前使用者的理財條件（含各欄位可選清單供表單 render）；無則回空 profile（僅帶清單）。 */
    public InvestmentProfileDto getProfile() {
        Long ownerId = tenantGuard.requireCurrentUserId();
        InvestmentProfile p = ownerId == null ? null : profileRepo.findByOwnerUserId(ownerId).orElse(null);
        return InvestmentProfileDto.from(p, GOAL_OPTIONS, RISK_OPTIONS, RETURN_OPTIONS);
    }

    /** upsert 目前使用者的理財條件（白名單驗證；無效 goals 略過），回更新後 DTO。 */
    public InvestmentProfileDto saveProfile(Integer age, Integer horizonYears, BigDecimal monthlyInvestment,
                                            YearMonth retirementDate,
                                            List<String> goals, String riskTolerance, String expectedReturn) {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            throw new IllegalStateException("無使用者情境，無法儲存理財條件");
        }
        validateRisk(riskTolerance);
        validateReturn(expectedReturn);
        InvestmentProfile p = profileRepo.findByOwnerUserId(ownerId).orElseGet(InvestmentProfile::new);
        p.setOwnerUserId(ownerId);
        p.setAge(age);
        p.setInvestmentHorizonYears(horizonYears);
        p.setMonthlyInvestment(monthlyInvestment);
        p.setRetirementDate(retirementDate);
        p.setGoals(joinGoals(goals));
        p.setRiskTolerance(blankToNull(riskTolerance));
        p.setExpectedAnnualReturn(blankToNull(expectedReturn));
        p.setUpdatedAt(Instant.now());
        InvestmentProfile saved = profileRepo.save(p);
        return InvestmentProfileDto.from(saved, GOAL_OPTIONS, RISK_OPTIONS, RETURN_OPTIONS);
    }

    // ===== 現況配置 =====

    /** 目前使用者最新快照的資產配置概覽（存款／基金／股票占比）；無快照回 empty。 */
    public CurrentAllocationDto getCurrentAllocation() {
        AssetSnapshot s = snapshotRepo.findLatest().orElse(null);
        if (s == null) {
            return CurrentAllocationDto.empty();
        }
        BigDecimal deposit = nz(s.getTotalDeposit());
        BigDecimal fund = nz(s.getTotalFundValue());
        BigDecimal stock = nz(s.getTotalStockValue());
        BigDecimal total = s.getTotalAssets() != null ? s.getTotalAssets() : deposit.add(fund).add(stock);
        List<CurrentAllocationDto.Item> items = new ArrayList<>();
        items.add(new CurrentAllocationDto.Item("存款（現金）", deposit, pct(deposit, total)));
        items.add(new CurrentAllocationDto.Item("信託基金", fund, pct(fund, total)));
        items.add(new CurrentAllocationDto.Item("股票", stock, pct(stock, total)));
        return new CurrentAllocationDto(s.getId(), s.getSnapshotDate(), total, items);
    }

    // ===== 建議查詢 =====

    public PortfolioAdvice latest() {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            return null;
        }
        PortfolioAdvice row = adviceRepo.findFirstByOwnerUserIdOrderByCreatedAtDesc(ownerId).orElse(null);
        // 自癒：PROCESSING 卡超過上限（背景執行緒中斷／服務重啟）→ 判 FAILED，避免前端無限輪詢
        if (row != null && PortfolioAdvice.STATUS_PROCESSING.equals(row.getStatus())
                && row.getCreatedAt() != null && row.getCreatedAt().isBefore(Instant.now().minus(PROCESSING_STALE))) {
            row.setStatus(PortfolioAdvice.STATUS_FAILED);
            row.setCompletedAt(Instant.now());
            row.setErrorMessage("產生逾時（可能因服務重啟中斷），請重新產生");
            row = save(row);
        }
        return row;
    }

    public List<PortfolioAdvice> history(int limit) {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            return List.of();
        }
        int n = Math.max(1, Math.min(limit, HISTORY_MAX));
        return adviceRepo.findRecentByOwner(ownerId, PageRequest.of(0, n));
    }

    // ===== 設定（模型／思考深度／web 搜尋）=====

    public String resolveModel() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getModel)
                .filter(m -> m != null && !m.isBlank())
                .orElse(defaultModel);
    }

    public String resolveEffort() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getEffort)
                .filter(e -> e != null && AVAILABLE_EFFORTS.stream().anyMatch(o -> o.id().equals(e)))
                .orElse(DEFAULT_EFFORT);
    }

    public int resolveWebSearchMaxUses() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getWebSearchMaxUses)
                .filter(v -> v != null && AVAILABLE_WEB_SEARCHES.stream().anyMatch(o -> o.value().equals(v)))
                .orElse(DEFAULT_WEB_SEARCH);
    }

    public PortfolioAdviceSettingsDto getSettings() {
        String currentModel = resolveModel();
        String currentEffort = resolveEffort();
        int currentWebSearch = resolveWebSearchMaxUses();

        List<PortfolioAdviceSettingsDto.ModelOption> models = new ArrayList<>(AVAILABLE_MODELS);
        if (models.stream().noneMatch(o -> o.id().equals(currentModel))) {
            models.add(0, new PortfolioAdviceSettingsDto.ModelOption(currentModel, currentModel));
        }
        List<PortfolioAdviceSettingsDto.EffortOption> efforts = new ArrayList<>(AVAILABLE_EFFORTS);
        if (efforts.stream().noneMatch(o -> o.id().equals(currentEffort))) {
            efforts.add(0, new PortfolioAdviceSettingsDto.EffortOption(currentEffort, currentEffort));
        }
        List<PortfolioAdviceSettingsDto.WebSearchOption> webSearches = new ArrayList<>(AVAILABLE_WEB_SEARCHES);
        if (webSearches.stream().noneMatch(o -> o.value().equals(currentWebSearch))) {
            webSearches.add(0, new PortfolioAdviceSettingsDto.WebSearchOption(currentWebSearch, currentWebSearch + " 次"));
        }
        return new PortfolioAdviceSettingsDto(currentModel, currentEffort, currentWebSearch, models, efforts, webSearches);
    }

    public PortfolioAdviceSettingsDto updateSettings(String model, String effort, Integer webSearchMaxUses) {
        if (model == null && effort == null && webSearchMaxUses == null) {
            throw new IllegalArgumentException("未提供任何可更新的設定（model / effort / webSearchMaxUses）");
        }
        if (model != null && AVAILABLE_MODELS.stream().noneMatch(o -> o.id().equals(model))) {
            throw new IllegalArgumentException("不支援的分析模型：" + model);
        }
        if (effort != null && AVAILABLE_EFFORTS.stream().noneMatch(o -> o.id().equals(effort))) {
            throw new IllegalArgumentException("不支援的思考深度：" + effort);
        }
        if (webSearchMaxUses != null && AVAILABLE_WEB_SEARCHES.stream().noneMatch(o -> o.value().equals(webSearchMaxUses))) {
            throw new IllegalArgumentException("不支援的 web 搜尋次數：" + webSearchMaxUses);
        }
        PortfolioAdviceSetting s = settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .orElseGet(PortfolioAdviceSetting::new);
        s.setId(PortfolioAdviceSetting.SINGLETON_ID);
        if (s.getModel() == null || s.getModel().isBlank()) {
            s.setModel(resolveModel());
        }
        if (s.getEffort() == null || s.getEffort().isBlank()) {
            s.setEffort(resolveEffort());
        }
        if (s.getWebSearchMaxUses() == null) {
            s.setWebSearchMaxUses(resolveWebSearchMaxUses());
        }
        if (model != null) {
            s.setModel(model);
        }
        if (effort != null) {
            s.setEffort(effort);
        }
        if (webSearchMaxUses != null) {
            s.setWebSearchMaxUses(webSearchMaxUses);
        }
        s.setUpdatedAt(Instant.now());
        settingRepo.save(s);
        log.info("資產配置建議：設定更新（model={}, effort={}, webSearchMaxUses={}）",
                s.getModel(), s.getEffort(), s.getWebSearchMaxUses());
        return getSettings();
    }

    // ===== 產生建議 =====

    /**
     * upsert 條件後，落一筆 {@code PROCESSING} 建議並立即回傳；實際 Claude 呼叫（數十秒）由背景執行緒
     * {@link #runGeneration} 進行，完成後把該列更新為 {@code OK}/{@code FAILED}。前端輪詢至非 PROCESSING。
     *
     * <p>非同步（非同步呼叫）是為避免同步長連線撞 nginx/proxy 60s 逾時；且送出即有 PROCESSING 回饋。
     * 多租戶：**在本（request）執行緒內**組好 system/user prompt（此時 owner filter 生效，取到正確的自己快照與明細），
     * 背景執行緒只做 Claude 呼叫並以 {@code adviceId} by-id 更新（不觸及 owner-scoped 查詢），
     * 避開背景執行緒 {@code TenantFilterAspect} 不啟用的坑。不拋出。
     */
    public PortfolioAdvice generate(Integer age, Integer horizonYears, BigDecimal monthlyInvestment,
                                    YearMonth retirementDate,
                                    List<String> goals, String riskTolerance, String expectedReturn) {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            throw new IllegalStateException("無使用者情境，無法產生資產配置建議");
        }
        // 先儲存條件（記住免重填、且作為本次建議的條件快照來源）
        saveProfile(age, horizonYears, monthlyInvestment, retirementDate, goals, riskTolerance, expectedReturn);
        InvestmentProfile profile = profileRepo.findByOwnerUserId(ownerId).orElseThrow();

        String model = resolveModel();
        int webSearchMaxUses = resolveWebSearchMaxUses();
        boolean webSearchOn = webSearchMaxUses > 0;
        String effort = resolveEffort();

        AssetSnapshot snapshot = snapshotRepo.findLatest().orElse(null);

        PortfolioAdvice row = new PortfolioAdvice();
        row.setOwnerUserId(ownerId);
        row.setCreatedAt(Instant.now());
        row.setModel(truncate(model, 64));
        // 條件快照
        row.setAge(profile.getAge());
        row.setInvestmentHorizonYears(profile.getInvestmentHorizonYears());
        row.setMonthlyInvestment(profile.getMonthlyInvestment());
        row.setGoals(profile.getGoals());
        row.setRiskTolerance(profile.getRiskTolerance());
        row.setExpectedAnnualReturn(profile.getExpectedAnnualReturn());
        // 資產依據
        if (snapshot != null) {
            row.setBasedOnSnapshotId(snapshot.getId());
            row.setBasedOnSnapshotDate(snapshot.getSnapshotDate());
            row.setBasedOnTotalAssets(snapshot.getTotalAssets());
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("資產配置建議：未設定 ANTHROPIC_API_KEY，跳過（NOT_CONFIGURED）");
            row.setStatus(PortfolioAdvice.STATUS_NOT_CONFIGURED);
            row.setCompletedAt(Instant.now());
            row.setErrorMessage("尚未設定 Anthropic API 金鑰（ANTHROPIC_API_KEY）");
            return save(row);
        }

        // 在 request 執行緒組 prompt（owner filter 生效 → 取到正確的自己快照與持有明細）
        String systemPrompt = buildSystemPrompt(webSearchOn);
        String userPrompt = buildUserPrompt(profile, snapshot, webSearchOn);

        row.setStatus(PortfolioAdvice.STATUS_PROCESSING);
        PortfolioAdvice saved = save(row);   // 立即落 PROCESSING，前端據以顯示「產生中」並輪詢
        final Long adviceId = saved.getId();

        log.info("資產配置建議：送出（owner={}, adviceId={}, model={}, effort={}, webSearchMaxUses={}, snapshot={}）",
                ownerId, adviceId, model, effort, webSearchMaxUses, snapshot == null ? "none" : snapshot.getId());
        try {
            generationExecutor.submit(() ->
                    runGeneration(adviceId, ownerId, model, effort, webSearchMaxUses, webSearchOn, systemPrompt, userPrompt));
        } catch (Exception e) {
            // 提交失敗（如關機中）→ 直接落 FAILED，避免卡 PROCESSING
            log.warn("資產配置建議：背景任務提交失敗（adviceId={}）: {}", adviceId, e.getMessage());
            saved.setStatus(PortfolioAdvice.STATUS_FAILED);
            saved.setCompletedAt(Instant.now());
            saved.setErrorMessage("背景任務提交失敗：" + truncate(e.getMessage(), 900));
            return save(saved);
        }
        return saved;
    }

    /**
     * 背景執行緒：實際呼叫 Claude、解析，並以 {@code adviceId} by-id 更新該列為 OK/FAILED。
     * 不觸及 owner-scoped 查詢（prompt 已於 request 執行緒組好）；{@code findById} 不受 {@code @Filter} 影響。不拋出。
     */
    private void runGeneration(Long adviceId, Long ownerId, String model, String effort, int webSearchMaxUses,
                               boolean webSearchOn, String systemPrompt, String userPrompt) {
        String rawText = null;
        String resultJson = null;
        String status;
        String error = null;
        try {
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens((long) MAX_TOKENS)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(mapEffort(effort)).build());
            if (webSearchOn) {
                pb.addTool(ToolUnion.ofWebSearchTool20260209(
                        WebSearchTool20260209.builder().maxUses((long) webSearchMaxUses).build()));
            }
            MessageCreateParams params = pb.system(systemPrompt).addUserMessage(userPrompt).build();

            Message resp = client().messages().create(params);
            rawText = extractText(resp);
            PortfolioAdviceResult result = parseResult(rawText);
            resultJson = objectMapper.writeValueAsString(sanitize(result));
            status = PortfolioAdvice.STATUS_OK;
            log.info("資產配置建議：完成（owner={}, adviceId={}, actions={}）",
                    ownerId, adviceId, result.actions() == null ? 0 : result.actions().size());
        } catch (Exception e) {
            log.warn("資產配置建議：產生失敗（owner={}, adviceId={}）: {}", ownerId, adviceId, e.getMessage(), e);
            status = PortfolioAdvice.STATUS_FAILED;
            error = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000);
        }
        // 更新結果（背景執行緒無 request context → 不啟用 ownerFilter；findById 本就不受 @Filter 影響）
        try {
            PortfolioAdvice row = adviceRepo.findById(adviceId).orElse(null);
            if (row == null) {
                log.warn("資產配置建議：找不到待更新列（adviceId={}）", adviceId);
                return;
            }
            row.setStatus(status);
            row.setCompletedAt(Instant.now());
            row.setRawResponse(truncate(rawText, 20000));
            row.setResultJson(resultJson);
            row.setErrorMessage(error);
            adviceRepo.save(row);
        } catch (Exception e) {
            log.error("資產配置建議：更新結果落庫失敗（adviceId={}）: {}", adviceId, e.getMessage(), e);
        }
    }

    private PortfolioAdvice save(PortfolioAdvice row) {
        try {
            return adviceRepo.save(row);
        } catch (Exception e) {
            log.error("資產配置建議：落庫失敗（owner={}）: {}", row.getOwnerUserId(), e.getMessage(), e);
            return row;
        }
    }

    // ===== Prompt =====

    private String buildSystemPrompt(boolean webSearchEnabled) {
        String marketPrinciple = webSearchEnabled
                ? "3. 可使用 web_search 參考「當前」總體經濟與市場狀況（利率／通膨走向、股債評價、匯率、重大風險事件等），把當前環境納入配置考量；references 附上你實際參考的來源（http/https 連結）。"
                : "3. 本次不提供網路搜尋（web_search 已停用）：請勿杜撰新聞或即時行情，僅依使用者條件與現有資產給一般性配置建議，references 一律回空陣列 []。";
        return """
            你是一位專業的個人理財與資產配置顧問。你的任務：根據「使用者的理財條件」與「使用者目前實際持有的資產」，
            給出個人化、可執行的資產配置建議。

            重要原則：
            1. 建議必須同時貼合使用者的年齡、投資年限、每月可投入金額、理財目標、風險承受度與獲利預期——風險承受度與投資年限是決定股債／現金比重的關鍵。
            1a. 退休兩階段：「每月可投入」僅在退休前（累積期）有效；退休後（退休日期之後）薪水停止、定期投入為 0，只能靠既有資產與其報酬支應。估算未來可累積金額時，每月投入只計算累積期的年數，切勿假設退休後仍持續投入。越接近或進入退休，配置應越保守（提高現金／固定收益、降低高波動部位）；退休後不可用「之後還會定期投入」來合理化承受更高風險或攤平短期虧損。若退休日期落在投資年限終點之後（無退休後階段），才可視為全期皆在累積。
            2. 先評估使用者「目前」的配置與風險（過度集中、現金過多／過少、與其風險屬性是否相稱），再提出「目標配置比例」與具體「調整動作」。targetAllocation 各類別的 targetPct 加總應約等於 100。
            %s
            4. 建議要具體且務實（可含定期定額、再平衡、緊急預備金、分散標的等），但**不得**推薦個股買賣時點或保證報酬。
            5. 全程使用台灣繁體中文；金額以新台幣（TWD）為單位。

            完成後，你的最後輸出「只包含一個 JSON 物件」，不要有任何多餘文字、不要用 markdown 反引號包裹，格式如下：
            {
              "summary": "一段話總結整體評析與核心建議方向（繁體中文）",
              "riskAssessment": "對使用者目前配置與風險的評估（繁體中文）",
              "targetAllocation": [
                {"assetClass": "現金/存款", "targetPct": 20, "rationale": "理由"},
                {"assetClass": "債券/固定收益", "targetPct": 15, "rationale": "理由"},
                {"assetClass": "台股", "targetPct": 30, "rationale": "理由"},
                {"assetClass": "海外股票", "targetPct": 25, "rationale": "理由"},
                {"assetClass": "其他（基金/REITs等）", "targetPct": 10, "rationale": "理由"}
              ],
              "actions": [
                {"title": "調整動作標題", "detail": "具體怎麼做（繁體中文）", "priority": "HIGH | MEDIUM | LOW"}
              ],
              "warnings": ["需提醒使用者注意的風險或前提（繁體中文）"],
              "references": [
                {"title": "參考來源標題", "url": "https://..."}
              ]
            }
            """.formatted(marketPrinciple);
    }

    private String buildUserPrompt(InvestmentProfile p, AssetSnapshot snapshot, boolean webSearchEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 使用者理財條件 ==\n");
        sb.append("目前年齡：").append(p.getAge() != null ? p.getAge() + " 歲" : "未提供").append("\n");
        sb.append("預計投資年限：").append(p.getInvestmentHorizonYears() != null ? p.getInvestmentHorizonYears() + " 年" : "未提供").append("\n");
        RetirementSpan span = retirementSpan(p.getRetirementDate(), p.getInvestmentHorizonYears());
        sb.append("預計退休日期：").append(p.getRetirementDate() != null ? p.getRetirementDate().toString() : "未提供").append("\n");
        if (p.getRetirementDate() != null && span.accumulationYears() != null) {
            String monthly = p.getMonthlyInvestment() != null ? money(p.getMonthlyInvestment()) + " 元" : "0 元";
            sb.append("累積期：").append(span.accumulationYears()).append(" 年（退休前，每月可投入 ").append(monthly).append("）\n");
            if (span.retirementYears() != null) {
                sb.append("退休後守成／提領期：").append(span.retirementYears())
                        .append(" 年（退休後薪水停止，每月投入為 0，僅靠既有資產與其報酬）\n");
            }
            BigDecimal projected = projectedContribution(p.getMonthlyInvestment(), span.accumulationYears());
            sb.append("退休前預估可再投入本金合計（僅累積期，退休後不計）：約 ").append(money(projected)).append(" 元\n");
        } else {
            sb.append("每月可投入金額：").append(p.getMonthlyInvestment() != null ? money(p.getMonthlyInvestment()) + " 元" : "未提供").append("（未提供退休日期，視為整段投資年限皆可投入）\n");
        }
        sb.append("理財目標：").append(goalLabels(p.getGoals())).append("\n");
        sb.append("風險承受度：").append(labelOf(RISK_OPTIONS, p.getRiskTolerance())).append("\n");
        sb.append("獲利預期：").append(labelOf(RETURN_OPTIONS, p.getExpectedAnnualReturn())).append("\n\n");

        if (snapshot == null) {
            sb.append("== 使用者目前資產 ==\n（尚無任何資產快照）——請針對其條件，給一般性的「起始配置」建議。\n\n");
        } else {
            BigDecimal deposit = nz(snapshot.getTotalDeposit());
            BigDecimal fund = nz(snapshot.getTotalFundValue());
            BigDecimal stock = nz(snapshot.getTotalStockValue());
            BigDecimal total = snapshot.getTotalAssets() != null ? snapshot.getTotalAssets() : deposit.add(fund).add(stock);
            sb.append("== 使用者目前資產（快照日 ").append(snapshot.getSnapshotDate()).append("，單位：新台幣）==\n");
            sb.append("資產總額：").append(money(total)).append(" 元\n");
            sb.append(String.format("現況配置：存款（現金）%s 元（%s%%）、信託基金 %s 元（%s%%）、股票 %s 元（%s%%）%n",
                    money(deposit), pct(deposit, total).toPlainString(),
                    money(fund), pct(fund, total).toPlainString(),
                    money(stock), pct(stock, total).toPlainString()));
            if (snapshot.getEstimatedAnnualDividend() != null) {
                sb.append("預估年配息：").append(money(snapshot.getEstimatedAnnualDividend())).append(" 元\n");
            }
            sb.append("\n");
            appendHoldings(sb, snapshot.getId());
        }

        if (webSearchEnabled) {
            sb.append("請結合當前總經／市場狀況（可 web_search）與上述條件、現有資產，給出個人化配置建議並輸出指定 JSON。");
        } else {
            sb.append("請依上述條件與現有資產，給出個人化配置建議並輸出指定 JSON（本次不搜尋網路，references 回空陣列）。");
        }
        return sb.toString();
    }

    /** 追加各類持有明細（存款／基金／股票），每類至多 {@link #MAX_HOLDINGS_PER_CLASS} 檔。 */
    private void appendHoldings(StringBuilder sb, Long snapshotId) {
        List<BankDeposit> deposits = depositRepo.findBySnapshotId(snapshotId);
        if (!deposits.isEmpty()) {
            sb.append("存款明細：\n");
            int n = 0;
            for (BankDeposit d : deposits) {
                if (n++ >= MAX_HOLDINGS_PER_CLASS) { sb.append("  …（其餘略）\n"); break; }
                sb.append("  - ").append(safe(d.getDepositType())).append("：").append(money(d.getAmount())).append(" 元");
                if (d.getAnnualInterestRate() != null) {
                    sb.append("（年利率 ").append(d.getAnnualInterestRate().toPlainString()).append("%）");
                }
                if (d.getCurrency() != null && !"TWD".equals(d.getCurrency())) {
                    sb.append("（原幣 ").append(d.getCurrency()).append("）");
                }
                sb.append("\n");
            }
        }
        List<FundHolding> funds = fundRepo.findBySnapshotId(snapshotId);
        if (!funds.isEmpty()) {
            sb.append("信託基金明細：\n");
            int n = 0;
            for (FundHolding f : funds) {
                if (n++ >= MAX_HOLDINGS_PER_CLASS) { sb.append("  …（其餘略）\n"); break; }
                sb.append("  - ").append(safe(f.getFundName())).append("：現值 ").append(money(f.getCurrentValue())).append(" 元");
                if (f.getEstimatedDividend() != null && f.getEstimatedDividend().signum() > 0) {
                    sb.append("（預估年配息 ").append(money(f.getEstimatedDividend())).append(" 元）");
                }
                sb.append("\n");
            }
        }
        List<StockHolding> stocks = stockRepo.findBySnapshotId(snapshotId);
        if (!stocks.isEmpty()) {
            sb.append("股票明細：\n");
            int n = 0;
            for (StockHolding s : stocks) {
                if (n++ >= MAX_HOLDINGS_PER_CLASS) { sb.append("  …（其餘略）\n"); break; }
                sb.append("  - ").append(safe(s.getStockCode()));
                if (s.getMarket() != null) sb.append("（").append(s.getMarket()).append("）");
                sb.append("：現值 ").append(money(s.getCurrentValue())).append(" 元");
                if (s.getDividendRate() != null && s.getDividendRate().signum() > 0) {
                    sb.append("（配息率 ").append(s.getDividendRate().multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP).toPlainString()).append("%）");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    // ===== 退休兩階段（衍生，不入庫）=====

    /**
     * 依退休年月與投資年限推導兩階段年數（衍生值，不入庫）。
     * accumulationYears：今天 → 退休年月的整月數 / 12「無條件捨去」（滿一年才算一年，避免高估退休前可投入期間）。
     * 退休晚於投資終點時：accumulationYears 夾為投資年限、retirementYears = 0（無退休後階段）。
     * 退休已過（≤今天）：accumulationYears = 0（累積期已結束）。
     */
    private RetirementSpan retirementSpan(YearMonth retirementDate, Integer horizonYears) {
        if (retirementDate == null) {
            return new RetirementSpan(null, null);
        }
        YearMonth now = YearMonth.now();
        long monthsToRetire = ChronoUnit.MONTHS.between(now, retirementDate);
        int accumulationYears = (int) Math.max(0, monthsToRetire / 12); // 整數除法即無條件捨去
        if (horizonYears == null) {
            return new RetirementSpan(accumulationYears, null);
        }
        accumulationYears = Math.min(accumulationYears, horizonYears);
        int retirementYears = Math.max(0, horizonYears - accumulationYears);
        return new RetirementSpan(accumulationYears, retirementYears);
    }

    /** 退休前累積年數 / 退休後守成年數（衍生，不入庫）。 */
    private record RetirementSpan(Integer accumulationYears, Integer retirementYears) {}

    /**
     * 估算「未來可投入的定期投入總額」——月投入只在累積期（退休前）有效，退休後每月投入視為 0。
     * 用 accumulationYears（而非 investmentHorizonYears）當乘數。
     */
    private BigDecimal projectedContribution(BigDecimal monthlyInvestment, Integer accumulationYears) {
        if (monthlyInvestment == null || accumulationYears == null || accumulationYears <= 0) {
            return BigDecimal.ZERO;
        }
        return monthlyInvestment
                .multiply(BigDecimal.valueOf(12L))
                .multiply(BigDecimal.valueOf(accumulationYears));
    }

    // ===== 回覆解析 =====

    private String extractText(Message resp) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.content()) {
            block.text().ifPresent(t -> sb.append(t.text()).append("\n"));
        }
        return sb.toString().trim();
    }

    private PortfolioAdviceResult parseResult(String text) throws Exception {
        if (text == null) throw new IllegalStateException("模型回覆為空");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("模型回覆不含 JSON 物件");
        }
        return objectMapper.readValue(text.substring(start, end + 1), PortfolioAdviceResult.class);
    }

    /** 淨化解析結果：references 僅保留 http(s)（web_search 為不可信來源，防前端 href XSS，前端另有一層）。 */
    private PortfolioAdviceResult sanitize(PortfolioAdviceResult r) {
        List<PortfolioAdviceResult.Reference> refs = new ArrayList<>();
        if (r.references() != null) {
            for (PortfolioAdviceResult.Reference ref : r.references()) {
                if (ref == null) continue;
                String url = safeHttpUrl(ref.url());
                if (ref.title() == null && url == null) continue;
                refs.add(new PortfolioAdviceResult.Reference(ref.title(), url));
            }
        }
        return new PortfolioAdviceResult(
                r.summary(), r.riskAssessment(), r.targetAllocation(), r.actions(), r.warnings(), refs);
    }

    private String safeHttpUrl(String url) {
        if (url == null) return null;
        String u = url.trim().toLowerCase();
        return (u.startsWith("http://") || u.startsWith("https://")) ? url.trim() : null;
    }

    // ===== Anthropic client =====

    private AnthropicClient client() {
        AnthropicClient c = anthropicClient;
        if (c == null) {
            synchronized (this) {
                c = anthropicClient;
                if (c == null) {
                    c = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                    anthropicClient = c;
                }
            }
        }
        return c;
    }

    @PreDestroy
    void shutdown() {
        generationExecutor.shutdown();
        try {
            if (!generationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                generationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            generationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AnthropicClient c = anthropicClient;
        if (c instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("關閉 Anthropic client: {}", e.getMessage());
            }
        }
    }

    private OutputConfig.Effort mapEffort(String effort) {
        return switch (effort == null ? "" : effort) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    // ===== 小工具 =====

    private void validateRisk(String risk) {
        if (risk != null && !risk.isBlank() && RISK_OPTIONS.stream().noneMatch(o -> o.id().equals(risk))) {
            throw new IllegalArgumentException("不支援的風險承受度：" + risk);
        }
    }

    private void validateReturn(String ret) {
        if (ret != null && !ret.isBlank() && RETURN_OPTIONS.stream().noneMatch(o -> o.id().equals(ret))) {
            throw new IllegalArgumentException("不支援的獲利預期：" + ret);
        }
    }

    /** 有效 goals code 以逗號串接（白名單過濾、去重、保序）；無則 null。 */
    private String joinGoals(List<String> goals) {
        if (goals == null || goals.isEmpty()) return null;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String g : goals) {
            if (g == null) continue;
            String code = g.trim();
            if (GOAL_OPTIONS.stream().anyMatch(o -> o.id().equals(code))) {
                seen.put(code, Boolean.TRUE);
            }
        }
        return seen.isEmpty() ? null : String.join(",", seen.keySet());
    }

    /** 逗號分隔 goals code → 「退休準備、資產增值」中文標籤串。 */
    private String goalLabels(String goalsCsv) {
        if (goalsCsv == null || goalsCsv.isBlank()) return "未提供";
        List<String> labels = new ArrayList<>();
        for (String code : goalsCsv.split(",")) {
            labels.add(labelOf(GOAL_OPTIONS, code.trim()));
        }
        return String.join("、", labels);
    }

    private String labelOf(List<InvestmentProfileDto.Option> options, String id) {
        if (id == null || id.isBlank()) return "未提供";
        return options.stream().filter(o -> o.id().equals(id)).map(InvestmentProfileDto.Option::label)
                .findFirst().orElse(id);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 占比%（保留 1 位小數）；total<=0 回 0。 */
    private static BigDecimal pct(BigDecimal value, BigDecimal total) {
        if (total == null || total.signum() <= 0) return BigDecimal.ZERO;
        return value.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.0f", v.setScale(0, RoundingMode.HALF_UP));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
