package com.steven.assets.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 資產交易紀錄 DTO（Requirement 49 / Task 237）。
 *
 * <p>一律用不可變 record；必填欄（transactionType／assetType／assetName／tradeDate／amount）加 {@code @NotNull}，
 * 與 entity {@code nullable=false} 及 DDL {@code NOT NULL} 三處對齊，讓漏填在 {@code @Valid} 即回 400，
 * 而非落 DB 觸發 {@code DataIntegrityViolationException} 回 500。
 *
 * <p>衍生值不入庫：{@code amountTwd}／{@code year} 於 {@code toResponse} 即時計算。
 */
public class AssetTransactionDto {

    /** 新增／編輯共用。 */
    public record CreateAssetTransactionRequest(
            @NotNull String transactionType,
            @NotNull String assetType,
            @NotNull String assetName,
            String assetCode,
            String market,
            String currency,
            String channel,
            @NotNull LocalDate tradeDate,
            BigDecimal shares,
            BigDecimal price,
            @NotNull BigDecimal amount,
            BigDecimal exchangeRate,
            String notes
    ) {}

    public record AssetTransactionResponse(
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
            BigDecimal exchangeRate,
            String notes,
            /** 台幣成交金額（currency=USD 且 exchangeRate 非 null 時＝amount×exchangeRate，否則＝amount） */
            BigDecimal amountTwd,
            Integer year
    ) {}

    public record YearSummaryResponse(
            Integer year,
            Integer buyCount,
            Integer sellCount,
            BigDecimal totalBuyAmountTwd,
            BigDecimal totalSellAmountTwd,
            List<AssetTransactionResponse> records
    ) {}
}
