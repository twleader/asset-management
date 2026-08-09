package com.steven.assets.repository;

import com.steven.assets.model.EtfNavObservation;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Read-only decision queries over the append-only ETF NAV observation stream. */
public interface EtfNavObservationRepository extends JpaRepository<EtfNavObservation, Long> {

    @Query("""
            select e from EtfNavObservation e
            where e.stockCode = :code and e.market = :market
              and e.navDate = :navDate and e.premiumDiscountPct is not null
              and e.observedAt <= :decisionInstant and e.availableAt <= :decisionInstant
            order by e.availableAt desc, e.observedAt desc, e.id desc
            """)
    List<EtfNavObservation> findPremiumObservationAsOf(
            @Param("code") String code,
            @Param("market") String market,
            @Param("navDate") LocalDate navDate,
            @Param("decisionInstant") Instant decisionInstant,
            Pageable pageable);

    default Optional<EtfNavObservation> findLatestPremiumObservationAsOf(
            String code, String market, LocalDate navDate, Instant decisionInstant) {
        return findPremiumObservationAsOf(
                code, market, navDate, decisionInstant, PageRequest.of(0, 1)).stream().findFirst();
    }

    /**
     * Bulk stream through a backtest's maximum signal instant.  The pure resolver
     * still applies each individual signal boundary and de-duplicates revisions.
     */
    @Query("""
            select e from EtfNavObservation e
            where e.stockCode = :code and e.market = :market
              and e.observedAt <= :maximumDecisionInstant
              and e.availableAt <= :maximumDecisionInstant
            order by e.navDate asc, e.availableAt asc, e.observedAt asc, e.id asc
            """)
    List<EtfNavObservation> findObservationStreamThrough(
            @Param("code") String code,
            @Param("market") String market,
            @Param("maximumDecisionInstant") Instant maximumDecisionInstant);
}
