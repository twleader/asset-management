package com.steven.assets.bff.publicsrpp;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Requirement 184／Task 484.7：SRPP capture／evaluate 的 owner 解析，供 t481（{@code event()}）與 t482（{@code decision()}）接用。
 *
 * <p>{@link #resolve(String)} 逐行等同既有 {@code PublicSrppOrchestratedService.owner(String)}：缺 email 用
 * configured-admin、否則 {@code byEmail}；5 秒逾時；錯誤與空結果一律 {@code OWNER_UNAVAILABLE}；結果必須
 * {@code id}／{@code role}／{@code status} 非空且 ACTIVE，缺 email 時還必須是 configured-admin。既有服務刻意不改，
 * 兩者行為以一致性測試鎖定。本任務只提供，尚未被任何端點使用。
 */
@Component
class SrppOwnerResolver {
    private static final Duration OWNER_TIMEOUT = Duration.ofSeconds(5);
    private final BusinessUserClient users;

    SrppOwnerResolver(BusinessUserClient users) {
        this.users = users;
    }

    Mono<BffUser> resolve(String email) {
        Mono<BffUser> lookup = email == null ? Mono.defer(users::configuredAdmin) : Mono.defer(() -> users.byEmail(email));
        return lookup.timeout(OWNER_TIMEOUT)
                .onErrorMap(error -> unavailable(error))
                .switchIfEmpty(Mono.error(() -> unavailable(null)))
                .flatMap(user -> {
                    boolean valid = user != null && user.id() != null && user.role() != null && user.status() != null
                            && user.isActive() && (email != null || user.configuredAdmin());
                    return valid ? Mono.just(user) : Mono.error(unavailable(null));
                });
    }

    /** 與 {@code PublicSrppOrchestratedService.fetch} 相同的顯式 owner headers；用法：{@code spec.headers(ownerHeaders(owner))}。 */
    static Consumer<HttpHeaders> ownerHeaders(BffUser owner) {
        return headers -> {
            headers.set(AuthConstants.HDR_USER_ID, String.valueOf(owner.id()));
            headers.set(AuthConstants.HDR_USER_ROLE, owner.role());
            headers.set(AuthConstants.HDR_USER_STATUS, owner.status());
        };
    }

    /** 與 {@code PublicSrppOrchestratedService.fetch} 相同：清除 caller 的 Reactor identity，避免 tenant filter 覆寫 owner headers。 */
    static <T> Mono<T> withoutCallerIdentity(Mono<T> call) {
        return call.contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    private static SrppCaptureProblemException unavailable(Throwable cause) {
        return new SrppCaptureProblemException(SrppCaptureProblemCatalog.OWNER_UNAVAILABLE, cause);
    }
}
