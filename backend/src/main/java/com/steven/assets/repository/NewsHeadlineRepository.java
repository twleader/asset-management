package com.steven.assets.repository;

import com.steven.assets.model.News;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * 本地財經新聞讀取（Requirement 31 / Task 149.21）。全域參考資料，無租戶過濾。
 * 寫入端在 external-materials-service（JdbcTemplate 直寫），此處只讀供市場分析餵入 prompt。
 */
public interface NewsHeadlineRepository extends JpaRepository<News, Long> {

    /** 取近期（published_at ≥ cutoff）新聞，越新在前。 */
    List<News> findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(Instant cutoff);

    /** 依爬取入庫時間 fetched_at 落於 [from, to) 者，越新在前（爬蟲資訊查詢頁，Requirement 37）。 */
    List<News> findByFetchedAtGreaterThanEqualAndFetchedAtLessThanOrderByFetchedAtDesc(Instant from, Instant to);

    /** 依資料日期 published_at 落於 [from, to) 者，越新在前（爬蟲資訊查詢頁，Requirement 37）。 */
    List<News> findByPublishedAtGreaterThanEqualAndPublishedAtLessThanOrderByPublishedAtDesc(Instant from, Instant to);
}
