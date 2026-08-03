package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** 一位 owner 的一個每日時間點及其複選指數。 */
@Entity
@Table(name = "index_export_schedule_time", uniqueConstraints = @UniqueConstraint(
        name = "uq_index_export_schedule_time", columnNames = {"schedule_id", "run_hour", "run_minute"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IndexExportScheduleTime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false) private IndexExportSchedule schedule;
    @Column(name = "run_hour", nullable = false) @Builder.Default private Integer runHour = 8;
    @Column(name = "run_minute", nullable = false) @Builder.Default private Integer runMinute = 0;
    @Column(nullable = false) @Builder.Default private Boolean enabled = Boolean.TRUE;
    @Column(name = "last_run_date") private LocalDate lastRunDate;
    @Column(name = "last_run_at") private LocalDateTime lastRunAt;
    @Column(name = "last_run_status", length = 500) private String lastRunStatus;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "index_export_schedule_time_market",
            joinColumns = @JoinColumn(name = "schedule_time_id"))
    @Column(name = "market", nullable = false, length = 16)
    @Builder.Default private Set<String> markets = new LinkedHashSet<>();
}
