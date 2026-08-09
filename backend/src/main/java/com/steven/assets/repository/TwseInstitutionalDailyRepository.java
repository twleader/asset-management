package com.steven.assets.repository;

import com.steven.assets.model.TwseInstitutionalDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only decision-time query for append-only TWSE institutional observations. */
public interface TwseInstitutionalDailyRepository
        extends JpaRepository<TwseInstitutionalDaily, Long> {

    @Query("""
            SELECT observation
              FROM TwseInstitutionalDaily observation
             WHERE observation.tradingDate BETWEEN :from AND :to
               AND observation.observedAt <= :decisionInstant
             ORDER BY observation.tradingDate ASC, observation.observedAt ASC
            """)
    List<TwseInstitutionalDaily> findVisibleRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("decisionInstant") Instant decisionInstant);
}
