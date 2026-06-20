package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stock")
@IdClass(StockId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stock {

    @Id
    @Column(nullable = false, length = 20)
    private String code;

    @Id
    @Column(nullable = false, length = 20)
    private String market;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 資產類別 override（Requirement 25）：對應 AssetClass.code（BOND / STOCK …）。
     * 非空時覆蓋 AssetClassifier 規則；null 時依規則自動判定。
     */
    @Column(name = "asset_class", length = 20)
    private String assetClass;

    /**
     * 股票風格 override（Requirement 26）：對應 StockStyle.code（GROWTH / INCOME）。
     * 非空時覆蓋規則；null 時依殖利率規則自動判定。僅 asset_class=STOCK 時有意義。
     */
    @Column(name = "stock_style", length = 20)
    private String stockStyle;

    /**
     * 債券期別 override（Requirement 27）：對應 BondTerm.code（SHORT / MID / LONG）。
     * 非空時覆蓋規則；null 時依名稱年期規則自動判定。僅 asset_class=BOND 時有意義。
     */
    @Column(name = "bond_term", length = 20)
    private String bondTerm;
}
