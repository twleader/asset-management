package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）純讀 response。
 *
 * <p>所有分數／建議皆為 {@code TW_RULES_V6} 即時計算的衍生值，不入庫；
 * {@code score=null} 代表必要資料不足，不以 0 分冒充有效判斷。</p>
 */
public final class TradingRadarDto {

    private TradingRadarDto() {}

    public record Response(
            String ruleVersion,
            String generatedAt,
            MarketSummary market,
            List<StockDecision> stocks,
            int skippedNonTwStocks
    ) {}

    public record MarketSummary(
            String regime,
            String regimeLabel,
            Integer score,
            boolean dataComplete,
            /** 大盤最新完成日 K 非當前交易日、且 Redis 亦無今日即時價：買進閘門關閉、不採計 RISK_ON 加分（Task 217.1，語意於 Task 228 擴充）。 */
            boolean stale,
            String asOfDate,
            BigDecimal price,
            BigDecimal changePercent,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String quarterlyConfirmation,
            String annualConfirmation,
            List<String> reasons,
            List<String> risks,
            /** regime 是否由 Redis 今日即時點位算出（相對於「已入庫完成日 K」）（Task 228）。 */
            boolean intraday,
            /** intraday=true 時為 Redis 即時價的 updatedAt（ISO 字串）；否則為 null（Task 228）。 */
            String liveUpdatedAt
    ) {}

    public record StockDecision(
            String stockCode,
            String stockName,
            String market,
            String assetClass,
            boolean distributionAdjusted,
            boolean held,
            String action,
            String actionLabel,
            Integer score,
            String counterTrendState,
            String counterTrendLabel,
            List<String> counterTrendReasons,
            List<String> counterTrendRisks,
            boolean dataComplete,
            BigDecimal price,
            BigDecimal changePercent,
            String priceUpdatedAt,
            String asOfDate,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String monthlyConfirmation,
            String quarterlyConfirmation,
            String annualConfirmation,
            List<String> reasons,
            List<String> risks
    ) {}
}
