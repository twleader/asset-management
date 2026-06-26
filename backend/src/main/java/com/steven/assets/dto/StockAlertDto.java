package com.steven.assets.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
        /**
         * 此警示要寄送的通知收件人 id 清單（Task 125）。create / update 以此覆寫 join 列。
         * null 視為「沿用未變更」（update 時不動 join）；空 list 代表「不寄給任何人」。
         */
        private List<Long> recipientIds;
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
        /** 此警示目前選定的通知收件人 id 清單（Task 125；供前端對話框預勾）。 */
        private List<Long> recipientIds;
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
