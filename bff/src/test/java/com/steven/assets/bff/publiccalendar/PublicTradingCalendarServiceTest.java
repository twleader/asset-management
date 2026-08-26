package com.steven.assets.bff.publiccalendar;

import com.steven.assets.bff.publicapi.PublicContractJsonFixtures;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import com.steven.assets.bff.tradingcalendar.TradingCalendarYearWindow;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 113: calendar is no-tenant and local year rejection is zero-I/O. */
class PublicTradingCalendarServiceTest {

    private static final TradingCalendarYearWindow WINDOW = new TradingCalendarYearWindow(
            Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void invalidYearFailsBeforeTheNoTenantClientIsCalled() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new AssertionError("invalid year must not call business"));
                }).build();
        PublicTradingCalendarService service = new PublicTradingCalendarService(client, WINDOW);

        assertThatThrownBy(() -> service.current(List.of("2024")))
                .isInstanceOf(PublicTradingCalendarRequestException.class);
        assertThatThrownBy(() -> service.current(List.of("2026", "2027")))
                .isInstanceOf(PublicTradingCalendarRequestException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void validYearUsesOnlyExactInternalPathAndDropsReactorIdentity() {
        AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> captured = new AtomicReference<>();
        WebClient client = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(PublicContractJsonFixtures.tradingCalendar(2026)).build());
                }).build();
        PublicTradingCalendarService service = new PublicTradingCalendarService(client, WINDOW);

        var relay = service.current(List.of("2026"))
                .contextWrite(context -> context.put(AuthConstants.CTX_IDENTITY,
                        new TenantIdentity(99L, "ADMIN", "ACTIVE")))
                .block();

        assertThat(relay).isNotNull();
        assertThat(relay.body().path("year").asInt()).isEqualTo(2026);
        assertThat(captured.get().url().getPath()).isEqualTo("/internal/public-market-data/trading-calendar");
        assertThat(captured.get().url().getQuery()).isEqualTo("year=2026");
        assertThat(captured.get().headers()).doesNotContainKeys(
                AuthConstants.HDR_USER_ID, AuthConstants.HDR_USER_ROLE, AuthConstants.HDR_USER_STATUS);
    }

    @Test
    void successfulHtmlMalformedEmptyAndSchemaMismatchedBodiesAreSanitized502() {
        for (Payload payload : new Payload[]{
                new Payload(MediaType.TEXT_HTML, "<html>internal-calendar-secret</html>"),
                new Payload(MediaType.APPLICATION_JSON, "{\"year\":"),
                new Payload(MediaType.APPLICATION_JSON, "{\"year\":2026}"),
                new Payload(MediaType.APPLICATION_JSON, "")}) {
            WebTestClient client = payloadClient(payload);

            client.get().uri("/api/public/trading-calendar?year=2026")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(502)
                    .jsonPath("$.title").isEqualTo("Trading calendar downstream failure")
                    .consumeWith(result -> assertThat(new String(result.getResponseBodyContent(),
                            java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("internal-calendar-secret", "payload is invalid", "JSON contract mismatch"));
        }
    }

    @Test
    void validCalendarIsReserializedAsApplicationJson() {
        WebTestClient client = payloadClient(new Payload(MediaType.parseMediaType("application/json;charset=UTF-8"),
                PublicContractJsonFixtures.tradingCalendar(2026)));

        client.get().uri("/api/public/trading-calendar?year=2026")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.year").isEqualTo(2026)
                .jsonPath("$.days.length()").isEqualTo(365);
    }

    private static WebTestClient payloadClient(Payload payload) {
        WebClient client = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, payload.contentType().toString())
                        .body(payload.body()).build()))
                .build();
        PublicTradingCalendarService service = new PublicTradingCalendarService(client, WINDOW);
        return WebTestClient.bindToController(new PublicTradingCalendarController(service))
                .controllerAdvice(new PublicTradingCalendarExceptionAdvice())
                .build();
    }

    private record Payload(MediaType contentType, String body) {}
}
