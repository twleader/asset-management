package com.steven.assets.dto;

import com.steven.assets.model.News;

import java.time.Instant;

/**
 * 爬蟲資訊查詢頁（Requirement 37 / Task 184）：{@code news_headline} 單列的唯讀 DTO。
 * {@code publishedAt}＝資料日期（新聞發布日／交易日）、{@code fetchedAt}＝爬取入庫時間。
 */
public record NewsHeadlineDto(
        Long id,
        String title,
        String source,
        String url,
        String category,
        String region,
        String summary,
        Instant publishedAt,
        Instant fetchedAt
) {
    public static NewsHeadlineDto from(News n) {
        return new NewsHeadlineDto(
                n.getId(), n.getTitle(), n.getSource(), n.getUrl(),
                n.getCategory(), n.getRegion(), n.getSummary(),
                n.getPublishedAt(), n.getFetchedAt());
    }
}
