package com.steven.assets.repository;

import com.steven.assets.model.StockPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockPriceHistoryRepository extends JpaRepository<StockPriceHistory, Long> {

    Optional<StockPriceHistory> findByStockCodeAndMarketAndTradingDate(String stockCode, String market, LocalDate tradingDate);

    List<StockPriceHistory> findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
        String stockCode, String market, LocalDate start, LocalDate end);

    @Query("SELECT MAX(h.tradingDate) FROM StockPriceHistory h WHERE h.stockCode = ?1 AND h.market = ?2")
    Optional<LocalDate> findMaxTradingDate(String stockCode, String market);

    @Query("SELECT MIN(h.tradingDate) FROM StockPriceHistory h WHERE h.stockCode = ?1 AND h.market = ?2")
    Optional<LocalDate> findMinTradingDate(String stockCode, String market);

    @Query("SELECT DISTINCT h.stockCode FROM StockPriceHistory h WHERE h.market = ?1")
    List<String> findDistinctStockCodesByMarket(String market);

    long countByStockCodeAndMarket(String stockCode, String market);

    /**
     * 找指定日期當天或最近之前的收盤價（closest-on-or-before）
     */
    @Query("SELECT h FROM StockPriceHistory h WHERE h.stockCode = ?1 AND h.market = ?2 AND h.tradingDate <= ?3 ORDER BY h.tradingDate DESC LIMIT 1")
    Optional<StockPriceHistory> findClosestPrice(String stockCode, String market, LocalDate date);

    /** 取最近 N 筆收盤價（降序），用於計算 MA / KD */
    @Query("SELECT h FROM StockPriceHistory h WHERE h.stockCode = ?1 AND h.market = ?2 ORDER BY h.tradingDate DESC LIMIT ?3")
    List<StockPriceHistory> findRecentN(String stockCode, String market, int n);

    /** 刪除交易日期早於指定日的舊資料 */
    long deleteByTradingDateBefore(LocalDate cutoffDate);
}
