package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 警示觸發歷史紀錄。每次警示條件成立時新增一筆，保留 30 天。
 *
 * 寫入時序：StockAlertService.evaluate 偵測到觸發 → 同步覆寫
 * StockAlert.last_triggered_* → INSERT 一筆 stock_alert_trigger。
 *
 * 5 個技術指標欄位（MA20 / MA60 / MA240 / K / D）皆無條件填寫，
 * 不論觸發類型為 PRICE / MA / KD，便於事後追蹤觸發當下完整技術面狀態。
 */
@Entity
@Table(name = "stock_alert_trigger", indexes = {
        @Index(name = "idx_stock_alert_trigger_alert_time", columnList = "alert_id, triggered_at DESC"),
        @Index(name = "idx_stock_alert_trigger_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlertTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Column(precision = 10, scale = 4)
    private BigDecimal price;

    /** 月線 MA20 */
    @Column(name = "monthly_ma", precision = 10, scale = 4)
    private BigDecimal monthlyMa;

    /** 季線 MA60 */
    @Column(name = "quarterly_ma", precision = 10, scale = 4)
    private BigDecimal quarterlyMa;

    /** 年線 MA240 */
    @Column(name = "annual_ma", precision = 10, scale = 4)
    private BigDecimal annualMa;

    @Column(name = "k_value", precision = 10, scale = 4)
    private BigDecimal kValue;

    @Column(name = "d_value", precision = 10, scale = 4)
    private BigDecimal dValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
