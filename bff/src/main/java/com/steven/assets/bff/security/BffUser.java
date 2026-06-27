package com.steven.assets.bff.security;

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
}
