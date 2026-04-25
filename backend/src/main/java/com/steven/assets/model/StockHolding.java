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
 * 股票持有明細
 * 涵蓋台股(富邦/國泰/元大券商)及美股
 */
@Entity
@Table(name = "stock_holding")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private AssetSnapshot snapshot;

    /** 股票代號 (例: 0050, NVDA) */
    @Column(nullable = false, length = 20)
    private String stockCode;

    /** 市場 */
    @Column(nullable = false, length = 20)
    private String market;

    /** 券商（關聯 broker 資料表，取代原 Broker enum） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_id")
    private BrokerEntity broker;

    /** 股數 (美股可為小數) */
    @Column(nullable = false, precision = 15, scale = 5)
    private BigDecimal shares;

    /** 投資成本 (台幣換算後) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal investmentCost;

    /** 現值 (台幣換算後) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal currentValue;

    /** 預估年配息 (台幣換算後) */
    @Column(precision = 20, scale = 4)
    private BigDecimal estimatedDividend;

    /** 配息率 */
    @Column(precision = 10, scale = 6)
    private BigDecimal dividendRate;

    /** 原始幣別 (TWD / USD) */
    @Column(length = 3)
    @Builder.Default
    private String currency = "TWD";

    /** 原幣現值 (用於美股原幣計算) */
    @Column(precision = 20, scale = 4)
    private BigDecimal originalCurrencyValue;

    /** 交易類型：買 / 賣 */
    @Column(length = 10)
    private String transactionType;

    /** 交易日期 */
    private LocalDate transactionDate;

    /** 交易日當日匯率（美股用，TWD/USD） */
    @Column(precision = 10, scale = 4)
    private BigDecimal transactionExchangeRate;

    /** 儀表板自訂顯示順序（null 表示未設定，排在最後） */
    @Column(name = "display_order")
    private Integer displayOrder;

    /** 損益 = 現值 - 投資成本 */
    public BigDecimal getProfit() {
        if (currentValue == null || investmentCost == null) return BigDecimal.ZERO;
        return currentValue.subtract(investmentCost);
    }

    /** 損益率 */
    public BigDecimal getProfitRate() {
        if (investmentCost == null || investmentCost.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return getProfit().divide(investmentCost, 6, RoundingMode.HALF_UP);
    }
}
