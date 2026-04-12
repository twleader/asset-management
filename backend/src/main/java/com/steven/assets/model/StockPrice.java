package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
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

    /** 股票名稱 */
    @Column(length = 50)
    private String stockName;

    /** 市場 */
    @Column(nullable = false, length = 20)
    private String market;

    /** 最新價格 (原幣) */
    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal price;

    /** 漲跌金額 */
    @Column(precision = 15, scale = 4)
    private BigDecimal priceChange;

    /** 漲跌幅 (%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal changePercent;

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
