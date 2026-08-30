package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色功能管理（Requirement 134／Task 407）：管理者可設定一般使用者角色能看到／使用哪些
 * 既有功能頁面。{@code code} 即為前端路由 path（如 {@code /stocks}），前端依
 * {@code enabledForUser} 過濾側邊選單與路由導覽，不涉及業務 API 本身的權限檢查
 * （見任務檔「明確不做」段落）。
 */
@Entity
@Table(name = "app_feature")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 識別代碼，同時也是前端路由 path（如 /stocks） */
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    /** 顯示名稱 */
    @Column(nullable = false, length = 50)
    private String displayName;

    /** 選單分組（可為 null） */
    @Column(length = 50)
    private String menuGroup;

    /** 顯示排序 */
    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** 一般使用者角色是否可見／可用此功能 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabledForUser = true;
}
