package com.steven.assets.repository;

import com.steven.assets.model.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {
    List<StockAlert> findAllByOrderByDisplayOrderAsc();
    List<StockAlert> findByActiveTrue();
    List<StockAlert> findByStockCodeAndMarket(String stockCode, String market);

    /**
     * 觀察清單衍生 view：每個 (stockCode, market) 一筆，附該股票最小 displayOrder（用於排序）。
     * 結果欄位 [stockCode, market, minDisplayOrder]，依 minDisplayOrder 升冪。
     */
    @Query("SELECT a.stockCode, a.market, MIN(a.displayOrder) as ord " +
            "FROM StockAlert a GROUP BY a.stockCode, a.market ORDER BY ord ASC")
    List<Object[]> findDistinctStockCodeMarket();

    /** 同股票同市場所有 alert 的最小 displayOrder（拖曳重排觀察清單時用作群組 anchor）。 */
    @Query("SELECT MIN(a.displayOrder) FROM StockAlert a " +
            "WHERE a.stockCode = :code AND a.market = :market")
    Integer findMinDisplayOrderFor(String code, String market);
}
