package com.steven.assets.repository;

import com.steven.assets.model.PaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentAccountRepository extends JpaRepository<PaymentAccount, Long> {

    List<PaymentAccount> findAllByOrderByCategorySortOrderAscCategoryDisplayNameAscSortOrderAscIdAsc();

    long countByCategoryId(Long categoryId);
}
