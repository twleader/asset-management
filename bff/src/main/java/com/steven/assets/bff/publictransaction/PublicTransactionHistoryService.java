package com.steven.assets.bff.publictransaction;

import com.steven.assets.bff.publicapi.StrictPublicJsonResponse;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Public configured-admin-or-by-email-selected owner bridge; it never reads the ledger directly. */
@Service
@RequiredArgsConstructor
public class PublicTransactionHistoryService {

    private static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final int EMAIL_MAX_LENGTH = 254;

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;

    /**
     * {@code email} 省略時 owner 為 configured-admin；帶入合法且 active 帳號的 email 時，
     * owner 改由該帳號決定（Requirement 140）。
     */
    public Mono<PublicTransactionHistoryRelay> current(
            List<String> years, List<String> starts, List<String> ends, String email) {
        // email 的錯誤契約優先於日期／year filter，避免不合法 email 被偽裝成 filter 錯誤。
        String normalizedEmail = normalizeEmail(email);
        PublicTransactionHistoryFilter.Filter filter = PublicTransactionHistoryFilter.parse(years, starts, ends);
        return resolveOwner(normalizedEmail).flatMap(admin -> businessServicesClient.get()
                .uri(uri -> {
                    uri.path("/internal/public-transaction-history/current");
                    if (filter.year() != null) {
                        uri.queryParam("year", filter.year());
                    } else if (filter.start() != null) {
                        uri.queryParam("start", filter.start());
                        uri.queryParam("end", filter.end());
                    }
                    return uri.build();
                })
                .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))
                .header(AuthConstants.HDR_USER_ROLE, admin.role())
                .header(AuthConstants.HDR_USER_STATUS, admin.status())
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(this::relayResponse)
                .timeout(DOWNSTREAM_TIMEOUT)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ignored -> new PublicTransactionHistoryTimeoutException())
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY)));
    }

    /**
     * email trim 後非空白時，owner 改由該帳號決定（僅需 {@code id}／{@code role}／{@code status} 非 null
     * 且 active，不必是 configured-admin——與既有 bootstrap 分支的唯一差異）；否則沿用既有
     * configured-admin bootstrap（逐位元組不變，含既有固定 503 契約）。
     */
    private Mono<BffUser> resolveOwner(String email) {
        String trimmed = normalizeEmail(email);
        if (trimmed != null) {
            return Mono.defer(() -> users.byEmail(trimmed))
                    .timeout(DOWNSTREAM_TIMEOUT)
                    .onErrorMap(ignored -> new PublicTransactionHistoryUnavailableException())
                    .switchIfEmpty(Mono.error(new PublicTransactionHistoryUnavailableException()))
                    .flatMap(account -> {
                        if (account == null || account.id() == null || account.role() == null
                                || account.status() == null || !account.isActive()) {
                            // 查無帳號與帳號非 ACTIVE 必須回完全相同的訊息，避免此參數成為帳號列舉工具。
                            return Mono.error(new PublicTransactionHistoryUnavailableException());
                        }
                        return Mono.just(account);
                    });
        }
        Mono<BffUser> bootstrap = Mono.defer(users::configuredAdmin)
                .onErrorMap(ignored -> new PublicTransactionHistoryUnavailableException())
                .switchIfEmpty(Mono.error(new PublicTransactionHistoryUnavailableException()));
        return bootstrap.flatMap(admin -> {
            if (admin == null || admin.id() == null || admin.role() == null || admin.status() == null
                    || !admin.configuredAdmin() || !admin.isActive()) {
                return Mono.error(new PublicTransactionHistoryUnavailableException());
            }
            return Mono.just(admin);
        });
    }

    /** {@code null}／空白回傳 {@code null}（沿用 configured-admin）；格式不合法在呼叫 business 前就地拒絕。 */
    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > EMAIL_MAX_LENGTH || !EMAIL.matcher(trimmed).matches()) {
            throw new PublicTransactionHistoryEmailRequestException();
        }
        return trimmed;
    }

    private Mono<PublicTransactionHistoryRelay> relayResponse(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response.createException().flatMap(Mono::error);
        }
        return StrictPublicJsonResponse.decode(response, StrictPublicJsonResponse.Contract.TRANSACTION_HISTORY,
                        PublicTransactionHistoryPayloadException::new)
                .map(PublicTransactionHistoryRelay::new);
    }
}
