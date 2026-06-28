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

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String ADMIN_EMAIL = "tw.leader@gmail.com";

    /** Reactor context key：本請求解析出的身分。 */
    public static final String CTX_IDENTITY = "tenantIdentity";
}
