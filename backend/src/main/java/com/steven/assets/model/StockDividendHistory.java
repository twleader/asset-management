package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_dividend_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockDividendHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "market", nullable = false, length = 20)
    private String market;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "cash_dividend")
    private BigDecimal cashDividend;

    @Column(name = "stock_dividend")
    private BigDecimal stockDividend;

    @Column(name = "ex_dividend_date")
    private LocalDate exDividendDate;

    @Column(name = "yield_pct")
    private BigDecimal yieldPct;

    @Column(name = "cash_payment_date")
    private LocalDate cashPaymentDate;

    @Column(name = "stock_payment_date")
    private LocalDate stockPaymentDate;

    @Column(name = "fill_days")
    private Integer fillDays;

    @Column(name = "previous_close")
    private BigDecimal previousClose;

    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
