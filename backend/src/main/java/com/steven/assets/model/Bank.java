package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 銀行設定
 * 取代原本的 BankName enum，改由資料庫管理，支援動態新增/停用。
 */
@Entity
@Table(name = "bank")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼（英文，唯一，如 fubon） */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** 顯示名稱（如 台北富邦銀行） */
    @Column(nullable = false, length = 50)
    private String displayName;

    /** Excel 匯入比對關鍵字，逗號分隔（如 富邦,北富,Fubon） */
    @Column(length = 200)
    private String keywords;

    /** 是否啟用（false = 軟刪除，停用後不出現於下拉選單） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
