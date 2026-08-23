package com.steven.assets.repository;

import com.steven.assets.model.StockDividendHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockDividendHistoryRepository extends JpaRepository<StockDividendHistory, Long> {

    /**
     * 查詢某檔最近 N 年的股利歷史，年份遞減、同年依 anchorDate 遞減。
     *
     * <p>Task 357：改用 {@code COALESCE(exDividendDate, exRightsDate)}，否則純配股
     * 事件（exDividendDate 為 null）在同年度排序會退化到 NULLS LAST 尾端，順序不正確。</p>
     */
    @Query("""
        SELECT h FROM StockDividendHistory h
        WHERE h.stockCode = :code AND h.market = :market AND h.year >= :sinceYear
          AND h.eventStatus = 'ACTIVE'
        ORDER BY h.year DESC, COALESCE(h.exDividendDate, h.exRightsDate) DESC NULLS LAST
        """)
    List<StockDividendHistory> findByStockSinceYear(String code, String market, int sinceYear);

    /**
     * 交易雷達還原權息用的區間事件；排除年度彙總列與零值事件。
     *
     * <p>Task 357：區間比較改用 anchorDate = {@code COALESCE(exDividendDate,
     * exRightsDate)}——純配股事件的 exDividendDate 為 null，裸欄位 BETWEEN 對 NULL
     * 恆為 UNKNOWN、等同隱性排除。</p>
     */
    @Query("""
        SELECT h FROM StockDividendHistory h
        WHERE h.stockCode = :code AND h.market = :market
          AND COALESCE(h.exDividendDate, h.exRightsDate) BETWEEN :fromDate AND :toDate
          AND h.eventStatus = 'ACTIVE'
          AND (COALESCE(h.cashDividend, 0) > 0 OR COALESCE(h.stockDividend, 0) > 0)
        ORDER BY COALESCE(h.exDividendDate, h.exRightsDate) ASC, h.id ASC
        """)
    List<StockDividendHistory> findAdjustmentEvents(
            String code, String market, LocalDate fromDate, LocalDate toDate);

    Optional<StockDividendHistory> findFirstByStockCodeAndMarketAndYearAndExDividendDate(
            String code, String market, Integer year, LocalDate exDividendDate);

    /** ex_dividend_date 為 null 的紀錄（年度彙總）。 */
    Optional<StockDividendHistory> findFirstByStockCodeAndMarketAndYearAndExDividendDateIsNull(
            String code, String market, Integer year);

    long countByStockCodeAndMarket(String code, String market);
}
