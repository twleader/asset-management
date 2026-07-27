package com.steven.assets.bff.security;

/**
 * 認證 / 多租戶共用常數（Requirement 28）。
 */
public final class AuthConstants {

    private AuthConstants() {}

    /** 傳給 business-services 的身分 header。 */
    public static final String HDR_USER_ID = "X-User-Id";
    public static final String HDR_USER_ROLE = "X-User-Role";
    public static final String HDR_USER_STATUS = "X-User-Status";

    /**
     * 管理者代看：選定目標 user id 存於此 cookie（stateless，不碰 WebSession）。
     * 只有 ADMIN 的請求才會被 {@code TenantWebFilter} 採信；非 ADMIN 即使自設此 cookie 也無效，故無需簽章。
     */
    public static final String COOKIE_IMPERSONATE = "IMPERSONATE_UID";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String AUTHORITY_ADMIN = "ROLE_ADMIN";
    public static final String AUTHORITY_USER = "ROLE_USER";

    /**
     * 登入時把 appUserId / status 編進 authorities，讓每個請求可直接從 principal 取得身分，
     * 不必每次都 round-trip business {@code by-email}（避免延遲與 WebClient 執行緒跳轉導致的回應問題）。
     */
    public static final String AUTHORITY_UID_PREFIX = "APP_UID_";
    public static final String AUTHORITY_STATUS_PREFIX = "APP_STATUS_";

    /**
     * 「主要管理者」（{@code ADMIN_EMAIL} 本人）旗標，登入時由 business 的 {@code protectedAdmin} 編入。
     *
     * <p><b>與 {@link #AUTHORITY_ADMIN} 語意不同，不可混用</b>：{@code role == ADMIN} 可以有多列，
     * 而主要管理者全庫唯一一人。Google Drive 同步的啟用權限用的是<b>這一個</b>——rclone remote
     * 全機只有一份且綁定特定帳號，若以 role 判定，第二位 ADMIN 的財務報表就會被上傳到該帳號。
     * 前端僅用它決定「顯不顯示」，真正的閘門在 business 端的 403（Requirement 51 / Task 242）。
     */
    public static final String AUTHORITY_CONFIGURED_ADMIN = "APP_CONFIGURED_ADMIN";

    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Reactor context key：本請求解析出的身分。 */
    public static final String CTX_IDENTITY = "tenantIdentity";
}
