package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WatchStockDto {

    @Data
    @NoArgsConstructor
    public static class Request {
        private String stockCode;
        private String stockName;
        private String market;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private Long id;
        private String stockCode;
        private String stockName;
        private String market;
        // 報價
        private BigDecimal price;
        private BigDecimal priceChange;
        private BigDecimal changePercent;
        private BigDecimal buyPrice;
        private BigDecimal sellPrice;
        private BigDecimal openPrice;
        private BigDecimal previousClose;
        private BigDecimal highPrice;
        private BigDecimal lowPrice;
        private Long volume;
        private String tradingDate;
        private String priceUpdatedAt;
        private Boolean closed;
        // 警示彙總（最近一次觸發；以下技術指標皆為「觸發當下」的快照值）
        private LocalDateTime lastTriggeredAt;
        private BigDecimal lastTriggeredPrice;
        private String lastTriggeredAlertType;
        private BigDecimal lastTriggeredMaValue;   // 觸發當下的季線
        @JsonProperty("lastTriggeredKValue")
        private BigDecimal lastTriggeredKValue;    // 觸發當下的 K 值
        @JsonProperty("lastTriggeredDValue")
        private BigDecimal lastTriggeredDValue;    // 觸發當下的 D 值
    }
}
