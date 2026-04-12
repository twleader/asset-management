package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * 信託基金持有明細
 * 透過華南銀行、元大銀行申購的境外基金
 */
@Entity
@Table(name = "fund_holding")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private AssetSnapshot snapshot;

    /** 基金名稱 */
    @Column(nullable = false, length = 100)
    private String fundName;

    /** 基金代號 (如 16B3) */
    @Column(length = 20)
    private String fundCode;

    /** 銷售銀行（關聯 bank 資料表） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_id")
    private Bank bank;

    /** 投資金額 (台幣) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal investmentAmount;

    /** 現值 (台幣) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal currentValue;

    /** 損益 = 現值 - 投資金額 */
    public BigDecimal getProfit() {
        if (currentValue == null || investmentAmount == null) return BigDecimal.ZERO;
        return currentValue.subtract(investmentAmount);
    }

    /** 損益率 */
    public BigDecimal getProfitRate() {
        if (investmentAmount == null || investmentAmount.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return getProfit().divide(investmentAmount, 6, java.math.RoundingMode.HALF_UP);
    }
}
