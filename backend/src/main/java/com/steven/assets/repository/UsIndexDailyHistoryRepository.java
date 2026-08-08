package com.steven.assets.repository;

import com.steven.assets.model.UsIndexDailyHistory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UsIndexDailyHistoryRepository
        extends JpaRepository<UsIndexDailyHistory, UsIndexDailyHistory.PK> {

    List<UsIndexDailyHistory> findByIndexCodeOrderByTradingDateAsc(String indexCode);

    /** 該指數最新一筆日線（供自動回補排程 self-heal 判斷是否過時）。 */
    Optional<UsIndexDailyHistory> findTopByIndexCodeOrderByTradingDateDesc(String indexCode);

    List<UsIndexDailyHistory> findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(
            String indexCode, LocalDate from, LocalDate to);

    List<UsIndexDailyHistory> findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(
            String indexCode, LocalDate from);

    @Query("SELECT u FROM UsIndexDailyHistory u WHERE u.indexCode = :indexCode ORDER BY u.tradingDate DESC")
    List<UsIndexDailyHistory> findTopNByIndexCodeOrderByTradingDateDesc(
            @Param("indexCode") String indexCode, PageRequest page);

    /**
     * 取指定指數最新 N 個交易日（trading_date 降序）。供 {@code TechnicalIndicatorService} 計算
     * IXIC 的 MA240 / KD（Task 294），比照 {@code TwseIndexDailyHistoryRepository} 的既有命名慣例。
     */
    default List<UsIndexDailyHistory> findTopNByIndexCodeOrderByTradingDateDesc(String indexCode, int n) {
        return findTopNByIndexCodeOrderByTradingDateDesc(indexCode, PageRequest.of(0, n));
    }
}
