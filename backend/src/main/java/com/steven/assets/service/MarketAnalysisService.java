package com.steven.assets.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
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
import com.steven.assets.model.News;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.DailyMarketAnalysisRepository;
import com.steven.assets.repository.MarketAnalysisSettingRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ===== 參考新聞時效驗證（Task 149.17）=====
    /** 自報 / 原文日期的精確 YYYY-MM-DD 前綴（錨定開頭；"2026-06"／"2026" 因缺日不匹配 → 剔除）。 */
    private static final Pattern ISO_DATE_PREFIX = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2})");
    /** 原文 JSON-LD 的 datePublished（值多為 2025-09-18T03:20:31+08:00，取前綴日期）。 */
    private static final Pattern JSONLD_DATE_PUBLISHED =
            Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    /** 原文 &lt;time datetime="..."&gt;。 */
    private static final Pattern TIME_DATETIME =
            Pattern.compile("<time\\b[^>]*?datetime\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** 回抓原文時，僅掃前段（發布日 meta / JSON-LD 通常落在 &lt;head&gt;），限記憶體。 */
    private static final int MAX_ARTICLE_SCAN = 300_000;
    /** 回抓原文的連線／讀取逾時（抓不到即降級保留模型日期，故取短值不拖慢 poller）。 */
    private static final Duration NEWS_FETCH_TIMEOUT = Duration.ofSeconds(6);

    // ===== 新聞來源地區封鎖：只留台/美/日/星，剔除中港澳（Task 149.18 建立、149.19 納入日本）=====
    // 下列三份清單為後端 curated 技術白名單（封鎖政策，非使用者可自訂之業務分類，
    // 比照 AVAILABLE_MODELS，不套用「Enum 必須入庫由 /api/settings 管理」規範）。
    // 經 workflow 對抗驗證：只做「完整結尾後綴／整段網域相等或子網域」比對，嚴禁 contains("cn") 子字串，
    // 故 cnyes.com（台灣鉅亨網）／cna.com.tw（台灣中央社）／cnbc.com／cnn.com 等「含 cn 但非中國」零誤殺。

    /** 中港澳地區 ccTLD host 結尾（{@code host.endsWith(suffix)}）。含前導點；{@code .cn} 亦涵蓋 {@code x.com.cn}。 */
    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(
            ".cn", ".com.cn", ".net.cn", ".org.cn", ".gov.cn", ".edu.cn", ".ac.cn",
            ".hk", ".com.hk", ".net.hk", ".org.hk", ".gov.hk", ".edu.hk", ".idv.hk",
            ".mo", ".com.mo", ".net.mo", ".org.mo", ".gov.mo", ".edu.mo");

    /** 中港澳媒體但用 .com/.cc/.net 等非地區 TLD 的具名網域（{@code host==domain} 或 {@code host.endsWith("."+domain)}）。 */
    private static final Set<String> BLOCKED_DOMAINS = Set.of(
            // 中國大陸
            "sfccn.com", "eastmoney.com", "eastmoneysec.com", "caixin.com", "caixinglobal.com",
            "yicai.com", "wallstreetcn.com", "cnstock.com", "stcn.com", "jrj.com", "hexun.com",
            "jiemian.com", "cgtn.com", "sina.com.cn", "qq.com", "163.com", "21jingji.com",
            "gelonghui.com", "futunn.com", "cnfin.com", "yuncaijing.com",
            // 香港
            "scmp.com", "hket.com", "mingpao.com", "hkej.com", "on.cc", "hk01.com",
            "stheadline.com", "wenweipo.com", "takungpao.com", "stnn.cc",
            // 澳門
            "macaodaily.com", "macaupostdaily.com", "todaymacao.com", "exmoo.com",
            "houkongdaily.com", "shimindaily.net", "aamacau.com");

    /** 中港澳來源顯示名關鍵詞（{@code source.contains}，繁簡兩式）。僅收絕不誤中台/美/日/星媒體者。 */
    private static final Set<String> BLOCKED_SOURCE_TOKENS = Set.of(
            "新浪財經", "新浪财经", "南方財經", "南方财经", "東方財富", "东方财富", "財新", "财新",
            "第一財經", "第一财经", "華爾街見聞", "华尔街见闻", "上海證券報", "上海证券报",
            "證券時報", "证券时报", "金融界", "和訊", "和讯", "澎湃", "界面新聞", "界面新闻",
            "環球時報", "环球时报", "新華社", "新华社", "新華網", "新华网", "人民日報", "人民日报",
            "央視", "央视", "中國證券報", "中国证券报", "每日經濟新聞", "每日经济新闻",
            "經濟觀察報", "经济观察报", "券商中國", "券商中国", "觀察者網", "观察者网",
            "南華早報", "南华早报", "香港經濟日報", "香港经济日报", "信報", "香港01",
            "東方日報", "东方日报", "星島", "星岛", "文匯報", "文汇报", "大公報", "大公报",
            "明報", "明报", "澳門日報", "澳门日报");

    private final DailyMarketAnalysisRepository analysisRepo;
    private final MarketAnalysisSettingRepository settingRepo;
    private final TwseIndexDailyHistoryRepository twseRepo;
    private final UsIndexDailyHistoryRepository usRepo;
    private final NewsHeadlineRepository newsRepo;
    private final ObjectMapper objectMapper;
    private final MarketAnalysisEmailDispatcher emailDispatcher;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    /** 環境預設模型（設定表未設或後備時使用）。 */
    @Value("${anthropic.model:claude-opus-4-8}")
    private String defaultModel;

    /** 參考新聞時效上限（天）：publishedAt 早於「分析日 − 此值」即剔除。預設 5（Task 149.18 由 30 收斂——只要這幾天的新聞、越近越重要）。 */
    @Value("${market-analysis.news-max-age-days:5}")
    private int newsMaxAgeDays;

    /** 是否回抓原文真實發布日驗證（治本層，可關）；關閉則僅依模型自報日期的格式＋時效過濾。 */
    @Value("${market-analysis.news-verify-published-date:true}")
    private boolean newsVerifyPublishedDate;

    /** 是否封鎖中港澳新聞來源（只留台/美/日/星，Task 149.18／149.19）；設 false 可整體關閉地區封鎖以快速回退。 */
    @Value("${market-analysis.news-region-block-enabled:true}")
    private boolean newsRegionBlockEnabled;

    /** 序列化 generate()：避免 cron 排程 / 開機 self-heal / 管理者手動 同時對同一交易日重複昂貴呼叫＋覆蓋。 */
    private final ReentrantLock generateLock = new ReentrantLock();

    /** 長生命週期共用 client（OkHttp 設計為可共享、執行緒安全）；lazy 建立，容器關閉時 close。 */
    private volatile AnthropicClient anthropicClient;

    /** 回抓新聞原文發布日用的共用 HttpClient（JDK，執行緒安全）；lazy 建立，JVM 關閉自動回收、無需顯式 close。 */
    private volatile HttpClient httpClient;

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
        // 本地抓取新聞（Task 149.21）：由 external-materials-service 寫入 news_headline，此處讀近 N 天餵入 prompt。
        // 有本地新聞時：webSearchOn → 本地新聞＋web_search 補；webSearchOff → 純本地新聞（省付費 web_search 且不再空白）。
        List<News> recentNews = fetchRecentLocalNews(date);
        log.info("今日股市分析：送出批次（date={}, trigger={}, model={}, effort={}, webSearchMaxUses={}, localNews={}）",
                date, trigger, model, effort, webSearchMaxUses, recentNews.size());

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
            // webSearchMaxUses=0 → 不加 web_search tool（純技術面）；>0 → 加上並設 maxUses 上限。
            // 【Task 149.20 修正】用「基本版」web_search_20250305，不可用「動態過濾版」web_search_20260209：
            // 20260209 底層以 code_execution 做動態過濾，而 code_execution 沙箱在 Message Batches API 下會
            // detection_timeout（實測回 {"status":"detection_timeout","error":"Detection timed out after 90.0s"},
            // return_code=1），導致模型多次搜尋皆失敗、放棄後 newsHighlights 回空（頁面「參考新聞」永遠空白）。
            // 基本版不走 code_execution、搜尋結果直接進 context，實測同批次同模型可穩定回 20 則真實新聞。
            // 本服務走 Batch API 才踩到此坑；PortfolioAdviceService 為「同步」呼叫、動態版正常，故不同動。
            if (webSearchOn) {
                pb.addTool(ToolUnion.ofWebSearchTool20250305(
                        WebSearchTool20250305.builder().maxUses((long) webSearchMaxUses).build()));
            }
            BatchCreateParams.Request.Params params = pb
                    .system(buildSystemPrompt(webSearchOn, recentNews))
                    .addUserMessage(buildUserPrompt(date, webSearchOn, recentNews))
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

        // 注意：MessageBatch.ProcessingStatus 是 SDK 的 enum-like 值類別（非 Java enum、有覆寫 equals），
        // retrieve() 反序列化回來的實例與靜態常數 ENDED 是不同物件參考，用 !=/== 會恆為「不相等」而永遠認不出
        // ENDED（批次早已完成卻被誤判為在製，直到逾時才落 FAILED）。故一律以 value()（其巢狀 Value 才是真 Java enum）比較。
        if (batch.processingStatus().value() != MessageBatch.ProcessingStatus.Value.ENDED) {
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
                    c = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
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

    private String buildSystemPrompt(boolean webSearchEnabled, List<News> recentNews) {
        // 新聞來源原則隨「本地新聞是否存在」×「web_search 是否開啟」四態切換（Task 149.21）。
        boolean hasLocal = recentNews != null && !recentNews.isEmpty();
        String newsPrinciple;
        if (hasLocal && webSearchEnabled) {
            newsPrinciple = "2. 下方已附【近期新聞（本地抓取）】清單（來源為台灣權威媒體與證交所公開資訊，已驗證來源與真實發布日），"
                    + "請以此清單為主要新聞面依據、優先採用；如需補「今日最新」動態，可再多次 web_search（換多組中英文關鍵字），"
                    + "但一律只採台灣/美國/日本/新加坡來源，嚴禁中國大陸/香港/澳門（新浪財經/東方財富/南方財經/財新/南華早報… 一律不可）。"
                    + "請在 newsHighlights 積極列出 3～6 則最相關、發布日在最近 " + newsMaxAgeDays + " 天內的新聞（優先取自本地清單）；每則 publishedAt 為原文實際發布日、精確到日（YYYY-MM-DD）。";
        } else if (hasLocal) {
            newsPrinciple = "2. 本次不進行網路搜尋（web_search 已停用）；請以下方【近期新聞（本地抓取）】清單為唯一新聞面依據"
                    + "（來源為台灣權威媒體與證交所公開資訊，已驗證來源與真實發布日），**不得杜撰清單以外的新聞或臆測日期**。"
                    + "請從清單中挑出 3～6 則對今日台股走向最相關者列入 newsHighlights，title/source/url/publishedAt 一律照清單原樣填。";
        } else if (webSearchEnabled) {
            newsPrinciple = "2. 先使用 web_search 搜尋最近幾日的財經新聞（台股、美股、日股、Fed 與利率、匯率、地緣政治、外資與法人動向、重要企業財報等），越近期的新聞權重越高、越舊越不重要。務必實際進行多次搜尋、換多組中英文關鍵字積極找出近期新聞，不要只搜一次就放棄。"
                    + "【新聞來源地區限制】只採用台灣、美國、日本、新加坡的新聞來源；嚴禁納入中國大陸、香港、澳門的媒體或報導（例如新浪財經、東方財富、南方財經、第一財經、財新、華爾街見聞、南華早報、香港經濟日報等一律不可），即使其內容與台股／美股相關也不得列入。"
                    + "可優先參考下列可靠來源（不限於此）：台灣證券交易所（twse.com.tw，三大法人買賣超、大盤成交統計）、公開資訊觀測站（mops.twse.com.tw，上市櫃重大訊息與財報）、玩股網（wantgoo.com）、MoneyDJ 理財網（moneydj.com）、日經中文網（zh.cn.nikkei.com）、自由時報財經（ec.ltn.com.tw）、經濟日報（money.udn.com）、華爾街日報中文網（cn.wsj.com）、紐約時報中文網（cn.nytimes.com），以及 Reuters、Bloomberg 等其他台/美/日/星主流財經媒體。"
                    + "【排除來源】請勿採用鉅亨網（cnyes.com）的報導（觀點偏頗），即使搜到也不要列入 newsHighlights。"
                    + "請在 newsHighlights 積極列出 3～6 則符合條件（來源屬台/美/日/星、發布日在最近 " + newsMaxAgeDays + " 天內）的新聞；每則 publishedAt 必須是該篇原文的實際發布日、精確到日（YYYY-MM-DD）。"
                    + "個別新聞若無法確認精確且近期的發布日、或來源屬中港澳，就略過該則（不要用舊聞或臆測日期充數、不要依賴既有記憶）；唯有確實搜尋後仍找不到任何符合條件的新聞時，才回空陣列 []。";
        } else {
            newsPrinciple = "2. 本次不提供網路新聞搜尋（web_search 已停用）且無本地新聞：請勿杜撰或臆測新聞，僅依下方台股與美股走勢數據做技術面研判，newsHighlights 一律回空陣列 []。";
        }
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
                {"title": "新聞標題", "source": "來源媒體", "url": "連結", "publishedAt": "YYYY-MM-DD（原文實際發布日、須為近期、精確到日）"}
              ],
              "twContext": "台股近期走勢摘要（技術面）",
              "usContext": "美股近期走勢摘要（技術面）"
            }
            """.formatted(newsPrinciple);
    }

    private String buildUserPrompt(LocalDate date, boolean webSearchEnabled, List<News> recentNews) {
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

        // 本地抓取新聞（Task 149.21）：來源可信、發布日精準，直接餵入（不經 sanitizeNews；那層只處理模型輸出）。
        boolean hasLocal = recentNews != null && !recentNews.isEmpty();
        if (hasLocal) {
            sb.append(buildLocalNewsBlock(recentNews));
        }

        if (webSearchEnabled && hasLocal) {
            sb.append("上方已附【近期新聞（本地抓取）】，請以此為主要新聞面依據、優先採用；如需補今日最新動態，可再多次 web_search（只採台/美/日/星、嚴禁中港澳）。")
              .append("請在 newsHighlights 積極列出 3～6 則最相關、最近 ").append(newsMaxAgeDays)
              .append(" 天內的新聞（優先取自本地清單），每則標出實際發布日（YYYY-MM-DD）。");
        } else if (hasLocal) {
            sb.append("本次不進行網路搜尋，請以上方【近期新聞（本地抓取）】為唯一新聞面依據，從中挑出 3～6 則對今日台股走向最相關者列入 newsHighlights（title/source/url/publishedAt 照清單原樣），")
              .append("不得杜撰清單以外的新聞。切記越近期的走勢與新聞越重要。");
        } else if (webSearchEnabled) {
            sb.append("請先多次 web_search（換多組中英文關鍵字、積極嘗試、勿只搜一次）最近幾日的財經新聞，再結合上述走勢做判斷。切記越近期的走勢與新聞越重要（越舊權重越低）；")
              .append("請在 newsHighlights 積極列出 3～6 則最近 ").append(newsMaxAgeDays)
              .append(" 天內、來源屬台灣/美國/日本/新加坡（可優先參考台灣證券交易所 twse.com.tw／公開資訊觀測站 mops.twse.com.tw、日經中文網、自由時報、經濟日報、華爾街日報中文網、紐約時報中文網等）、且能明確標出實際發布日（YYYY-MM-DD）的新聞；嚴禁納入中國大陸/香港/澳門來源。個別不確定發布日或非近期者略過該則，唯有確實找不到任何符合者才回空陣列。");
        } else {
            sb.append("本次不進行新聞搜尋、亦無本地新聞，請僅依上述台股與美股走勢做技術面判斷，newsHighlights 回空陣列。切記越近期的走勢越重要。");
        }
        return sb.toString();
    }

    private static final java.time.ZoneId TW_ZONE = java.time.ZoneId.of("Asia/Taipei");
    /** 餵入 prompt 的本地新聞最多筆數（避免灌爆 context；已依發布日新→舊排序）。 */
    private static final int LOCAL_NEWS_MAX = 40;

    /**
     * 讀近 {@code newsMaxAgeDays} 天的本地抓取新聞（news_headline，由 external-materials-service 寫入）。
     * 讀取失敗（表不存在/DB 例外）不影響分析——回空清單、退回既有 web_search / 純技術面行為。
     */
    private List<News> fetchRecentLocalNews(LocalDate date) {
        try {
            java.time.Instant cutoff = date.minusDays(Math.max(1, newsMaxAgeDays))
                    .atStartOfDay(TW_ZONE).toInstant();
            return newsRepo.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(cutoff);
        } catch (Exception e) {
            log.warn("今日股市分析：讀本地新聞失敗（改不注入本地新聞）：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 組「近期新聞（本地抓取）」prompt 區塊：每則 `YYYY-MM-DD [source] title — url` ＋（有摘要則）縮排摘要。
     * 先列 TWSE 量化資訊（三大法人/成交量——數量少但訊號最強，且 published_at=當日 00:00 排序在後、
     * 易被一般新聞的 cap 擠掉），再列一般新聞（僅一般新聞受 {@link #LOCAL_NEWS_MAX} 上限）。
     */
    private String buildLocalNewsBlock(List<News> news) {
        List<News> twse = new ArrayList<>();
        List<News> articles = new ArrayList<>();
        for (News x : news) {
            if (x.getCategory() != null && !News.CATEGORY_NEWS.equals(x.getCategory())) twse.add(x);
            else articles.add(x);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== 近期新聞（本地抓取，來源可信、發布日精準，請優先採用）==\n");
        for (News x : twse) appendLocalNews(sb, x);          // TWSE 量化資訊全列（數量有限）
        int n = 0;
        for (News x : articles) {                            // 一般新聞受上限
            if (n++ >= LOCAL_NEWS_MAX) break;
            appendLocalNews(sb, x);
        }
        sb.append("\n");
        return sb.toString();
    }

    private void appendLocalNews(StringBuilder sb, News x) {
        java.time.LocalDate d = x.getPublishedAt() == null ? null
                : x.getPublishedAt().atZone(TW_ZONE).toLocalDate();
        sb.append(d == null ? "????-??-??" : d.toString())
          .append(" [").append(x.getSource() == null ? "" : x.getSource()).append("] ")
          .append(x.getTitle() == null ? "" : x.getTitle().strip());
        if (x.getUrl() != null && !x.getUrl().isBlank()) sb.append(" — ").append(x.getUrl().strip());
        sb.append("\n");
        String summary = x.getSummary();
        if (summary != null && !summary.isBlank()) {
            sb.append("    ").append(summary.strip()).append("\n");
        }
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
        row.setNewsHighlights(objectMapper.writeValueAsString(
                sanitizeNews(r.newsHighlights(), row.getAnalysisDate())));
    }

    /**
     * 過濾模型回傳的參考新聞（Task 149.17／149.18）：多層把關，避免把過時／不可信／中港澳新聞當「近期重點」顯示。
     * <ol>
     *   <li>只保留 http(s) URL（web_search 為不可信來源，防 {@code javascript:}／{@code data:} 於前端
     *       {@code <a href>} 造成 XSS；前端另有一層 {@code safeUrl()}）。</li>
     *   <li><b>地區封鎖（149.18／149.19）</b>：只留台/美/日/星，剔除中港澳來源（{@link #isRegionBlocked}）。置於日期解析與昂貴回抓之前，命中即最早剔除。</li>
     *   <li>{@code publishedAt} 須為精確 {@code YYYY-MM-DD} 且落在
     *       {@code [analysisDate - newsMaxAgeDays, analysisDate + 1d]}（149.18 起 newsMaxAgeDays 預設 5 天）；否則剔除。
     *       （模型自報日期不可信——實測把 2025-09-18 舊聞標成「2026-06」，故硬性要求精確到日＋時效。）</li>
     *   <li>對通過者若開啟 {@link #newsVerifyPublishedDate} 且有連結，回抓原文真實發布日：抓到即以真日期
     *       覆寫顯示並複驗時效（治本、擋「謊報精確近期日期」）；抓不到則保留驗證後的模型日期。</li>
     * </ol>
     * 全部剔除則回空陣列（前端既有空狀態）——寧缺勿濫，當日多空判斷仍以走勢量化數據成立。
     */
    private List<MarketAnalysisResult.NewsHighlight> sanitizeNews(
            List<MarketAnalysisResult.NewsHighlight> news, LocalDate analysisDate) {
        List<MarketAnalysisResult.NewsHighlight> out = new ArrayList<>();
        if (news == null) return out;
        LocalDate refDate = analysisDate != null ? analysisDate : LocalDate.now();
        LocalDate oldest = refDate.minusDays(Math.max(1, newsMaxAgeDays));
        LocalDate newest = refDate.plusDays(1);   // 容忍時區落差（原文可能標成分析日隔天）
        for (MarketAnalysisResult.NewsHighlight n : news) {
            if (n == null) continue;
            String url = safeHttpUrl(n.url());

            // 地區封鎖（Task 149.18／149.19）：只留台/美/日/星，剔除中港澳來源。置於日期解析與昂貴回抓之前，命中即最早剔除、省 HTTP 成本。
            if (newsRegionBlockEnabled && isRegionBlocked(url, n.source())) {
                log.info("今日股市分析：剔除中港澳來源新聞（source={}, url={}）：{}", n.source(), url, n.title());
                continue;
            }
            // 使用者排除來源（Task 149.22）：鉅亨網 cnyes 觀點偏頗，即使 web_search 搜到也不列入。
            if (isExcludedSource(url, n.source())) {
                log.info("今日股市分析：剔除使用者排除來源新聞（source={}, url={}）：{}", n.source(), url, n.title());
                continue;
            }

            // 第 1 層：自報日期須精確到日（"2026-06"／null／雜訊 → 剔除）
            LocalDate date = parseIsoDatePrefix(n.publishedAt());
            if (date == null) {
                log.info("今日股市分析：剔除新聞（publishedAt 非精確日期 '{}'）：{}", n.publishedAt(), n.title());
                continue;
            }
            // 第 2 層：回抓原文真實發布日（可關）——抓到即以真日期為準並覆寫顯示。
            // 本地抓取來源（twse/wantgoo/moneydj/ltn/udn）發布日已由 producer 直接讀 time/pubDate 驗證過，
            // 且列表/AMP/轉址頁的 og:date 常誤導，故不對這些可信來源回抓覆寫（Task 149.21 review #3），
            // 僅套第 1 層格式與第 3 層時窗。web_search 來源（模型自報日期不可信）仍照舊回抓校正。
            if (newsVerifyPublishedDate && url != null && !isTrustedLocalNewsHost(url)) {
                LocalDate real = fetchPublishedDate(url);
                if (real != null && !real.equals(date)) {
                    log.info("今日股市分析：新聞發布日以原文校正 {} → {}：{}", date, real, n.title());
                    date = real;
                }
            }
            // 第 3 層：時效區間（用第 2 層校正後的日期）
            if (date.isBefore(oldest) || date.isAfter(newest)) {
                log.info("今日股市分析：剔除過時／異常日期新聞（{}，窗 {}~{}）：{}", date, oldest, newest, n.title());
                continue;
            }
            out.add(new MarketAnalysisResult.NewsHighlight(
                    n.title(), n.source(), url, date.toString()));
        }
        return out;
    }

    /** 僅接受 http/https，其餘（含 null）回 null。 */
    private String safeHttpUrl(String url) {
        if (url == null) return null;
        String u = url.trim().toLowerCase();
        return (u.startsWith("http://") || u.startsWith("https://")) ? url.trim() : null;
    }

    /**
     * 判斷該新聞是否為中港澳來源（Task 149.18）：依 url 的 host 與 source 顯示名比對三份封鎖清單。
     * 只做「完整結尾後綴 / 整段網域相等或子網域 / 來源名子字串」比對，故 cnyes.com（台灣鉅亨網）、
     * cna.com.tw（台灣中央社）、cnbc.com、cnn.com 等「含 cn 但非中國」不會被誤判。
     */
    private boolean isRegionBlocked(String url, String source) {
        String host = extractHost(url);
        if (host != null) {
            for (String suffix : BLOCKED_HOST_SUFFIXES) {
                if (host.endsWith(suffix)) return true;
            }
            for (String domain : BLOCKED_DOMAINS) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
        }
        if (source != null) {
            for (String token : BLOCKED_SOURCE_TOKENS) {
                if (source.contains(token)) return true;
            }
        }
        return false;
    }

    /** 本地抓取來源網域（Task 149.21／149.22）：發布日已由 producer 驗證，sanitizeNews 不再回抓覆寫其日期。 */
    private static final Set<String> TRUSTED_LOCAL_NEWS_HOSTS = Set.of(
            "twse.com.tw", "wantgoo.com", "moneydj.com", "ltn.com.tw", "udn.com");

    /** url 是否屬本地抓取的可信來源（host 相等或子網域）。 */
    private boolean isTrustedLocalNewsHost(String url) {
        String host = extractHost(url);
        if (host == null) return false;
        for (String s : TRUSTED_LOCAL_NEWS_HOSTS) {
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        return false;
    }

    /** 使用者排除的來源網域（Task 149.22）：鉅亨網 cnyes 觀點偏頗，一律不採用（含 web_search 搜到者）。 */
    private static final Set<String> EXCLUDED_NEWS_HOSTS = Set.of("cnyes.com");
    /** 使用者排除的來源顯示名關鍵詞（source.contains）。 */
    private static final Set<String> EXCLUDED_SOURCE_TOKENS = Set.of("鉅亨", "cnyes", "Anue");

    /** url/source 是否屬使用者排除來源（host 相等或子網域 / 來源名子字串）。 */
    private boolean isExcludedSource(String url, String source) {
        String host = extractHost(url);
        if (host != null) {
            for (String s : EXCLUDED_NEWS_HOSTS) {
                if (host.equals(s) || host.endsWith("." + s)) return true;
            }
        }
        if (source != null) {
            for (String t : EXCLUDED_SOURCE_TOKENS) {
                if (source.contains(t)) return true;
            }
        }
        return false;
    }

    /** 從 http(s) URL 取小寫 host；失敗（含 null／無 host）回 null。 */
    private String extractHost(String url) {
        if (url == null) return null;
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析精確 {@code YYYY-MM-DD} 前綴為 {@link LocalDate}；缺日（"2026-06"）／非日曆日（"2026-13-40"）／null 皆回 null。 */
    private LocalDate parseIsoDatePrefix(String s) {
        if (s == null) return null;
        Matcher m = ISO_DATE_PREFIX.matcher(s.trim());
        if (!m.find()) return null;
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 回抓新聞原文、擷取其真實發布日；任何失敗（逾時／非 2xx／解析不到）一律回 null（由呼叫端降級保留模型日期）。
     * 短 UA {@code Mozilla/5.0}（長 Chrome UA 易被 WAF 擋）、短逾時、跟隨轉址。
     */
    private LocalDate fetchPublishedDate(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(NEWS_FETCH_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return null;
            String body = resp.body();
            if (body != null && body.length() > MAX_ARTICLE_SCAN) {
                body = body.substring(0, MAX_ARTICLE_SCAN);
            }
            return extractPublishedDate(body);
        } catch (Exception e) {
            log.debug("今日股市分析：回抓新聞發布日失敗（url={}）: {}", url, e.getMessage());
            return null;
        }
    }

    /** 依序試 JSON-LD {@code datePublished} → {@code meta[article:published_time]} → {@code meta[datePublished]} → {@code meta[name=date]} → {@code <time datetime>}，取首個可解析為精確日期者。 */
    private LocalDate extractPublishedDate(String html) {
        if (html == null || html.isBlank()) return null;
        String[] candidates = {
                firstGroup(html, JSONLD_DATE_PUBLISHED),
                metaContentByKey(html, "article:published_time"),
                metaContentByKey(html, "datePublished"),
                metaContentByKey(html, "date"),
                firstGroup(html, TIME_DATETIME),
        };
        for (String c : candidates) {
            LocalDate d = parseIsoDatePrefix(c);
            if (d != null) return d;
        }
        return null;
    }

    private String firstGroup(String html, Pattern p) {
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** 抓 {@code <meta>} 之 content：屬性（property／name／itemprop）值為 key，容忍 key／content 屬性任一順序。 */
    private String metaContentByKey(String html, String key) {
        String q = Pattern.quote(key);
        Matcher keyFirst = Pattern.compile(
                "<meta\\b[^>]*?(?:property|name|itemprop)\\s*=\\s*[\"']" + q + "[\"'][^>]*?content\\s*=\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (keyFirst.find()) return keyFirst.group(1);
        Matcher contentFirst = Pattern.compile(
                "<meta\\b[^>]*?content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?(?:property|name|itemprop)\\s*=\\s*[\"']" + q + "[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (contentFirst.find()) return contentFirst.group(1);
        return null;
    }

    /** lazy 建立並重用單一 JDK HttpClient（跟隨轉址、短連線逾時）。 */
    private HttpClient httpClient() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                c = httpClient;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(NEWS_FETCH_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    httpClient = c;
                }
            }
        }
        return c;
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
