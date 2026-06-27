package com.steven.assets.bff.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;

/**
 * 解析目前登入者身分、注入 {@code X-User-*} header、並擋下未核准帳號（Requirement 28）。
 *
 * <p>順序在 Spring Security 之後（{@link Order} 0；security 在 -100），故能讀到 Reactor context 內的認證。
 * 每個請求：
 * <ol>
 *   <li>剝除 client 端任何偽造的 {@code X-User-*}（防偽）。</li>
 *   <li>有登入者 → 以其 email 即時查 business {@code by-email} 取 fresh role/status（核准/停用立即生效）。</li>
 *   <li>解析 effectiveUserId：管理者且 session 有代看目標 → 目標；否則自己。</li>
 *   <li>非 ACTIVE 且存取業務 API → 回 {@code 403 {code:ACCOUNT_PENDING}}。</li>
 *   <li>注入 {@code X-User-Id}=effectiveUserId、{@code X-User-Role}=本人 role、{@code X-User-Status}=本人 status，
 *       並寫入 Reactor context 供 aggregation WebClient 取用。</li>
 * </ol>
 */
@Component
@Order(0)
public class TenantWebFilter implements WebFilter {

    private final BusinessUserClient businessUserClient;

    public TenantWebFilter(BusinessUserClient businessUserClient) {
        this.businessUserClient = businessUserClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 1. 一律剝除 client 偽造 header
        ServerWebExchange stripped = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(AuthConstants.HDR_USER_ID);
                    h.remove(AuthConstants.HDR_USER_ROLE);
                    h.remove(AuthConstants.HDR_USER_STATUS);
                }))
                .build();

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated()
                        && auth.getPrincipal() instanceof OidcUser)
                .flatMap(auth -> {
                    OidcUser oidc = (OidcUser) auth.getPrincipal();
                    String email = oidc.getEmail();
                    return businessUserClient.byEmail(email)
                            .flatMap(me -> applyIdentity(stripped, chain, me));
                })
                // 未登入 / 非 OIDC：不帶身分繼續（受保護路徑已由 SecurityConfig 擋下）
                .switchIfEmpty(Mono.defer(() -> chain.filter(stripped)));
    }

    private Mono<Void> applyIdentity(ServerWebExchange exchange, WebFilterChain chain, BffUser me) {
        if (me == null || me.id() == null) {
            return chain.filter(exchange);
        }
        return exchange.getSession().flatMap(session -> {
            Long effectiveUserId = resolveEffectiveUserId(me, session);

            // 未核准 / 停用：擋下業務 API（放行 /api/me、/api/impersonate）
            String path = exchange.getRequest().getPath().value();
            if (!me.isActive() && isGuardedApi(path)) {
                return writePendingForbidden(exchange);
            }

            TenantIdentity identity = new TenantIdentity(effectiveUserId, me.role(), me.status());
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.headers(h -> {
                        h.set(AuthConstants.HDR_USER_ID, String.valueOf(effectiveUserId));
                        h.set(AuthConstants.HDR_USER_ROLE, me.role());
                        h.set(AuthConstants.HDR_USER_STATUS, me.status());
                    }))
                    .build();

            return chain.filter(mutated)
                    .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, identity));
        });
    }

    /** 管理者且 session 有代看目標 → 目標 id；否則自己。 */
    private Long resolveEffectiveUserId(BffUser me, org.springframework.web.server.WebSession session) {
        if (me.isAdmin()) {
            Object target = session.getAttribute(AuthConstants.SESSION_IMPERSONATE);
            if (target instanceof Number n) {
                return n.longValue();
            }
        }
        return me.id();
    }

    /** 是否為「需 ACTIVE 才能用」的業務 API（排除 /api/me、/api/impersonate）。 */
    private boolean isGuardedApi(String path) {
        if (path == null || !path.startsWith("/api/")) return false;
        return !path.startsWith("/api/me") && !path.startsWith("/api/impersonate");
    }

    private Mono<Void> writePendingForbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":\"ACCOUNT_PENDING\",\"message\":\"帳號等待管理者核准\"}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buf = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buf));
    }
}
