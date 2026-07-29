package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 到價警示（Requirement 16）。
 *
 * <p>Requirement 28（多租戶）：以 {@code ownerUserId} 隔離；觀察清單、trigger、recipient join 皆繼承。
 * 背景偵測 cron 不啟用 owner filter，掃全體 active 警示、寄信給各警示自選的收件人。
 */
@Entity
@Table(name = "stock_alert")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    /**
     * 所屬複合條件群組（Task 253）。沿用本專案「以 Long id 顯式關聯」慣例（同 {@link StockAlertTrigger#getAlertId()}），
     * 不映射 JPA 關聯，避免 lazy-init 與 dispatcher 取值複雜化。
     *
     * <p><b>{@code null}</b> ＝ 獨立單一條件，行為與 Task 253 前完全相同：自行評估、自行記 24h cooldown、
     * 自行寄信，同一檔股票掛多條時語意是 OR。
     *
     * <p><b>非空</b> ＝ {@link StockAlertGroup} 的 AND 成員，<b>不得自行觸發</b>：
     * 背景檢查一律以 {@code findByActiveTrueAndGroupIdIsNull()} 取獨立條件，成員不會進入 {@code evaluate}。
     * 成員的 {@code active} 恆為 true（啟停由群組那一列決定）、{@code last_triggered_*} 五欄一律不寫
     * （觸發狀態只記在群組上，避免同一次 AND 觸發在兩處各留一份紀錄）。
     * 成員的 {@code displayOrder} 也另有語意：從群組自己的 {@code displayOrder} 起算，決定群組內條件的串接順序，
     * 同時避免壓低觀察清單去重查詢的 {@code MIN(display_order)} 而把該股票整組頂到最前面。
     */
    @Column(name = "group_id")
    private Long groupId;

    /**
     * 警示類型：
     * PRICE_ABOVE / PRICE_BELOW  — 現價高於 / 低於 threshold
     * MA_ABOVE_PCT / MA_BELOW_PCT — 現價偏離 MA{maPeriod} 達 threshold %（threshold=0 表示剛跨過）
     * KD_ABOVE / KD_BELOW        — K 值高於 / 低於 threshold
     * KD_D_ABOVE / KD_D_BELOW    — D 值高於 / 低於 threshold
     */
    @Column(nullable = false, length = 50)
    private String alertType;

    /** 均線天數（僅 MA_*_PCT 類型使用，例：20=月線、60=季線、240=年線；其他類型為 null） */
    @Column(name = "ma_period")
    private Integer maPeriod;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal threshold;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    private LocalDateTime lastTriggeredAt;

    /** 觸發當下的股價 */
    @Column(name = "last_triggered_price")
    private BigDecimal lastTriggeredPrice;

    /** 觸發當下的均線值（季線或年線，MA 條件才有） */
    @Column(name = "last_triggered_ma_value")
    private BigDecimal lastTriggeredMaValue;

    /** 觸發當下的 K 值（KD 條件才有） */
    @Column(name = "last_triggered_kd_value")
    private BigDecimal lastTriggeredKdValue;

    /** 觸發當下的 D 值（KD 條件才有） */
    @Column(name = "last_triggered_d_value")
    private BigDecimal lastTriggeredDValue;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
