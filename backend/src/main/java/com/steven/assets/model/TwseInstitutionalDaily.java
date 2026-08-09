package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * TWSE BFI82U 三大法人買賣超原始數值的 append-only observation。
 *
 * <p>本表只接受 {@code TwseInfoFetchClient} 的 typed numeric 結果；不得由
 * {@code news_headline} 的 title／summary 反向解析。失敗抓取也以
 * {@code status=UNAVAILABLE} 留下稽核列，但 resolver 只會選 AVAILABLE 完整列。</p>
 */
@Entity
@Table(name = "twse_institutional_daily")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TwseInstitutionalDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate tradingDate;

    @Column(precision = 20, scale = 2)
    private BigDecimal foreignNet;

    @Column(precision = 20, scale = 2)
    private BigDecimal trustNet;

    @Column(precision = 20, scale = 2)
    private BigDecimal dealerNet;

    @Column(precision = 20, scale = 2)
    private BigDecimal totalNet;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(columnDefinition = "text")
    private String sourceUrl;

    @Column(nullable = false)
    private Instant observedAt;

    private Instant sourceAvailableAt;

    @Column(nullable = false, length = 64)
    private String availabilityBasis;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "text")
    private String errorReason;
}
