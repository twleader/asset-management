package com.steven.assets.repository;

import com.steven.assets.model.UsIndexDailyHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface UsIndexDailyHistoryRepository
        extends JpaRepository<UsIndexDailyHistory, UsIndexDailyHistory.PK> {

    List<UsIndexDailyHistory> findByIndexCodeOrderByTradingDateAsc(String indexCode);

    List<UsIndexDailyHistory> findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(
            String indexCode, LocalDate from, LocalDate to);

    List<UsIndexDailyHistory> findByIndexCodeAndTradingDateGreaterThanEqualOrderByTradingDateAsc(
            String indexCode, LocalDate from);
}
