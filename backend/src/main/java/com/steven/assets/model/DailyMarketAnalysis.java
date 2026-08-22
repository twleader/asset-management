package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 今日股市分析（Requirement 31）：每個台股交易日 07:30 由 Claude Opus 4.8 綜合台股/美股近一年日線走勢
 * 與近期國內外財經新聞（模型 web_search 即時搜尋）判斷當天台股走向。
 *
 * <p>全域參考資料——不分租戶、無 {@code owner_user_id} 欄位、無 {@code @Filter}，不受
 * {@code TenantFilterAspect} owner 過濾（比照 {@link TwseIndexDailyHistory} / {@link UsIndexDailyHistory}）。
 * 每交易日一筆（{@code analysis_date} 主鍵）；同日重跑覆蓋（upsert）。
 *
 * <p>{@code keyFactors} 與 {@code newsHighlights} 以 JSON 字串存放，Controller 回前端時解析回陣列。
 */
@Entity
@Table(name = "daily_market_analysis")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyMarketAnalysis {

    /** 分析成功。 */
    public static final String STATUS_OK = "OK";
    /** LLM 呼叫或解析失敗。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 未設定 ANTHROPIC_API_KEY，跳過分析。 */
    public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";
    /** 已送出 Batch API 批次、等待結果中（非同步）；由背景 poller 收尾為 OK/FAILED。 */
    public static final String STATUS_PROCESSING = "PROCESSING";

    /** 被分析的交易日（＝產生當日，Asia/Taipei）。 */
    @Id
    @Column(name = "analysis_date")
    private LocalDate analysisDate;

    /** 方向判斷：BULLISH（偏多）/ BEARISH（偏空）/ NEUTRAL（中性）/ UNKNOWN。 */
    @Column(name = "bias", length = 16)
    private String bias;

    /** 信心度 0..100（可為 null）。 */
    @Column(name = "confidence")
    private Integer confidence;

    /** 當日走向總結（繁體中文一段）。 */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** 關鍵因素清單（JSON array 字串）。 */
    @Column(name = "key_factors", columnDefinition = "TEXT")
    private String keyFactors;

    /** 參考新聞摘要（JSON array 字串：[{title,source,url,publishedAt}]）。 */
    @Column(name = "news_highlights", columnDefinition = "TEXT")
    private String newsHighlights;

    /** 台股近期走勢摘要。 */
    @Column(name = "tw_context", columnDefinition = "TEXT")
    private String twContext;

    /** 美股近期走勢摘要。 */
    @Column(name = "us_context", columnDefinition = "TEXT")
    private String usContext;

    /**
     * 分類分點 fragment（{@code MarketAnalysisResult.FactorGroups} 的 JSON 序列化，Requirement 94／Task 357）。
     * 只有本機規則引擎路徑會寫入；LLM 路徑與既有列一律維持 {@code null}。
     */
    @Column(name = "factor_groups", columnDefinition = "TEXT")
    private String factorGroups;

    /** 所用模型（claude-opus-4-8）。 */
    @Column(name = "model", length = 64)
    private String model;

    /** OK / FAILED / NOT_CONFIGURED / PROCESSING。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /** 在製中 Anthropic Batch id（status=PROCESSING 時非空；收尾後清為 null）。 */
    @Column(name = "batch_id", length = 64)
    private String batchId;

    /** status=FAILED 時的錯誤摘要。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 模型原始回覆（除錯用）。 */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    /** 產生時間。 */
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /**
     * 每日 Email 寄送冪等記號（Requirement 31 / Task 151）：批次收尾首次落 OK 並寄出後戳記；
     * 非 null 表示已寄過該交易日結果，之後（含手動重跑同日）不重寄。
     */
    @Column(name = "email_sent_at")
    private Instant emailSentAt;
}
