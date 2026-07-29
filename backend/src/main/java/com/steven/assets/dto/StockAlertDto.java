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

    /**
     * 複合條件群組內的單一條件（Task 253）。
     * Request 方向只讀 {@code alertType} / {@code maPeriod} / {@code threshold}；
     * {@code label} 僅 Response 回填（由後端 {@code StockAlertService.buildLabel} 產生），送進來時忽略
     * —— 顯示文字是可從三個欄位算出的衍生值，不接受前端自訂。
     */
    public record ConditionItem(
            String alertType,
            /** MA_*_PCT 類型必填：均線天數（20 / 60 / 240）；其餘類型必須為 null */
            Integer maPeriod,
            BigDecimal threshold,
            /** 單條條件的顯示文案（例「低於季線 10%（92.09）」），僅 Response 回填 */
            String label
    ) {}

    /**
     * 建立 / 更新複合條件群組（Task 253）。群組內條件為單層 AND：全部在同一次檢查中成立才觸發一次。
     * {@code conditions} 筆數限制 2～5，驗證見 {@code StockAlertService.validateConditions}。
     */
    public record GroupRequest(
            String stockCode,
            String stockName,
            String market,
            /** 群組成員條件（依此順序決定 displayOrder 與合併 label 的串接順序） */
            List<ConditionItem> conditions,
            Boolean active,
            /**
             * 此群組要寄送的通知收件人 id 清單（比照單一警示）。create / update 以此覆寫 join 列。
             * null 視為「沿用未變更」（update 時不動 join；create 時預設全選）；空 list 代表「不寄給任何人」。
             */
            List<Long> recipientIds
    ) {
        public GroupRequest {
            if (active == null) active = true;   // 比照 Request：JSON 省略 active 時視為啟用
        }
    }

    /**
     * 拖曳重排的單一項目（Task 253）。獨立條件與群組**共用同一個排序空間**，
     * 但 id 分屬 {@code stock_alert} / {@code stock_alert_group} 兩表、值必然重疊，
     * 故不能只送 id，必須以 {@code kind} 指明要更新哪一張表。
     *
     * @param kind {@code "SINGLE"}（獨立條件）或 {@code "GROUP"}（複合條件群組）
     * @param id   對應表的主鍵
     */
    public record OrderItem(String kind, Long id) {}

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
            String conditionLabel,
            // 以下兩欄為 Task 253 新增，刻意放在參數列尾端：@Builder 雖以欄位名指定、
            // 但既有呼叫端仍以此順序閱讀，附加在末端可讓 diff 只有增量、不動既有欄位。
            /**
             * {@code "SINGLE"} ＝ 獨立單一條件；{@code "GROUP"} ＝ 複合條件群組（Task 253）。
             * 前端據此把編輯 / 刪除 / 啟停分派到 {@code /groups/**} 或既有單一條件端點。
             */
            String kind,
            /**
             * 群組成員條件（{@code kind="GROUP"} 才有值，{@code SINGLE} 為 null）。
             * 順序為成員 {@code displayOrder} 升冪，與 {@code conditionLabel} 的串接順序一致。
             */
            List<ConditionItem> conditions
    ) {}
}
