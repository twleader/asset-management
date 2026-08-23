package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Blogger OAuth 憑證與連接狀態（Requirement 102 / Task 366）。<b>全域單例</b>，不是 per-owner 設定——
 * 部落格 {@code twleader.blogspot.com} 全機只有一份、綁定 {@code shi.chihung@gmail.com} 這一個 Google
 * 帳號，與觸發「連接 Blogger 帳號」動作的本 App 登入身分（主要管理者 {@code tw.leader@gmail.com}）
 * 是兩個獨立帳號，故本表沒有 {@code owner_user_id} 這種業務鍵，<b>不套 {@code @Filter(ownerFilter)}</b>。
 *
 * <p><b>{@code id} 固定為 {@code 1L}、不加 {@code @GeneratedValue}</b>，對應 DB 層
 * {@code CHECK (id = 1)}——「全表恆只有一列」由 DB 約束保證，不是應用層 upsert 的假設
 * （比照既有 {@code BackupSetting} 的既有寫法：{@code repo.findById(1L).orElseGet(() -> builder().id(1L)...)}）。
 * 呼叫端一律用 {@link com.steven.assets.repository.BlogPublishCredentialRepository#findById}，
 * 不得 {@code findAll().stream().findFirst()}。
 */
@Entity
@Table(name = "blog_publish_credential")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlogPublishCredential {

    /** 固定為 1；對應 DB 層 {@code CHECK (id = 1)} 的全域單例保護。 */
    @Id
    private Long id;

    /** Blogger 部落格 id（由 {@code blogs/byurl} 解析而得，非使用者輸入）。 */
    @Column(name = "blog_id", length = 64)
    private String blogId;

    /** 目標部落格網址；固定值，僅供顯示。 */
    @Column(name = "blog_url", nullable = false, length = 512)
    @Builder.Default
    private String blogUrl = "https://twleader.blogspot.com/";

    /** Google 帳號顯示名稱（{@code users/self} 的 {@code displayName}），純供 UI 顯示。 */
    @Column(name = "account_label", length = 255)
    private String accountLabel;

    @Column(name = "access_token", length = 2048)
    private String accessToken;

    @Column(name = "access_token_expires_at")
    private LocalDateTime accessTokenExpiresAt;

    @Column(name = "refresh_token", length = 2048)
    private String refreshToken;

    /**
     * refresh token 已被 Google 判定失效（{@code invalid_grant}）——需要使用者重新走一次
     * 〔連接 Blogger 帳號〕。設為 true 後，{@code BlogOAuthService.ensureAccessToken()} 一律直接
     * 擲例外，不會再嘗試用舊 refresh token 續期。
     */
    @Column(name = "needs_reconnect", nullable = false)
    @Builder.Default
    private boolean needsReconnect = false;

    /** 第一次成功連接的時間；重新連接（re-consent）不覆寫此欄。 */
    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
