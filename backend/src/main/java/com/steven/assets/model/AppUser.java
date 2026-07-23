package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 使用者主檔（Requirement 28：Gmail OAuth2 登入與多租戶資料隔離）。
 *
 * <p>身分由 Google OAuth2 的 email 唯一識別。role / status 以字串存（禁止 enum 寫死於 schema）：
 * <ul>
 *   <li>role：{@code ADMIN}（管理者；由環境變數指定的主要管理者可代看全部使用者）/ {@code USER}</li>
 *   <li>status：{@code PENDING}（待管理者核准）/ {@code ACTIVE}（可使用）/ {@code DISABLED}（已停用）</li>
 * </ul>
 * 受隔離的資產類資料以本表 id 作為 {@code owner_user_id} 關聯。
 */
@Entity
@Table(name = "app_user")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(length = 255)
    private String name;

    @Column(length = 512)
    private String picture;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String role = ROLE_USER;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    @Transient
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
