package com.steven.assets.bff.security;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
// AuthConstants 不再於此引用（header 由 WebClient tenant filter 注入）
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * BFF 呼叫 business-services {@code /internal/users/**} 的反應式客戶端（Requirement 28）。
 *
 * <p>這些呼叫屬「身分解析的引導階段」，刻意不帶 {@code X-User-*} header（business 端 {@code AdminGateInterceptor}
 * 對 {@code login-upsert} / {@code by-email} 放行）。
 */
@Component
public class BusinessUserClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient businessServicesClient;

    public BusinessUserClient(WebClient businessServicesClient) {
        this.businessServicesClient = businessServicesClient;
    }

    /** 登入 upsert：建立或更新使用者，回傳 role/status。 */
    public Mono<BffUser> loginUpsert(String email, String name, String picture) {
        return businessServicesClient.post()
                .uri("/internal/users/login-upsert")
                .bodyValue(Map.of(
                        "email", email == null ? "" : email,
                        "name", name == null ? "" : name,
                        "picture", picture == null ? "" : picture))
                .retrieve()
                .bodyToMono(MAP)
                .map(BusinessUserClient::toUser);
    }

    /** 依 email 查目前使用者（即時 role/status）。 */
    public Mono<BffUser> byEmail(String email) {
        return businessServicesClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/users/by-email").queryParam("email", email).build())
                .retrieve()
                .bodyToMono(MAP)
                .map(BusinessUserClient::toUser);
    }

    /**
     * 管理者：列出全部使用者。{@code X-User-*}（含 ADMIN role）由 WebClient 的 tenant filter 從 Reactor context
     * 自動注入（呼叫端為管理者請求情境），business 端 ADMIN 守門才會放行。
     */
    public Mono<List<BffUser>> listAll() {
        return businessServicesClient.get()
                .uri("/internal/users")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .map(list -> list.stream().map(BusinessUserClient::toUser).toList());
    }

    private static BffUser toUser(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        Object id = m.get("id");
        return new BffUser(
                id == null ? null : ((Number) id).longValue(),
                (String) m.get("email"),
                (String) m.get("name"),
                (String) m.get("picture"),
                (String) m.get("role"),
                (String) m.get("status"));
    }
}
