package com.steven.assets.bff.security;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
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
 *   <li>有登入者 → 以其 email 即時查 business {@code by-email} 取 fresh role/status（核准/停用立即生效）。</li>
 *   <li>解析 effectiveUserId：管理者且 {@code IMPERSONATE_UID} cookie 有代看目標 → 目標；否則自己。</li>
 *   <li>非 ACTIVE 且存取業務 API → 回 {@code 403 {code:ACCOUNT_PENDING}}。</li>
 *   <li>單次 {@code mutate}：以 {@code set} 寫入 {@code X-User-*}（覆蓋 client 任何偽造值），並寫入 Reactor
 *       context 供 aggregation WebClient 取用。未登入則單次 {@code mutate} 剝除偽造 header 後放行。</li>
 * </ol>
 * 注意：只做「單次 mutate」——避免 exchange 被巢狀 decorate 兩層，導致 controller 回應編碼時
 * {@code setContentLength} 撞上已凍結的 response header（UnsupportedOperationException）。
 */
@Component
@Order(0)
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated()
                        && auth.getPrincipal() instanceof OidcUser)
                // 身分直接取自登入 principal（authorities 已含 id/role/status），不再每請求查 business
                .flatMap(auth -> applyIdentity(exchange, chain,
                        BffUser.fromPrincipal((OidcUser) auth.getPrincipal())))
                // 未登入 / 非 OIDC：剝除偽造 header 後放行（受保護路徑已由 SecurityConfig 擋下）
                .switchIfEmpty(Mono.defer(() -> chain.filter(stripForgedHeaders(exchange))));
    }

    private Mono<Void> applyIdentity(ServerWebExchange exchange, WebFilterChain chain, BffUser me) {
        if (me == null || me.id() == null) {
            return chain.filter(stripForgedHeaders(exchange));
        }
        Long effectiveUserId = resolveEffectiveUserId(me, exchange);

        // 未核准 / 停用：擋下業務 API（放行 /api/me、/api/impersonate）
        String path = exchange.getRequest().getPath().value();
        if (!me.isActive() && isGuardedApi(path)) {
            return writePendingForbidden(exchange);
        }

        TenantIdentity identity = new TenantIdentity(effectiveUserId, me.role(), me.status());
        // 單次 mutate：set 直接覆蓋 client 任何偽造的 X-User-*（不需先 remove）
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set(AuthConstants.HDR_USER_ID, String.valueOf(effectiveUserId));
                    h.set(AuthConstants.HDR_USER_ROLE, me.role());
                    h.set(AuthConstants.HDR_USER_STATUS, me.status());
                }))
                .build();

        return chain.filter(mutated)
                .contextWrite(Context.of(AuthConstants.CTX_IDENTITY, identity));
    }

    /**
     * 管理者代看：只有 ADMIN 才採信 {@code IMPERSONATE_UID} cookie（stateless，不碰 WebSession）。非 ADMIN 一律自己。
     */
    private Long resolveEffectiveUserId(BffUser me, ServerWebExchange exchange) {
        if (me.isAdmin()) {
            HttpCookie c = exchange.getRequest().getCookies().getFirst(AuthConstants.COOKIE_IMPERSONATE);
            if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
                try {
                    return Long.valueOf(c.getValue().trim());
                } catch (NumberFormatException ignored) {
                    // 壞 cookie → 視為看自己
                }
            }
        }
        return me.id();
    }

    private ServerWebExchange stripForgedHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(AuthConstants.HDR_USER_ID);
                    h.remove(AuthConstants.HDR_USER_ROLE);
                    h.remove(AuthConstants.HDR_USER_STATUS);
                }))
                .build();
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
