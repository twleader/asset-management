package com.steven.assets.bff.security;

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
 * <p>把選定目標 user id 寫入 WebSession；之後每個請求 {@code TenantWebFilter} 以此為 effectiveUserId。
 * {@code userId} 為空或等於自己 → 清除代看、回到看自己。
 */
@RestController
@RequestMapping("/api/impersonate")
public class ImpersonationController {

    private final BusinessUserClient businessUserClient;

    public ImpersonationController(BusinessUserClient businessUserClient) {
        this.businessUserClient = businessUserClient;
    }

    @PostMapping
    public Mono<ResponseEntity<Void>> impersonate(@RequestBody(required = false) Map<String, Object> body,
                                                  @AuthenticationPrincipal OidcUser oidc,
                                                  ServerWebExchange exchange) {
        Object uid = body == null ? null : body.get("userId");
        return businessUserClient.byEmail(oidc.getEmail()).flatMap(me ->
                exchange.getSession().flatMap(session -> {
                    boolean clear = !(uid instanceof Number)
                            || (me != null && me.id() != null && ((Number) uid).longValue() == me.id());
                    if (clear) {
                        session.getAttributes().remove(AuthConstants.SESSION_IMPERSONATE);
                    } else {
                        session.getAttributes().put(AuthConstants.SESSION_IMPERSONATE, ((Number) uid).longValue());
                    }
                    return session.save().thenReturn(ResponseEntity.ok().<Void>build());
                }));
    }
}
