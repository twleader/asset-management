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

    /**
     * 最近一次觸發時間，**存「該股市場」的牆鐘**（台股台北／美股紐約／英股倫敦），
     * 由 {@code StockAlertService.computeTriggeredAt()} 的 {@code ZonedDateTime.now(市場 zone)} 寫入。
     *
     * <p>這是 Requirement 53「naive 欄位一律存台北牆鐘」的**唯二例外之一**（另一個是
     * {@code StockAlertTrigger.triggeredAt}）——顯示端要的就是「紐約時間 12:00 觸發」。
     * <b>不可納入任何時區校正 migration</b>，也<b>不可</b>拿它跟 JVM 牆鐘比較：
     * 冷卻判定必須用 {@code MarketZones.nowLocal(market)}，否則冷卻長度會變成 24h ± 市場 offset。
     */
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
