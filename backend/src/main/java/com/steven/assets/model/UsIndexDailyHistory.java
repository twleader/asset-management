package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 美股四大指數每日 OHLC。
 * Requirement 18：「GDP + 台股大盤」頁面之日線圖支援「台股 / 美股四大指數」切換。
 * index_code ∈ {DJI 道瓊, SPX 標普500, IXIC 那斯達克綜合, SOX 費城半導體}。
 * 來源：Yahoo Finance v8 chart API（^DJI / ^GSPC / ^IXIC / ^SOX，range=10y&interval=1d）。
 * 與 {@link TwseIndexDailyHistory} 分表：台股大盤為單一指數（無 code 欄）且已與觀察清單 0000 耦合。
 */
@Entity
@Table(name = "us_index_daily_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@IdClass(UsIndexDailyHistory.PK.class)
public class UsIndexDailyHistory {

    @Id
    @Column(name = "index_code", length = 16)
    private String indexCode;

    @Id
    @Column(name = "trading_date")
    private LocalDate tradingDate;

    @Column(name = "open_point", precision = 14, scale = 4)
    private BigDecimal openPoint;

    @Column(name = "high_point", precision = 14, scale = 4)
    private BigDecimal highPoint;

    @Column(name = "low_point", precision = 14, scale = 4)
    private BigDecimal lowPoint;

    @Column(name = "close_point", nullable = false, precision = 14, scale = 4)
    private BigDecimal closePoint;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private String indexCode;
        private LocalDate tradingDate;
    }
}
