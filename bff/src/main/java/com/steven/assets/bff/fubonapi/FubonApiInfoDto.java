package com.steven.assets.bff.fubonapi;

/**
 * FubonApiView 專屬 BFF（「系統資訊」分組，Requirement 121）單筆富邦 SDK 唯讀查詢能力。
 *
 * <p>唯讀資訊展示用的不可變 record。清單為 {@link FubonApiInfoBffController} 內建靜態資料，
 * 涵蓋富邦官方 SDK（{@code fubon_neo} 2.2.9）{@code accounting}／{@code stock}／
 * {@code marketdata} 命名空間中已驗證存在、且確認為唯讀查詢的方法，一律排除任何下單／
 * 改單／撤單等寫入方法。
 *
 * @param connected       本系統是否已實際呼叫此 SDK 能力
 * @param category        分類（連線狀態查詢／帳戶／庫存查詢／委託與交易資訊查詢／個股報價查詢／歷史成交查詢／行情查詢／即時推播）
 * @param name             中文名稱
 * @param sdkReference     SDK 方法或頻道的完整可查證路徑（如 {@code sdk.accounting.bank_remain}），已串接與未串接皆必填
 * @param httpEndpoint     本系統已串接時對應的 {@code method + path}；未串接為空字串，不得虛構
 * @param description      白話唯讀用途說明
 * @param consumer         已串接時為實際呼叫端服務；未串接為「－（尚未串接）」
 * @param requestSummary   請求參數摘要
 * @param responseSummary  回應內容摘要
 */
public record FubonApiInfoDto(
        boolean connected,
        String category,
        String name,
        String sdkReference,
        String httpEndpoint,
        String description,
        String consumer,
        String requestSummary,
        String responseSummary
) {
}
