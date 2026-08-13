package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Requirement 69：一個 parent schedule 的一個每日時間與獨立當日 guard。 */
@Entity
@Table(name = "export_schedule_time", uniqueConstraints = @UniqueConstraint(
        name = "uq_export_schedule_time", columnNames = {"schedule_id", "run_hour", "run_minute"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExportScheduleTime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ExportScheduleSetting schedule;

    @Column(name = "run_hour", nullable = false) private Integer runHour;
    @Column(name = "run_minute", nullable = false) private Integer runMinute;
    @Column(nullable = false) @Builder.Default private Boolean enabled = Boolean.TRUE;
    @Column(name = "last_run_date") private LocalDate lastRunDate;
    @Column(name = "last_run_at") private LocalDateTime lastRunAt;
    @Column(name = "last_run_status", length = 500) private String lastRunStatus;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
