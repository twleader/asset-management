package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Requirement 112 的 configured-admin owner-scoped readonly transaction ledger contract。 */
public final class PublicTransactionHistoryDto {
    private PublicTransactionHistoryDto() {}

    public enum TransactionHistoryMode { ALL, YEAR, DATE_RANGE }

    public record PublicTransactionHistoryResponse(
            TransactionHistorySelection selection,
            TransactionPeriodSummary allTimeSummary,
            TransactionPeriodSummary summary,
            List<TransactionYearSummary> yearSummaries,
            List<PublicTransactionRecord> records) {
        public PublicTransactionHistoryResponse {
            yearSummaries = yearSummaries == null ? List.of() : List.copyOf(yearSummaries);
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public record TransactionHistorySelection(
            TransactionHistoryMode mode,
            Integer year,
            LocalDate start,
            LocalDate end) {}

    public record TransactionPeriodSummary(
            int buyCount,
            int sellCount,
            BigDecimal totalBuyAmountTwd,
            BigDecimal totalSellAmountTwd) {}

    public record TransactionYearSummary(
            Integer year,
            int buyCount,
            int sellCount,
            BigDecimal totalBuyAmountTwd,
            BigDecimal totalSellAmountTwd) {}

    /** Frozen 18-field ledger row; fee/tax are retained only as original-currency records. */
    public record PublicTransactionRecord(
            Long id,
            String transactionType,
            String assetType,
            String assetName,
            String assetCode,
            String market,
            String currency,
            String channel,
            LocalDate tradeDate,
            BigDecimal shares,
            BigDecimal price,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal transactionTax,
            BigDecimal exchangeRate,
            String notes,
            BigDecimal amountTwd,
            Integer year) {}
}
