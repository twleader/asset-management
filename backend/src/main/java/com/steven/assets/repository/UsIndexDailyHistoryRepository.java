package com.steven.assets.repository;

import com.steven.assets.model.UsIndexDailyHistory;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
