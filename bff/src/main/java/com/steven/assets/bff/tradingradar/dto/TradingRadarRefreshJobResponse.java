package com.steven.assets.bff.tradingradar.dto;

/** Owner-scoped progress only; owner identity and provider diagnostics never enter this DTO. */
public record TradingRadarRefreshJobResponse(String jobId, String status, String createdAt,
                                            String completedAt, PriceRefresh priceRefresh) {
    public record PriceRefresh(String outcome, boolean twMarketOpen, long elapsedMs) {}
}
