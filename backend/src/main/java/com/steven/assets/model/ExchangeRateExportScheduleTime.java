package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 台幣兌美元匯出的一個每日執行時間與其獨立 guard（Requirement 145 / Task 423）。 */
@Entity
@Table(name = "exchange_rate_export_schedule_time", uniqueConstraints = @UniqueConstraint(
        name = "uq_exchange_rate_export_schedule_time", columnNames = {"schedule_id", "run_hour", "run_minute"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRateExportScheduleTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ExchangeRateExportSchedule schedule;

    @Column(name = "run_hour", nullable = false)
    private Integer runHour;

    @Column(name = "run_minute", nullable = false)
    private Integer runMinute;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 500)
    private String lastRunStatus;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
