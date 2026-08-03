package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** per-user 共用的大盤指數匯出設定；時間與指數選擇存於子列。 */
@Entity
@Table(name = "index_export_schedule", uniqueConstraints = @UniqueConstraint(
        name = "uq_index_export_schedule_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IndexExportSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(nullable = false) @Builder.Default private Boolean enabled = Boolean.FALSE;
    @Column(name = "output_subpath", nullable = false, length = 255)
    @Builder.Default private String outputSubpath = "input";
    @Column(name = "range_months") private Integer rangeMonths;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("runHour ASC, runMinute ASC, id ASC")
    @Builder.Default private List<IndexExportScheduleTime> times = new ArrayList<>();

    @Column(name = "gdrive_enabled", nullable = false) private boolean gdriveEnabled;
    @Column(name = "gdrive_subpath", length = 512) private String gdriveSubpath;
    @Column(name = "gdrive_last_run_at") private LocalDateTime gdriveLastRunAt;
    @Column(name = "gdrive_last_status", length = 512) private String gdriveLastStatus;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    public void addTime(IndexExportScheduleTime time) {
        time.setSchedule(this);
        times.add(time);
    }
}
