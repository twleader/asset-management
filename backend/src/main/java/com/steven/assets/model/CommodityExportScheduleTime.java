package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 油價金價匯出的一個每日執行時間與獨立當日 guard（Requirement 72 / Task 330）。
 *
 * <p>不另存 {@code owner_user_id}——owner 一律由 {@link #schedule} 取得，HTTP 入口必須先由
 * {@code CommodityExportScheduleRepository.findByOwnerUserId} 限縮本人再讀 children，
 * 不可讓 client 以任意 schedule id 繞過 owner filter。
 *
 * <p><b>Lombok 註解刻意用 {@code @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder}，
 * 不用 {@code @Data}</b>：parent {@link CommodityExportSchedule} 是 {@code @Data}，若這裡也用
 * {@code @Data}，雙向 {@code @ManyToOne}／{@code @OneToMany} 關聯會讓兩邊自動產生的
 * {@code toString()}/{@code equals()}/{@code hashCode()} 互相遞迴呼叫對方，導致 {@code StackOverflowError}。
 * 比照既有 {@code ExportScheduleTime}（Requirement 69）的寫法。
 */
@Entity
@Table(name = "commodity_export_schedule_time", uniqueConstraints = @UniqueConstraint(
        name = "uq_commodity_export_schedule_time", columnNames = {"schedule_id", "run_hour", "run_minute"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommodityExportScheduleTime {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private CommodityExportSchedule schedule;

    @Column(name = "run_hour", nullable = false) private Integer runHour;
    @Column(name = "run_minute", nullable = false) private Integer runMinute;
    @Column(nullable = false) @Builder.Default private Boolean enabled = Boolean.TRUE;
    @Column(name = "last_run_date") private LocalDate lastRunDate;
    @Column(name = "last_run_at") private LocalDateTime lastRunAt;
    @Column(name = "last_run_status", length = 500) private String lastRunStatus;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
