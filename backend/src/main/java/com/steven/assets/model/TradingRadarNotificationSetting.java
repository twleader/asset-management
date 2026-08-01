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

    /**
     * {@code lastAction} 是哪個 {@code TradingRadarRuleEngine.RULE_VERSION} 算出來的（Task 264）。
     *
     * <p>與現行 RULE_VERSION 不符時視同<b>未初始化</b>：Requirement 44 明訂規則版本變更後
     * 通知基準須全部重建、升級後首輪只建基準不寄信。V9 改變了動作映射結構，拿 V9 動作比對
     * V8 的 {@code lastAction} 必然大量不相等而觸發假通知。</p>
     *
     * <p>既有列為 {@code null}（版本未知），與任何版本皆不相等，故升級後首輪自動重建。</p>
     */
    @Column(length = 30)
    private String ruleVersion;

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
