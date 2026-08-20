package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 除 TWSE 外的 code-keyed 指數每日 OHLC（TPEX + 美股四大 + 英德韓日）。表名沿用
 * us_index_daily_history，相容名稱不變；TPEX 為官方逐月來源例外。
 * Requirement 18：「GDP + 台股集中／櫃買／海外市場」頁面之日線圖。
 * index_code ∈ {DJI 道瓊, SPX 標普500, IXIC 那斯達克綜合, SOX 費城半導體,
 *               FTSE 英國富時100, DAX 德國DAX, KOSPI 韓國KOSPI, N225 日經225, TPEX 台股櫃買市場}。
 * 來源：TPEX 走官方逐月資料；其餘既有代碼走 Yahoo Finance v8 chart。
 * 與 {@link TwseIndexDailyHistory} 分表：TWSE 是單一指數（無 code 欄）且已與觀察清單 0000 耦合。
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

    /** 成交量（股）。TPEX 走官方成交張數／仟股換算；其餘既有代碼取 Yahoo volume。⚠️ 各市場口徑不一致，不得跨指數比較。 */
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
