package com.steven.assets.repository;

import com.steven.assets.model.FundHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface FundHoldingRepository extends JpaRepository<FundHolding, Long> {

    List<FundHolding> findBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(f.currentValue) FROM FundHolding f WHERE f.snapshot.id = :snapshotId")
    BigDecimal sumCurrentValueBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(f.investmentAmount) FROM FundHolding f WHERE f.snapshot.id = :snapshotId")
    BigDecimal sumInvestmentAmountBySnapshotId(Long snapshotId);
}
