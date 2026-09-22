package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    @Column(length = 20)
    @Builder.Default
    private String currency = "TWD";

    /** 年利率（百分比，1.5 表示 1.5%；TRANSIT_* 一律 null） */
    @Column(name = "annual_interest_rate", precision = 7, scale = 4)
    private BigDecimal annualInterestRate;

    /** 在途款項的明確處理日；一般存款及尚未取得日期的舊列保持 null。 */
    @Column(name = "processing_date")
    private LocalDate processingDate;

    /** 備註 (例如定存到期日、利率等) */
    @Column(length = 200)
    private String notes;

    /**
     * 建立來源。手動管理資產與 Excel 匯入固定為 {@code MANUAL}；富邦唯讀對帳
     * 只能建立／更新自己的 {@code FUBON_SYNC} 在途款項，不能接管手動列。
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String source = "MANUAL";
}
