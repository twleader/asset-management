package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 股票每日收盤價歷史
 */
@Entity
@Table(name = "stock_price_history", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_code", "market", "trading_date"})
}, indexes = {
    @Index(name = "idx_sph_code_date", columnList = "stock_code, trading_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(nullable = false)
    private LocalDate tradingDate;

    @Column(precision = 15, scale = 4)
    private BigDecimal openPrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal highPrice;

    @Column(precision = 15, scale = 4)
    private BigDecimal lowPrice;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal closePrice;

    @Column
    private Long volume;
}
