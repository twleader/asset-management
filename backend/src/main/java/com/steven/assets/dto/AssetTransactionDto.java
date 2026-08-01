package com.steven.assets.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 資產交易紀錄 DTO（Requirement 49 / Task 237）。
 *
 * <p>一律用不可變 record；必填欄（transactionType／assetType／assetName／tradeDate／amount）加 {@code @NotNull}，
 * 與 entity {@code nullable=false} 及 DDL {@code NOT NULL} 三處對齊，讓漏填在 {@code @Valid} 階段即被擋下、
 * 不落 DB（實際狀態碼為 500 而非 400——{@code GlobalExceptionHandler} 的
 * {@code @ExceptionHandler(Exception.class)} 兜底吃掉了 {@code MethodArgumentNotValidException}，
 * 為本服務所有 {@code @Valid @RequestBody} 端點的共同行為；統一為 400 屬跨切面變更，另案處理）。
 *
 * <p>衍生值不入庫：{@code amountTwd}／{@code year} 於 {@code toResponse} 即時計算。
 *
 * <p>{@code fee}／{@code transactionTax}（Task 268）是純記錄欄，不參與 {@code amountTwd} 與年度彙總計算。
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
            /** 手續費（選填，原幣）。負值於 @Valid 階段擋下；null≠0，兩者語意不同不得互轉。 */
            @PositiveOrZero BigDecimal fee,
            /** 證交稅（選填，原幣）。語意同 fee；不依交易類型設限（英股印花稅課在買進）。 */
            @PositiveOrZero BigDecimal transactionTax,
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
            BigDecimal fee,
            BigDecimal transactionTax,
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
