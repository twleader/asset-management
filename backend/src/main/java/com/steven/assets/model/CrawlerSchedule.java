package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 公開資訊爬蟲執行時間設定（Requirement 37 / Task 184）：一列一時間點，由「爬蟲資訊查詢」頁維護。
 * 目前僅 {@code crawler_key='news-poller'}（{@code external-materials-service} 的 {@code NewsPoller}），
 * 保留 {@code crawler_key} 供日後擴充其他爬蟲。{@code NewsPoller} 每分鐘讀已啟用列比對當前 {@code HH:mm} 觸發。
 *
 * <p>全域設定——不分租戶、無 {@code owner_user_id}、無 {@code @Filter}（比照 {@link News} /
 * {@link DailyMarketAnalysis} 等全域參考資料）。
 */
@Entity
@Table(name = "crawler_schedule")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerSchedule {

    /** 公開資訊新聞爬蟲 NewsPoller。 */
    public static final String CRAWLER_NEWS_POLLER = "news-poller";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 爬蟲代號（目前僅 {@code news-poller}）。 */
    @Column(name = "crawler_key", length = 64, nullable = false)
    private String crawlerKey;

    /** 執行時（0–23，Asia/Taipei）。 */
    @Column(name = "run_hour", nullable = false)
    private int runHour;

    /** 執行分（0–59）。 */
    @Column(name = "run_minute", nullable = false)
    private int runMinute;

    /** 是否啟用此時間點。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
