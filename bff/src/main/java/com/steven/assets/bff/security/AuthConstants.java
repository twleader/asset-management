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

    /** 管理者代看：選定目標 user id 存於 WebSession 此屬性。 */
    public static final String SESSION_IMPERSONATE = "IMPERSONATE_USER_ID";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String AUTHORITY_ADMIN = "ROLE_ADMIN";
    public static final String AUTHORITY_USER = "ROLE_USER";

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String ADMIN_EMAIL = "tw.leader@gmail.com";

    /** Reactor context key：本請求解析出的身分。 */
    public static final String CTX_IDENTITY = "tenantIdentity";
}
