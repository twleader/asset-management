package com.steven.assets.repository;

import com.steven.assets.model.BondTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BondTermRepository extends JpaRepository<BondTerm, Long> {

    Optional<BondTerm> findByCode(String code);

    List<BondTerm> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    List<BondTerm> findAllByOrderBySortOrderAscDisplayNameAsc();
}
