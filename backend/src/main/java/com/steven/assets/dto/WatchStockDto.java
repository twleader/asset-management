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
        // 警示彙總（最近一次觸發）
        private LocalDateTime lastTriggeredAt;
        private BigDecimal lastTriggeredPrice;
        private String lastTriggeredAlertType;
        // 即時技術指標（不論是否觸發警示，皆計算最新值）
        private BigDecimal quarterlyMa;   // 季線 MA60
        @JsonProperty("kValue")
        private BigDecimal kValue;        // KD 之 K
        @JsonProperty("dValue")
        private BigDecimal dValue;        // KD 之 D
    }
}
