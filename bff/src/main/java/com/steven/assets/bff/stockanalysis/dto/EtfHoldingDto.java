package com.steven.assets.bff.stockanalysis.dto;

import java.math.BigDecimal;

/**
 * business `/api/market-data/etf-holdings` 回應中單一持股列（Task 359.4）。
 * 欄位對齊 business {@code MarketDataService.EtfHolding}，供 WebClient 反序列化。
 *
 * 「其它」聚合列（見 {@code EtfHoldingsAggregator}）以 {@code stockCode=null}、
 * {@code stockName="其它"}、{@code shares=null} 表示——前端既有的 null-safe 顯示邏輯
 * （tooltip 依 {@code stockCode} 判斷要不要顯示代號、{@code shares==null} 顯示「—」）
 * 可直接沿用，不需要另寫特例。
 */
public record EtfHoldingDto(String stockCode, String stockName, BigDecimal weight, BigDecimal shares) {}
