package com.steven.assets.repository;

import com.steven.assets.model.RealizedGain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface RealizedGainRepository extends JpaRepository<RealizedGain, Long> {

    @Query("SELECT r FROM RealizedGain r WHERE r.tradeDate BETWEEN :start AND :end ORDER BY r.tradeDate ASC")
    List<RealizedGain> findByYearRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    default List<RealizedGain> findByYearOrderByTradeDateAsc(Integer year) {
        return findByYearRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    List<RealizedGain> findAllByOrderByTradeDateDesc();

    @Query("SELECT DISTINCT YEAR(r.tradeDate) FROM RealizedGain r ORDER BY YEAR(r.tradeDate) DESC")
    List<Integer> findDistinctYears();

    @Query("SELECT SUM(r.proceeds - r.investmentCost) FROM RealizedGain r WHERE YEAR(r.tradeDate) = :year")
    BigDecimal sumProfitByYear(@Param("year") Integer year);

    @Query("SELECT SUM(r.proceeds) FROM RealizedGain r WHERE YEAR(r.tradeDate) = :year")
    BigDecimal sumProceedsByYear(@Param("year") Integer year);
}
