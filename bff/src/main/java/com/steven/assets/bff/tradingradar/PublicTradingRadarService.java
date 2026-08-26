package com.steven.assets.bff.tradingradar;

import com.steven.assets.bff.publicapi.StrictPublicJsonResponse;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

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

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9.\\-]{1,12}$");
    private static final Pattern MARKET = Pattern.compile("^[\\p{L}0-9]{1,10}$");
    private static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(5);

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;

    public Mono<PublicTradingRadarRelay> today() {
        return relay("/internal/public-trading-radar/current/list", StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST);
    }

    /** 指定展開列的 selector 在 BFF 本地先完整驗證，無效輸入不能 bootstrap 或 outbound。 */
    public Mono<PublicTradingRadarRelay> stock(List<String> stockCodes, List<String> markets) {
        String stockCode = singleSelector(stockCodes, CODE);
        String market = singleSelector(markets, MARKET);
        return relay(uri -> uri.path("/internal/public-trading-radar/current/stock")
                .queryParam("stockCode", stockCode)
                .queryParam("market", market)
                .build(), StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL);
    }

    private Mono<PublicTradingRadarRelay> relay(String path, StrictPublicJsonResponse.Contract contract) {
        return relay(uri -> uri.path(path).build(), contract);
    }

    private Mono<PublicTradingRadarRelay> relay(
            java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri,
            StrictPublicJsonResponse.Contract contract) {
        Mono<BffUser> bootstrap = Mono.defer(users::configuredAdmin)
                .onErrorMap(ignored -> new PublicTradingRadarUnavailableException())
                .switchIfEmpty(Mono.error(new PublicTradingRadarUnavailableException()));

        return bootstrap.flatMap(admin -> {
            if (admin == null || admin.id() == null || admin.role() == null || admin.status() == null
                    || !admin.configuredAdmin() || !admin.isActive()) {
                return Mono.error(new PublicTradingRadarUnavailableException());
            }
            return businessServicesClient.get()
                    .uri(uri)
                    .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))
                    .header(AuthConstants.HDR_USER_ROLE, admin.role())
                    .header(AuthConstants.HDR_USER_STATUS, admin.status())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> relayResponse(response, contract))
                    .timeout(DOWNSTREAM_TIMEOUT)
                    .onErrorMap(java.util.concurrent.TimeoutException.class,
                            ignored -> new PublicTradingRadarTimeoutException())
                    .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
        });
    }

    private Mono<PublicTradingRadarRelay> relayResponse(
            ClientResponse response, StrictPublicJsonResponse.Contract contract) {
        if (response.statusCode().is2xxSuccessful()) {
            return StrictPublicJsonResponse.decode(response, contract,
                            PublicTradingRadarPayloadException::new)
                    .map(PublicTradingRadarRelay::new);
        }
        if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
            return response.releaseBody().then(Mono.error(new PublicTradingRadarStockNotFoundException()));
        }
        return response.createException().flatMap(Mono::error);
    }

    private String singleSelector(List<String> values, Pattern pattern) {
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw new PublicTradingRadarRequestException();
        }
        String normalized = values.getFirst().trim();
        if (normalized.isEmpty() || !pattern.matcher(normalized).matches()) {
            throw new PublicTradingRadarRequestException();
        }
        return normalized;
    }
}
