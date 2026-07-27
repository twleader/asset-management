package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 台幣兌美元匯率每日排程自動匯出設定（Requirement 42 / Task 204）。
 *
 * <p>結構與 {@link CommodityExportSchedule} 相同：每個使用者一列（{@code owner_user_id} UNIQUE），
 * 以 {@code @Filter(ownerFilter)} 隔離設定本身。HTTP 情境（BFF→business）由
 * {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped；
 * 背景排程無 request context → filter 不啟用，{@code findAll()} 讀全部列供逐列產檔。
 *
 * <p><b>背景產檔不需 {@code enableFilter}</b>：被匯出的 {@link ExchangeRateHistory} 是<b>全域公開資料</b>
 * （無 {@code owner_user_id}、未套 {@code @Filter}），故隔離的只有「設定」，資料本身人人相同
 * （同 Requirement 41 油價金價、Requirement 37 交易日曆）。照抄 {@link RealizedGainExportSchedule}
 * 的 {@code exportXxxForOwner} 在此是多餘的。
 *
 * <p><b>刻意不設 {@code currency} 欄</b>：本頁為「台幣兌美元」單一幣別頁，服務層以常數 USD 產檔。
 * 多幣別頁面目前不存在，加欄等於為不存在的需求預留未使用欄位；日後真要多幣別再加欄補 migration。
 */
@Entity
@Table(name = "exchange_rate_export_schedule", uniqueConstraints = @UniqueConstraint(
        name = "uq_exchange_rate_export_schedule_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRateExportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 是否啟用每日排程 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.FALSE;

    /** 每日執行時（0..23） */
    @Column(name = "run_hour", nullable = false)
    @Builder.Default
    private Integer runHour = 8;

    /** 每日執行分（0..59） */
    @Column(name = "run_minute", nullable = false)
    @Builder.Default
    private Integer runMinute = 0;

    /** 輸出相對子路徑（相對容器基底目錄 EXPORT_OUTPUT_DIR），預設 input */
    @Column(name = "output_subpath", nullable = false, length = 255)
    @Builder.Default
    private String outputSubpath = "input";

    /**
     * 匯出範圍月數；{@code null} ＝ 全部十年。
     * 每次執行以「執行當日往前推 N 個月」計算起訖，使留存檔隨時間滾動而非固定區間。
     */
    @Column(name = "range_months")
    private Integer rangeMonths;

    /** 當日已執行的日期（成功或失敗都設，避免同分鐘每 poll 重跑） */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    /** 上次執行時間 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 上次執行結果（「成功：/path」或「失敗：訊息」） */
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
}
