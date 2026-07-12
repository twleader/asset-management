package com.steven.assets.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class StockAlertDto {

    public record Request(
            String stockCode,
            String stockName,
            String market,
            String alertType,
            /** MA_*_PCT 類型必填：均線天數（20 / 60 / 240） */
            Integer maPeriod,
            BigDecimal threshold,
            Boolean active,
            /**
             * 此警示要寄送的通知收件人 id 清單（Task 125）。create / update 以此覆寫 join 列。
             * null 視為「沿用未變更」（update 時不動 join）；空 list 代表「不寄給任何人」。
             */
            List<Long> recipientIds
    ) {
        public Request {
            if (active == null) active = true;   // 保留原 @Data 欄位預設 active=true（JSON 省略時視為啟用）
        }
    }

    @Builder
    public record Response(
            Long id,
            String stockCode,
            String stockName,
            String market,
            String alertType,
            Integer maPeriod,
            BigDecimal threshold,
            Boolean active,
            /** 此警示目前選定的通知收件人 id 清單（Task 125；供前端對話框預勾）。 */
            List<Long> recipientIds,
            LocalDateTime lastTriggeredAt,
            BigDecimal lastTriggeredPrice,
            // 觸發當下的技術指標（凍結值，由 checkMaDeviation/checkKdValue 觸發時寫入 model）；
            // PRICE_ABOVE/BELOW 等不算 MA/KD 的警示為 null，前端顯示「—」
            BigDecimal lastTriggeredMaValue,
            BigDecimal lastTriggeredKdValue,
            BigDecimal lastTriggeredDValue,
            LocalDateTime createdAt,
            String conditionLabel
    ) {}
}
