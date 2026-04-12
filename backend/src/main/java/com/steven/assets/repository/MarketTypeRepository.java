package com.steven.assets.repository;

import com.steven.assets.model.MarketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketTypeRepository extends JpaRepository<MarketType, Long> {

    Optional<MarketType> findByCode(String code);

    List<MarketType> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    List<MarketType> findAllByOrderBySortOrderAscDisplayNameAsc();
}
