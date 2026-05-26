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
        /** MA_*_PCT 類型必填：均線天數（20 / 60 / 240） */
        private Integer maPeriod;
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
        private Integer maPeriod;
        private BigDecimal threshold;
        private Boolean active;
        private LocalDateTime lastTriggeredAt;
        private BigDecimal lastTriggeredPrice;
        // 觸發當下的技術指標（凍結值，由 checkMaDeviation/checkKdValue 觸發時寫入 model）；
        // PRICE_ABOVE/BELOW 等不算 MA/KD 的警示為 null，前端顯示「—」
        private BigDecimal lastTriggeredMaValue;
        private BigDecimal lastTriggeredKdValue;
        private BigDecimal lastTriggeredDValue;
        private LocalDateTime createdAt;
        private String conditionLabel;
    }
}
