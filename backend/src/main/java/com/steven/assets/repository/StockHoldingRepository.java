package com.steven.assets.repository;

import com.steven.assets.model.StockHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface StockHoldingRepository extends JpaRepository<StockHolding, Long> {

    List<StockHolding> findBySnapshotId(Long snapshotId);

    List<StockHolding> findBySnapshotIdAndMarket(Long snapshotId, String market);

    @Query("SELECT SUM(s.currentValue) FROM StockHolding s WHERE s.snapshot.id = :snapshotId")
    BigDecimal sumCurrentValueBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(s.investmentCost) FROM StockHolding s WHERE s.snapshot.id = :snapshotId")
    BigDecimal sumInvestmentCostBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(s.estimatedDividend) FROM StockHolding s WHERE s.snapshot.id = :snapshotId")
    BigDecimal sumEstimatedDividendBySnapshotId(Long snapshotId);
}
