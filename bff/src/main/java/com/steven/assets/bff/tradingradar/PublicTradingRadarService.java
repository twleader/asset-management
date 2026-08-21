package com.steven.assets.bff.tradingradar;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 公開今日交易雷達的 configured-admin owner boundary（Requirement 86 / Task 347）。
 *
 * <p>bootstrap 與資料 downstream 是兩個刻意分離的 publisher：前者任何 HTTP、transport、
 * codec 或 projection error 都先轉成固定 unavailable 訊號；後者才以已驗證 owner 的顯式
 * tenant headers 呼叫純 current read，並清除匿名 request 可能帶入的 Reactor 身分。</p>
 */
@Service
@RequiredArgsConstructor
public class PublicTradingRadarService {

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;

    public Mono<PublicTradingRadarRelay> today() {
        Mono<BffUser> bootstrap = Mono.defer(users::configuredAdmin)
                .onErrorMap(ignored -> new PublicTradingRadarUnavailableException())
                .switchIfEmpty(Mono.error(new PublicTradingRadarUnavailableException()));

        return bootstrap.flatMap(admin -> {
            if (admin == null || admin.id() == null || admin.role() == null || admin.status() == null
                    || !admin.configuredAdmin() || !admin.isActive()) {
                return Mono.error(new PublicTradingRadarUnavailableException());
            }
            return businessServicesClient.get()
                    .uri("/api/trading-radar/current")
                    .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))
                    .header(AuthConstants.HDR_USER_ROLE, admin.role())
                    .header(AuthConstants.HDR_USER_STATUS, admin.status())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), ClientResponse::createException)
                    .toEntity(byte[].class)
                    .map(entity -> new PublicTradingRadarRelay(
                            entity.getStatusCode().value(),
                            entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            entity.getBody()))
                    .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
        });
    }
}
