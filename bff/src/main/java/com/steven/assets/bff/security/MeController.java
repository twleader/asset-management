package com.steven.assets.bff.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目前登入者資訊（Requirement 28）。前端唯一的登入態判斷來源。
 *
 * <p>{@code status} 即時查 business DB（核准/停用立即生效）。管理者額外回 {@code switchableUsers}（可代看清單）。
 */
@RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/me")
public class MeController {

    private final BusinessUserClient businessUserClient;

    public MeController(BusinessUserClient businessUserClient) {
        this.businessUserClient = businessUserClient;
    }

    @GetMapping
    public Mono<Map<String, Object>> me(@AuthenticationPrincipal OidcUser oidc, ServerWebExchange exchange) {
        if (oidc == null) {
            return Mono.just(Map.of());
        }
        // 身分取自登入 principal（不再每請求查 business）
        BffUser me = BffUser.fromPrincipal(oidc);
        boolean isAdmin = me != null && me.isAdmin();
        Long selfId = me == null ? null : me.id();
        // effectiveUserId 取 TenantWebFilter 已寫入的 X-User-Id header（權威值）。
        // 不可直接讀 cookie：TenantWebFilter 已 mutate 過 request，mutated request 的 cookie 不保證可重新解析。
        Long effectiveUserId = parseLongOr(exchange.getRequest().getHeaders()
                .getFirst(AuthConstants.HDR_USER_ID), selfId);
        boolean isImpersonating = isAdmin && effectiveUserId != null && !effectiveUserId.equals(selfId);

        // 管理者額外列出可代看的使用者；任何失敗都回空清單，絕不讓 /api/me 整個壞掉
        Mono<List<BffUser>> usersMono = (isAdmin && me != null)
                ? businessUserClient.listAll(me).onErrorReturn(List.of())
                : Mono.just(List.of());
        return usersMono.map(users -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email", oidc.getEmail());
            body.put("name", me != null && me.name() != null ? me.name() : oidc.getFullName());
            body.put("picture", me != null && me.picture() != null ? me.picture() : oidc.getPicture());
            body.put("role", me == null ? AuthConstants.ROLE_USER : me.role());
            body.put("status", me == null ? null : me.status());
            body.put("effectiveUserId", effectiveUserId);
            body.put("isImpersonating", isImpersonating);
            body.put("effectiveUserName", resolveEffectiveName(users, effectiveUserId, me, oidc));

            List<Map<String, Object>> switchable = new ArrayList<>();
            for (BffUser u : users) {
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("id", u.id());
                um.put("email", u.email());
                um.put("name", u.name());
                um.put("role", u.role());
                um.put("status", u.status());
                switchable.add(um);
            }
            body.put("switchableUsers", switchable);
            return body;
        });
    }

    private Long parseLongOr(String s, Long fallback) {
        if (s != null && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                // 壞值 → fallback
            }
        }
        return fallback;
    }

    private String resolveEffectiveName(List<BffUser> users, Long effectiveUserId, BffUser me, OidcUser oidc) {
        if (effectiveUserId != null && me != null && effectiveUserId.equals(me.id())) {
            return me.name() != null ? me.name() : oidc.getFullName();
        }
        for (BffUser u : users) {
            if (u.id() != null && u.id().equals(effectiveUserId)) {
                return u.name() != null ? u.name() : u.email();
            }
        }
        return me != null ? me.name() : oidc.getFullName();
    }
}
