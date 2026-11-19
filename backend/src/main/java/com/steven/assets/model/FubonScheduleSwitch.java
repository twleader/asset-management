package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 富邦唯讀排程執行開關（Requirement 218／Task 612）：一個排程一列，列由 Liquibase seed，
 * 可用的 {@code schedule_key} 以資料表為準。全域設定，無 owner。
 */
@Entity
@Table(name = "fubon_schedule_switch")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FubonScheduleSwitch {

    @Id
    @Column(name = "schedule_key", length = 64, nullable = false)
    private String scheduleKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
