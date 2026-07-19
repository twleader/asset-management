package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）純讀 response。
 *
 * <p>所有分數／建議皆為 {@code TW_RULES_V4} 即時計算的衍生值，不入庫；
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
            /** 大盤最新完成日 K 非當前交易日：買進閘門關閉、不採計 RISK_ON 加分（Task 217.1）。 */
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
            List<String> risks
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
            /**
             * 底層資產幣別對台幣的五年期分位（0–100）；台幣資產為 null（Requirement 47）。
             * 供畫面揭露「現在換匯貴不貴」——台幣計價的美債 ETF 其報價相當部分由匯率驅動
             * （實測 00719B 與 USD/TWD 近一年相關 0.9737），不揭露會讓使用者以為漲勢來自標的本身。
             */
            BigDecimal fxPercentile,
            /** 底層資產幣別（TWD/USD/GBP…），供前端判斷是否顯示匯率相關說明。 */
            String underlyingCurrency,
            List<String> reasons,
            List<String> risks
    ) {}
}
