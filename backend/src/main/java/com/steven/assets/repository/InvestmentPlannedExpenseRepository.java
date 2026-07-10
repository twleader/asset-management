package com.steven.assets.repository;

import com.steven.assets.model.InvestmentPlannedExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface InvestmentPlannedExpenseRepository extends JpaRepository<InvestmentPlannedExpense, Long> {

    /** 某使用者的大筆花費清單（依花費日期排序）。 */
    List<InvestmentPlannedExpense> findByOwnerUserIdOrderByExpenseDate(Long ownerUserId);

    /**
     * 刪除某使用者全部大筆花費（saveProfile replace 前先清空）。以明確 ownerId 為條件、不依賴 @Filter。
     * 自帶 @Transactional：derived delete 需交易界定；saveProfile 經代理呼叫時已在交易內（此處 join），
     * generate 內部自呼叫 saveProfile（proxy 不套）時，仍由本方法自身交易保障。
     */
    @Transactional
    void deleteByOwnerUserId(Long ownerUserId);
}
