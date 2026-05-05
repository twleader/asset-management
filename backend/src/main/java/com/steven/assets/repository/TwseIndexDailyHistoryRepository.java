package com.steven.assets.repository;

import com.steven.assets.model.TwseIndexDailyHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TwseIndexDailyHistoryRepository
        extends JpaRepository<TwseIndexDailyHistory, LocalDate> {

    List<TwseIndexDailyHistory> findAllByOrderByTradingDateAsc();

    List<TwseIndexDailyHistory> findByTradingDateBetweenOrderByTradingDateAsc(LocalDate from, LocalDate to);

    List<TwseIndexDailyHistory> findByTradingDateGreaterThanEqualOrderByTradingDateAsc(LocalDate from);

    /** 取最新 N 個交易日（trading_date 降序）。供 0000 觀察列計算最新收盤、昨收與季線(MA60)。 */
    List<TwseIndexDailyHistory> findTop60ByOrderByTradingDateDesc();
}
