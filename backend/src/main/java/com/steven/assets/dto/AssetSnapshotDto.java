package com.steven.assets.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class AssetSnapshotDto {

    // ===== Request =====
    public record CreateSnapshotRequest(
            @NotNull LocalDate snapshotDate,
            BigDecimal usdExchangeRate,
            String notes,
            List<DepositRequest> deposits,
            List<FundRequest> funds,
            List<StockRequest> stocks
    ) {}

    public record DepositRequest(
            Long bankId,                   // 參照 bank.id（取代原 BankName enum）
            @NotNull String depositType,
            @NotNull BigDecimal amount,
            BigDecimal originalAmount,
            String currency,
            BigDecimal annualInterestRate, // 年利率（百分比，1.5 = 1.5%）
            String notes,
            Long id,                       // optional；只可對應本快照既有列，不能指定來源
            LocalDate processingDate        // TRANSIT_* 才適用，null 不代表今天
    ) {
        public DepositRequest(Long bankId, String depositType, BigDecimal amount, BigDecimal originalAmount,
                String currency, BigDecimal annualInterestRate, String notes) {
            this(bankId, depositType, amount, originalAmount, currency, annualInterestRate, notes, null, null);
        }
    }

    public record FundRequest(
            @NotNull String fundName,
            String fundCode,
            Long bankId,                   // 參照 bank.id（銷售銀行）
            @NotNull BigDecimal investmentAmount,
            @NotNull BigDecimal currentValue,
            BigDecimal units,              // (Requirement 19) 非空時，currentValue 由系統 NAV × FX 自動算出覆寫
            BigDecimal estimatedDividend   // (Requirement 20) 預估年配息台幣；units 非空時由系統自動算覆寫
    ) {}

    public record StockRequest(
            @NotNull String stockCode,
            @NotNull String stockName,
            @NotNull String market,
            Long brokerId,                 // 參照 broker.id（取代原 Broker enum）
            @NotNull BigDecimal shares,
            @NotNull BigDecimal investmentCost,
            @NotNull BigDecimal currentValue,
            BigDecimal estimatedDividend,
            BigDecimal dividendRate,
            String currency,
            BigDecimal originalCurrencyValue,
            String transactionType,
            LocalDate transactionDate,
            BigDecimal transactionExchangeRate
    ) {}

    // ===== Response =====
    public record SnapshotSummaryResponse(
            Long id,
            LocalDate snapshotDate,
            BigDecimal usdExchangeRate,
            BigDecimal totalDeposit,
            BigDecimal totalFundValue,
            BigDecimal totalFundCost,
            BigDecimal totalStockValue,
            BigDecimal totalStockCost,
            BigDecimal totalAssets,
            BigDecimal estimatedAnnualDividend,
            BigDecimal realizedGain,
            BigDecimal fundProfit,
            BigDecimal stockProfit,
            String notes
    ) {}

    public record SnapshotDetailResponse(
            Long id,
            LocalDate snapshotDate,
            BigDecimal usdExchangeRate,
            BigDecimal totalDeposit,
            BigDecimal totalFundValue,
            BigDecimal totalFundCost,
            BigDecimal totalStockValue,
            BigDecimal totalStockCost,
            BigDecimal totalAssets,
            BigDecimal estimatedAnnualDividend,
            BigDecimal realizedGain,
            String notes,
            List<DepositResponse> deposits,
            List<FundResponse> funds,
            List<StockResponse> stocks
    ) {}

    public record DepositResponse(
            Long id,
            Long bankId,
            String bankDisplayName,
            String depositType,
            String depositDisplayName,
            BigDecimal amount,
            BigDecimal originalAmount,
            String currency,
            BigDecimal annualInterestRate,    // 年利率（百分比；nullable）
            BigDecimal estimatedAnnualInterest, // 預估年利息（TWD，amount × rate / 100；rate null 時為 null）
            String notes,
            String updateMode,                // 唯讀 MANUAL / AUTO；不接受任何寫入 payload
            LocalDate processingDate
    ) {}

    public record FundResponse(
            Long id,
            String fundName,
            String fundCode,
            Long bankId,
            String bankDisplayName,
            BigDecimal investmentAmount,
            BigDecimal currentValue,
            BigDecimal units,                 // (Requirement 19)
            BigDecimal estimatedDividend,     // (Requirement 20) 預估年配息台幣
            BigDecimal dividendRate,          // estimatedDividend / currentValue
            BigDecimal profit,
            BigDecimal profitRate
    ) {}

    public record StockResponse(
            Long id,
            String stockCode,
            String stockName,
            String market,
            Long brokerId,
            String brokerDisplayName,
            BigDecimal shares,
            BigDecimal investmentCost,      // 原始幣別金額（USD 就是美元）
            BigDecimal investmentCostTwd,   // 統一台幣換算值（供 Dashboard 等匯總頁使用）
            BigDecimal currentValue,
            BigDecimal profit,
            BigDecimal profitRate,
            BigDecimal estimatedDividend,
            BigDecimal dividendRate,
            String currency,
            BigDecimal originalCurrencyValue,
            String transactionType,
            LocalDate transactionDate,
            BigDecimal transactionExchangeRate,
            Integer displayOrder
    ) {}

    public record StockOrderRequest(
            @NotNull String stockCode,
            @NotNull String market,
            @NotNull Integer displayOrder
    ) {}

    // ===== Asset History =====
    public record AssetHistoryResponse(
            Long id,
            LocalDate snapshotDate,
            BigDecimal totalDeposit,
            BigDecimal totalTwdDeposit,
            BigDecimal totalUsdDeposit,
            BigDecimal totalFundValue,
            BigDecimal totalTwStockValue,
            BigDecimal totalUsStockValue,
            BigDecimal totalUkStockValue,
            BigDecimal totalStockValue,
            BigDecimal totalAssets,
            BigDecimal increase,
            BigDecimal increaseRate,
            BigDecimal investmentRate,
            BigDecimal estimatedAnnualDividend,
            BigDecimal realizedGain,
            // Requirement 25：現金／債券／股票 三分類（與六分類同源、加總相等）
            BigDecimal cashValue,
            BigDecimal bondValue,
            BigDecimal stockValue,
            // Requirement 26：股票細分成長型／收益型（growthValue + incomeValue == stockValue）
            BigDecimal growthValue,
            BigDecimal incomeValue,
            // Requirement 27：債券細分短/中/長期（bondShortValue + bondMidValue + bondLongValue == bondValue）
            BigDecimal bondShortValue,
            BigDecimal bondMidValue,
            BigDecimal bondLongValue
    ) {}

    /**
     * 單一快照逐持股分類（供圓餅圖外圈 hover 列出該分類底下持股；Requirement 25/26/27）。
     * stockStyle 僅 assetClass=STOCK 時有值；bondTerm 僅 assetClass=BOND 時有值。
     */
    public record HoldingClassifiedResponse(
            String code,
            String name,
            String market,
            BigDecimal currentValue,
            String assetClass,
            String stockStyle,
            String bondTerm
    ) {}
}
