package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/** 每位使用者、每檔股票一筆的交易雷達狀態通知設定（Requirement 44）。 */
@Entity
@Table(name = "trading_radar_notification_setting",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trn_setting_owner_stock",
                columnNames = {"owner_user_id", "stock_code", "market"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingRadarNotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** false 表示下一輪只寫 baseline，不寄信。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean initialized = false;

    @Column(name = "last_action", length = 50)
    private String lastAction;

    @Column(name = "last_counter_trend_state", length = 50)
    private String lastCounterTrendState;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
