package com.steven.assets.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 後端第二層 ADMIN 守門（Requirement 28）。
 *
 * <p>BFF 已在對外層擋過備份/使用者管理端點，這是 business-services 端的縱深防禦：
 * 對 {@code /api/backups/**} 與 {@code /internal/users/**}（管理操作）要求 {@code X-User-Role = ADMIN}。
 * 登入相關端點（{@code /internal/users/login-upsert}、{@code /internal/users/by-email}）例外放行——
 * 任何已登入者（含非管理者）登入與 {@code /api/me} 取狀態都會用到，當下不應要求 ADMIN。
 */
@Component
public class AdminGateInterceptor implements HandlerInterceptor {

    private final CurrentUserContext currentUser;

    public AdminGateInterceptor(CurrentUserContext currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (path != null && (path.startsWith("/internal/users/login-upsert")
                || path.startsWith("/internal/users/by-email"))) {
            return true;
        }
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return true;
    }
}
