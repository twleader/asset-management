package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 券商設定
 * 取代原本的 Broker enum，改由資料庫管理，支援動態新增/停用。
 * 類別名稱使用 BrokerEntity 以避免與已廢棄的 Broker enum 衝突（enum 刪除後可重命名）。
 */
@Entity
@Table(name = "broker")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrokerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼（英文，唯一，如 fubon） */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** 顯示名稱（如 富邦證券） */
    @Column(nullable = false, length = 50)
    private String displayName;

    /** Excel 匯入比對關鍵字，逗號分隔（如 富邦,Fubon） */
    @Column(length = 200)
    private String keywords;

    /** 是否啟用（false = 軟刪除） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
