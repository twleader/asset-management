package com.steven.assets.security;

/**
 * 嘗試存取非自己擁有的資源（Requirement 28：多租戶）。對外回 404，避免洩漏他人資料是否存在。
 */
public class TenantAccessException extends RuntimeException {
    public TenantAccessException() {
        super("查無此資源");
    }
}
