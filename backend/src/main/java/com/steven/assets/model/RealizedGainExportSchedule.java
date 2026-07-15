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
 * 已實現損益每日排程自動匯出設定（Requirement 38 / Task 192）。
 *
 * <p>每個使用者一列（{@code owner_user_id} UNIQUE），以 {@code @Filter(ownerFilter)} 隔離設定本身。
 * HTTP 情境（BFF→business）由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped；
 * 背景排程 {@code RealizedGainExportScheduleService} 無 request context → filter 不啟用，
 * {@code findAll()} 讀全部列（跨所有 owner），產檔時才對「該列 owner」手動 {@code enableFilter}
 * （見 {@code ExcelExportService.exportRealizedGainsForOwner}）。
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

    /** 當日已執行的日期（成功或失敗都設，避免同分鐘每 poll 重跑） */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    /** 上次執行時間 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 上次執行結果（「成功：/path」或「失敗：訊息」） */
    @Column(name = "last_run_status", length = 500)
    private String lastRunStatus;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
