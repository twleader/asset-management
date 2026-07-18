package com.steven.assets.security;

/**
 * 請求未帶身分（`CurrentUserContext` 無使用者）卻存取需要身分的資源（Requirement 28）。對外回 401。
 *
 * <p>與 {@link TenantAccessException}（→404，已識別但非本人所有）、{@link AdminRequiredException}
 * （→403，已識別但權限不足）成套且語意互斥。原本各 service 對此情境拋 {@code IllegalStateException}，
 * 因無對應 handler 而落入 500 兜底，故另立此型別。
 */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException() {
        super("未識別使用者");
    }

    public UnauthenticatedException(String message) {
        super(message);
    }
}
