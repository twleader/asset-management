package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * 抓取到的一則新聞 / 公開資訊（Task 149.21），供 {@code NewsPoller} 算去重鍵後 upsert 至 news_headline。
 *
 * @param title       標題
 * @param source      來源代號：wantgoo / moneydj / ltn / udn / twse / bot-fx / us-index（後二為 Task 180 匯率・美股快照）
 * @param url         原文/查詢頁連結（TWSE 帶 date 參數使每日 URL 唯一 → 天然去重；fx/us-market 為固定頁 URL、每輪就地覆寫）
 * @param category    news / twse-institutional / twse-turnover / fx / us-market（fx=台幣兌美元匯率、us-market=美股指數，Task 180）
 * @param region      TW / US / JP / SG（本地權威新聞固定 TW；us-market 快照為 US）
 * @param summary     摘要/數據明細（可為 null）
 * @param publishedAt 原文/資料真實發布時間。輸出 SRPP JSON 時以 Asia/Taipei（+08:00）序列化（Task 177），
 *                    使日期與交易日／{@code tradingDayCutoff} 一致（避免 UTC 使 TW 凌晨/整點資料的日期倒退一天）。
 * @param tags        來源提供的結構化相關實體標籤（目前僅 wantgoo {@code newsTags}），供個股過濾用（Task 178）。
 *                    {@code @JsonIgnore}：僅供過濾判定，不入 {@code news_headline}、不寫入 SRPP JSON。其他來源為空 list。
 */
public record NewsRow(
        String title,
        String source,
        String url,
        String category,
        String region,
        String summary,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Taipei")
        Instant publishedAt,
        @JsonIgnore List<String> tags) {

    /** 相容既有 7-arg 呼叫（無結構化 tags 的來源）：tags 預設空。 */
    public NewsRow(String title, String source, String url, String category,
                   String region, String summary, Instant publishedAt) {
        this(title, source, url, category, region, summary, publishedAt, List.of());
    }
}
