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
 * 來源：TWSE MI_5MINS_HIST 月報（`https://www.twse.com.tw/rwd/zh/TAIEX/MI_5MINS_HIST`，非 openapi，
 * 欄位為中文「開盤指數／最高指數／最低指數／收盤指數」）。
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

    /**
     * 同日「發行量加權股價報酬指數」（含息）收盤，供績效比較頁含息 vs 純價格報酬率比較（Requirement 33）。
     * nullable：舊列與尚未回補的日期為 null，由 /api/twse-daily-index/refresh-tr 回補、每日排程增量更新。
     */
    @Column(name = "close_point_tr", precision = 12, scale = 2)
    private BigDecimal closePointTr;

    /** 成交股數（股）。來源 TWSE FMTQIK 月報「成交股數」欄；供 Requirement 18 成交量柱狀子圖。 */
    @Column(name = "trade_volume")
    private Long tradeVolume;

    /** 成交金額（元）。來源 TWSE FMTQIK 月報「成交金額」欄；台股柱狀圖的柱值即由此換算億元。 */
    @Column(name = "trade_value", precision = 20, scale = 0)
    private BigDecimal tradeValue;
}
