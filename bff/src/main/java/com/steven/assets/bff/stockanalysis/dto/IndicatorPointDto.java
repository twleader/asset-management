package com.steven.assets.bff.stockanalysis.dto;

import java.math.BigDecimal;

/**
 * business `/api/market-data/indicators/series` 的逐日指標點（Task 261）。
 * 視窗／暖機不足的欄位為 null。
 */
public record IndicatorPointDto(
        String tradingDate,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma240,
        BigDecimal k,
        BigDecimal d,
        BigDecimal j9,
        BigDecimal k3d2,
        BigDecimal rsv) {}
