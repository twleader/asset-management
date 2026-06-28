package com.steven.assets.bff.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 管理者代看切換（Requirement 28）。僅 ADMIN（SecurityConfig 對 {@code /api/impersonate/**} 限 ROLE_ADMIN）。
 *
 * <p>把選定目標 user id 寫入 {@code IMPERSONATE_UID} cookie（stateless，不碰 WebSession，避免 session
 * save-on-commit 的 UnsupportedOperationException）；之後每個請求 {@code TenantWebFilter} 以此為 effectiveUserId。
 * {@code userId} 為空或等於自己 → 清除 cookie、回到看自己。
 */
@RestController
@RequestMapping("/api/impersonate")
public class ImpersonationController {

    @PostMapping
    public Mono<ResponseEntity<Void>> impersonate(@RequestBody(required = false) Map<String, Object> body,
                                                  @AuthenticationPrincipal OidcUser oidc) {
        Object uid = body == null ? null : body.get("userId");
        BffUser me = BffUser.fromPrincipal(oidc);
        boolean clear = !(uid instanceof Number)
                || (me != null && me.id() != null && ((Number) uid).longValue() == me.id());
        ResponseCookie cookie = clear
                ? ResponseCookie.from(AuthConstants.COOKIE_IMPERSONATE, "")
                        .path("/").httpOnly(true).sameSite("Lax").maxAge(0).build()
                : ResponseCookie.from(AuthConstants.COOKIE_IMPERSONATE, String.valueOf(((Number) uid).longValue()))
                        .path("/").httpOnly(true).sameSite("Lax").build();
        // 用 ResponseEntity 的 Set-Cookie header 寫（與回應一起送出），避免 exchange.getResponse().addCookie()
        // 在回應已 committed 後才執行而丟例外
        return Mono.just(ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).<Void>build());
    }
}
