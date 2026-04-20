package com.steven.assets.repository;

import com.steven.assets.model.TransitFundType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransitFundTypeRepository extends JpaRepository<TransitFundType, Long> {

    Optional<TransitFundType> findByCode(String code);

    List<TransitFundType> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    List<TransitFundType> findAllByOrderBySortOrderAscDisplayNameAsc();
}
