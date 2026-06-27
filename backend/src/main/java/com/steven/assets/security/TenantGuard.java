package com.steven.assets.security;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * by-id 存取的歸屬驗證（Requirement 28：多租戶）。
 *
 * <p>Hibernate {@code @Filter} 不會套用於 {@code EntityManager.find()}（即 {@code JpaRepository.findById} /
 * {@code deleteById}），故由 id 直接載入的單筆 entity 可能屬於他人。受隔離 entity 的 by-id 讀寫一律呼叫
 * {@link #assertOwned(Long)} 把關。
 *
 * <p>無使用者情境（背景 cron）時不檢查，維持既有全體掃描行為。
 */
@Component
public class TenantGuard {

    private final CurrentUserContext currentUser;

    public TenantGuard(CurrentUserContext currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * 驗證 entity 的 owner 與目前 effectiveUserId 相符；不符視為查無此資源（避免洩漏他人資料存在）。
     */
    public void assertOwned(Long ownerUserId) {
        if (!currentUser.hasUser()) {
            return; // 背景情境不設限
        }
        if (ownerUserId == null || !ownerUserId.equals(currentUser.getEffectiveUserId())) {
            throw new TenantAccessException();
        }
    }

    /** 以目前 effectiveUserId 作為新建資料的 owner；無情境時回傳 null（背景不應建立受隔離資料）。 */
    public Long requireCurrentUserId() {
        return currentUser.getEffectiveUserId();
    }

    public boolean hasUser() {
        return currentUser.hasUser();
    }

    /** 便利方法：載入後立即驗證歸屬並回傳。 */
    public <T> T owned(T entity, Supplier<Long> ownerExtractor) {
        if (entity != null) {
            assertOwned(ownerExtractor.get());
        }
        return entity;
    }
}
