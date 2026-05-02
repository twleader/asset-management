package com.steven.assets.repository;

import com.steven.assets.model.FundNav;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface FundNavRepository extends JpaRepository<FundNav, Long> {

    Optional<FundNav> findTopByFundCodeOrderByNavDateDesc(String fundCode);

    Optional<FundNav> findByFundCodeAndNavDate(String fundCode, LocalDate navDate);
}
