package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 已實現損益每日排程自動匯出設定（Requirement 39 / Task 196；多時間點 Requirement 73 / Task 331）。
 *
 * <p>每個使用者一列（{@code owner_user_id} UNIQUE），以 {@code @Filter(ownerFilter)} 隔離設定本身。
 * HTTP 情境（BFF→business）由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped；
 * 背景排程 {@code RealizedGainExportScheduleService} 無 request context → filter 不啟用，
 * {@code findAll()} 讀全部列（跨所有 owner），產檔時才對「該列 owner」手動 {@code enableFilter}
 * （見 {@code ExcelExportService.realizedGainsDocForOwner}）。
 *
 * <p>每日執行時間自 Requirement 73 起改由子表 {@link RealizedGainExportScheduleTime} 承載，
 * 每個時間各自 enabled／當日 guard／狀態；parent 只保留共用輸出設定、總開關與最近一次整體摘要。
 */
@Entity
@Table(name = "realized_gain_export_schedule", uniqueConstraints = @UniqueConstraint(
        name = "uq_rg_export_schedule_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealizedGainExportSchedule {

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

    /** rollback shadow：新 scheduler 不得作為 due 來源，保留讓舊 image 可 rollback。 */
    @Column(name = "run_hour", nullable = false)
    @Builder.Default
    private Integer runHour = 8;

    /** rollback shadow：新 scheduler 不得作為 due 來源。 */
    @Column(name = "run_minute", nullable = false)
    @Builder.Default
    private Integer runMinute = 0;

    /** 輸出相對子路徑（相對容器基底目錄 EXPORT_OUTPUT_DIR），預設 input */
    @Column(name = "output_subpath", nullable = false, length = 255)
    @Builder.Default
    private String outputSubpath = "input";

    /** rollback representative 的 guard；新 scheduler 以 child guard 為準。 */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    /** 上次執行時間（最近一次任一 child／run-now 的整體摘要） */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 上次執行結果（「成功：/path」或「失敗：訊息」），同為最近一次整體摘要 */
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

    /**
     * 每日執行時間清單（Requirement 73）。
     *
     * <p>{@code @Builder.Default} 不可省略：{@code builder()} 建構的 parent 若讓本欄停在 {@code null}，
     * 所有 {@code getTimes().isEmpty()} 的檢查都會直接 NPE，而錯誤現象與多時間點毫無關聯、難以回推。
     */
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("runHour ASC, runMinute ASC, id ASC")
    @Builder.Default
    private List<RealizedGainExportScheduleTime> times = new ArrayList<>();

    public void addTime(RealizedGainExportScheduleTime time) {
        time.setSchedule(this);
        times.add(time);
    }
}
