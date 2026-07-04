package com.steven.assets.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.WebSearchTool20260209;
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
    private static final int WEB_SEARCH_MAX_USES = 6;

    /**
     * 可選分析模型白名單——有效 Claude model id 的技術白名單（非使用者可自訂之業務分類，
     * 故不套用「Enum 必須入庫由 /api/settings 管理」規範）。頁面下拉即由此提供。
     */
    private static final List<MarketAnalysisSettingsDto.ModelOption> AVAILABLE_MODELS = List.of(
            new MarketAnalysisSettingsDto.ModelOption("claude-opus-4-8", "Opus 4.8（最佳品質）"),
            new MarketAnalysisSettingsDto.ModelOption("claude-sonnet-5", "Sonnet 5（品質接近、較省）"),
            new MarketAnalysisSettingsDto.ModelOption("claude-haiku-4-5", "Haiku 4.5（最省、較粗略）")
    );

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

    // ===== 設定（模型）=====

    /** 解析要用的模型：設定表 model（非空）→ 否則環境預設。 */
    public String resolveModel() {
        return settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .map(MarketAnalysisSetting::getModel)
                .filter(m -> m != null && !m.isBlank())
                .orElse(defaultModel);
    }

    /** 目前設定 + 可選模型清單（若現值不在白名單則補入，確保下拉恆含現值）。 */
    public MarketAnalysisSettingsDto getSettings() {
        String current = resolveModel();
        List<MarketAnalysisSettingsDto.ModelOption> options = new ArrayList<>(AVAILABLE_MODELS);
        if (options.stream().noneMatch(o -> o.id().equals(current))) {
            options.add(0, new MarketAnalysisSettingsDto.ModelOption(current, current));
        }
        return new MarketAnalysisSettingsDto(current, options);
    }

    /** 更新分析模型（限白名單）；回傳更新後設定。 */
    public MarketAnalysisSettingsDto updateModel(String model) {
        if (model == null || AVAILABLE_MODELS.stream().noneMatch(o -> o.id().equals(model))) {
            throw new IllegalArgumentException("不支援的分析模型：" + model);
        }
        MarketAnalysisSetting s = settingRepo.findById(MarketAnalysisSetting.SINGLETON_ID)
                .orElseGet(MarketAnalysisSetting::new);
        s.setId(MarketAnalysisSetting.SINGLETON_ID);
        s.setModel(model);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepo.save(s);
        log.info("今日股市分析：模型設定更新為 {}", model);
        return getSettings();
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
        DailyMarketAnalysis result;
        generateLock.lock();
        try {
            if (skipIfAlreadyOk && hasOkFor(date)) {
                log.info("今日股市分析：{} 已有成功分析，略過（trigger={}）", date, trigger);
                return analysisRepo.findById(date).orElse(null);
            }
            result = doGenerate(date, trigger);
        } finally {
            generateLock.unlock();
        }
        // 每日自動寄送（Requirement 31 / Task 151）：僅排程 / self-heal 路徑（skipIfAlreadyOk）且分析成功時寄，
        // 管理者手動重跑（skipIfAlreadyOk=false）不寄；同一交易日已有 OK 者上方已略過 → 天然只寄一次。
        // 於鎖外寄送（不讓 SMTP 佔用 generateLock）；dispatcher 內部一律 try/catch 不拋。
        if (skipIfAlreadyOk && result != null && DailyMarketAnalysis.STATUS_OK.equals(result.getStatus())) {
            emailDispatcher.dispatchDaily(MarketAnalysisDto.from(result, objectMapper));
        }
        return result;
    }

    private DailyMarketAnalysis doGenerate(LocalDate date, String trigger) {
        String model = resolveModel();
        log.info("今日股市分析：開始（date={}, trigger={}, model={}）", date, trigger, model);
        DailyMarketAnalysis row = analysisRepo.findById(date).orElseGet(DailyMarketAnalysis::new);
        row.setAnalysisDate(date);
        row.setModel(truncate(model, 64));
        row.setGeneratedAt(Instant.now());
        // 重跑既有 OK 筆時，先清掉上一次成功內容——非 OK 結果不得帶出過期的多空判斷／新聞
        clearContent(row);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("今日股市分析：未設定 ANTHROPIC_API_KEY，跳過（NOT_CONFIGURED）");
            row.setStatus(DailyMarketAnalysis.STATUS_NOT_CONFIGURED);
            row.setErrorMessage("尚未設定 Anthropic API 金鑰（ANTHROPIC_API_KEY）");
            return save(row, date);
        }

        String rawText = null;
        try {
            String system = buildSystemPrompt();
            String user = buildUserPrompt(date);

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens((long) MAX_TOKENS)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .addTool(WebSearchTool20260209.builder().maxUses((long) WEB_SEARCH_MAX_USES).build())
                    .system(system)
                    .addUserMessage(user)
                    .build();

            Message resp = client().messages().create(params);
            rawText = extractText(resp);
            row.setRawResponse(truncate(rawText, 20000));

            MarketAnalysisResult result = parseResult(rawText);
            applyResult(row, result);
            row.setStatus(DailyMarketAnalysis.STATUS_OK);
            row.setErrorMessage(null);
            log.info("今日股市分析：完成（date={}, bias={}, confidence={}）",
                    date, row.getBias(), row.getConfidence());
        } catch (Exception e) {
            log.warn("今日股市分析：失敗（date={}）: {}", date, e.getMessage(), e);
            // 內容欄位已於 clearContent 清空，FAILED 不帶出上一次成功內容；rawResponse 保留（若已取得回覆）供除錯
            row.setStatus(DailyMarketAnalysis.STATUS_FAILED);
            row.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
            if (rawText != null) {
                row.setRawResponse(truncate(rawText, 20000));
            }
        }
        return save(row, date);
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

    private String buildSystemPrompt() {
        return """
            你是一位資深台股策略分析師。你的任務：綜合「量化的台股大盤與美股指數近一年走勢」與「近期國內外財經新聞」，
            對「今天」台股（加權指數 TAIEX）當日可能的走向做出多空判斷。

            重要原則：
            1. 越近期的走勢與新聞，權重越高（近幾日 > 近一月 > 近一季 > 近一年）。
            2. 先使用 web_search 搜尋近 1～2 週的財經新聞（台股、美股、Fed 與利率、匯率、地緣政治、外資與法人動向、
               重要企業財報等），越近期的新聞越重要。
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
            """;
    }

    private String buildUserPrompt(LocalDate date) {
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

        sb.append("請先 web_search 近期財經新聞，再結合上述走勢做判斷。切記越近期的走勢與新聞越重要。");
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
