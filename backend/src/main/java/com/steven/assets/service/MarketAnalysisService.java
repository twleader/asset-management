package com.steven.assets.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.MarketAnalysisDto;
import com.steven.assets.dto.MarketAnalysisResult;
import com.steven.assets.dto.MarketAnalysisSettingsDto;
import com.steven.assets.model.DailyMarketAnalysis;
import com.steven.assets.model.MarketAnalysisSetting;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.DailyMarketAnalysisRepository;
import com.steven.assets.repository.MarketAnalysisSettingRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 今日股市分析（Requirement 31）。
 *
 * <p>每個台股交易日 07:30 由 {@code MarketAnalysisScheduler} 觸發（或管理者手動）：讀本地
 * 台股大盤 / 美股主要指數近一年日線走勢，組「越近期越重要」提示詞，呼叫 Claude Opus 4.8
 * （adaptive thinking + {@code web_search} server tool 即時搜近期財經新聞），解析 JSON 判斷 upsert
 * 進 {@code daily_market_analysis}。
 *
 * <p>優雅降級：{@code ANTHROPIC_API_KEY} 未設定 → {@code NOT_CONFIGURED}；呼叫／解析失敗 →
 * {@code FAILED} + 錯誤摘要 + 原始回覆，皆不拋出中斷排程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketAnalysisService {

    /** 美股主要指數（作為台股走向判斷的輸入）。 */
    private static final List<String> US_INDEX_CODES = List.of("DJI", "SPX", "IXIC", "SOX");
    private static final int MAX_TOKENS = 16000;

    /** 批次逾時上限：送出超過此時間仍未 ENDED 則判 FAILED（Anthropic 批次上限 24h，取較保守值）。 */
    private static final Duration BATCH_MAX_AGE = Duration.ofHours(12);

    /**
     * 可選分析模型白名單——有效 Claude model id 的技術白名單（非使用者可自訂之業務分類，
     * 故不套用「Enum 必須入庫由 /api/settings 管理」規範）。頁面下拉即由此提供。
     */
    private static final List<MarketAnalysisSettingsDto.ModelOption> AVAILABLE_MODELS = List.of(
            new MarketAnalysisSettingsDto.ModelOption("claude-opus-4-8", "Opus 4.8（最佳品質）"),
            new MarketAnalysisSettingsDto.ModelOption("claude-sonnet-5", "Sonnet 5（品質接近、較省）"),
            new MarketAnalysisSettingsDto.ModelOption("claude-haiku-4-5", "Haiku 4.5（最省、較粗略）")
    );

    /**
     * 可選思考深度（effort）白名單——對應 Anthropic {@code output_config.effort}。thinking 輸出按 output token
     * 計價（最貴），effort 越低思考 token 越少、越省。僅提供 low/medium/high（{@code xhigh/max} 更貴、
     * 與本頁「成本控管」目的相反故不列）。同屬技術白名單（非使用者可自訂之業務分類），不套用「Enum 入庫管理」規範。
     */
    private static final List<MarketAnalysisSettingsDto.EffortOption> AVAILABLE_EFFORTS = List.of(
            new MarketAnalysisSettingsDto.EffortOption("low", "Low（最省、較快）"),
            new MarketAnalysisSettingsDto.EffortOption("medium", "Medium（平衡，建議）"),
            new MarketAnalysisSettingsDto.EffortOption("high", "High（最深入、最貴）")
    );

    /** 設定表未設或後備時使用的思考深度。medium＝成本/品質平衡（較未指定時的 API 預設 high 省）。 */
    private static final String DEFAULT_EFFORT = "medium";

    /**
     * 可選新聞搜尋次數（web_search {@code maxUses}）白名單。次數越少，灌回 context 的搜尋結果越少、
     * 每輪重複處理的 token 越省；{@code 0} 表示關閉（不加 web_search tool，純技術面）。同屬技術白名單。
     */
    private static final List<MarketAnalysisSettingsDto.WebSearchOption> AVAILABLE_WEB_SEARCHES = List.of(
            new MarketAnalysisSettingsDto.WebSearchOption(0, "關閉（純技術面，最省）"),
            new MarketAnalysisSettingsDto.WebSearchOption(3, "3 次（精簡）"),
            new MarketAnalysisSettingsDto.WebSearchOption(4, "4 次（平衡）"),
            new MarketAnalysisSettingsDto.WebSearchOption(6, "6 次（完整，預設）")
    );

    /** 設定表未設或後備時使用的新聞搜尋次數。6＝維持既有行為（新聞面最完整）。 */
    private static final int DEFAULT_WEB_SEARCH = 6;

    private final DailyMarketAnalysisRepository analysisRepo;
    private final MarketAnalysisSettingRepository settingRepo;
    private final TwseIndexDailyHistoryRepository twseRepo;
    private final UsIndexDailyHistoryRepository usRepo;
    private final ObjectMapper objectMapper;
    private final MarketAnalysisEmailDispatcher emailDispatcher;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    /** 環境預設模型（設定表未設或後備時使用）。 */
    @Value("${anthropic.model:claude-opus-4-8}")
    private String defaultModel;

    /** 序列化 generate()：避免 cron 排程 / 開機 self-heal / 管理者手動 同時對同一交易日重複昂貴呼叫＋覆蓋。 */
    private final ReentrantLock generateLock = new ReentrantLock();

    /** 長生命週期共用 client（OkHttp 設計為可共享、執行緒安全）；lazy 建立，容器關閉時 close。 */
    private volatile AnthropicClient anthropicClient;

    // ===== 對外查詢 =====

    public DailyMarketAnalysis latest() {
        return analysisRepo.findTopByOrderByAnalysisDateDesc().orElse(null);
    }

    public List<DailyMarketAnalysis> history(int limit) {
        return analysisRepo.findRecent(Math.max(1, Math.min(limit, 365)));
    }

    public boolean hasOkFor(LocalDate date) {
        return analysisRepo.findById(date)
                .map(a -> DailyMarketAnalysis.STATUS_OK.equals(a.getStatus()))
                .orElse(false);
    }

    // ===== 設定（模型 / 思考深度）=====

    /** 解析要用的模型：設定表 model（非空）→ 否則環境預設。 */
    public String resolveModel() {
        return settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .map(MarketAnalysisSetting::getModel)
                .filter(m -> m != null && !m.isBlank())
                .orElse(defaultModel);
    }

    /** 解析要用的思考深度 effort：設定表 effort（白名單內）→ 否則 {@link #DEFAULT_EFFORT}。 */
    public String resolveEffort() {
        return settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .map(MarketAnalysisSetting::getEffort)
                .filter(e -> e != null && AVAILABLE_EFFORTS.stream().anyMatch(o -> o.id().equals(e)))
                .orElse(DEFAULT_EFFORT);
    }

    /** 解析要用的新聞搜尋次數：設定表 web_search_max_uses（白名單內）→ 否則 {@link #DEFAULT_WEB_SEARCH}。 */
    public int resolveWebSearchMaxUses() {
        return settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .map(MarketAnalysisSetting::getWebSearchMaxUses)
                .filter(v -> v != null && AVAILABLE_WEB_SEARCHES.stream().anyMatch(o -> o.value().equals(v)))
                .orElse(DEFAULT_WEB_SEARCH);
    }

    /** 每日自動分析是否啟用：設定表 enabled → 否則預設 true（維持既有行為）。手動觸發不受此限。 */
    public boolean isEnabled() {
        return settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .map(MarketAnalysisSetting::getEnabled)
                .orElse(Boolean.TRUE);
    }

    /** 目前設定 + 可選模型／思考深度／新聞搜尋次數清單（若現值不在白名單則補入，確保下拉恆含現值）。 */
    public MarketAnalysisSettingsDto getSettings() {
        String currentModel = resolveModel();
        String currentEffort = resolveEffort();
        int currentWebSearch = resolveWebSearchMaxUses();
        boolean currentEnabled = isEnabled();

        List<MarketAnalysisSettingsDto.ModelOption> models = new ArrayList<>(AVAILABLE_MODELS);
        if (models.stream().noneMatch(o -> o.id().equals(currentModel))) {
            models.add(0, new MarketAnalysisSettingsDto.ModelOption(currentModel, currentModel));
        }
        List<MarketAnalysisSettingsDto.EffortOption> efforts = new ArrayList<>(AVAILABLE_EFFORTS);
        if (efforts.stream().noneMatch(o -> o.id().equals(currentEffort))) {
            efforts.add(0, new MarketAnalysisSettingsDto.EffortOption(currentEffort, currentEffort));
        }
        List<MarketAnalysisSettingsDto.WebSearchOption> webSearches = new ArrayList<>(AVAILABLE_WEB_SEARCHES);
        if (webSearches.stream().noneMatch(o -> o.value().equals(currentWebSearch))) {
            webSearches.add(0, new MarketAnalysisSettingsDto.WebSearchOption(currentWebSearch, currentWebSearch + " 次"));
        }
        return new MarketAnalysisSettingsDto(
                currentModel, currentEffort, currentWebSearch, currentEnabled, models, efforts, webSearches);
    }

    /**
     * 更新分析設定（模型／思考深度／新聞搜尋次數限白名單；enabled 為開關）：null 表示該欄不變；至少須提供一項。
     * 回傳更新後設定。
     */
    public MarketAnalysisSettingsDto updateSettings(String model, String effort, Integer webSearchMaxUses, Boolean enabled) {
        if (model == null && effort == null && webSearchMaxUses == null && enabled == null) {
            throw new IllegalArgumentException("未提供任何可更新的設定（model / effort / webSearchMaxUses / enabled）");
        }
        if (model != null && AVAILABLE_MODELS.stream().noneMatch(o -> o.id().equals(model))) {
            throw new IllegalArgumentException("不支援的分析模型：" + model);
        }
        if (effort != null && AVAILABLE_EFFORTS.stream().noneMatch(o -> o.id().equals(effort))) {
            throw new IllegalArgumentException("不支援的思考深度：" + effort);
        }
        if (webSearchMaxUses != null && AVAILABLE_WEB_SEARCHES.stream().noneMatch(o -> o.value().equals(webSearchMaxUses))) {
            throw new IllegalArgumentException("不支援的新聞搜尋次數：" + webSearchMaxUses);
        }
        MarketAnalysisSetting s = settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .orElseGet(MarketAnalysisSetting::new);
        s.setId(MarketAnalysisSetting.SINGLETON_ID);
        // 既有列各欄已非空（欄位 NOT NULL + seed/預設）；新建列先以現行解析值補齊未指定欄，避免 NOT NULL 違反
        if (s.getModel() == null || s.getModel().isBlank()) {
            s.setModel(resolveModel());
        }
        if (s.getEffort() == null || s.getEffort().isBlank()) {
            s.setEffort(resolveEffort());
        }
        if (s.getWebSearchMaxUses() == null) {
            s.setWebSearchMaxUses(resolveWebSearchMaxUses());
        }
        if (s.getEnabled() == null) {
            s.setEnabled(isEnabled());
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
        if (enabled != null) {
            s.setEnabled(enabled);
        }
        s.setUpdatedAt(LocalDateTime.now());
        settingRepo.save(s);
        log.info("今日股市分析：設定更新（model={}, effort={}, webSearchMaxUses={}, enabled={}）",
                s.getModel(), s.getEffort(), s.getWebSearchMaxUses(), s.getEnabled());
        return getSettings();
    }

    /** 字串 effort → Anthropic {@code OutputConfig.Effort}（白名單保證非法值不會到這，後備 MEDIUM）。 */
    private OutputConfig.Effort mapEffort(String effort) {
        return switch (effort == null ? "" : effort) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    // ===== 產生 =====

    /**
     * 產生（或重跑）指定交易日的分析並 upsert。不拋出：失敗以 FAILED / NOT_CONFIGURED 落庫並回傳。
     * 管理者手動觸發用（無論當日是否已成功，皆重跑）。
     */
    public DailyMarketAnalysis generate(LocalDate date, String trigger) {
        return generateInternal(date, trigger, false);
    }

    /**
     * 排程 / self-heal 用：以 {@link #generateLock} 序列化，若當日已有成功分析則略過，
     * 避免 cron 與 self-heal 同時對同一交易日重複昂貴呼叫＋覆蓋。
     */
    public DailyMarketAnalysis generateIfAbsent(LocalDate date, String trigger) {
        return generateInternal(date, trigger, true);
    }

    private DailyMarketAnalysis generateInternal(LocalDate date, String trigger, boolean skipIfAlreadyOk) {
        generateLock.lock();
        try {
            DailyMarketAnalysis existing = analysisRepo.findById(date).orElse(null);
            // 已在批次處理中：不論排程或手動皆不重複送出（避免重複花費＋覆蓋在製批次）
            if (existing != null && DailyMarketAnalysis.STATUS_PROCESSING.equals(existing.getStatus())) {
                log.info("今日股市分析：{} 已有在製批次（batchId={}），略過重複送出（trigger={}）",
                        date, existing.getBatchId(), trigger);
                return existing;
            }
            // 排程／self-heal：已成功則略過（手動則允許重跑）
            if (skipIfAlreadyOk && existing != null && DailyMarketAnalysis.STATUS_OK.equals(existing.getStatus())) {
                log.info("今日股市分析：{} 已有成功分析，略過（trigger={}）", date, trigger);
                return existing;
            }
            return submitBatch(date, trigger, existing);
        } finally {
            generateLock.unlock();
        }
    }

    /**
     * 送出 Batch API 批次（1 request，省 50% token 成本），落 {@code PROCESSING} + {@code batch_id} 後立即回；
     * 結果由 {@link #pollPendingBatches()} 於批次 {@code ENDED} 後收尾。不拋出。
     */
    private DailyMarketAnalysis submitBatch(LocalDate date, String trigger, DailyMarketAnalysis existing) {
        String model = resolveModel();
        String effort = resolveEffort();
        int webSearchMaxUses = resolveWebSearchMaxUses();
        boolean webSearchOn = webSearchMaxUses > 0;
        log.info("今日股市分析：送出批次（date={}, trigger={}, model={}, effort={}, webSearchMaxUses={}）",
                date, trigger, model, effort, webSearchMaxUses);

        DailyMarketAnalysis row = existing != null ? existing : new DailyMarketAnalysis();
        row.setAnalysisDate(date);
        row.setModel(truncate(model, 64));
        row.setGeneratedAt(Instant.now());   // 送出時間；PROCESSING 期間供 poller 逾時判斷
        row.setBatchId(null);
        // 重跑既有 OK 筆時，先清掉上一次成功內容——PROCESSING／非 OK 不得帶出過期的多空判斷／新聞
        clearContent(row);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("今日股市分析：未設定 ANTHROPIC_API_KEY，跳過（NOT_CONFIGURED）");
            row.setStatus(DailyMarketAnalysis.STATUS_NOT_CONFIGURED);
            row.setErrorMessage("尚未設定 Anthropic API 金鑰（ANTHROPIC_API_KEY）");
            return save(row, date);
        }

        try {
            BatchCreateParams.Request.Params.Builder pb = BatchCreateParams.Request.Params.builder()
                    .model(model)
                    .maxTokens((long) MAX_TOKENS)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(mapEffort(effort)).build());
            // webSearchMaxUses=0 → 不加 web_search tool（純技術面）；>0 → 加上並設 maxUses 上限
            if (webSearchOn) {
                pb.addTool(ToolUnion.ofWebSearchTool20260209(
                        WebSearchTool20260209.builder().maxUses((long) webSearchMaxUses).build()));
            }
            BatchCreateParams.Request.Params params = pb
                    .system(buildSystemPrompt(webSearchOn))
                    .addUserMessage(buildUserPrompt(date, webSearchOn))
                    .build();

            MessageBatch batch = client().messages().batches().create(BatchCreateParams.builder()
                    .addRequest(BatchCreateParams.Request.builder()
                            .customId(customId(date))
                            .params(params)
                            .build())
                    .build());

            row.setStatus(DailyMarketAnalysis.STATUS_PROCESSING);
            row.setBatchId(truncate(batch.id(), 64));
            row.setErrorMessage(null);
            log.info("今日股市分析：批次已送出（date={}, batchId={}）", date, batch.id());
        } catch (Exception e) {
            log.warn("今日股市分析：送出批次失敗（date={}）: {}", date, e.getMessage(), e);
            row.setStatus(DailyMarketAnalysis.STATUS_FAILED);
            row.setBatchId(null);
            row.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
        }
        return save(row, date);
    }

    // ===== 批次收尾（poller）=====

    /**
     * 收尾在製批次：撈所有 {@code PROCESSING} 列，批次 {@code ENDED} 則取結果落庫（OK/FAILED）並清 {@code batch_id}；
     * 逾時（送出超過 {@link #BATCH_MAX_AGE} 仍未完成）則標 FAILED。由排程 poller 週期呼叫；不拋出。
     */
    public void pollPendingBatches() {
        List<DailyMarketAnalysis> pending = analysisRepo.findByStatus(DailyMarketAnalysis.STATUS_PROCESSING);
        for (DailyMarketAnalysis row : pending) {
            try {
                finalizeIfReady(row);
            } catch (Exception e) {
                log.warn("今日股市分析：收尾批次例外（date={}, batchId={}）: {}",
                        row.getAnalysisDate(), row.getBatchId(), e.getMessage());
            }
        }
    }

    private void finalizeIfReady(DailyMarketAnalysis row) {
        LocalDate date = row.getAnalysisDate();
        String batchId = row.getBatchId();
        boolean stale = row.getGeneratedAt() != null
                && row.getGeneratedAt().isBefore(Instant.now().minus(BATCH_MAX_AGE));

        if (batchId == null || batchId.isBlank()) {
            markBatchFailed(row, date, "PROCESSING 但缺 batch_id");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return;   // 無金鑰無法查詢；留待設定後或逾時處理
        }

        MessageBatch batch;
        try {
            batch = client().messages().batches().retrieve(batchId);
        } catch (Exception e) {
            if (stale) {
                markBatchFailed(row, date, "批次查詢逾時失敗：" + e.getMessage());
            } else {
                log.info("今日股市分析：批次查詢暫時失敗，稍後重試（date={}, batchId={}）: {}", date, batchId, e.getMessage());
            }
            return;
        }

        if (batch.processingStatus() != MessageBatch.ProcessingStatus.ENDED) {
            if (stale) {
                markBatchFailed(row, date, "批次逾時未完成（status=" + batch.processingStatus() + "）");
            }
            return;   // 仍在製，下輪再查
        }

        // ENDED：取本列對應 custom_id 的結果
        String wantId = customId(date);
        MessageBatchIndividualResponse mine;
        try (StreamResponse<MessageBatchIndividualResponse> results =
                     client().messages().batches().resultsStreaming(batchId)) {
            mine = results.stream()
                    .filter(r -> wantId.equals(r.customId()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            if (stale) {
                markBatchFailed(row, date, "批次取結果失敗：" + e.getMessage());
            } else {
                log.info("今日股市分析：批次取結果暫時失敗，稍後重試（date={}）: {}", date, e.getMessage());
            }
            return;
        }

        if (mine == null) {
            markBatchFailed(row, date, "批次結果找不到對應請求");
            return;
        }
        MessageBatchResult res = mine.result();
        if (res == null || !res.isSucceeded()) {
            String kind = res == null ? "null"
                    : res.isErrored() ? "errored"
                    : res.isCanceled() ? "canceled"
                    : res.isExpired() ? "expired" : "unknown";
            markBatchFailed(row, date, "批次請求未成功（" + kind + "）");
            return;
        }

        // 成功：解析 message → 落 OK
        row.setGeneratedAt(Instant.now());
        row.setBatchId(null);
        try {
            String rawText = extractText(res.asSucceeded().message());
            row.setRawResponse(truncate(rawText, 20000));
            applyResult(row, parseResult(rawText));
            row.setStatus(DailyMarketAnalysis.STATUS_OK);
            row.setErrorMessage(null);
            log.info("今日股市分析：批次完成（date={}, bias={}, confidence={}）",
                    date, row.getBias(), row.getConfidence());
        } catch (Exception e) {
            log.warn("今日股市分析：批次結果解析失敗（date={}）: {}", date, e.getMessage(), e);
            String rawKept = row.getRawResponse();   // 保留原始回覆供除錯
            clearContent(row);
            row.setRawResponse(rawKept);
            row.setStatus(DailyMarketAnalysis.STATUS_FAILED);
            row.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
        }
        // 每日自動寄送（Requirement 31 / Task 151）：批次收尾首次落 OK 即寄一次；以 email_sent_at 為冪等記號，
        // 已寄過（含同一交易日手動重跑）不重寄 → 滿足「僅每日自動寄、不重複打擾」。dispatcher 內部 try/catch 不拋。
        if (DailyMarketAnalysis.STATUS_OK.equals(row.getStatus()) && row.getEmailSentAt() == null) {
            try {
                if (emailDispatcher.dispatchDaily(MarketAnalysisDto.from(row, objectMapper))) {
                    row.setEmailSentAt(Instant.now());
                }
            } catch (Exception e) {
                log.warn("今日股市分析：寄送每日 email 失敗（date={}）: {}", date, e.getMessage());
            }
        }
        save(row, date);
    }

    /** 批次收尾判定為失敗：清內容＋清 batch_id，落 FAILED。 */
    private void markBatchFailed(DailyMarketAnalysis row, LocalDate date, String reason) {
        log.warn("今日股市分析：批次收尾失敗（date={}）: {}", date, reason);
        clearContent(row);
        row.setBatchId(null);
        row.setGeneratedAt(Instant.now());
        row.setStatus(DailyMarketAnalysis.STATUS_FAILED);
        row.setErrorMessage(truncate(reason, 1000));
        save(row, date);
    }

    /** 批次 custom_id（單一 request；需符合 ^[a-zA-Z0-9_-]{1,64}$）。 */
    private String customId(LocalDate date) {
        return "ma-" + date;   // e.g. ma-2026-07-04
    }

    /** upsert，包 try/catch：save 本身失敗（如欄位長度）也不往外拋，維持不中斷排程／手動觸發的契約。 */
    private DailyMarketAnalysis save(DailyMarketAnalysis row, LocalDate date) {
        try {
            return analysisRepo.save(row);
        } catch (Exception e) {
            log.error("今日股市分析：落庫失敗（date={}）: {}", date, e.getMessage(), e);
            return row;
        }
    }

    /** 清空分析內容欄位（供重跑既有 row 時，確保非 OK 狀態不殘留上一次成功內容）。 */
    private void clearContent(DailyMarketAnalysis row) {
        row.setBias(null);
        row.setConfidence(null);
        row.setSummary(null);
        row.setKeyFactors(null);
        row.setNewsHighlights(null);
        row.setTwContext(null);
        row.setUsContext(null);
        row.setRawResponse(null);
    }

    /** lazy 建立並重用單一 client（apiKey 於運行期固定）。 */
    private AnthropicClient client() {
        AnthropicClient c = anthropicClient;
        if (c == null) {
            synchronized (this) {
                c = anthropicClient;
                if (c == null) {
                    // 明確請求逾時（加固）：批次 retrieve／resultsStreaming 若因網路停滯或連線半開而卡住，
                    // 以此為上限拋出（而非 SDK 預設 10 分鐘），避免單次收尾呼叫長時間阻塞排程執行緒。
                    c = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .timeout(Duration.ofSeconds(90))
                            .build();
                    anthropicClient = c;
                }
            }
        }
        return c;
    }

    @PreDestroy
    void closeClient() {
        AnthropicClient c = anthropicClient;
        if (c instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("關閉 Anthropic client: {}", e.getMessage());
            }
        }
    }

    // ===== Prompt =====

    private String buildSystemPrompt(boolean webSearchEnabled) {
        // 新聞來源原則隨「新聞搜尋次數」設定切換：開啟 → 指示先 web_search；關閉 → 純技術面、不得杜撰新聞
        String newsPrinciple = webSearchEnabled
                ? "2. 先使用 web_search 搜尋近 1～2 週的財經新聞（台股、美股、Fed 與利率、匯率、地緣政治、外資與法人動向、重要企業財報等），越近期的新聞越重要。"
                : "2. 本次不提供網路新聞搜尋（web_search 已停用）：請勿杜撰或臆測新聞，僅依下方台股與美股走勢數據做技術面研判，newsHighlights 一律回空陣列 []。";
        return """
            你是一位資深台股策略分析師。你的任務：綜合「量化的台股大盤與美股指數近一年走勢」與「近期國內外財經新聞」，
            對「今天」台股（加權指數 TAIEX）當日可能的走向做出多空判斷。

            重要原則：
            1. 越近期的走勢與新聞，權重越高（近幾日 > 近一月 > 近一季 > 近一年）。
            %s
            3. 結合我提供的台股大盤與美股主要指數（道瓊 DJI、標普 SPX、那斯達克 IXIC、費城半導體 SOX）近一年日線走勢做技術面研判。
            4. 全程使用台灣繁體中文。

            完成研判後，你的最後輸出「只包含一個 JSON 物件」，不要有任何多餘文字、不要用 markdown 反引號包裹，格式如下：
            {
              "bias": "BULLISH | BEARISH | NEUTRAL",              // 偏多 / 偏空 / 中性
              "confidence": 0-100,                                 // 判斷信心度（整數）
              "summary": "一段話總結今天台股可能走向與主要理由（繁體中文）",
              "keyFactors": ["影響今天走向的關鍵因素（3~6 點）", "..."],
              "newsHighlights": [
                {"title": "新聞標題", "source": "來源媒體", "url": "連結", "publishedAt": "YYYY-MM-DD"}
              ],
              "twContext": "台股近期走勢摘要（技術面）",
              "usContext": "美股近期走勢摘要（技術面）"
            }
            """.formatted(newsPrinciple);
    }

    private String buildUserPrompt(LocalDate date, boolean webSearchEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("今天日期：").append(date).append("（Asia/Taipei）。請判斷今天台股（加權指數）的走向。\n\n");

        LocalDate since = date.minusYears(1);

        // 台股大盤
        List<double[]> taiex = twseCloses(since);
        sb.append("== 台股大盤（TAIEX / 加權指數）近一年日線 ==\n");
        sb.append(seriesQuickStats(taiex));
        sb.append("最近 60 個交易日收盤（由舊到新，越後面越近）：\n");
        sb.append(recentCloses(taiex, 60)).append("\n\n");

        // 美股主要指數
        sb.append("== 美股主要指數近一年日線 ==\n");
        for (String code : US_INDEX_CODES) {
            List<double[]> series = usCloses(code, since);
            if (series.isEmpty()) continue;
            sb.append("[").append(code).append("] ").append(usIndexName(code)).append("\n");
            sb.append(seriesQuickStats(series));
            sb.append("最近 60 個交易日收盤（由舊到新）：\n");
            sb.append(recentCloses(series, 60)).append("\n\n");
        }

        if (webSearchEnabled) {
            sb.append("請先 web_search 近期財經新聞，再結合上述走勢做判斷。切記越近期的走勢與新聞越重要。");
        } else {
            sb.append("本次不進行新聞搜尋，請僅依上述台股與美股走勢做技術面判斷，newsHighlights 回空陣列。切記越近期的走勢越重要。");
        }
        return sb.toString();
    }

    /** 台股大盤（tradingDate → double[]{epochDay, close}）由舊到新。 */
    private List<double[]> twseCloses(LocalDate since) {
        List<TwseIndexDailyHistory> rows =
                twseRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(since);
        List<double[]> out = new ArrayList<>(rows.size());
        for (TwseIndexDailyHistory r : rows) {
            if (r.getClosePoint() == null) continue;
            out.add(new double[]{r.getTradingDate().toEpochDay(), r.getClosePoint().doubleValue()});
        }
        return out;
    }

    private List<double[]> usCloses(String code, LocalDate since) {
        List<UsIndexDailyHistory> rows =
                usRepo.findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(code, since);
        List<double[]> out = new ArrayList<>(rows.size());
        for (UsIndexDailyHistory r : rows) {
            if (r.getClosePoint() == null) continue;
            out.add(new double[]{r.getTradingDate().toEpochDay(), r.getClosePoint().doubleValue()});
        }
        return out;
    }

    /** 近期加權用的快照統計：最新值 + 對 5/20/60/120/240 交易日前的漲跌幅%。 */
    private String seriesQuickStats(List<double[]> series) {
        int n = series.size();
        if (n == 0) return "（無資料）\n";
        double latest = series.get(n - 1)[1];
        LocalDate latestDate = LocalDate.ofEpochDay((long) series.get(n - 1)[0]);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("最新收盤 %.2f（%s）；漲跌幅：", latest, latestDate));
        sb.append("近1週(5日) ").append(changePct(series, 5)).append("、");
        sb.append("近1月(20日) ").append(changePct(series, 20)).append("、");
        sb.append("近1季(60日) ").append(changePct(series, 60)).append("、");
        sb.append("近半年(120日) ").append(changePct(series, 120)).append("、");
        sb.append("近1年(240日) ").append(changePct(series, 240)).append("\n");
        return sb.toString();
    }

    private String changePct(List<double[]> series, int backTradingDays) {
        int n = series.size();
        int idx = n - 1 - backTradingDays;
        if (idx < 0) return "N/A";
        double base = series.get(idx)[1];
        double latest = series.get(n - 1)[1];
        if (base == 0) return "N/A";
        double pct = (latest - base) / base * 100.0;
        return String.format("%+.2f%%", pct);
    }

    /** 最近 count 筆「YYYY-MM-DD:close」由舊到新，逗號分隔。 */
    private String recentCloses(List<double[]> series, int count) {
        int n = series.size();
        int from = Math.max(0, n - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < n; i++) {
            if (i > from) sb.append(", ");
            LocalDate d = LocalDate.ofEpochDay((long) series.get(i)[0]);
            sb.append(d).append(":").append(BigDecimal.valueOf(series.get(i)[1])
                    .setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return sb.toString();
    }

    private String usIndexName(String code) {
        return switch (code) {
            case "DJI" -> "道瓊工業指數";
            case "SPX" -> "標普 500";
            case "IXIC" -> "那斯達克綜合";
            case "SOX" -> "費城半導體";
            default -> code;
        };
    }

    // ===== 回覆解析 =====

    /** 收集回覆中所有 text 區塊（忽略 server_tool_use / web_search_tool_result）。 */
    private String extractText(Message resp) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.content()) {
            block.text().ifPresent(t -> sb.append(t.text()).append("\n"));
        }
        return sb.toString().trim();
    }

    /** 取首個 { 至末個 } 以 Jackson 解析（容錯模型多帶前後文字 / 反引號）。 */
    private MarketAnalysisResult parseResult(String text) throws Exception {
        if (text == null) throw new IllegalStateException("模型回覆為空");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("模型回覆不含 JSON 物件");
        }
        String json = text.substring(start, end + 1);
        return objectMapper.readValue(json, MarketAnalysisResult.class);
    }

    private void applyResult(DailyMarketAnalysis row, MarketAnalysisResult r) throws Exception {
        row.setBias(normalizeBias(r.bias()));
        row.setConfidence(clampConfidence(r.confidence()));
        row.setSummary(r.summary());
        row.setTwContext(r.twContext());
        row.setUsContext(r.usContext());
        row.setKeyFactors(objectMapper.writeValueAsString(
                r.keyFactors() != null ? r.keyFactors() : List.of()));
        row.setNewsHighlights(objectMapper.writeValueAsString(sanitizeNews(r.newsHighlights())));
    }

    /**
     * 過濾模型回傳的新聞連結：只保留 http(s) URL（新聞來自 web_search 為不可信來源，
     * 防止 javascript: / data: 等 scheme 在前端 href 造成 XSS／危險導頁）。前端另有一層防護。
     */
    private List<MarketAnalysisResult.NewsHighlight> sanitizeNews(
            List<MarketAnalysisResult.NewsHighlight> news) {
        List<MarketAnalysisResult.NewsHighlight> out = new ArrayList<>();
        if (news == null) return out;
        for (MarketAnalysisResult.NewsHighlight n : news) {
            if (n == null) continue;
            out.add(new MarketAnalysisResult.NewsHighlight(
                    n.title(), n.source(), safeHttpUrl(n.url()), n.publishedAt()));
        }
        return out;
    }

    /** 僅接受 http/https，其餘（含 null）回 null。 */
    private String safeHttpUrl(String url) {
        if (url == null) return null;
        String u = url.trim().toLowerCase();
        return (u.startsWith("http://") || u.startsWith("https://")) ? url.trim() : null;
    }

    private String normalizeBias(String bias) {
        if (bias == null) return "UNKNOWN";
        String b = bias.trim().toUpperCase();
        return switch (b) {
            case "BULLISH", "BEARISH", "NEUTRAL" -> b;
            default -> "UNKNOWN";
        };
    }

    private Integer clampConfidence(Integer c) {
        if (c == null) return null;
        return Math.max(0, Math.min(100, c));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
