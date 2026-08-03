package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 海外指數每日 OHLC（美股四大 + 英德韓日）。表名沿用 us_index_daily_history，語意已一般化為「海外指數日線」。
 * Requirement 18：「GDP + 台股大盤」頁面之日線圖支援「台股 / 海外指數」切換。
 * index_code ∈ {DJI 道瓊, SPX 標普500, IXIC 那斯達克綜合, SOX 費城半導體,
 *               FTSE 英國富時100, DAX 德國DAX, KOSPI 韓國KOSPI, N225 日經225}。
 * 來源：Yahoo Finance v8 chart API（^DJI / ^GSPC / ^IXIC / ^SOX / ^FTSE / ^GDAXI / ^KS11 / ^N225，range=10y&interval=1d）。
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

    /** 成交量（股）。取自 Yahoo v8 chart 的 indicators.quote[0].volume。⚠️ 各市場口徑不一致，不得跨指數比較：
     *  實測 SOX 恆為 0（純計算型指數無成交量）、KOSPI 量級明顯偏小（非股數原值）。 */
    @Column(name = "volume")
    private Long volume;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private String indexCode;
        private LocalDate tradingDate;
    }
}
