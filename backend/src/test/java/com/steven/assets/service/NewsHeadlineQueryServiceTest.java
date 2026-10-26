package com.steven.assets.service;

import com.steven.assets.model.News;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.util.MarketZones;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsHeadlineQueryServiceTest {
    private final NewsHeadlineRepository repo = mock(NewsHeadlineRepository.class);
    private final NewsHeadlineQueryService service = new NewsHeadlineQueryService(repo);
    @Test
    void publishedUsesTaipeiHalfOpenDayAndCategoryFilteringPreservesRepositoryOrder() {
        var day = LocalDate.of(2026,10,25);
        var from = day.atStartOfDay(MarketZones.TW_ZONE).toInstant();
        var to = day.plusDays(1).atStartOfDay(MarketZones.TW_ZONE).toInstant();
        News first = new News(); first.setCategory("NEWS"); first.setTitle("newest");
        News ignored = new News(); ignored.setCategory("fx");
        News second = new News(); second.setCategory("news"); second.setTitle("older");
        when(repo.findByPublishedAtGreaterThanEqualAndPublishedAtLessThanOrderByPublishedAtDesc(from,to)).thenReturn(List.of(first,ignored,second));
        assertThat(service.query(" 2026-10-25 "," PuBLished "," news ")).extracting("title").containsExactly("newest","older");
        verify(repo).findByPublishedAtGreaterThanEqualAndPublishedAtLessThanOrderByPublishedAtDesc(from,to);
        verifyNoMoreInteractions(repo);
    }
    @Test
    void blankDateDefaultsToTodayAndUnknownDateFieldUsesFetchedWithNoCategory() {
        var today = LocalDate.now(MarketZones.TW_ZONE);
        var from = today.atStartOfDay(MarketZones.TW_ZONE).toInstant();
        var to = today.plusDays(1).atStartOfDay(MarketZones.TW_ZONE).toInstant();
        News row = new News();
        when(repo.findByFetchedAtGreaterThanEqualAndFetchedAtLessThanOrderByFetchedAtDesc(from,to)).thenReturn(List.of(row));
        assertThat(service.query(" ",null," ")).hasSize(1);
        verify(repo).findByFetchedAtGreaterThanEqualAndFetchedAtLessThanOrderByFetchedAtDesc(from,to);
    }
}
