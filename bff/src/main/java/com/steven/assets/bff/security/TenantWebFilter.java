package com.steven.assets.bff.security;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
 * 解析目前登入者身分、注入 {@code X-User-*} header、處理管理者代看切換、並擋下未核准帳號（Requirement 28）。
 *
 * <p>順序在 Spring Security 之後（{@link Order} 0；security 在 -100），故能讀到 Reactor context 內的認證。
 * 每個請求：
 * <ol>
 *   <li>身分直接取自登入 principal（authorities 已含 id/role/status），不再每請求查 business。</li>
 *   <li>{@code POST /api/impersonate} → 在此寫 {@code IMPERSONATE_UID} cookie + {@code 204} short-circuit（見下）。</li>
 *   <li>解析 effectiveUserId：管理者且 {@code IMPERSONATE_UID} cookie 有代看目標 → 目標；否則自己。</li>
 *   <li>非 ACTIVE 且存取業務 API → 回 {@code 403 {code:ACCOUNT_PENDING}}。</li>
 *   <li>單次 {@code mutate}：以 {@code set} 寫入 {@code X-User-*}（覆蓋 client 任何偽造值），並寫入 Reactor
 *       context 供 aggregation WebClient 取用。未登入則單次 {@code mutate} 剝除偽造 header 後放行。</li>
 * </ol>
 *
 * <p>注意（二次訂閱陷阱）：{@code chain.filter(...)} 回傳 {@code Mono<Void>}，永遠只發 onComplete、不發 onNext。
 * 故「{@code ...flatMap(me -> chain.filter(...)).switchIfEmpty(chain.filter(...))}」的整條 {@code flatMap}
 * 會被 {@code switchIfEmpty} 誤判為 empty 而觸發，使同一 exchange 的過濾鏈被「第二次訂閱」：第一趟（已登入）
 * 已把 controller 回應 commit（200/204），第二趟在 response 已凍結後重跑，result handler 對唯讀 header 呼叫
 * {@code setContentLength} 即拋 {@code UnsupportedOperationException}（{@code /api/impersonate} 第二趟落到
 * {@code ResourceWebHandler} → 404；proxied SSE 則為 {@code Rejecting additional inbound receiver}）。
 * 因此本類別把「未登入」轉成 {@code defaultIfEmpty(ANONYMOUS)} 的哨兵 onNext，後續只有「一個」{@code flatMap}
 * 呼叫 {@code chain.filter}，保證整條鏈恰好訂閱一次。（巢狀 mutate 並非主因。）
 *
 * <p>注意（代看為何放在 WebFilter 而非 {@code @RestController}）：本 BFF 是 Spring Cloud Gateway，{@code @RestController}
 * 的 handler 執行時 response 已 commit、{@code getHeaders()} 唯讀，任何在 controller 內寫 {@code Set-Cookie} 的做法
 * （{@code addCookie} / {@code ResponseEntity} / {@code getHeaders().add}）都會丟 {@code UnsupportedOperationException}。
 * WebFilter 在 {@code chain.filter} 前 response 仍可寫（與登入／登出清 cookie 同一視窗），故代看切換在此處理。
 */
@Component
@Order(0)
public class TenantWebFilter implements WebFilter {

    /**
     * 未登入哨兵：{@code id == null}，{@link #applyIdentity} 會走「剝除偽造 header 後放行」分支。
     * 用它把「未登入」表成一個 onNext，避免以 {@code switchIfEmpty} 包覆 {@code chain.filter}
     * （{@code Mono<Void>}）而造成過濾鏈被二次訂閱（見 class doc）。
     */
    private static final BffUser ANONYMOUS = new BffUser(null, null, null, null, null, null, false);

    /**
     * 代看 cookie 的 {@code Secure} 屬性開關（Requirement 30）：由 {@code SESSION_COOKIE_SECURE} 控制，預設 false。
     * 正式環境上 TLS（https）後設 true 才帶 Secure；dev/純 http 維持 false，否則瀏覽器不回送 cookie。
     * 必須與 {@link com.steven.assets.bff.config.SecurityConfig#clearImpersonateCookie()} 用同一值，
     * 否則清除代看 cookie 時屬性不符而清不掉。
     */
    @org.springframework.beans.factory.annotation.Value("${SESSION_COOKIE_SECURE:false}")
    private boolean cookieSecure;

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
                .flatMap(me -> {
                    // 代看切換：在此 WebFilter 寫 cookie（response 尚可寫）並 204 short-circuit，不進 controller（見 class doc）
                    if (me.id() != null && isImpersonateRequest(exchange)) {
                        return handleImpersonate(exchange, me);
                    }
                    return applyIdentity(exchange, chain, me);
                });
    }

    private boolean isImpersonateRequest(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && "/api/impersonate".equals(exchange.getRequest().getPath().value());
    }

    /**
     * 管理者代看切換：把目標 user id 寫進 {@code IMPERSONATE_UID} cookie（query param {@code userId}），
     * 省略或等於自己 → 清除 cookie（回到看自己）。僅 ADMIN（SecurityConfig 已限 {@code /api/impersonate}=ROLE_ADMIN，
     * 此處再防禦一次）。直接寫回應 + {@code 204}，不轉發下游。
     */
    private Mono<Void> handleImpersonate(ServerWebExchange exchange, BffUser me) {
        ServerHttpResponse resp = exchange.getResponse();
        if (!me.isAdmin()) {
            resp.setStatusCode(HttpStatus.FORBIDDEN);
            return resp.setComplete();
        }
        Long target = parseLongOrNull(exchange.getRequest().getQueryParams().getFirst("userId"));
        boolean clear = target == null || (me.id() != null && target.equals(me.id()));
        ResponseCookie cookie = clear
                ? ResponseCookie.from(AuthConstants.COOKIE_IMPERSONATE, "")
                        .path("/").httpOnly(true).secure(cookieSecure).sameSite("Lax").maxAge(0).build()
                : ResponseCookie.from(AuthConstants.COOKIE_IMPERSONATE, String.valueOf(target))
                        .path("/").httpOnly(true).secure(cookieSecure).sameSite("Lax").build();
        resp.getHeaders().add(HttpHeaders.SET_COOKIE, cookie.toString());
        resp.setStatusCode(HttpStatus.NO_CONTENT);
        return resp.setComplete();
    }

    private Long parseLongOrNull(String s) {
        if (s != null && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                // 壞值 → 視為清除
            }
        }
        return null;
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
