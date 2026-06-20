package com.steven.assets.repository;

import com.steven.assets.model.StockStyle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockStyleRepository extends JpaRepository<StockStyle, Long> {

    Optional<StockStyle> findByCode(String code);

    List<StockStyle> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    List<StockStyle> findAllByOrderBySortOrderAscDisplayNameAsc();
}
