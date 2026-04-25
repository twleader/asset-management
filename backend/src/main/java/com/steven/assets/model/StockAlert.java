package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_alert")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    /**
     * 警示類型：
     * QUARTERLY_MA_ABOVE_PCT  — 現價高於季線 X%
     * QUARTERLY_MA_BELOW_PCT  — 現價低於季線 X%
     * ANNUAL_MA_ABOVE_PCT     — 現價高於年線 X%（threshold=0 表示剛高於年線）
     * ANNUAL_MA_BELOW_PCT     — 現價低於年線 X%
     * KD_ABOVE                — K 值高於 threshold
     * KD_BELOW                — K 值低於 threshold
     */
    @Column(nullable = false, length = 50)
    private String alertType;

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
