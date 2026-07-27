package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 212）：一爬蟲一列，由「爬蟲資訊查詢」頁維護。
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

    /** 尚未設定（或 DB 讀取失敗）時的預設子路徑；= Task 212 前 {@code /srpp-input} volume 的同一個 host 目錄。 */
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

    /**
     * 是否在本機檔寫成功後，額外上傳一份副本到 Google Drive（Requirement 50 / Task 241）。
     *
     * <p><b>這是「附加」開關，不是儲存目標單選</b>：為真時本機仍照寫，只是多上傳一份。刻意不提供
     * 「只寫 Drive」——SRPP 退休規劃專案依賴本機 {@code public_info_<日期>.json}，單選會讓使用者
     * 選了 Drive 就靜默切斷 SRPP 的資料來源（症狀是 SRPP 讀到過期檔案而不報錯）。
     */
    @Column(name = "gdrive_enabled", nullable = false)
    private boolean gdriveEnabled;

    /**
     * Google Drive 上的相對子路徑（例 {@code 投資理財/資產管理}），基底為 rclone remote
     * （名稱由 {@code GDRIVE_OUTPUT_REMOTE} 指定，不寫死於程式）。與 {@link #outputSubpath} 同構：
     * 只存相對子路徑，不存絕對路徑或含 remote 前綴的完整 spec。{@link #gdriveEnabled} 為真時必填。
     */
    @Column(name = "gdrive_subpath", length = 512)
    private String gdriveSubpath;

    /**
     * 上次 Drive 上傳的<b>判斷</b>時間（含成功、失敗與跳過，非僅成功）。
     *
     * <p>本欄與 {@link #gdriveLastStatus} 由 {@code external-materials-service} 的 {@code NewsPoller}
     * 寫入——<b>刻意的所有權例外</b>：上傳結果只有 ext 知道，沒有別的地方能寫。ext 只碰這兩欄，列的
     * 所有權仍在 backend（ext 端 UPDATE 命中 0 列時只 warn，不得 upsert）。使用者設定的 PUT 不得碰這兩欄。
     */
    @Column(name = "gdrive_last_run_at")
    private Instant gdriveLastRunAt;

    /**
     * 上次上傳結果：成功記落點與檔案大小、失敗記錯誤摘要、跳過記原因。截斷至 512 字元內。
     *
     * <p>之所以需要這一欄：上傳目的地不在使用者眼前的檔案系統，不回報狀態的話「檔案沒上去」只會
     * 體現為 Drive 上少一個檔案，使用者無從得知。
     */
    @Column(name = "gdrive_last_status", length = 512)
    private String gdriveLastStatus;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
