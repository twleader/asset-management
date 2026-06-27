package com.steven.assets.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 從 BFF 傳來的身分 header 填入 {@link CurrentUserContext}（Requirement 28：多租戶）。
 *
 * <p>header 約定：
 * <ul>
 *   <li>{@code X-User-Id}：effectiveUserId（管理者代看時為選定目標）</li>
 *   <li>{@code X-User-Role}：ADMIN / USER</li>
 *   <li>{@code X-User-Status}：ACTIVE / PENDING / DISABLED</li>
 * </ul>
 * business-services 不對外，header 信任建立於「compose 內網、BFF 為唯一入口」。無 header（如背景 cron 不經此 filter，
 * 或內部健康檢查）時 context 維持空值，owner 過濾不啟用。
 */
@Component
public class CurrentUserFilter extends OncePerRequestFilter {

    public static final String HDR_USER_ID = "X-User-Id";
    public static final String HDR_USER_ROLE = "X-User-Role";
    public static final String HDR_USER_STATUS = "X-User-Status";

    private final ObjectProvider<CurrentUserContext> contextProvider;

    public CurrentUserFilter(ObjectProvider<CurrentUserContext> contextProvider) {
        this.contextProvider = contextProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(HDR_USER_ID);
        if (userId != null && !userId.isBlank()) {
            CurrentUserContext ctx = contextProvider.getObject();
            try {
                ctx.setEffectiveUserId(Long.valueOf(userId.trim()));
            } catch (NumberFormatException ignored) {
                // 非數字 id 視為無身分
            }
            ctx.setRole(request.getHeader(HDR_USER_ROLE));
            ctx.setStatus(request.getHeader(HDR_USER_STATUS));
        }
        chain.doFilter(request, response);
    }
}
