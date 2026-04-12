package com.steven.assets.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class RealizedGainDto {

    public record CreateRealizedGainRequest(
            @NotNull String assetName,
            String assetCode,
            String market,
            String currency,
            String broker,
            @NotNull LocalDate tradeDate,
            BigDecimal shares,
            BigDecimal salePrice,
            @NotNull BigDecimal proceeds,
            @NotNull BigDecimal investmentCost,
            @NotNull BigDecimal profit,
            BigDecimal profitRate
    ) {}

    public record RealizedGainResponse(
            Long id,
            String assetName,
            String assetCode,
            String market,
            String currency,
            String broker,
            BigDecimal exchangeRate,
            LocalDate tradeDate,
            BigDecimal shares,
            BigDecimal salePrice,
            BigDecimal proceeds,
            BigDecimal investmentCost,
            BigDecimal profit,
            BigDecimal profitRate,
            /** 台幣收帳金額 (TWD 幣別=原值, USD 幣別=proceeds*exchangeRate) */
            BigDecimal proceedsTwd,
            /** 台幣投資成本 */
            BigDecimal investmentCostTwd,
            /** 台幣獲利 */
            BigDecimal profitTwd,
            Integer year
    ) {}

    public record YearSummaryResponse(
            Integer year,
            /** 台幣加總 */
            BigDecimal totalProceedsTwd,
            BigDecimal totalCostTwd,
            BigDecimal totalProfitTwd,
            BigDecimal avgProfitRate,
            List<RealizedGainResponse> records
    ) {}
}
