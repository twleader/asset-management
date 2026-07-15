package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 本地財經新聞（Requirement 31 / Task 149.21）：由 external-materials-service 抓取權威來源
 * （玩股網 / MoneyDJ / 自由時報 / 經濟日報）、證交所公開資訊（三大法人買賣超、大盤成交統計）
 * 與匯率／美股／韓股量化快照，以 JdbcTemplate
 * 直寫共用 postgres 的 {@code news_headline}；本 entity 供 business-services 讀取後餵入
 * {@link com.steven.assets.service.MarketAnalysisService} 的今日股市分析 prompt。
 *
 * <p>全域參考資料——不分租戶、無 {@code owner_user_id}、無 {@code @Filter}，不受 owner 過濾
 * （比照 {@link TwseIndexDailyHistory} / {@link DailyMarketAnalysis}）。
 */
@Entity
@Table(name = "news_headline")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class News {

    /** 純新聞（RSS/JSON 標題）。 */
    public static final String CATEGORY_NEWS = "news";
    /** 證交所三大法人買賣金額。 */
    public static final String CATEGORY_TWSE_INSTITUTIONAL = "twse-institutional";
    /** 證交所大盤成交統計。 */
    public static final String CATEGORY_TWSE_TURNOVER = "twse-turnover";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 新聞標題 / 資料摘要標題。 */
    @Column(name = "title", length = 500, nullable = false)
    private String title;

    /** 來源代號：wantgoo / moneydj / ltn / udn / twse / bot-fx / us-index / kr-index / kr-intraday。 */
    @Column(name = "source", length = 100, nullable = false)
    private String source;

    /** 原文 / 查詢頁連結。 */
    @Column(name = "url", length = 1024, nullable = false)
    private String url;

    /** 分類：news / twse-institutional / twse-turnover。 */
    @Column(name = "category", length = 32, nullable = false)
    private String category;

    /** 地區：TW / US / JP / SG（本地抓取固定為權威台灣來源＝TW）。 */
    @Column(name = "region", length = 16)
    private String region;

    /** 摘要 / 數據明細（法人買賣超金額、成交量等）。 */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** 原文/資料的真實發布時間（RSS pubDate / JSON publishAt / 交易日）。 */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /** 抓取入庫時間。 */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** 去重鍵：{@code sha256(source|url|category)}，供 ext 端 {@code ON CONFLICT} upsert。 */
    @Column(name = "dedupe_key", length = 64, nullable = false)
    private String dedupeKey;
}
