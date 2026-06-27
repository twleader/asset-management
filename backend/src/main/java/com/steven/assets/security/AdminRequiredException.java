package com.steven.assets.security;

/**
 * 需要管理者權限的操作被非管理者呼叫（Requirement 28）。對外回 403。
 */
public class AdminRequiredException extends RuntimeException {
    public AdminRequiredException() {
        super("此操作僅限管理者");
    }
}
