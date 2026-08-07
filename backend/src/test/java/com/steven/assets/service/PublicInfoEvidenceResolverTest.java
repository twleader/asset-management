package com.steven.assets.service;

import com.steven.assets.model.News;
import com.steven.assets.repository.NewsHeadlineRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code public_info_*} 同源查詢只選相關原文，不做情緒評分。 */
class PublicInfoEvidenceResolverTest {

    @Test
    void codeNeedsBoundaryAndFullNameIndustryUseExactPhrase() {
        NewsHeadlineRepository repository = mock(NewsHeadlineRepository.class);
        Instant now = Instant.parse("2026-08-08T04:00:00Z");
        when(repository.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        news(1, "2330 台積電公布財報", "半導體業營收成長", now.minusSeconds(60)),
                        news(2, "12330 數字不得誤命中", null, now.minusSeconds(120)),
                        news(3, "半導體業景氣追蹤", null, now.minusSeconds(180)),
                        news(4, "2330 未來新聞", null, now.plusSeconds(60))));

        var result = new PublicInfoEvidenceResolver(repository)
                .resolve("2330", "台積電", "半導體業", now);

        assertEquals(List.of("台積電公布財報"),
                result.company().stream().map(v -> v.title().replace("2330 ", "")).toList());
        assertEquals(2, result.industry().size());
        assertTrue(result.company().stream().noneMatch(v -> v.title().contains("12330")));
    }

    @Test
    void noEvidenceIsEmptyAndNeverBecomesNegativeSignal() {
        NewsHeadlineRepository repository = mock(NewsHeadlineRepository.class);
        when(repository.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        var result = new PublicInfoEvidenceResolver(repository)
                .resolve("2330", "台積電", "半導體業", Instant.now());
        assertTrue(result.company().isEmpty());
        assertTrue(result.industry().isEmpty());
    }

    @Test
    void preloadedRowsStillApplyEachDecisionDaysOwnLookbackWindow() {
        NewsHeadlineRepository repository = mock(NewsHeadlineRepository.class);
        PublicInfoEvidenceResolver resolver = new PublicInfoEvidenceResolver(repository);
        Instant decision = Instant.parse("2026-08-08T04:00:00Z");
        List<News> preloaded = List.of(
                news(1, "2330 新聞", null, decision.minus(java.time.Duration.ofDays(10))),
                news(2, "2330 過期新聞", null, decision.minus(java.time.Duration.ofDays(121))));

        var result = resolver.resolveFromRows("2330", "台積電", null, decision, preloaded);

        assertEquals(List.of("2330 新聞"), result.company().stream().map(v -> v.title()).toList());
    }

    private static News news(long id, String title, String summary, Instant publishedAt) {
        return new News(id, title, "twse", "https://example.test/" + id,
                News.CATEGORY_NEWS, "TW", summary, publishedAt, publishedAt, "d" + id);
    }
}
