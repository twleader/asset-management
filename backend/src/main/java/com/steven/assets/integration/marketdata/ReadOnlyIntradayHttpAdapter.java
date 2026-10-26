package com.steven.assets.integration.marketdata;

import com.steven.assets.service.port.ReadOnlyIntradayPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.*;
import org.springframework.http.HttpStatusCode;
import com.steven.assets.model.marketdata.ReadOnlyIntradayData.ReadOnlyIntradayTicks;

@Component
public class ReadOnlyIntradayHttpAdapter implements ReadOnlyIntradayPort {
    private final WebClient client;
    public ReadOnlyIntradayHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).build();
    }
    /** 只代理指定日期的純讀 tick outcome；任何 transport/status 問題交由 BFF child fallback。 */
    public ReadOnlyIntradayTicks readIntradayTicks(String code, String market, LocalDate date) {
        return client.get()
                .uri(builder -> builder.path("/internal/intraday-ticks-readonly")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("date", date)
                        .build())
                .exchangeToMono(response -> decode(response.statusCode(), response))
                .block(Duration.ofSeconds(2));
    }

    private reactor.core.publisher.Mono<ReadOnlyIntradayTicks> decode(
            HttpStatusCode status,
            org.springframework.web.reactive.function.client.ClientResponse response) {
        if (!status.is2xxSuccessful()) {
            return reactor.core.publisher.Mono.error(
                    new IllegalStateException("readonly intraday downstream status unavailable"));
        }
        return response.bodyToMono(ReadOnlyIntradayTicks.class)
                .switchIfEmpty(reactor.core.publisher.Mono.error(
                        new IllegalStateException("readonly intraday downstream body unavailable")));
    }

}
