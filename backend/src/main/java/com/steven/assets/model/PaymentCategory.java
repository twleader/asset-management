package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代繳分類（Requirement 22）
 * 由資料庫管理，不寫死 Enum；如 繳費 / 繳稅 / 服務。
 */
@Entity
@Table(name = "payment_category")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼（英文，唯一，如 bill / tax / service） */
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    /** 顯示名稱（如 繳費 / 繳稅 / 服務） */
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    /** 顯示排序 */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** 是否啟用（停用後不出現於新增 dialog 下拉選單，但舊紀錄仍顯示分類） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
