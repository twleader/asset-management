package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * 交易雷達排程匯出的輸出資料夾與上次執行狀態（Requirement 48 追加 / Task 231）。一使用者一列。
 *
 * <p><b>只存相對子路徑</b>：實際寫入目錄 = 容器內基底 {@code EXPORT_OUTPUT_DIR}（預設 {@code /home/steven}，
 * docker volume 對映 host 家目錄）resolve {@link #outputSubpath}。絕對路徑等於把容器內檔案系統位置寫進 DB，
 * 跨環境不可攜且繞過基底防護，故一律於 service 層擋下（沿用 Requirement 34／39／41／42／45 的路徑模型）。
 *
 * <p>與 {@link TradingRadarExportTime} 分表的理由：該表語意為「一列一執行時間點」，本表為「一使用者一個值」。
 * 併入會使路徑隨時間點列數重複儲存同一事實，且刪一個時間點會連帶弄丟路徑。
 *
 * <p>使用者可能「只設了時間點、還沒設資料夾」，此時本列不存在——排程寫入狀態時須自行 upsert 建列
 * （以 {@link #DEFAULT_SUBPATH} 為預設），不可假設該列必定存在。
 */
@Entity
@Table(name = "trading_radar_export_setting", uniqueConstraints = @UniqueConstraint(
        name = "uq_tr_export_setting_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingRadarExportSetting {

    /** 尚未設定時的預設輸出子路徑（相對 EXPORT_OUTPUT_DIR）。 */
    public static final String DEFAULT_SUBPATH = "input";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 輸出目錄相對子路徑（相對容器基底 EXPORT_OUTPUT_DIR） */
    @Column(name = "output_subpath", nullable = false, length = 512)
    @Builder.Default
    private String outputSubpath = DEFAULT_SUBPATH;

    /** 上次執行時間 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 上次執行結果（「成功：/path」「當日尚無快照，未產檔」或「失敗：訊息」） */
    @Column(name = "last_run_status", length = 500)
    private String lastRunStatus;

    // ── Google Drive 同步（Requirement 51 / Task 242，changeset v1.76.0）─────────────
    // 本機輸出行為完全不變、一律照寫；以下欄位只控制「要不要在本機檔寫成功後多上傳一份副本」。

    /**
     * 是否額外上傳一份到 Google Drive。
     *
     * <p><b>只有「主要管理者」（{@code ADMIN_EMAIL}）可啟用</b>——rclone remote 全機只有一份且綁定
     * 某一個特定 Google 帳號，若允許其他使用者啟用，其財務報表會被上傳到該帳號的雲端硬碟，
     * 且從當事人角度完全不可見。判定走 {@code GdriveOutputSupport.isDriveAllowedFor}。
     */
    @Column(name = "gdrive_enabled", nullable = false)
    private boolean gdriveEnabled;

    /** Drive 上的相對子路徑（基底為 rclone remote）；啟用時必填。 */
    @Column(name = "gdrive_subpath", length = 512)
    private String gdriveSubpath;

    /** 上次 Drive 上傳的<b>判斷</b>時間（含成功／失敗／跳過，非僅成功）。 */
    @Column(name = "gdrive_last_run_at")
    private LocalDateTime gdriveLastRunAt;

    /**
     * 上次 Drive 上傳結果。<b>與 {@link #lastRunStatus} 刻意分離、不得併入</b>：
     * 「本機成功、Drive 失敗」是正常且必須可分辨的狀態，共用一欄會讓本機明明寫成功卻顯示失敗。
     */
    @Column(name = "gdrive_last_status", length = 512)
    private String gdriveLastStatus;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── 發布到 Blog（Requirement 102 / Task 366，changeset v1.112.0）─────────────
    // 與上方 gdrive_* 五欄語意平行但彼此獨立：本機／Drive／Blog 三個輸出通道各自成敗，
    // 一個通道失敗不得覆蓋另一個通道的狀態欄。

    /**
     * 是否啟用「排程自動發布到 Blog」。沿用既有〔匯出執行時間設定〕的時間點
     * （{@link TradingRadarExportTime}），不另外開一套 Blog 專屬排程時間 UI。
     *
     * <p>與 {@link #gdriveEnabled} 相同的權限模型：blog 全機唯一、綁定特定 Google 帳號
     * （{@code shi.chihung@gmail.com}），只有主要管理者可啟用，判定走
     * {@code BlogPublishOutputSupport.isBlogAllowedFor}。
     */
    @Column(name = "blog_enabled", nullable = false)
    private boolean blogEnabled;

    /** 已發布文章的 Blogger post id；null 代表尚未發布過（下次發布走建立而非更新）。 */
    @Column(name = "blog_last_post_id", length = 64)
    private String blogLastPostId;

    /** 已發布文章的公開網址，供設定頁顯示可點擊連結。 */
    @Column(name = "blog_last_post_url", length = 512)
    private String blogLastPostUrl;

    /** 上次 Blog 發布的<b>判斷</b>時間（含成功／失敗，不含「查無快照未執行」）。 */
    @Column(name = "blog_last_run_at")
    private LocalDateTime blogLastRunAt;

    /**
     * 上次 Blog 發布結果。<b>與 {@link #lastRunStatus}／{@link #gdriveLastStatus} 刻意分離、
     * 不得併入</b>：三個輸出通道的成敗必須各自可分辨。
     */
    @Column(name = "blog_last_status", length = 512)
    private String blogLastStatus;
}
