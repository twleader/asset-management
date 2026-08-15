package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Requirement 73：一個已實現損益匯出排程的一個每日時間與獨立當日 guard。
 *
 * <p>owner 只透過 parent {@link RealizedGainExportSchedule} 取得，本表<b>不重複保存</b>
 * {@code owner_user_id}——同一事實存兩份會有不一致風險，且 HTTP 一律先以 owner 取得 parent
 * 再讀 children，不接受 client 傳入的 schedule／time id。
 */
@Entity
@Table(name = "realized_gain_export_schedule_time", uniqueConstraints = @UniqueConstraint(
        name = "uq_rg_export_schedule_time", columnNames = {"schedule_id", "run_hour", "run_minute"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RealizedGainExportScheduleTime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private RealizedGainExportSchedule schedule;

    @Column(name = "run_hour", nullable = false) private Integer runHour;
    @Column(name = "run_minute", nullable = false) private Integer runMinute;
    @Column(nullable = false) @Builder.Default private Boolean enabled = Boolean.TRUE;
    @Column(name = "last_run_date") private LocalDate lastRunDate;
    @Column(name = "last_run_at") private LocalDateTime lastRunAt;
    @Column(name = "last_run_status", length = 500) private String lastRunStatus;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
