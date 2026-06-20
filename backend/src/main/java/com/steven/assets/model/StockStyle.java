package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 股票風格設定（Requirement 26）
 * 「成長型／收益型」的單一事實來源，是套在 asset_class=STOCK 之上的正交子維度。
 * code 即為儲存在 stock.stock_style 的值（GROWTH / INCOME）。
 */
@Entity
@Table(name = "stock_style")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockStyle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼（GROWTH / INCOME），同時是存入 stock.stock_style 的值 */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /** 顯示名稱（成長型 / 收益型） */
    @Column(nullable = false, length = 50)
    private String displayName;

    /** 顯示排序 */
    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** 是否啟用 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * 殖利率門檻（小數，如 0.0400 = 4%）：僅「收益型(INCOME)」列有值。
     * dividendRate >= 此值 → 收益型。可由設定頁調整，不寫死。
     */
    @Column(name = "dividend_threshold", precision = 6, scale = 4)
    private BigDecimal dividendThreshold;
}
