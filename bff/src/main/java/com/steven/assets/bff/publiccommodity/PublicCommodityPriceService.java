package com.steven.assets.bff.publiccommodity;

import com.steven.assets.bff.security.AuthConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** No-tenant, one-request bridge for the existing persisted commodity live aggregation. */
@Service
@RequiredArgsConstructor
public class PublicCommodityPriceService {

    static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(5);

    @Qualifier("publicMarketDataBusinessClient")
    private final WebClient marketDataClient;

    public Mono<CommodityPriceBatchResponse> current() {
        return marketDataClient.get()
                .uri("/api/market-data/commodity/live")
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(this::decodeResponse)
                .timeout(DOWNSTREAM_TIMEOUT)
                .onErrorMap(TimeoutException.class, ignored -> new PublicCommodityPriceTimeoutException())
                .onErrorMap(WebClientResponseException.class, ignored -> new PublicCommodityPricePayloadException())
                .onErrorMap(WebClientException.class, ignored -> new PublicCommodityPriceTransportException())
                .contextWrite(context -> context.delete(AuthConstants.CTX_IDENTITY));
    }

    private Mono<CommodityPriceBatchResponse> decodeResponse(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful() || !isApplicationJson(response)) {
            return response.releaseBody().then(Mono.error(new PublicCommodityPricePayloadException()));
        }
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(new PublicCommodityPricePayloadException()))
                .map(PublicCommodityPriceMapper::decode)
                .onErrorMap(error -> !(error instanceof PublicCommodityPricePayloadException),
                        ignored -> new PublicCommodityPricePayloadException());
    }

    private static boolean isApplicationJson(ClientResponse response) {
        MediaType contentType = response.headers().contentType().orElse(null);
        return contentType != null
                && "application".equalsIgnoreCase(contentType.getType())
                && "json".equalsIgnoreCase(contentType.getSubtype());
    }
}
