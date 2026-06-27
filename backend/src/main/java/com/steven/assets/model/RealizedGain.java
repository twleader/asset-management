package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已實現損益紀錄
 * 記錄每一筆股票/基金賣出的損益
 *
 * <p>Requirement 28（多租戶）：以 {@code ownerUserId} 隔離。
 */
@Entity
@Table(name = "realized_gain")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RealizedGain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

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

    /** 交易當天匯率 (USD 計價時使用) */
    @Column(precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    /** 年度，由 tradeDate 即時衍生 */
    @Transient
    public Integer getYear() {
        return tradeDate != null ? tradeDate.getYear() : null;
    }
}
