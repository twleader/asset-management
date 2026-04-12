package com.steven.assets.repository;

import com.steven.assets.model.RealizedGain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface RealizedGainRepository extends JpaRepository<RealizedGain, Long> {

    List<RealizedGain> findByYearOrderByTradeDateAsc(Integer year);

    List<RealizedGain> findAllByOrderByTradeDateDesc();

    @Query("SELECT DISTINCT r.year FROM RealizedGain r ORDER BY r.year DESC")
    List<Integer> findDistinctYears();

    @Query("SELECT SUM(r.profit) FROM RealizedGain r WHERE r.year = :year")
    BigDecimal sumProfitByYear(@Param("year") Integer year);

    @Query("SELECT SUM(r.proceeds) FROM RealizedGain r WHERE r.year = :year")
    BigDecimal sumProceedsByYear(@Param("year") Integer year);
}
