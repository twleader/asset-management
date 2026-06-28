package com.steven.assets.bff.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantWebFilter} 的二次訂閱回歸測試。
 *
 * <p>歷史 bug：{@code flatMap(me -> chain.filter(...)).switchIfEmpty(chain.filter(...))} 中
 * {@code chain.filter(...)} 為 {@code Mono<Void>}（只 onComplete、不 onNext），整條 {@code flatMap}
 * 被 {@code switchIfEmpty} 誤判為 empty 而觸發，使每個已登入請求的過濾鏈被「第二次訂閱」——第二趟在
 * 回應已 committed 後重跑，{@code setContentLength} 撞唯讀 header 拋 {@code UnsupportedOperationException}。
 * 本測試直接數 {@code chain.filter} 的訂閱次數：修法後恰好一次（舊碼為二次）。
 */
class TenantWebFilterTest {

    private final TenantWebFilter filter = new TenantWebFilter();

    /** 已登入（OIDC principal，含 APP_UID/APP_STATUS/ROLE authorities）→ 過濾鏈只訂閱一次，且注入 X-User-Id。 */
    @Test
    void authenticatedRequest_subscribesChainExactlyOnce() {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(AuthConstants.AUTHORITY_USER),
                new SimpleGrantedAuthority(AuthConstants.AUTHORITY_UID_PREFIX + "42"),
                new SimpleGrantedAuthority(AuthConstants.AUTHORITY_STATUS_PREFIX + AuthConstants.STATUS_ACTIVE));
        OidcIdToken idToken = OidcIdToken.withTokenValue("tok").claim("sub", "42").build();
        DefaultOidcUser oidc = new DefaultOidcUser(authorities, idToken);
        TestingAuthenticationToken auth = new TestingAuthenticationToken(oidc, null, authorities);
        auth.setAuthenticated(true);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bff/dashboard/summary"));

        AtomicInteger subscribeCount = new AtomicInteger();
        AtomicReference<String> seenUserId = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            subscribeCount.incrementAndGet();
            seenUserId.set(ex.getRequest().getHeaders().getFirst(AuthConstants.HDR_USER_ID));
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(subscribeCount.get())
                .as("已登入請求的過濾鏈必須恰好訂閱一次（舊 switchIfEmpty 二次訂閱會是 2）")
                .isEqualTo(1);
        assertThat(seenUserId.get()).isEqualTo("42");
    }

    /** 未登入 → 走 ANONYMOUS 哨兵分支，剝除偽造 header 後放行，過濾鏈一樣只訂閱一次。 */
    @Test
    void unauthenticatedRequest_stripsForgedHeaderAndSubscribesChainOnce() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bff/dashboard/summary")
                        .header(AuthConstants.HDR_USER_ID, "999")); // client 偽造值

        AtomicInteger subscribeCount = new AtomicInteger();
        AtomicReference<String> seenUserId = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            subscribeCount.incrementAndGet();
            seenUserId.set(ex.getRequest().getHeaders().getFirst(AuthConstants.HDR_USER_ID));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(subscribeCount.get()).isEqualTo(1);
        assertThat(seenUserId.get()).as("未登入應剝除 client 偽造的 X-User-Id").isNull();
    }
}
