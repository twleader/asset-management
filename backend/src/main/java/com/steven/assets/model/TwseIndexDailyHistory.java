package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 台股大盤（TAIEX）每日收盤點位。
 * Requirement 18：「GDP + 台股大盤」頁面之大盤日線（近 10 年 + MA20/60/240）。
 */
@Entity
@Table(name = "twse_index_daily_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwseIndexDailyHistory {

    @Id
    @Column(name = "trading_date")
    private LocalDate tradingDate;

    @Column(name = "close_point", nullable = false, precision = 12, scale = 2)
    private BigDecimal closePoint;
}
