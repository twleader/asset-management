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
 *   <li>引導端點（login-upsert / by-email，無 {@code X-User-*}）context 無身分 → 不啟用。</li>
 * </ul>
 *
 * 注意：Hibernate {@code @Filter} 不套用於 {@code EntityManager.find()}（findById），by-id 存取另以
 * {@link TenantGuard} 驗證歸屬。
 */
@Aspect
@Component
public class TenantFilterAspect {

    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantFilterAspect(ObjectProvider<CurrentUserContext> currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Before("execution(* com.steven.assets.repository..*(..))")
    public void enableOwnerFilter() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return; // 背景執行緒：不啟用
        }
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (!ctx.hasUser()) {
            return; // 無身分情境
        }
        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", ctx.getEffectiveUserId());
    }
}
