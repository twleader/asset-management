package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ETF 每日淨值與折溢價歷史（Requirement 34／Task 215 建表，Task 264 新增 business 端讀取路徑）。
 *
 * <p><b>全域公開行情，無 {@code owner_user_id}、不套 Hibernate {@code @Filter}</b>，
 * 比照 {@code stock_price_history}／{@code commodity_price_history}，所有使用者共用同一份。</p>
 *
 * <p>{@code premiumDiscountPct} 為來源發布的權威值，<b>不得由市價與淨值反推</b>（Task 259）：
 * 台股該值由證交所發布，且其淨值欄在股票型 ETF 已四捨五入至小數 2 位，反推誤差達 0.07 個百分點。
 * 為 {@code null} 就是缺值。</p>
 */
@Entity
@Table(name = "etf_nav_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EtfNavHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    @Column(nullable = false)
    private LocalDate navDate;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal nav;

    @Column(precision = 8, scale = 4)
    private BigDecimal premiumDiscountPct;

    @Column(length = 30)
    private String source;

    @Column(length = 20)
    private String pctOrigin;
}
