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

    /**
     * 除權日（Task 357 / Requirement 94）。與 {@link #exDividendDate} 各自獨立落地，
     * 不再互相 fallback：只配股事件此欄有值、exDividendDate 為 null；只配息事件則相反。
     * nullable 且無預設值——「這個事件沒有除權」與「還沒回補」都必須能表示成 null。
     */
    @Column(name = "ex_rights_date")
    private LocalDate exRightsDate;

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

    @Column(name = "source", length = 128)
    private String source;

    /** Append-only snapshot event identity; nullable for legacy rows. */
    @Column(name = "event_key", length = 64)
    private String eventKey;

    @Column(name = "event_status", nullable = false, length = 16)
    private String eventStatus;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * anchorDate = {@code min(exDividendDate, exRightsDate)}（Task 357／Requirement 94）——
     * <b>不是 {@code COALESCE}</b>，理由見 {@link DividendDates#anchorDate}。
     *
     * <p>純配股事件的 {@link #exDividendDate} 為 {@code null}；供全庫所有把
     * 「除息日是否存在」當作事件存在性／區間過濾／排序依據的呼叫端共用，
     * 取代直接呼叫 {@code getExDividendDate()}，避免純配股事件被排除或 NPE。</p>
     *
     * <p>算術本體一律委派 {@link DividendDates#anchorDate}，backend 內只有那一份實作。</p>
     */
    public LocalDate anchorDate() {
        return DividendDates.anchorDate(exDividendDate, exRightsDate);
    }
}
