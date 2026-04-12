package com.steven.assets.repository;

import com.steven.assets.model.BankDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface BankDepositRepository extends JpaRepository<BankDeposit, Long> {

    List<BankDeposit> findBySnapshotId(Long snapshotId);

    List<BankDeposit> findBySnapshotIdAndBankId(Long snapshotId, Long bankId);

    @Query("SELECT SUM(d.amount) FROM BankDeposit d WHERE d.snapshot.id = :snapshotId")
    BigDecimal sumAmountBySnapshotId(Long snapshotId);
}
