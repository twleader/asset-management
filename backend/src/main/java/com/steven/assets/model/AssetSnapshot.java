package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 資產快照 - 記錄某一日期的完整資產狀況
 *
 * <p>Requirement 28（多租戶）：以 {@code ownerUserId} 隔離；同一使用者同一日期僅一筆，
 * 唯一性改為複合 {@code (owner_user_id, snapshot_date)}（不同使用者同一天各可有一筆）。
 */
@Entity
@Table(name = "asset_snapshot", uniqueConstraints = @UniqueConstraint(
        name = "uq_snapshot_owner_date", columnNames = {"owner_user_id", "snapshot_date"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28），子表 deposits/funds/stocks 經本快照繼承 owner */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** 美元匯率 (台幣/美元) */
    @Column(precision = 10, scale = 4)
    private BigDecimal usdExchangeRate;

    /** 存款總計 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalDeposit;

    /** 信託基金現值總計 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalFundValue;

    /** 信託基金投資成本總計 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalFundCost;

    /** 股票現值總計 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalStockValue;

    /** 股票投資成本總計 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalStockCost;

    /** 資產總計 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalAssets;

    /** 預估年配息 (台幣) */
    @Column(precision = 20, scale = 2)
    private BigDecimal estimatedAnnualDividend;

    /** 已實現損益 (台幣, 當年度) */
    @Column(precision = 20, scale = 2)
    private BigDecimal realizedGain;

    /** 備註 */
    @Column(length = 500)
    private String notes;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 64)
    @Builder.Default
    private List<BankDeposit> deposits = new ArrayList<>();

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 64)
    @Builder.Default
    private List<FundHolding> funds = new ArrayList<>();

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 64)
    @Builder.Default
    private List<StockHolding> stocks = new ArrayList<>();
}
