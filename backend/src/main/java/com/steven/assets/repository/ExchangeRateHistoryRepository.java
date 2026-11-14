package com.steven.assets.repository;

import com.steven.assets.model.ExchangeRateHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateHistoryRepository extends JpaRepository<ExchangeRateHistory, Long> {

    @EntityGraph(attributePaths = "sourceCapture")
    Optional<ExchangeRateHistory> findByCurrencyAndRateDate(String currency, LocalDate rateDate);

    @EntityGraph(attributePaths = "sourceCapture")
    List<ExchangeRateHistory> findByCurrencyAndRateDateBetweenOrderByRateDateAsc(
        String currency, LocalDate start, LocalDate end);

    /** Exact currency-set read for one request-scoped radar context. */
    @EntityGraph(attributePaths = "sourceCapture")
    List<ExchangeRateHistory> findByCurrencyInAndRateDateBetweenOrderByCurrencyAscRateDateAsc(
            Collection<String> currencies, LocalDate start, LocalDate end);

    @EntityGraph(attributePaths = "sourceCapture")
    List<ExchangeRateHistory> findByCurrencyOrderByRateDateAsc(String currency);

    @Query("SELECT MAX(e.rateDate) FROM ExchangeRateHistory e WHERE e.currency = ?1")
    Optional<LocalDate> findMaxRateDate(String currency);

    @Query("SELECT MIN(e.rateDate) FROM ExchangeRateHistory e WHERE e.currency = ?1")
    Optional<LocalDate> findMinDate(String currency);

    long countByCurrency(String currency);

    /** 刪除指定幣別中日期早於指定日的舊資料 */
    long deleteByCurrencyAndRateDateBefore(String currency, LocalDate cutoffDate);

    /** 取得指定日期或之前最近的匯率 (用於歷史交易查匯率) */
    @Query("SELECT e FROM ExchangeRateHistory e WHERE e.currency = ?1 AND e.rateDate <= ?2 ORDER BY e.rateDate DESC LIMIT 1")
    Optional<ExchangeRateHistory> findClosestRate(String currency, LocalDate date);

    /** 該幣別最新一筆匯率（不限日期）。 */
    @EntityGraph(attributePaths = "sourceCapture")
    Optional<ExchangeRateHistory> findFirstByCurrencyOrderByRateDateDesc(String currency);
}
