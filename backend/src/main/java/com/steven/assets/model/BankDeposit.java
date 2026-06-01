package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * 銀行存款明細
 * 涵蓋台幣活存、定存、美元存款等各類型
 */
@Entity
@Table(name = "bank_deposit")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private AssetSnapshot snapshot;

    /** 銀行（關聯 bank 資料表，取代原 BankName enum） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_id")
    private Bank bank;

    /** 存款類型 */
    @Column(nullable = false, length = 30)
    private String depositType;

    /** 金額 (台幣換算後) */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    /** 原始金額 (若為美元存款，記錄美元原始金額) */
    @Column(precision = 20, scale = 4)
    private BigDecimal originalAmount;

    /** 原始幣別 (TWD / USD) */
    @Column(length = 3)
    @Builder.Default
    private String currency = "TWD";

    /** 年利率（百分比，1.5 表示 1.5%；TRANSIT_* 一律 null） */
    @Column(name = "annual_interest_rate", precision = 7, scale = 4)
    private BigDecimal annualInterestRate;

    /** 備註 (例如定存到期日、利率等) */
    @Column(length = 200)
    private String notes;
}
