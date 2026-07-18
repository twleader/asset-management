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

    long countByCommodityCode(String commodityCode);

    /** 十年視窗清理：刪除 cutoff 之前的資料。 */
    long deleteByCommodityCodeAndPriceDateBefore(String commodityCode, LocalDate cutoffDate);
}
