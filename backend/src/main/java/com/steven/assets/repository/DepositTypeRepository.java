package com.steven.assets.repository;

import com.steven.assets.model.DepositTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepositTypeRepository extends JpaRepository<DepositTypeEntity, Long> {

    Optional<DepositTypeEntity> findByCode(String code);

    List<DepositTypeEntity> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    List<DepositTypeEntity> findAllByOrderBySortOrderAscDisplayNameAsc();
}
