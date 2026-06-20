package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 債券期別設定（Requirement 27）
 * 「短期／中期／長期」的單一事實來源，是套在 asset_class=BOND 之上的子維度。
 * code 即為儲存在 stock.bond_term 的值（SHORT / MID / LONG）。
 */
@Entity
@Table(name = "bond_term")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BondTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼（SHORT / MID / LONG），同時是存入 stock.bond_term 的值 */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    /** 顯示名稱（短期 / 中期 / 長期） */
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
