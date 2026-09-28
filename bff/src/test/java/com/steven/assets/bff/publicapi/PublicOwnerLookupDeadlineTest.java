package com.steven.assets.bff.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.latestassets.*;
import com.steven.assets.bff.portfolioadvice.*;
import com.steven.assets.bff.publictransaction.*;
import com.steven.assets.bff.security.BusinessUserClient;
import com.steven.assets.bff.tradingradar.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 465: a stalled owner lookup must end before any personal-data read starts. */
class PublicOwnerLookupDeadlineTest {
    enum Endpoint { ASSETS, ADVICE, RADAR_TODAY, RADAR_STOCK, TRANSACTIONS }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void configuredAdminDeadlineCancelsLookupWithoutReadingData(Endpoint endpoint) {
        var lookupCount = new AtomicInteger();
        var dataCount = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        Boundary boundary = boundary(endpoint, hangingClient(lookupCount, dataCount, cancelled));

        StepVerifier.withVirtualTime(boundary.read())
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(4999))
                .thenAwait(Duration.ofMillis(1))
                .expectError(boundary.errorType())
                .verify(Duration.ofSeconds(2));

        assertThat(lookupCount).hasValue(1);
        assertThat(dataCount).hasValue(0);
        assertThat(cancelled).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void configuredAdminDeadlineKeepsEachHttpProblemContract(Endpoint endpoint) {
        var lookupCount = new AtomicInteger();
        var dataCount = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        Boundary boundary = boundary(endpoint, hangingClient(lookupCount, dataCount, cancelled));
        WebTestClient client = WebTestClient.bindToController(boundary.controller())
                .controllerAdvice(boundary.advice())
                .configureClient().responseTimeout(Duration.ofSeconds(10)).build();

        client.get().uri(boundary.uri()).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo(boundary.title())
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").isEqualTo(boundary.detail())
                .jsonPath("$.instance").isEqualTo(boundary.uri().split("\\?")[0]);

        assertThat(lookupCount).hasValue(1);
        assertThat(dataCount).hasValue(0);
        assertThat(cancelled).isTrue();
    }

    private WebClient hangingClient(AtomicInteger lookupCount, AtomicInteger dataCount,
                                    AtomicBoolean cancelled) {
        return WebClient.builder().baseUrl("http://business.invalid")
                .exchangeFunction(request -> {
                    if (request.url().getPath().equals("/internal/users/configured-admin")) {
                        lookupCount.incrementAndGet();
                        return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
                    }
                    dataCount.incrementAndGet();
                    return Mono.error(new AssertionError("data read before owner lookup completed"));
                }).build();
    }

    private Boundary boundary(Endpoint endpoint, WebClient client) {
        var users = new BusinessUserClient(client);
        return switch (endpoint) {
            case ASSETS -> {
                var service = new LatestAssetsPublicService(users, client, new ObjectMapper());
                yield new Boundary(() -> service.getLatest(null),
                        new LatestAssetsPublicController(service), new LatestAssetsPublicExceptionAdvice(),
                        LatestAssetsUnavailableException.class, "/api/assets/latest",
                        "Latest assets unavailable", "主要管理者不可用");
            }
            case ADVICE -> {
                var service = new PublicPortfolioAdviceService(users, client);
                yield new Boundary(() -> service.latest(null),
                        new PublicPortfolioAdviceController(service), new PublicPortfolioAdviceExceptionAdvice(),
                        PublicPortfolioAdviceUnavailableException.class, "/api/public/portfolio-advice/latest",
                        "Portfolio advice unavailable", "主要管理者不可用");
            }
            case RADAR_TODAY, RADAR_STOCK -> {
                var service = new PublicTradingRadarService(users, client);
                boolean stock = endpoint == Endpoint.RADAR_STOCK;
                yield new Boundary(() -> stock
                        ? service.stock(List.of("2330"), List.of("台股"), null) : service.today(null),
                        new PublicTradingRadarController(service), new PublicTradingRadarExceptionAdvice(),
                        PublicTradingRadarUnavailableException.class,
                        stock ? "/api/public/trading-radar/stock?stockCode=2330&market=台股"
                                : "/api/public/trading-radar/today",
                        "Trading radar unavailable", "主要管理者不可用");
            }
            case TRANSACTIONS -> {
                var service = new PublicTransactionHistoryService(users, client);
                yield new Boundary(() -> service.current(null, null, null, null),
                        new PublicTransactionHistoryController(service), new PublicTransactionHistoryExceptionAdvice(),
                        PublicTransactionHistoryUnavailableException.class, "/api/public/transactions",
                        "Transaction history unavailable", "交易紀錄服務暫時不可用");
            }
        };
    }

    private record Boundary(Supplier<Mono<?>> read, Object controller, Object advice,
                            Class<? extends Throwable> errorType, String uri, String title, String detail) {}
}
