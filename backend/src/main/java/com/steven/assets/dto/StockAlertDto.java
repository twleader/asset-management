package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class StockAlertDto {

    @Data
    public static class Request {
        private String stockCode;
        private String stockName;
        private String market;
        private String alertType;
        private BigDecimal threshold;
        private Boolean active = true;
    }

    @Data
    public static class Response {
        private Long id;
        private String stockCode;
        private String stockName;
        private String market;
        private String alertType;
        private BigDecimal threshold;
        private Boolean active;
        private LocalDateTime lastTriggeredAt;
        private BigDecimal lastTriggeredPrice;
        private BigDecimal lastTriggeredMaValue;
        private BigDecimal lastTriggeredKdValue;
        private BigDecimal lastTriggeredDValue;
        // 即時技術指標（觸發時搭配顯示，與 alert 條件無關）
        private BigDecimal quarterlyMa;
        @JsonProperty("kValue")
        private BigDecimal kValue;
        @JsonProperty("dValue")
        private BigDecimal dValue;
        private LocalDateTime createdAt;
        private String conditionLabel;
    }
}
