package com.steven.assets.bff.stockanalysis.dto;

import java.util.List;

/**
 * business `/api/market-data/etf-holdings` 回應（Task 359.4）。
 * 欄位對齊 business {@code MarketDataService.EtfHoldingsResult}，供 WebClient 反序列化；
 * 頂層 JSON 形狀（欄位名稱與巢狀結構）與 business 現況完全一致，前端 `getEtfHoldings()`
 * 呼叫端與既有型別不需要跟著改。
 *
 * 唯一的差異在 {@code holdings}：business 回傳完整清單，BFF 端經 {@code EtfHoldingsAggregator}
 * 依 {@code weight} 排序＋截斷為「前 10 大 + 其它」後才回給前端；{@code supported}／
 * {@code source}／{@code asOfDate}／{@code message} 等頂層欄位原封不動透傳。
 */
public record EtfHoldingsDto(
        String stockCode,
        String market,
        boolean supported,
        String source,
        String asOfDate,
        String message,
        List<EtfHoldingDto> holdings) {}
