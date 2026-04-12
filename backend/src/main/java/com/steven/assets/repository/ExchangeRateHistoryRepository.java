package com.steven.assets.repository;

import com.steven.assets.model.ExchangeRateHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateHistoryRepository extends JpaRepository<ExchangeRateHistory, Long> {

    Optional<ExchangeRateHistory> findByCurrencyAndRateDate(String currency, LocalDate rateDate);

    List<ExchangeRateHistory> findByCurrencyAndRateDateBetweenOrderByRateDateAsc(
        String currency, LocalDate start, LocalDate end);

    List<ExchangeRateHistory> findByCurrencyOrderByRateDateAsc(String currency);

    @Query("SELECT MAX(e.rateDate) FROM ExchangeRateHistory e WHERE e.currency = ?1")
    Optional<LocalDate> findMaxRateDate(String currency);

    long countByCurrency(String currency);

    /** 刪除指定幣別中日期早於指定日的舊資料 */
    long deleteByCurrencyAndRateDateBefore(String currency, LocalDate cutoffDate);

    /** 取得指定日期或之前最近的匯率 (用於歷史交易查匯率) */
    @Query("SELECT e FROM ExchangeRateHistory e WHERE e.currency = ?1 AND e.rateDate <= ?2 ORDER BY e.rateDate DESC LIMIT 1")
    Optional<ExchangeRateHistory> findClosestRate(String currency, LocalDate date);
}
