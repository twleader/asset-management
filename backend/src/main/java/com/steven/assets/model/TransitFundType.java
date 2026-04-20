package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 在途款項類型設定
 * code 即為儲存在 bank_deposit.deposit_type 的值。
 * payable=true 表示「待付」（金額為負），false 表示「待收」（金額為正）。
 */
@Entity
@Table(name = "transit_fund_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransitFundType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 50)
    private String displayName;

    /** true=待付（負值），false=待收（正值） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean payable = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
