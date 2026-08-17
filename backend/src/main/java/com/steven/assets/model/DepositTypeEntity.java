package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 存款類型設定
 * 取代原本的 DepositType enum，改由資料庫管理，支援動態新增/停用。
 * code 即為儲存在 bank_deposit.deposit_type 欄位中的值（如 活存、定存）。
 */
@Entity
@Table(name = "deposit_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepositTypeEntity {

    /**
     * {@code withdrawal_order} 的預設值，與 Liquibase 欄位 DEFAULT 一致（中位數，介於活存與定存之間）。
     * 使用者新增自己的存款類型時若未指定，即落在此值。
     */
    public static final int DEFAULT_WITHDRAWAL_ORDER = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼，同時也是存入 bank_deposit.deposit_type 的值（如 活存） */
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    /** 顯示名稱（如 台幣活存） */
    @Column(nullable = false, length = 50)
    private String displayName;

    /** 顯示排序 */
    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** 提領優先序：越小越優先被提領（資產配置建議的存款減碼 waterfall 用）。 */
    @Column(name = "withdrawal_order", nullable = false)
    @Builder.Default
    private Integer withdrawalOrder = DEFAULT_WITHDRAWAL_ORDER;

    /** 是否啟用 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
