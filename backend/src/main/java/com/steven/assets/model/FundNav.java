package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 基金淨值快取 (Requirement 19)。
 *
 * external-materials-service 每日 cron 從 FundClear 抓取所有啟用基金的最新 NAV 寫入此表，
 * business-services 在算 FundHolding currentValue 時讀取最新一筆。
 */
@Entity
@Table(name = "fund_nav", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fund_nav_code_date", columnNames = {"fund_code", "nav_date"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundNav {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_code", nullable = false, length = 20)
    private String fundCode;

    @Column(name = "nav_date", nullable = false)
    private LocalDate navDate;

    /** 原幣計價的淨值，scale 6 */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal nav;

    /** 抓取來源：FUNDCLEAR / MONEYDJ */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
