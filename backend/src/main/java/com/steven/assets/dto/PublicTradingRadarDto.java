package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Container-only current-read projection for the anonymous 9090 trading-radar APIs.
 *
 * <p>首頁只取收合列所需欄位；指定股票明細則保留既有完整 {@link TradingRadarDto.StockDecision}
 * 契約。兩者都由同一次 {@code TradingRadarService.getCurrent()} 組裝結果投影，不能讀快照或重算。</p>
 */
public final class PublicTradingRadarDto {
    private PublicTradingRadarDto() {}

    public record TradingRadarListResponse(
            String ruleVersion,
            String actionPolicyVersion,
            String generatedAt,
            TradingRadarDto.MarketSummary market,
            TradingRadarDto.MarketSummary usMarket,
            List<TradingRadarListStock> stocks,
            int skippedNonTwStocks,
            List<TradingRadarDto.PublicInformationItem> publicInformation) {
        public TradingRadarListResponse {
            stocks = stocks == null ? List.of() : List.copyOf(stocks);
        }
    }

    /** 首頁收合列；刻意不含 reasons／evidence／完整 fundamental 與 K 棒等展開樹。 */
    public record TradingRadarListStock(
            String stockCode,
            String stockName,
            String market,
            String assetClass,
            boolean distributionAdjusted,
            boolean held,
            BigDecimal fxPercentile,
            String underlyingCurrency,
            TradingRadarListFundamental fundamental,
            String shortAction,
            String shortActionLabel,
            Integer shortScore,
            String swingAction,
            String swingActionLabel,
            Integer swingScore,
            String action,
            String actionLabel,
            Integer score,
            boolean horizonConflict,
            String timingState,
            String timingLabel,
            String counterTrendState,
            String counterTrendLabel,
            BigDecimal price,
            BigDecimal changePercent,
            String quoteStatus,
            BigDecimal etfPremiumLivePct,
            String etfPremiumLiveNavAsOf,
            BigDecimal weeklyMa,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String kdHeat,
            TradingRadarDto.WeeklyIndicators weeklyIndicators,
            String asOfDate) {}

    /** 首頁只需要基本面可用性與產業摘要，避免把來源／證據 tree 放進第一屏。 */
    public record TradingRadarListFundamental(
            boolean applicable,
            int coverage,
            String industryName,
            BigDecimal industryRevenueYoyPct) {}

    public record TradingRadarStockDetailResponse(
            String ruleVersion,
            String actionPolicyVersion,
            String generatedAt,
            TradingRadarDto.MarketSummary market,
            TradingRadarDto.MarketSummary usMarket,
            TradingRadarDto.StockDecision stock) {}
}
