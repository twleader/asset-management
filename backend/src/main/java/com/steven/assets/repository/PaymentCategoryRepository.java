package com.steven.assets.repository;

import com.steven.assets.model.PaymentCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentCategoryRepository extends JpaRepository<PaymentCategory, Long> {

    Optional<PaymentCategory> findByCode(String code);

    List<PaymentCategory> findAllByOrderBySortOrderAscDisplayNameAsc();

    List<PaymentCategory> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();
}
