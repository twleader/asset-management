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

    /** 所有持有過的基金名稱（去重、排序）——供「資產類別歸類」設定頁列出可 override 的基金。 */
    @Query("SELECT DISTINCT f.fundName FROM FundHolding f WHERE f.fundName IS NOT NULL ORDER BY f.fundName")
    List<String> findDistinctFundNames();

    @Query("SELECT SUM(f.currentValue) FROM FundHolding f WHERE f.snapshot.id = :snapshotId")
    BigDecimal sumCurrentValueBySnapshotId(Long snapshotId);

    @Query("SELECT SUM(f.investmentAmount) FROM FundHolding f WHERE f.snapshot.id = :snapshotId")
    BigDecimal sumInvestmentAmountBySnapshotId(Long snapshotId);
}
