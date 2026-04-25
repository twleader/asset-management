package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票即時/收盤價格
 * 盤中每 10 分鐘更新，收盤後存收盤價直到下次開盤
 */
@Entity
@Table(name = "stock_price", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stockCode", "market"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 股票代號 */
    @Column(nullable = false, length = 20)
    private String stockCode;

    /** 市場 */
    @Column(nullable = false, length = 20)
    private String market;

    /** 最新價格 (原幣) */
    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal price;

    /** 漲跌金額 = price - previousClose（即時計算） */
    @Transient
    public BigDecimal getPriceChange() {
        if (price == null || previousClose == null) return null;
        return price.subtract(previousClose).setScale(4, RoundingMode.HALF_UP);
    }

    /** 漲跌幅 (%) = priceChange / previousClose × 100（即時計算） */
    @Transient
    public BigDecimal getChangePercent() {
        if (previousClose == null || previousClose.signum() == 0 || price == null) return null;
        return price.subtract(previousClose)
                .divide(previousClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** 五檔買進最佳價 */
    @Column(precision = 15, scale = 4)
    private BigDecimal buyPrice;

    /** 五檔賣出最佳價 */
    @Column(precision = 15, scale = 4)
    private BigDecimal sellPrice;

    /** 開盤價 */
    @Column(precision = 15, scale = 4)
    private BigDecimal openPrice;

    /** 昨收 */
    @Column(precision = 15, scale = 4)
    private BigDecimal previousClose;

    /** 當日最高 */
    @Column(precision = 15, scale = 4)
    private BigDecimal highPrice;

    /** 當日最低 */
    @Column(precision = 15, scale = 4)
    private BigDecimal lowPrice;

    /** 成交量（台股單位為張，美股為股） */
    @Column
    private Long volume;

    /** 交易日 */
    @Column(nullable = false)
    private LocalDate tradingDate;

    /** 最後更新時間 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 是否為收盤價 (true=收盤價, false=盤中即時價) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean closed = false;

    /** 資料來源 */
    @Column(length = 50)
    private String source;
}
