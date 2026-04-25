package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 台幣兌外幣匯率歷史
 */
@Entity
@Table(name = "exchange_rate_history", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"currency", "rateDate"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 幣別 (USD, EUR, ...) */
    @Column(nullable = false, length = 10)
    private String currency;

    @Column(nullable = false)
    private LocalDate rateDate;

    /** 即期買入 (銀行買入) */
    @Column(precision = 10, scale = 4)
    private BigDecimal buyRate;

    /** 即期賣出 (銀行賣出) */
    @Column(precision = 10, scale = 4)
    private BigDecimal sellRate;

    /** 中間價 = (買入+賣出)/2，由欄位即時計算 */
    @Transient
    public BigDecimal getMidRate() {
        if (buyRate != null && sellRate != null) {
            return buyRate.add(sellRate).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        }
        if (buyRate != null) return buyRate;
        return sellRate;
    }
}
