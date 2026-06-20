package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 資產類別設定（Requirement 25）
 * 「現金／債券／股票」三分類的單一事實來源，取代寫死 enum。
 * code 即為儲存在 stock.asset_class 與分類運算使用的值（CASH / BOND / STOCK）。
 */
@Entity
@Table(name = "asset_class")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼（CASH / BOND / STOCK），同時是存入 stock.asset_class 的值 */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /** 顯示名稱（現金 / 債券 / 股票） */
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
}
