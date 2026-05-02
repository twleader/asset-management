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
 * 信託基金配息歷史 (Requirement 20)。
 *
 * external-materials-service 每日 cron 從 FundClear `info-dividend/query` 抓近 13 個月寫入；
 * `FundDividendService` 取近 12 個月 amount 加總算「每單位年配息」原幣，乘以 FX 與 units 得台幣年估值。
 */
@Entity
@Table(name = "fund_dividend_history", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fund_div_code_date", columnNames = {"fund_code", "base_date"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundDividendHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_code", nullable = false, length = 20)
    private String fundCode;

    /** 配息基準日（FundClear `asiBaseDate`） */
    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    /** 每單位原幣配息金額 */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    /** 配息頻率描述（每月 / 每季 / …） */
    @Column(length = 20)
    private String frequency;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
