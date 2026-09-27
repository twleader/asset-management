package com.steven.assets.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** One independently configured daily index export for one owner. */
@Entity
@Table(name = "index_export_schedule", indexes =
        @Index(name = "idx_index_export_schedule_owner", columnList = "owner_user_id"))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexExportSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(length = 20)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.FALSE;

    @Column(name = "run_hour", nullable = false)
    @Builder.Default
    private Integer runHour = 8;

    @Column(name = "run_minute", nullable = false)
    @Builder.Default
    private Integer runMinute = 0;

    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 500)
    private String lastRunStatus;

    @Column(name = "output_subpath", nullable = false, length = 255)
    @Builder.Default
    private String outputSubpath = "input";

    @Column(name = "range_months")
    private Integer rangeMonths;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "index_export_schedule_market",
            joinColumns = @JoinColumn(name = "schedule_id", nullable = false))
    @Column(name = "market", nullable = false, length = 16)
    @Builder.Default
    private Set<String> markets = new LinkedHashSet<>();

    @Column(name = "gdrive_enabled", nullable = false)
    private boolean gdriveEnabled;

    @Column(name = "gdrive_subpath", length = 512)
    private String gdriveSubpath;

    @Column(name = "gdrive_last_run_at")
    private LocalDateTime gdriveLastRunAt;

    @Column(name = "gdrive_last_status", length = 512)
    private String gdriveLastStatus;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
