package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class WatchStockDto {

    /** 該股票一筆警示條件的扁平表示（供前端「警示條件」欄逐條顯示）。 */
    public record Condition(
            String label,
            Boolean active,
            /** 該條件在最後一個交易日（或交易當日）及前一日內已觸發（與「警示」欄使用同一份 cutoff），前端以紅字顯示。 */
            Boolean triggered,
            /**
             * 這一條是否為複合條件群組合併而成（Task 253），前端據此在 label 前掛「複合」標籤。
             *
             * <p>非有不可：群組在後端已被合併成單一 {@code Condition}，合併後的 label 與獨立條件
             * 長得一模一樣，前端沒有任何欄位可分辨。用 label 內含「 且 」去猜是不可靠的
             * ——單一條件的股名或未來的文案都可能出現同一個字。
             */
            Boolean group
    ) {}

    /** 觀察清單操作以 (stockCode, market) tuple 識別股票（不再有獨立 watch_stock.id）。 */
    public record Key(
            String stockCode,
            String market
    ) {}

    @Builder
    public record Response(
            String stockCode,
            String stockName,
            String market,
            // 報價
            BigDecimal price,
            BigDecimal priceChange,
            BigDecimal changePercent,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal previousClose,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume,
            String tradingDate,
            String priceUpdatedAt,
            Boolean closed,
            String quoteStatus,
            // 該股票所有警示條件（依 displayOrder 升冪），「警示條件」欄逐條列出
            List<Condition> conditions,
            // 警示彙總（最近一次觸發）
            LocalDateTime lastTriggeredAt,
            BigDecimal lastTriggeredPrice,
            String lastTriggeredAlertType,
            // 即時技術指標（不論是否觸發警示，皆計算最新值；同義欄位同一來源 TechnicalIndicatorService.computeAll）
            BigDecimal monthlyMa,     // 月線 MA20
            BigDecimal quarterlyMa,   // 季線 MA60
            BigDecimal annualMa,      // 年線 MA240
            @JsonProperty("kValue")
            BigDecimal kValue,        // KD 之 K
            @JsonProperty("dValue")
            BigDecimal dValue         // KD 之 D
    ) {}
}
