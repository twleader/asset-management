package com.steven.assets.bff.transaction;

import com.steven.assets.bff.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Task601: local protection only; never changes global session, CORS or CSRF settings. */
@Component
@Order(1)
public final class TradeReportImportBoundary implements WebFilter {
    static final String PREFIX = "/api/bff/transaction/report-import";
    static final String RAW_PREFIX = "/api/asset-transactions/report-import";
    private final String allowedOrigin;

    public TradeReportImportBoundary(@Value("${APP_PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.allowedOrigin = origin(publicBaseUrl);
    }

    static String origin(String value) {
        try {
            URI u = URI.create(value == null ? "" : value);
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null
                    || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                    || !(u.getRawPath().isEmpty() || "/".equals(u.getRawPath()))) return null;
            return u.getScheme() + "://" + u.getRawAuthority();
        } catch (RuntimeException ex) { return null; }
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith(RAW_PREFIX)) return reject(exchange);
        if (!path.startsWith(PREFIX)) return chain.filter(exchange);
        if (!(path.equals(PREFIX + "/preview") || path.equals(PREFIX + "/apply"))
                || !HttpMethod.POST.equals(exchange.getRequest().getMethod())
                || exchange.getRequest().getURI().getRawQuery() != null) return reject(exchange);
        HttpHeaders h = exchange.getRequest().getHeaders();
        if (allowedOrigin == null || !single(h, HttpHeaders.ORIGIN, allowedOrigin)
                || !single(h, "Sec-Fetch-Site", "same-origin")
                || !single(h, "X-Trade-Report-Import", "1")
                || !jsonContent(h))
            return reject(exchange);
        return ReactiveSecurityContextHolder.getContext()
                .map(c -> c.getAuthentication())
                .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof OidcUser)
                .map(a -> BffUser.fromPrincipal((OidcUser)a.getPrincipal()))
                .filter(me -> me != null && me.id() != null && me.isActive())
                .flatMap(me -> Mono.deferContextual(context -> {
                    TenantIdentity identity = context.getOrDefault(AuthConstants.CTX_IDENTITY, null);
                    var cookie = exchange.getRequest().getCookies().getFirst(AuthConstants.COOKIE_IMPERSONATE);
                    boolean ownCookie = cookie == null || cookie.getValue().isBlank()
                            || cookie.getValue().equals(me.id().toString());
                    boolean allowed = identity != null && identity.isActive()
                            && me.id().equals(identity.effectiveUserId()) && ownCookie;
                    // Return a boolean so downstream Mono<Void> cannot trigger a second subscription.
                    return Mono.just(allowed);
                }))
                .defaultIfEmpty(false)
                .flatMap(allowed -> allowed ? chain.filter(exchange) : reject(exchange));
    }

    private static boolean jsonContent(HttpHeaders headers) {
        try { return headers.getContentType() != null
                && MediaType.APPLICATION_JSON.isCompatibleWith(headers.getContentType()); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean single(HttpHeaders h, String key, String expected) {
        List<String> values = h.get(key);
        return values != null && values.size() == 1 && expected.equals(values.getFirst());
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"code\":\"REPORT_IMPORT_FORBIDDEN\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
