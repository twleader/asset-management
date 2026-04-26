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
        // 當前技術指標（不論是哪一類警示，皆計算當前值；與觀察清單共用 TechnicalIndicatorService）
        private BigDecimal quarterlyMa;
        private BigDecimal kValue;
        private BigDecimal dValue;
        private LocalDateTime createdAt;
        private String conditionLabel;
    }
}
