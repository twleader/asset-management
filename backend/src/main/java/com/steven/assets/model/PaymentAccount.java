package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

/**
 * 代繳記錄（Requirement 22）
 * 個人定期扣繳項目對照表：分類 + 項目 + 扣款帳戶（純字串）+ 備註。
 * 不與 bank / broker entity 關聯，不影響任何資產計算。
 *
 * <p>Requirement 28（多租戶）：以 {@code ownerUserId} 隔離。
 */
@Entity
@Table(name = "payment_account")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private PaymentCategory category;

    /** 項目名稱（如 市話 + MOD、台電電費） */
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    /** 扣款帳戶（自由輸入，可填銀行帳戶或信用卡名稱） */
    @Column(name = "payment_account", length = 100)
    private String paymentAccount;

    /** 備註（用戶號碼、電號、水號等） */
    @Column(length = 255)
    private String note;

    /** 顯示排序 */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
