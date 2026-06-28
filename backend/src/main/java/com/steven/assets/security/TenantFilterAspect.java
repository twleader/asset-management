package com.steven.assets.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 多租戶 owner 過濾的啟用點（Requirement 28）。
 *
 * <p>在「每次 repository 方法呼叫前」於目前 Hibernate session 啟用 {@code ownerFilter}。選在 repository 層而非
 * 請求進入點，是因為此時已位於 service 的 {@code @Transactional}（或 OSIV）所綁定的 session 內，
 * 不依賴 interceptor 與 OSIV 的註冊順序，過濾必定套用到實際執行的查詢。
 *
 * <ul>
 *   <li>背景執行緒（cron / Redis 訂閱）沒有 request context → 不啟用，維持掃全體資料的既有行為。</li>
 *   <li>HTTP 請求但無身分（{@code X-User-*} 未帶到，理應只發生於 login-upsert / by-email 這類引導端點，
 *       它們查的是非隔離的 app_user）→ <b>fail-closed</b>：以不可能的 ownerId 啟用 filter，讓受隔離 entity 回空，
 *       避免「BFF 漏帶 header」退化成洩漏他人資料。非隔離 entity 不受 {@code @Filter} 影響、照常運作。</li>
 * </ul>
 *
 * 注意：Hibernate {@code @Filter} 不套用於 {@code EntityManager.find()}（findById），by-id 存取另以
 * {@link TenantGuard} 驗證歸屬。
 */
@Aspect
@Component
public class TenantFilterAspect {

    /** fail-closed 用的不可能 ownerId（IDENTITY 由 1 起算，永不為負）。 */
    private static final long FAIL_CLOSED_OWNER_ID = -1L;

    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantFilterAspect(ObjectProvider<CurrentUserContext> currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Before("execution(* com.steven.assets.repository..*(..))")
    public void enableOwnerFilter() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return; // 背景執行緒：不啟用，維持掃全體
        }
        CurrentUserContext ctx = currentUserProvider.getObject();
        // 有 HTTP 請求但無身分 → fail-closed（受隔離 entity 回空），絕不退化成回全體
        long ownerId = ctx.hasUser() ? ctx.getEffectiveUserId() : FAIL_CLOSED_OWNER_ID;
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ownerId);
    }
}
