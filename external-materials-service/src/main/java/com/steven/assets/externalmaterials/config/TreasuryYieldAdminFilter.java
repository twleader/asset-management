package com.steven.assets.externalmaterials.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Treasury internal API 的獨立 ADMIN 縱深守門。
 *
 * <p>external-materials-service 目前沒有全域 security filter；Task 275 不允許因此只依賴 Docker network。
 * business proxy 必須以部署環境注入的 shared service credential 呼叫；角色 header 只作補充授權，
 * 絕不是可由 caller 自行宣稱的 authentication。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TreasuryYieldAdminFilter extends OncePerRequestFilter {

    static final String PATH = "/internal/macro/treasury-yield";
    static final String ROLE_HEADER = "X-User-Role";
    static final String TOKEN_HEADER = "X-Internal-Service-Token";

    private final byte[] expectedTokenDigest;

    public TreasuryYieldAdminFilter(
            @Value("${asset.internal.treasury-token:}") String configuredToken) {
        this.expectedTokenDigest = digest(configuredToken);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.equals(PATH) || path.startsWith(PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedTokenDigest == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Treasury service credential is not configured");
            return;
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Treasury API requires service authentication");
            return;
        }
        byte[] actualDigest = digest(token);
        if (actualDigest == null || !MessageDigest.isEqual(expectedTokenDigest, actualDigest)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Treasury service credential is invalid");
            return;
        }
        String role = request.getHeader(ROLE_HEADER);
        if (role == null || role.isBlank()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Treasury API requires ADMIN authorization");
            return;
        }
        if (!"ADMIN".equals(role)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Treasury API requires ADMIN");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Hash first so MessageDigest.isEqual always compares fixed-length arrays. */
    private static byte[] digest(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
