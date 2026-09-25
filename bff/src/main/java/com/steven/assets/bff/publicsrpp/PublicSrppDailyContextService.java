package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 9090 {@code GET /api/public/srpp/daily-context} 的 BFF 流程（Requirement 163／Task 454）：
 * query 400（零 outbound）→ owner resolve（Requirement 140 selector，5 秒）→ business 唯讀內部端點（5 秒）
 * → strict validator → 原位元組回傳。
 *
 * <p>owner 規則同 Requirement 140，但刻意較嚴：空白 email 已在 query 階段 400；configured-admin 與 by-email
 * 兩種 lookup 都有 5 秒逾時；查無、非 ACTIVE、逾時、錯誤一律同一個 503 {@code OWNER_UNAVAILABLE}，不可區辨。
 * business 呼叫清除 caller Reactor identity，並顯式帶解析出的 owner {@code X-User-*}；URI 不含 email。
 * 本服務不直查 DB／Redis，也不呼叫任何外部行情或券商。
 */
@Service
public class PublicSrppDailyContextService {

    static final Duration OWNER_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    static final Duration BUSINESS_TIMEOUT = Duration.ofSeconds(5);

    /** business 非 2xx 可合法回傳的 status → code 對照；其餘一律 502 UPSTREAM_INVALID。 */
    private static final Map<Integer, Set<SrppProblemCatalog>> RELAYABLE = Map.of(
            400, Set.of(SrppProblemCatalog.INVALID_REQUEST),
            404, Set.of(SrppProblemCatalog.CONTEXT_NOT_FOUND, SrppProblemCatalog.SOURCE_EVIDENCE_NOT_FOUND),
            409, Set.of(SrppProblemCatalog.POLICY_UNSUPPORTED, SrppProblemCatalog.CONTEXT_STALE,
                    SrppProblemCatalog.CONTEXT_IDENTITY_MISMATCH, SrppProblemCatalog.NON_TRADING_DAY),
            503, Set.of(SrppProblemCatalog.OWNER_UNAVAILABLE, SrppProblemCatalog.CALENDAR_UNAVAILABLE,
                    SrppProblemCatalog.CONTEXT_NOT_READY));
    private static final Set<String> PROBLEM_FIELDS =
            Set.of("type", "title", "status", "detail", "instance", "code", "retryable");

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;
    private final Duration ownerLookupTimeout;
    private final Duration businessTimeout;

    @Autowired
    public PublicSrppDailyContextService(BusinessUserClient users,
                                         @Qualifier("businessServicesClient") WebClient businessServicesClient) {
        this(users, businessServicesClient, OWNER_LOOKUP_TIMEOUT, BUSINESS_TIMEOUT);
    }

    /** 測試用：縮短逾時以驗證 5 秒規則的分支，不必真的等待。 */
    public PublicSrppDailyContextService(BusinessUserClient users, WebClient businessServicesClient,
                                  Duration ownerLookupTimeout, Duration businessTimeout) {
        this.users = users;
        this.businessServicesClient = businessServicesClient;
        this.ownerLookupTimeout = ownerLookupTimeout;
        this.businessTimeout = businessTimeout;
    }

    /** 驗證通過時回傳 business 原始位元組；失敗一律以 {@link SrppProblemException} 結束。 */
    public Mono<byte[]> read(ServerHttpRequest request) {
        return Mono.defer(() -> {
            SrppDailyContextQuery query = SrppDailyContextQuery.parse(request.getQueryParams(), request.getHeaders());
            return resolveOwner(query.email()).flatMap(owner -> fetch(query, owner));
        });
    }

    private Mono<BffUser> resolveOwner(String email) {
        Mono<BffUser> lookup = email == null
                ? Mono.defer(users::configuredAdmin)
                : Mono.defer(() -> users.byEmail(email));
        return lookup
                .timeout(ownerLookupTimeout)
                .onErrorMap(error -> ownerUnavailable(error))
                .switchIfEmpty(Mono.error(() -> ownerUnavailable(null)))
                .flatMap(account -> {
                    boolean usable = account != null && account.id() != null && account.role() != null
                            && account.status() != null && account.isActive()
                            && (email != null || account.configuredAdmin());
                    // 查無與非 ACTIVE／非 configured-admin 必須回相同 problem，避免成為帳號列舉工具。
                    return usable ? Mono.just(account) : Mono.error(ownerUnavailable(null));
                });
    }

    private Mono<byte[]> fetch(SrppDailyContextQuery query, BffUser owner) {
        return businessServicesClient.get()
                .uri(query.businessUri())
                .header(AuthConstants.HDR_USER_ID, String.valueOf(owner.id()))
                .header(AuthConstants.HDR_USER_ROLE, owner.role())
                .header(AuthConstants.HDR_USER_STATUS, owner.status())
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .exchangeToMono(response -> handle(query, response))
                .timeout(businessTimeout)
                .onErrorMap(error -> !(error instanceof SrppProblemException), PublicSrppDailyContextService::classify)
                // shared WebClient 的 tenant filter 會讀 Reactor context；清掉 caller 身分，只留上面顯式的 owner header。
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    private Mono<byte[]> handle(SrppDailyContextQuery query, ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        MediaType contentType = response.headers().contentType().orElse(null);
        Mono<byte[]> body = response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]);
        if (status.is2xxSuccessful()) {
            return body.map(bytes -> {
                try {
                    SrppDailyContextResponseValidator.validate(query, contentType, bytes);
                } catch (SrppPayloadException invalid) {
                    throw new SrppProblemException(SrppProblemCatalog.UPSTREAM_INVALID, invalid);
                }
                return bytes;
            });
        }
        return body.flatMap(bytes -> Mono.error(translateProblem(status.value(), contentType, bytes)));
    }

    /** business 非 2xx：只有格式、status 與 code 全部相符時才以 BFF 自有文案輸出同一 code。 */
    static SrppProblemException translateProblem(int status, MediaType contentType, byte[] bytes) {
        try {
            if (contentType == null || !MediaType.APPLICATION_PROBLEM_JSON.equalsTypeAndSubtype(contentType)) {
                throw new SrppPayloadException("business 錯誤回應 Content-Type 不是 application/problem+json");
            }
            JsonNode problem = SrppDailyContextResponseValidator.parseStrict(bytes);
            if (!problem.isObject() || problem.size() != PROBLEM_FIELDS.size()) {
                throw new SrppPayloadException("business problem 欄位數不符");
            }
            for (String field : PROBLEM_FIELDS) {
                if (!problem.has(field)) throw new SrppPayloadException("business problem 缺欄位 " + field);
            }
            if (!"about:blank".equals(problem.get("type").textValue())
                    || !problem.get("title").isTextual() || !problem.get("detail").isTextual()
                    || !SrppProblemCatalog.INSTANCE.equals(problem.get("instance").textValue())
                    || !problem.get("retryable").isBoolean()
                    || !problem.get("status").isIntegralNumber() || problem.get("status").intValue() != status
                    || !problem.get("code").isTextual()) {
                throw new SrppPayloadException("business problem 欄位型別或常數不符");
            }
            SrppProblemCatalog code = SrppProblemCatalog.valueOf(problem.get("code").textValue());
            if (!RELAYABLE.getOrDefault(status, Set.of()).contains(code)) {
                throw new SrppPayloadException("business problem code 與 status 不符");
            }
            return new SrppProblemException(code);
        } catch (SrppPayloadException invalid) {
            return new SrppProblemException(SrppProblemCatalog.UPSTREAM_INVALID, invalid);
        } catch (IllegalArgumentException unknownCode) {
            return new SrppProblemException(SrppProblemCatalog.UPSTREAM_INVALID,
                    new SrppPayloadException("business problem code 未知", unknownCode));
        }
    }

    private static Throwable classify(Throwable error) {
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return new SrppProblemException(SrppProblemCatalog.CONTEXT_NOT_READY, error);
        }
        return new SrppProblemException(SrppProblemCatalog.UPSTREAM_INVALID, error);
    }

    private static SrppProblemException ownerUnavailable(Throwable cause) {
        return new SrppProblemException(SrppProblemCatalog.OWNER_UNAVAILABLE, cause);
    }
}
