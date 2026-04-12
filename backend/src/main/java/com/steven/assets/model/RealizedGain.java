package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已實現損益紀錄
 * 記錄每一筆股票/基金賣出的損益
 */
@Entity
@Table(name = "realized_gain")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealizedGain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 股票/基金名稱 */
    @Column(nullable = false, length = 50)
    private String assetName;

    /** 股票代號 */
    @Column(length = 20)
    private String assetCode;

    /** 市場 */
    @Column(length = 20)
    private String market;

    /** 幣別 (TWD / USD) */
    @Column(length = 10)
    private String currency;

    /** 交易券商 */
    @Column(length = 30)
    private String broker;

    /** 交易日期 */
    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 賣出股數/單位數 */
    @Column(precision = 15, scale = 5)
    private BigDecimal shares;

    /** 賣出單價 */
    @Column(precision = 15, scale = 4)
    private BigDecimal salePrice;

    /** 收帳金額 (台幣換算) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal proceeds;

    /** 投資成本 (台幣換算) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal investmentCost;

    /** 實現獲利 = 收帳 - 成本 */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal profit;

    /** 交易當天匯率 (USD 計價時使用) */
    @Column(precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    /** 獲利率 */
    @Column(precision = 10, scale = 6)
    private BigDecimal profitRate;

    /** 年度 (trade_year 避免 SQL 保留字衝突) */
    @Column(name = "trade_year", nullable = false)
    private Integer year;
}
