package com.steven.assets.repository;

import com.steven.assets.model.TwseIndexDailyHistory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TwseIndexDailyHistoryRepository
        extends JpaRepository<TwseIndexDailyHistory, LocalDate> {

    List<TwseIndexDailyHistory> findAllByOrderByTradingDateAsc();

    List<TwseIndexDailyHistory> findByTradingDateBetweenOrderByTradingDateAsc(LocalDate from, LocalDate to);

    List<TwseIndexDailyHistory> findByTradingDateGreaterThanEqualOrderByTradingDateAsc(LocalDate from);

    @Query("SELECT t FROM TwseIndexDailyHistory t WHERE t.tradingDate < ?1 ORDER BY t.tradingDate DESC LIMIT 1")
    Optional<TwseIndexDailyHistory> findPreviousBefore(LocalDate date);

    /** 取最新 60 個交易日（trading_date 降序）。供 0000 觀察列計算最新收盤、昨收與季線(MA60)。 */
    List<TwseIndexDailyHistory> findTop60ByOrderByTradingDateDesc();

    @Query("SELECT t FROM TwseIndexDailyHistory t ORDER BY t.tradingDate DESC")
    List<TwseIndexDailyHistory> findTopNByOrderByTradingDateDesc(PageRequest page);

    /** 取最新 N 個交易日（trading_date 降序）。供 TechnicalIndicatorService 計算 MA240 / KD。 */
    default List<TwseIndexDailyHistory> findTopNByOrderByTradingDateDesc(int n) {
        return findTopNByOrderByTradingDateDesc(PageRequest.of(0, n));
    }
}
