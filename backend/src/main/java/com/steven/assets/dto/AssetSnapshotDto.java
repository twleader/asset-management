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
            String notes
    ) {}

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
            String notes
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
            BigDecimal totalStockValue,
            BigDecimal totalAssets,
            BigDecimal increase,
            BigDecimal increaseRate,
            BigDecimal investmentRate,
            BigDecimal estimatedAnnualDividend,
            BigDecimal realizedGain
    ) {}
}
