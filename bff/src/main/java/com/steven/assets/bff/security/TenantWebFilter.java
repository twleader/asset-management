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
 * 注意（二次訂閱陷阱）：{@code chain.filter(...)} 回傳 {@code Mono<Void>}，永遠只發 onComplete、不發 onNext。
 * 故「{@code ...flatMap(me -> chain.filter(...)).switchIfEmpty(chain.filter(...))}」的整條 {@code flatMap}
 * 會被 {@code switchIfEmpty} 誤判為 empty 而觸發，使同一 exchange 的過濾鏈被「第二次訂閱」：第一趟（已登入）
 * 已把 controller 回應 commit（200/204），第二趟在 response 已凍結後重跑，result handler 對唯讀 header 呼叫
 * {@code setContentLength} 即拋 {@code UnsupportedOperationException}（{@code /api/impersonate} 第二趟落到
 * {@code ResourceWebHandler} → 404；proxied SSE 則為 {@code Rejecting additional inbound receiver}）。
 * 因此本類別把「未登入」轉成 {@code defaultIfEmpty(ANONYMOUS)} 的哨兵 onNext，後續只有「一個」{@code flatMap}
 * 呼叫 {@code chain.filter}，保證整條鏈恰好訂閱一次。（巢狀 mutate 並非主因。）
 */
@Component
@Order(0)
public class TenantWebFilter implements WebFilter {

    /**
     * 未登入哨兵：{@code id == null}，{@link #applyIdentity} 會走「剝除偽造 header 後放行」分支。
     * 用它把「未登入」表成一個 onNext，避免以 {@code switchIfEmpty} 包覆 {@code chain.filter}
     * （{@code Mono<Void>}）而造成過濾鏈被二次訂閱（見 class doc）。
     */
    private static final BffUser ANONYMOUS = new BffUser(null, null, null, null, null, null);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated()
                        && auth.getPrincipal() instanceof OidcUser)
                // 身分直接取自登入 principal（authorities 已含 id/role/status），不再每請求查 business
                .map(auth -> BffUser.fromPrincipal((OidcUser) auth.getPrincipal()))
                // 未登入 / 非 OIDC → 補 ANONYMOUS 哨兵：保證下面恰好一個 flatMap 呼叫 chain.filter、整條鏈只訂閱一次。
                // 不可改回 switchIfEmpty(chain.filter(...))：chain.filter 是 Mono<Void> 永遠 complete-empty，會誤觸而二次訂閱。
                .defaultIfEmpty(ANONYMOUS)
                .flatMap(me -> applyIdentity(exchange, chain, me));
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
