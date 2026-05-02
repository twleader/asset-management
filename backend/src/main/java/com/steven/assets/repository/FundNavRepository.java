package com.steven.assets.repository;

import com.steven.assets.model.FundNav;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface FundNavRepository extends JpaRepository<FundNav, Long> {

    Optional<FundNav> findTopByFundCodeOrderByNavDateDesc(String fundCode);

    Optional<FundNav> findByFundCodeAndNavDate(String fundCode, LocalDate navDate);

    /** 該基金指定日期或之前最近一筆 NAV（closest-on-or-before）— Requirement 21 基準日估值。 */
    Optional<FundNav> findFirstByFundCodeAndNavDateLessThanEqualOrderByNavDateDesc(
            String fundCode, LocalDate navDate);
}
