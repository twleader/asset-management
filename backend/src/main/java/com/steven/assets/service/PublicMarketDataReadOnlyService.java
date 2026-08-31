package com.steven.assets.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Requirement 108／Task 372 的 container-only 市場資料 bridge。
 *
 * <p>此 service 不接觸 owner、快照或持股；分時只代理 external 的 exact pure-read
 * endpoint，絕不重用會 cold-start 的 {@code HistoricalDataService.fetchIntradaySession}。</p>
 */
@Slf4j
@Service
public class PublicMarketDataReadOnlyService {

    private static final Duration EXTERNAL_READ_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient externalClient;

    @Autowired
    public PublicMarketDataReadOnlyService(
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this(WebClient.builder().baseUrl(externalUrl).build());
    }

    PublicMarketDataReadOnlyService(WebClient externalClient) {
        this.externalClient = externalClient;
    }

    /** 只代理指定日期的純讀 tick outcome；任何 transport/status 問題交由 BFF child fallback。 */
    public ReadOnlyIntradayTicks readIntradayTicks(String code, String market, LocalDate date) {
        return externalClient.get()
                .uri(builder -> builder.path("/internal/intraday-ticks-readonly")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("date", date)
                        .build())
                .exchangeToMono(response -> decode(response.statusCode(), response))
                .block(EXTERNAL_READ_TIMEOUT);
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

    /** JSON 合約刻意只含公開分時 outcome，不暴露 Redis payload 或例外文字。 */
    public record ReadOnlyIntradayTicks(LocalDate tradingDate, String readStatus, List<Tick> ticks) {
        public ReadOnlyIntradayTicks {
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
        }
    }

    public record Tick(String time, BigDecimal price) {}
}
