package com.steven.assets.security;

/**
 * 需要管理者權限的操作被非管理者呼叫（Requirement 28）。對外回 403。
 */
public class AdminRequiredException extends RuntimeException {
    public AdminRequiredException() {
        super("此操作僅限管理者");
    }

    /**
     * 指定訊息的版本（Requirement 51 / Task 243）。
     *
     * <p>Google Drive 同步的判準是「主要管理者」（{@code ADMIN_EMAIL} 本人）而非 {@code role == ADMIN}，
     * 沿用預設訊息「此操作僅限管理者」會讓 role 為 ADMIN 的第二位使用者看到自相矛盾的錯誤。
     * 訊息會經 {@code GlobalExceptionHandler} 放進 {@code ProblemDetail.detail}，前端直接顯示。
     */
    public AdminRequiredException(String message) {
        super(message);
    }
}
