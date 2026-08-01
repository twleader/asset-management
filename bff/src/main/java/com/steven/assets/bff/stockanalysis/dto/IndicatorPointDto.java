package com.steven.assets.bff.stockanalysis.dto;

import java.math.BigDecimal;

/**
 * business `/api/market-data/indicators/series` 的逐日指標點（Task 261）。
 * 視窗／暖機不足的欄位為 null。
 */
public record IndicatorPointDto(
        String tradingDate,
        /** 週線 MA5（Task 265）。 */
        BigDecimal ma5,
        BigDecimal ma20,
        BigDecimal ma60,
        BigDecimal ma240,
        BigDecimal k,
        BigDecimal d,
        BigDecimal j9,
        BigDecimal k3d2,
        BigDecimal rsv,
        // Task 262：指標選單新增 MACD／RSI／乖離率／威廉指標
        BigDecimal ema12,
        BigDecimal ema26,
        BigDecimal dif,
        BigDecimal macd,
        BigDecimal osc,
        BigDecimal rsi5,
        BigDecimal rsi10,
        BigDecimal bias10,
        BigDecimal bias20,
        BigDecimal b10b20,
        BigDecimal wr9) {}
