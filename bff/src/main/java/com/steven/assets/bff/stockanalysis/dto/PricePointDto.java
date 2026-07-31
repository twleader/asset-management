package com.steven.assets.bff.stockanalysis.dto;

import java.math.BigDecimal;

/**
 * business `/api/market-data/history/stock` 回應中本頁用得到的欄位（Task 261）。
 * 其餘欄位由 Jackson 忽略（Spring Boot 預設 FAIL_ON_UNKNOWN_PROPERTIES=false）。
 */
public record PricePointDto(String tradingDate, BigDecimal closePrice) {}
