package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 台股大盤（TAIEX）每日 OHLC。
 * Requirement 18：「GDP + 台股大盤」頁面之大盤日線（近 10 年 + MA20/60/240）。
 * Requirement 14：觀察清單代號 0000 = 台股大盤，KD / 高低 / 開盤等取自此表。
 * 來源：TWSE openapi MI_5MINS_HIST 月報（含 OpeningIndex/HighestIndex/LowestIndex/ClosingIndex）。
 * 舊資料 OHLC 可能為 null（v1.21.0 只抓 ClosingIndex），下次回補後會補完。
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

    @Column(name = "open_point", precision = 12, scale = 2)
    private BigDecimal openPoint;

    @Column(name = "high_point", precision = 12, scale = 2)
    private BigDecimal highPoint;

    @Column(name = "low_point", precision = 12, scale = 2)
    private BigDecimal lowPoint;

    @Column(name = "close_point", nullable = false, precision = 12, scale = 2)
    private BigDecimal closePoint;
}
