package com.steven.assets.bff.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * business-services {@code /internal/users} 回應的精簡投影（Requirement 28）。
 */
public record BffUser(
        Long id,
        String email,
        String name,
        String picture,
        String role,
        String status
) {
    public boolean isAdmin() {
        return AuthConstants.ROLE_ADMIN.equals(role);
    }

    public boolean isActive() {
        return AuthConstants.STATUS_ACTIVE.equals(status);
    }

    /**
     * 從登入 principal 的 authorities 還原身分（id / role / status 於登入時編入），
     * 不需每請求 round-trip business {@code by-email}。
     */
    public static BffUser fromPrincipal(OidcUser oidc) {
        if (oidc == null) {
            return null;
        }
        Long id = null;
        String role = AuthConstants.ROLE_USER;
        String status = null;
        for (GrantedAuthority a : oidc.getAuthorities()) {
            String s = a.getAuthority();
            if (s == null) continue;
            if (s.equals(AuthConstants.AUTHORITY_ADMIN)) {
                role = AuthConstants.ROLE_ADMIN;
            } else if (s.startsWith(AuthConstants.AUTHORITY_UID_PREFIX)) {
                try {
                    id = Long.valueOf(s.substring(AuthConstants.AUTHORITY_UID_PREFIX.length()));
                } catch (NumberFormatException ignored) {
                    // 忽略壞值
                }
            } else if (s.startsWith(AuthConstants.AUTHORITY_STATUS_PREFIX)) {
                status = s.substring(AuthConstants.AUTHORITY_STATUS_PREFIX.length());
            }
        }
        return new BffUser(id, oidc.getEmail(), oidc.getFullName(), oidc.getPicture(), role, status);
    }
}
