package com.steven.assets.bff.security;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
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
                .map(BusinessUserClient::toUser)
                // by-email 和 configured-admin 一樣都是取得可信 owner 前的 bootstrap lookup。
                // 即使匿名 9090 request 意外攜帶登入／代看 Reactor context，也不得讓 shared
                // WebClient filter 注入 X-User-*，以免 lookup 受到呼叫者 tenant 影響。
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    /**
     * 取得 business 端唯一權威決定的主要管理者。這是 bootstrap lookup，刻意不帶
     * tenant header；business 只放行 exact GET /internal/users/configured-admin。
     */
    public Mono<BffUser> configuredAdmin() {
        return businessServicesClient.get()
                .uri("/internal/users/configured-admin")
                .retrieve()
                .bodyToMono(MAP)
                .map(BusinessUserClient::toUser)
                // 這是取得第一個可信身分前的 bootstrap call；即使呼叫端已有登入者或
                // 管理者代看 context，也不得讓 shared WebClient filter 加上該身分。
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    /**
     * 管理者：列出全部使用者。{@code X-User-*} header 由呼叫端（{@link MeController}）以已解析的管理者身分
     * <b>顯式帶入</b>——不依賴 Reactor context 傳遞，確保 business 端 ADMIN 守門必定放行。
     */
    public Mono<List<BffUser>> listAll(BffUser admin) {
        return businessServicesClient.get()
                .uri("/internal/users")
                .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))
                .header(AuthConstants.HDR_USER_ROLE, admin.role())
                .header(AuthConstants.HDR_USER_STATUS, admin.status())
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
                (String) m.get("status"),
                // business 的 UserDto.UserResponse.protectedAdmin ＝ isConfiguredAdmin(email)（ADMIN_EMAIL 本人）。
                // 走 business 既有的單一判定入口，BFF 不自行比對 ADMIN_EMAIL——那會變成第二個判準、各自演化。
                Boolean.TRUE.equals(m.get("protectedAdmin")));
    }
}
