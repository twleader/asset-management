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

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
