package com.steven.assets.service;

import com.steven.assets.dto.NewsHeadlineDto;
import com.steven.assets.model.News;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NewsHeadlineQueryService {
    private final NewsHeadlineRepository repo;
    public List<NewsHeadlineDto> query(String date, String dateField, String category) {
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
