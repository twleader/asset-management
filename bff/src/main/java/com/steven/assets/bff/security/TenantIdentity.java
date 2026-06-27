package com.steven.assets.bff.security;

/**
 * 單一請求解析出的身分（Requirement 28）。
 *
 * @param effectiveUserId 要過濾/歸屬的使用者 id（一般使用者＝自己；管理者代看＝選定目標）
 * @param role            登入者本人的角色（ADMIN/USER）—— 給 business 端 ADMIN 守門用，代看時不變
 * @param status          登入者本人的狀態（ACTIVE/PENDING/DISABLED）
 */
public record TenantIdentity(Long effectiveUserId, String role, String status) {
    public boolean isActive() {
        return AuthConstants.STATUS_ACTIVE.equals(status);
    }
}
