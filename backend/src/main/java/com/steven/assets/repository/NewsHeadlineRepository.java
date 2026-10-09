package com.steven.assets.repository;

import com.steven.assets.model.News;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 本地財經新聞讀取（Requirement 31 / Task 149.21）。全域參考資料，無租戶過濾。
 * 寫入端在 external-materials-service（JdbcTemplate 直寫），此處只讀供市場分析餵入 prompt。
 */
public interface NewsHeadlineRepository extends JpaRepository<News, Long> {

    /** 取近期（published_at ≥ cutoff）新聞，越新在前。 */
    List<News> findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(Instant cutoff);

    /** 依爬取入庫時間 fetched_at 落於 [from, to) 者，越新在前（爬蟲資訊查詢頁，Requirement 38）。 */
    List<News> findByFetchedAtGreaterThanEqualAndFetchedAtLessThanOrderByFetchedAtDesc(Instant from, Instant to);

    /** 依資料日期 published_at 落於 [from, to) 者，越新在前（爬蟲資訊查詢頁，Requirement 38）。 */
    List<News> findByPublishedAtGreaterThanEqualAndPublishedAtLessThanOrderByPublishedAtDesc(Instant from, Instant to);

    /**
     * 依去重鍵 {@code sha256(source|url|category)} 取單列（{@code dedupe_key} 有唯一索引 {@code uk_news_headline_dedupe}）。
     * SRPP 事件證據擷取（Requirement 181／Task 481.4）以此驗證 {@code NEWS_HEADLINE} 引用確實存在。
     */
    Optional<News> findByDedupeKey(String dedupeKey);
}
