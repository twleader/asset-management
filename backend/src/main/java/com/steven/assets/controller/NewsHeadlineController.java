package com.steven.assets.controller;

import com.steven.assets.dto.NewsHeadlineDto;
import com.steven.assets.model.News;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 爬蟲資訊查詢（Requirement 38 / Task 192）business API：依指定日期查 {@code news_headline} 爬回的資料。
 * 全域參考資料（無租戶過濾），與 {@link com.steven.assets.service.MarketAnalysisService} 讀同一份表。
 */
@RestController
@RequestMapping("/api/news-headlines")
@RequiredArgsConstructor
public class NewsHeadlineController {

    private final NewsHeadlineRepository repo;

    /**
     * GET /api/news-headlines?date=YYYY-MM-DD&dateField=fetched|published&category=
     * <ul>
     *   <li>{@code date}：查詢日期（Asia/Taipei）；省略＝今天。</li>
     *   <li>{@code dateField}：{@code fetched}（預設，依爬取入庫日 {@code fetched_at}）或 {@code published}（依資料日 {@code published_at}）。</li>
     *   <li>{@code category}：選填，過濾類別（news／twse-institutional／twse-turnover／fx／us-market）。</li>
     * </ul>
     * 區間為該日 [00:00, 翌日 00:00)（Asia/Taipei），越新在前。
     */
    @GetMapping
    public List<NewsHeadlineDto> query(
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "fetched") String dateField,
            @RequestParam(required = false) String category) {

        LocalDate day = (date == null || date.isBlank())
                ? LocalDate.now(MarketZones.TW_ZONE)
                : LocalDate.parse(date.trim());
        Instant from = day.atStartOfDay(MarketZones.TW_ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(MarketZones.TW_ZONE).toInstant();

        boolean byPublished = "published".equalsIgnoreCase(dateField == null ? "" : dateField.trim());
        List<News> rows = byPublished
                ? repo.findByPublishedAtGreaterThanEqualAndPublishedAtLessThanOrderByPublishedAtDesc(from, to)
                : repo.findByFetchedAtGreaterThanEqualAndFetchedAtLessThanOrderByFetchedAtDesc(from, to);

        String cat = category == null || category.isBlank() ? null : category.trim();
        return rows.stream()
                .filter(n -> cat == null || cat.equalsIgnoreCase(n.getCategory()))
                .map(NewsHeadlineDto::from)
                .toList();
    }
}
