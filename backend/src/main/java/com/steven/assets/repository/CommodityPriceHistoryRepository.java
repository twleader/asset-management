package com.steven.assets.repository;

import com.steven.assets.model.CommodityPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 原物料（油價／金價）歷史查詢（Requirement 40）。全域公開資料，無 owner 過濾。
 */
@Repository
public interface CommodityPriceHistoryRepository extends JpaRepository<CommodityPriceHistory, Long> {

    List<CommodityPriceHistory> findByCommodityCodeAndPriceDateBetweenOrderByPriceDateAsc(
            String commodityCode, LocalDate start, LocalDate end);

    List<CommodityPriceHistory> findByCommodityCodeOrderByPriceDateAsc(String commodityCode);

    /** 取最近日期（判斷是否需刷新）。 */
    @Query("SELECT MAX(c.priceDate) FROM CommodityPriceHistory c WHERE c.commodityCode = ?1")
    Optional<LocalDate> findMaxPriceDate(String commodityCode);

    /**
     * 即時報價漲跌計算用（Requirement 77 / Task 337）：{@code sessionDate} 當盤是否已收盤結算。
     * 命中代表 337.6 收盤校正已把該盤收盤價寫進本表——夜盤情境下取這一列自己的收盤當前收。
     */
    Optional<CommodityPriceHistory> findByCommodityCodeAndPriceDate(String commodityCode, LocalDate priceDate);

    /**
     * 即時報價漲跌計算用（Requirement 77 / Task 337）：嚴格早於指定日期的最後一筆收盤，
     * 供「該盤仍在進行中」或「{@code SETTLED} 那一列要跳過自己」兩種情境取前收。
     */
    Optional<CommodityPriceHistory> findFirstByCommodityCodeAndPriceDateLessThanOrderByPriceDateDesc(
            String commodityCode, LocalDate priceDate);

    long countByCommodityCode(String commodityCode);

    /** 十年視窗清理：刪除 cutoff 之前的資料。 */
    long deleteByCommodityCodeAndPriceDateBefore(String commodityCode, LocalDate cutoffDate);
}
