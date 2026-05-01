package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "backup_setting")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupSetting {

    @Id
    private Integer id;

    @Column(name = "manual_retention", nullable = false)
    private Integer manualRetention;

    @Column(name = "daily_retention", nullable = false)
    private Integer dailyRetention;

    @Column(name = "weekly_retention", nullable = false)
    private Integer weeklyRetention;

    /** 備份總開關：false 時所有手動 / 排程備份直接 skip（自救點不受影響）。 */
    @Column(name = "backup_enabled", nullable = false)
    @Builder.Default
    private Boolean backupEnabled = Boolean.TRUE;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
