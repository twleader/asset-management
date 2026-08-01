package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 資產交易紀錄（手動買賣流水帳，Requirement 49）。
 *
 * <p>flow event ledger：逐筆記錄「某日、以某價、經某券商，買進／賣出某股票或基金若干股」的原始交易事件。
 * 與 {@link RealizedGain}（賣出結算損益）、{@link AssetSnapshot}（時點存量）語意不同，三者互不自動衍生、不共用資料表。
 *
 * <p>Requirement 28（多租戶）：per-user 私人資料，以 {@code ownerUserId} ＋ {@code @Filter(ownerFilter)} 隔離。
 * HTTP 情境由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped；
 * 背景排程無 request context → 產檔時手動 {@code enableFilter}（見 {@code ExcelExportService.exportAssetTransactionsForOwner}）。
 */
@Entity
@Table(name = "asset_transaction")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 交易類型（買 / 賣） */
    @Column(nullable = false, length = 10)
    private String transactionType;

    /** 資產類型（股票 / 基金） */
    @Column(nullable = false, length = 10)
    private String assetType;

    /** 股票／基金名稱 */
    @Column(nullable = false, length = 50)
    private String assetName;

    /** 股票／基金代號 */
    @Column(length = 20)
    private String assetCode;

    /** 市場 */
    @Column(length = 20)
    private String market;

    /** 幣別（TWD / USD） */
    @Column(length = 10)
    private String currency;

    /** 券商／通路（成交當下名稱字串） */
    @Column(length = 30)
    private String channel;

    /** 交易日期 */
    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 數量（股數／單位數） */
    @Column(precision = 15, scale = 5)
    private BigDecimal shares;

    /** 單價（原幣，小數 6 位；Task 239 由 (15,4) 加寬） */
    @Column(precision = 17, scale = 6)
    private BigDecimal price;

    /** 成交金額（原幣） */
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    /**
     * 手續費（原幣，與 amount 同幣別；Task 268）。
     *
     * <p>純記錄欄：不參與 amountTwd 計算、不參與年度彙總、不回頭調整 amount。
     * {@code null} ＝「這筆沒記費用」、{@code 0} ＝「確實免收」，兩者語意不同，任一層都不得互相轉換。
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal fee;

    /**
     * 證交稅（原幣，與 amount 同幣別；Task 268）。
     *
     * <p>純記錄欄，語意同 {@link #fee}。不依交易類型設限——英股印花稅課在買進，
     * 綁死「賣才有稅」會使英股買進的稅無處可記。
     */
    @Column(name = "transaction_tax", precision = 15, scale = 2)
    private BigDecimal transactionTax;

    /** 交易當天匯率（USD 計價時使用） */
    @Column(precision = 10, scale = 4)
    private BigDecimal exchangeRate;

    /** 備註 */
    @Column(length = 500)
    private String notes;

    /** 年度，由 tradeDate 即時衍生（不建 DB 欄位）。 */
    @Transient
    public Integer getYear() {
        return tradeDate != null ? tradeDate.getYear() : null;
    }
}
