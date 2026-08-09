package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable ETF NAV/premium observation used by decision-time radar evidence.
 *
 * <p>{@link EtfNavHistory} remains the compatible daily-current view.  Historical
 * signals must use this append-only stream because a daily current row contains
 * no proof of when an intraday or post-close value became known.</p>
 */
@Entity
@Table(name = "etf_nav_observation",
        uniqueConstraints = @UniqueConstraint(name = "uq_etf_nav_observation_identity",
                columnNames = {"stock_code", "market", "nav_date", "observed_at", "source"}),
        indexes = {
                @Index(name = "idx_etf_nav_observation_decision",
                        columnList = "stock_code,market,available_at,nav_date"),
                @Index(name = "idx_etf_nav_observation_nav_date",
                        columnList = "stock_code,market,nav_date,available_at")
        })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtfNavObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(name = "nav_date", nullable = false)
    private LocalDate navDate;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal nav;

    @Column(name = "premium_discount_pct", precision = 8, scale = 4)
    private BigDecimal premiumDiscountPct;

    @Column(name = "pct_origin", length = 20)
    private String pctOrigin;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /** Effective known-at boundary, conservatively max(observed-at, source available-at). */
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "availability_basis", nullable = false, length = 48)
    private String availabilityBasis;
}
