package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 基金逐檔分類 override（Requirement 25/26/27）。
 *
 * 持有基金以「名稱」為穩定識別（fund_holding.fund_code 多為 NULL），故以 fund_name 為 PK。
 * 三欄皆 nullable，與 {@code stock} 主檔的 asset_class／stock_style／bond_term 三個 override 欄結構平行：
 * 某欄非空 = 該維度人工指定、覆蓋 AssetClassifier 規則；null = 依規則。
 * 任一欄非空即建列、全清則由 service 刪列。
 */
@Entity
@Table(name = "fund_class_override")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundClassOverride {

    @Id
    @Column(name = "fund_name", length = 150)
    private String fundName;

    /** 對應 AssetClass.code（BOND / STOCK），null = 依名稱規則 */
    @Column(name = "asset_class", length = 20)
    private String assetClass;

    /** 對應 StockStyle.code（GROWTH / INCOME），僅有效 asset_class=STOCK 時有意義；null = 依規則（基金無殖利率 → 成長型） */
    @Column(name = "stock_style", length = 20)
    private String stockStyle;

    /** 對應 BondTerm.code（SHORT / MID / LONG），僅有效 asset_class=BOND 時有意義；null = 依名稱年期規則 */
    @Column(name = "bond_term", length = 20)
    private String bondTerm;
}
