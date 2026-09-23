package com.steven.assets.dto;

import java.util.List;

/** Self-contained browser reads; never persisted as an export snapshot. */
public final class TradingRadarPanelDto {
    private TradingRadarPanelDto() {}

    public record Panel<T>(String panel, String ruleVersion, String actionPolicyVersion,
                           String generatedAt, T data) {}

    public record MarketData(TradingRadarDto.MarketSummary market) {}

    public record StocksData(TradingRadarDto.MarketSummary market,
                             List<TradingRadarDto.ListStock> stocks, int skippedNonTwStocks) {
        public StocksData { stocks = List.copyOf(stocks); }
    }

    public record PublicInformationData(List<TradingRadarDto.PublicInformationItem> publicInformation) {
        public PublicInformationData { publicInformation = List.copyOf(publicInformation); }
    }

    public record StockEvaluation(String ruleVersion, String actionPolicyVersion, String generatedAt,
                                  TradingRadarDto.MarketSummary market,
                                  TradingRadarDto.ListStock summary, TradingRadarDto.StockDecision stock) {}

    public record RefreshJob(String jobId, String status, String createdAt, String completedAt,
                             TradingRadarDto.PriceRefresh priceRefresh) {}
}
