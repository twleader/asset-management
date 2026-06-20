package com.steven.assets.repository;

import com.steven.assets.model.AssetClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssetClassRepository extends JpaRepository<AssetClass, Long> {

    Optional<AssetClass> findByCode(String code);

    List<AssetClass> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    List<AssetClass> findAllByOrderBySortOrderAscDisplayNameAsc();
}
