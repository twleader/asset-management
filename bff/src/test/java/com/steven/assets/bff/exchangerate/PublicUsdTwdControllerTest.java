package com.steven.assets.bff.exchangerate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

class PublicUsdTwdControllerTest {

    @Test
    void exactGetReturnsFixedJsonContractAndDoesNotExposeInternalWatermark() {
        client(request -> request.url().getPath().endsWith("/live")
                ? ok("{\"pair\":\"USD/TWD\",\"baseCurrency\":\"USD\",\"quoteCurrency\":\"TWD\","
                    + "\"date\":\"2026-08-13\",\"buyRate\":32.1000,\"sellRate\":32.2000,"
                    + "\"source\":\"MEGA_BANK\",\"polledAt\":\"2026-08-13T15:10:14Z\","
                    + "\"sourceUpdatedAt\":\"2026-08-13T15:10:12Z\","
                    + "\"liveUpdateStatus\":\"ACTIVE\",\"quoteStatus\":\"LIVE\","
                    + "\"sourceUpdatedAtHighWatermarks\":{\"MEGA_BANK\":\"secret\"}}")
                : ok("[{\"rateDate\":\"2026-08-13\",\"buyRate\":32.1000,\"sellRate\":32.2000}]"))
                .get().uri("/api/public/exchange-rate/usd-twd")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.pair").isEqualTo("USD/TWD")
                .jsonPath("$.refreshIntervalSeconds").isEqualTo(2)
                .jsonPath("$.requestedStartDate").isEqualTo("2025-08-13")
                .jsonPath("$.requestedEndDate").isEqualTo("2026-08-13")
                .jsonPath("$.spot.midRate").isEqualTo(32.15)
                .jsonPath("$.count").isEqualTo(1)
                .jsonPath("$.sourceUpdatedAtHighWatermarks").doesNotExist()
                .jsonPath("$.spot.sourceUpdatedAtHighWatermarks").doesNotExist();
    }

    @Test
    void notFoundAndMalformedDownstreamReturnSanitizedProblemJson() {
        client(request -> request.url().getPath().endsWith("/live")
                ? ClientResponse.create(HttpStatus.NOT_FOUND).build()
                : ok("[]"))
                .get().uri("/api/public/exchange-rate/usd-twd")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);

        client(request -> ok("{broken-secret-token"))
                .get().uri("/api/public/exchange-rate/usd-twd")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat(detail.toString())
                                .doesNotContain("secret-token", "stacktrace"));
    }

    private static WebTestClient client(
            java.util.function.Function<org.springframework.web.reactive.function.client.ClientRequest,
                    ClientResponse> exchange) {
        WebClient downstream = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> Mono.just(exchange.apply(request))).build();
        UsdTwdPublicService service = new UsdTwdPublicService(downstream, Clock.fixed(
                Instant.parse("2026-08-13T15:10:14Z"), ZoneId.of("Asia/Taipei")));
        return WebTestClient.bindToController(new PublicUsdTwdController(service))
                .controllerAdvice(new PublicUsdTwdExceptionAdvice()).build();
    }

    private static ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }
}
