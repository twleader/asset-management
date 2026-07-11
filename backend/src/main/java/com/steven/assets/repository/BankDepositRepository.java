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

    /** 一併抓銀行（避免 lazy／N+1）——資產配置建議組 prompt 需存款所屬銀行名稱。 */
    @Query("SELECT d FROM BankDeposit d LEFT JOIN FETCH d.bank WHERE d.snapshot.id = :snapshotId")
    List<BankDeposit> findWithBankBySnapshotId(Long snapshotId);

    List<BankDeposit> findBySnapshotIdAndBankId(Long snapshotId, Long bankId);

    @Query("SELECT SUM(d.amount) FROM BankDeposit d WHERE d.snapshot.id = :snapshotId")
    BigDecimal sumAmountBySnapshotId(Long snapshotId);
}
