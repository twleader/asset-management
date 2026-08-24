package com.steven.assets.bff.publicquote;

import com.steven.assets.bff.stockanalysis.StockAnalysisChartDataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** HTTP 層釘住 raw cache miss 204 與 date-window 400，不讓 service 契約在 controller 漂移。 */
class PublicQuoteMarketDataControllerTest {

    @Test
    void oneRawCacheMissIsStillNoContent() {
        client(request -> ClientResponse.create(HttpStatus.NO_CONTENT).build())
                .get().uri("/api/quotes/one?code=2330&market=%E5%8F%B0%E8%82%A1")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    @Test
    void invalidDateWindowReturnsSanitizedBadRequestBeforeRawIo() {
        client(request -> {
            throw new AssertionError("raw external must not be called for an invalid window");
        }).get().uri("/api/quotes?start=2026-08-25&end=2026-08-24")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("報價日期參數不合法");
    }

    private static WebTestClient client(
            java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest,
                    ClientResponse> rawExchange) {
        WebClient raw = WebClient.builder().baseUrl("http://external.invalid")
                .exchangeFunction(request -> Mono.just(rawExchange.apply(request))).build();
        WebClient market = WebClient.builder().baseUrl("http://business.invalid")
                .exchangeFunction(request -> Mono.error(new AssertionError("market child must not be called"))).build();
        PublicQuoteMarketDataService service = new PublicQuoteMarketDataService(raw, market,
                new StockAnalysisChartDataService(),
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneId.of("Asia/Taipei")),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 8);
        return WebTestClient.bindToController(new PublicQuoteMarketDataController(service))
                .controllerAdvice(new PublicQuoteMarketDataExceptionAdvice())
                .build();
    }
}
