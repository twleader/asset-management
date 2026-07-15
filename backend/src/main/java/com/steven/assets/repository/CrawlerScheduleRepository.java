package com.steven.assets.repository;

import com.steven.assets.model.CrawlerSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 公開資訊爬蟲執行時間設定（Requirement 37 / Task 184）。全域設定，無租戶過濾。
 * 設定變更走「整批覆寫」：{@link #deleteByCrawlerKey} 後重新 saveAll。
 */
public interface CrawlerScheduleRepository extends JpaRepository<CrawlerSchedule, Long> {

    /** 某爬蟲的所有時間點，依時分升序。 */
    List<CrawlerSchedule> findByCrawlerKeyOrderByRunHourAscRunMinuteAsc(String crawlerKey);

    /**
     * 整批覆寫前先刪除該爬蟲既有時間點。用 bulk {@code @Modifying} DELETE（直接對 DB 執行 DML），
     * 避免衍生 deleteBy 走 persistence context 時 Hibernate 把後續 INSERT 排在 DELETE 之前 flush、
     * 撞 {@code uq_crawler_schedule_key_time} 唯一鍵。{@code flushAutomatically} 先寫出 pending 變更。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from CrawlerSchedule c where c.crawlerKey = :crawlerKey")
    void deleteByCrawlerKey(@Param("crawlerKey") String crawlerKey);
}
