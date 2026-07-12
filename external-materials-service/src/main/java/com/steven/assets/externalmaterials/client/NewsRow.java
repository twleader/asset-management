package com.steven.assets.externalmaterials.client;

import java.time.Instant;

/**
 * 抓取到的一則新聞 / 公開資訊（Task 149.21），供 {@code NewsPoller} 算去重鍵後 upsert 至 news_headline。
 *
 * @param title       標題
 * @param source      來源代號：wantgoo / moneydj / ltn / udn / twse
 * @param url         原文/查詢頁連結（TWSE 帶 date 參數使每日 URL 唯一 → 天然去重）
 * @param category    news / twse-institutional / twse-turnover
 * @param region      TW / US / JP / SG（本地權威來源固定 TW）
 * @param summary     摘要/數據明細（可為 null）
 * @param publishedAt 原文/資料真實發布時間
 */
public record NewsRow(
        String title,
        String source,
        String url,
        String category,
        String region,
        String summary,
        Instant publishedAt) {
}
