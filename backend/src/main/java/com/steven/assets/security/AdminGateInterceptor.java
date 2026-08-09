package com.steven.assets.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 後端第二層 ADMIN 守門（Requirement 28、Requirement 29）。
 *
 * <p>BFF 已在對外層擋過管理端點，這是 business-services 端的縱深防禦，對下列路徑要求 {@code X-User-Role = ADMIN}：
 * <ul>
 *   <li>{@code /api/backups/**}、{@code /internal/users/**}（備份/還原、使用者管理）：所有方法皆限 ADMIN。</li>
 *   <li>{@code /internal/macro/treasury-yield/**}：查詢、單 tenor context 與 refresh 全部限 ADMIN。</li>
 *   <li>{@code /api/settings/**} 與 {@code /api/funds(/**)}（全域共用參考資料：銀行/券商/存款類型/市場別/資產分類/
 *       信託基金主檔…）：<b>寫入（POST/PUT/PATCH/DELETE）限 ADMIN；讀取（GET/HEAD/OPTIONS）開放給任何已登入者</b>，
 *       因下拉選單等需讀取共用設定，但一般使用者不得竄改影響全體租戶的參考資料（Requirement 29）。
 *       （{@code /api/fund-nav}、{@code /api/fund-dividend} 屬操作型 refresh/backfill，比照行情 refresh 開放、不在此。）</li>
 * </ul>
 * 登入相關端點（{@code /internal/users/login-upsert}、{@code /internal/users/by-email}）例外放行——
 * 任何已登入者（含非管理者）登入與 {@code /api/me} 取狀態都會用到，當下不應要求 ADMIN。
 *
 * <p>注意：per-user 資料（{@code /api/payment-accounts}、{@code /api/notification-recipients}）base path
 * 不在 {@code /api/settings} 之下，故不受本守門影響，一般使用者仍可管理自己的資料。
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
        // 全域共用參考資料（設定 + 基金主檔）：讀取開放，寫入才限 ADMIN
        if (path != null && isGlobalReferenceDataPath(path) && isReadOnlyMethod(request.getMethod())) {
            return true;
        }
        if (!currentUser.hasUser()) {
            throw new UnauthenticatedException();
        }
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return true;
    }

    /** 安全（唯讀）方法：不改變狀態，開放給已登入者。 */
    private boolean isReadOnlyMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    /** 全域共用參考資料路徑（讀開放、寫限 ADMIN）：設定主檔與信託基金主檔。 */
    private boolean isGlobalReferenceDataPath(String path) {
        // "/api/funds" 與 "/api/funds/..." 皆納入；不會誤含 "/api/fund-nav"、"/api/fund-dividend"（無 's'）
        return path.startsWith("/api/settings/") || path.startsWith("/api/funds");
    }
}
