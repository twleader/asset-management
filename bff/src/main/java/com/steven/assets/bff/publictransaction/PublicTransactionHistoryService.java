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

/** Public configured-admin-only-by-selection bridge; it never reads the ledger directly. */
@Service
@RequiredArgsConstructor
public class PublicTransactionHistoryService {

    private static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(5);

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;

    public Mono<PublicTransactionHistoryRelay> current(
            List<String> years, List<String> starts, List<String> ends) {
        PublicTransactionHistoryFilter.Filter filter = PublicTransactionHistoryFilter.parse(years, starts, ends);
        Mono<BffUser> bootstrap = Mono.defer(users::configuredAdmin)
                .onErrorMap(ignored -> new PublicTransactionHistoryUnavailableException())
                .switchIfEmpty(Mono.error(new PublicTransactionHistoryUnavailableException()));
        return bootstrap.flatMap(admin -> {
            if (admin == null || admin.id() == null || admin.role() == null || admin.status() == null
                    || !admin.configuredAdmin() || !admin.isActive()) {
                return Mono.error(new PublicTransactionHistoryUnavailableException());
            }
            return businessServicesClient.get()
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
                    .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
        });
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
