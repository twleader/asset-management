package com.steven.assets.dto;

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
        private LocalDateTime createdAt;
        private String conditionLabel;
    }
}
