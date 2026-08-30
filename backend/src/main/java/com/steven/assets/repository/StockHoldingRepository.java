package com.steven.assets.repository;

import com.steven.assets.model.StockHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface StockHoldingRepository extends JpaRepository<StockHolding, Long> {

    /** Same per-owner latest-snapshot union as the external-materials radar collector (R123). */
    @Query(value = """
            SELECT stock_code FROM stock_holding WHERE market = '台股'
              AND snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot
                ORDER BY owner_user_id, snapshot_date DESC, id DESC)
            UNION
            SELECT stock_code FROM stock_alert WHERE market = '台股'
            ORDER BY stock_code
            """, nativeQuery = true)
    List<String> findTwRadarCandidateCodes();

    List<StockHolding> findBySnapshotId(Long snapshotId);

    List<StockHolding> findBySnapshotIdAndMarket(Long snapshotId, String market);

    @Query("SELECT SUM(s.currentValue) FROM StockHolding s WHERE s.snapshot.id = :snapshotId")
    BigDecimal sumCurrentValueBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(s.investmentCost) FROM StockHolding s WHERE s.snapshot.id = :snapshotId")
    BigDecimal sumInvestmentCostBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(s.estimatedDividend) FROM StockHolding s WHERE s.snapshot.id = :snapshotId")
    BigDecimal sumEstimatedDividendBySnapshotId(Long snapshotId);

    @Modifying
    @Query("UPDATE StockHolding s SET s.displayOrder = :displayOrder " +
           "WHERE s.snapshot.id = :snapshotId AND s.stockCode = :stockCode AND s.market = :market")
    void updateDisplayOrder(@Param("snapshotId") Long snapshotId,
                            @Param("stockCode") String stockCode,
                            @Param("market") String market,
                            @Param("displayOrder") Integer displayOrder);
}
