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
 * 交易日曆每日排程自動匯出設定（Requirement 37 / Task 185）。
 *
 * <p>每個使用者一列（{@code owner_user_id} UNIQUE），以 {@code @Filter(ownerFilter)} 隔離，
 * 使用者只能存取自己的排程設定。HTTP 情境（BFF→business）由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動 owner-scoped；背景排程 {@code TradingCalendarExportScheduleService} 無 request context → filter 不啟用，
 * {@code findAll()} 讀全部列。因交易日曆為全域資料，產檔時**無需** owner 資料過濾（與 {@link ExportScheduleSetting}
 * 需縮資產不同）。
 */
@Entity
@Table(name = "trading_calendar_export_schedule", uniqueConstraints = @UniqueConstraint(
        name = "uq_tc_export_schedule_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingCalendarExportSchedule {

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

    /**
     * 匯出格式：json / excel。
     *
     * @deprecated Requirement 55（Task 271）起<b>一律同時產出兩份</b>，本欄位已停用、程式一律不讀寫。
     *             欄位保留僅為避免不可逆的 drop column；DB 上它是 {@code NOT NULL} ＋ CHECK 約束，
     *             故 <b>預設值必須留著</b>（移除或改成會寫入 null 的形式，新增列會違反 NOT NULL）。
     *             既有列殘留的 {@code json}／{@code excel} 值不影響行為，不需要 migration 清空。
     */
    @Deprecated
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String format = "json";

    /** 輸出相對子路徑（相對容器基底目錄 EXPORT_OUTPUT_DIR），預設 input */
    @Column(name = "output_subpath", nullable = false, length = 255)
    @Builder.Default
    private String outputSubpath = "input";

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
