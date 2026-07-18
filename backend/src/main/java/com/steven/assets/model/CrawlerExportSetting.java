package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 209）：一爬蟲一列，由「爬蟲資訊查詢」頁維護。
 * 目前僅 {@code crawler_key='news-poller'}（{@code external-materials-service} 的 {@code NewsPoller}
 * 每輪寫出 {@code public_info_<yyyy-MM-dd>.json} 前讀取）。
 *
 * <p><b>只存相對子路徑</b>：實際寫入目錄 = 容器內基底 {@code EXPORT_OUTPUT_DIR}（預設 {@code /home/steven}，
 * docker volume 對映 host 家目錄）resolve {@link #outputSubpath}。絕對路徑等於把容器內檔案系統位置寫進 DB，
 * 跨環境不可攜且繞過基底防護，故一律於 service 層擋下（沿用 Requirement 34／39／41／42 的路徑模型）。
 *
 * <p>與 {@link CrawlerSchedule} 的差異：該表語意為「一列一執行時間點」，本表為「一爬蟲一個值」。刻意不併入
 * 同一張表——併入會使路徑隨時間點列數重複儲存同一事實（CLAUDE.md 資料庫完整正規化），且刪一個時間點會連帶
 * 弄丟路徑。兩者同為全域設定：不分租戶、無 {@code owner_user_id}、無 {@code @Filter}。
 */
@Entity
@Table(name = "crawler_export_setting")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerExportSetting {

    /** 尚未設定（或 DB 讀取失敗）時的預設子路徑；= Task 209 前 {@code /srpp-input} volume 的同一個 host 目錄。 */
    public static final String DEFAULT_SUBPATH = "Project/SRPP/data/input";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 爬蟲代號（目前僅 {@code news-poller}），一爬蟲一列。 */
    @Column(name = "crawler_key", length = 64, nullable = false, unique = true)
    private String crawlerKey;

    /** 輸出目錄相對子路徑（相對容器基底 {@code EXPORT_OUTPUT_DIR}）。 */
    @Column(name = "output_subpath", length = 512, nullable = false)
    private String outputSubpath;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
