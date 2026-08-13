package com.steven.assets.bff.exchangerate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsdTwdPublicServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T15:10:14Z"), ZoneId.of("Asia/Taipei"));

    @Test
    void aggregatesExactOneYearQueriesSortsHistoryAndComputesMidScaleFour() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        UsdTwdPublicService service = service(request -> {
            requests.add(request.url());
            if (request.url().getPath().endsWith("/live")) return jsonRaw(liveMega());
            return json(List.of(
                    history("2026-08-13", "32.1000", "32.2000"),
                    history("2025-08-13", "29.8300", "29.9300")));
        });

        UsdTwdPublicDto.Response response = service.getUsdTwd().block();

        assertThat(response).isNotNull();
        assertThat(response.requestedStartDate().toString()).isEqualTo("2025-08-13");
        assertThat(response.requestedEndDate().toString()).isEqualTo("2026-08-13");
        assertThat(response.refreshIntervalSeconds()).isEqualTo(2);
        assertThat(response.timezone()).isEqualTo("Asia/Taipei");
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.history()).extracting(p -> p.date().toString())
                .containsExactly("2025-08-13", "2026-08-13");
        assertThat(response.spot().midRate().toPlainString()).isEqualTo("32.1500");
        assertThat(response.history().get(0).midRate().toPlainString()).isEqualTo("29.8800");
        assertThat(requests).anyMatch(uri -> "/api/market-data/exchange-rate".equals(uri.getPath())
                && uri.getQuery().contains("currency=USD")
                && uri.getQuery().contains("start=2025-08-13")
                && uri.getQuery().contains("end=2026-08-13"));
        assertThat(requests).anyMatch(uri ->
                "/api/market-data/exchange-rate/usd-twd/live".equals(uri.getPath()));
    }

    @Test
    void responseDefensivelyCopiesHistory() {
        ArrayList<UsdTwdPublicDto.HistoryPoint> points = new ArrayList<>();
        points.add(new UsdTwdPublicDto.HistoryPoint(
                java.time.LocalDate.of(2026, 8, 13), java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE));
        UsdTwdPublicDto.Response response = new UsdTwdPublicDto.Response(
                "USD/TWD", "USD", "TWD", java.time.LocalDate.of(2025, 8, 13),
                java.time.LocalDate.of(2026, 8, 13), 2, "Asia/Taipei", "ACTIVE", null, 1, points);
        points.clear();
        assertThat(response.history()).hasSize(1);
        assertThatThrownBy(() -> response.history().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyHistoryOrLive404IsTypedNotFound() {
        assertThatThrownBy(() -> service(request -> request.url().getPath().endsWith("/live")
                ? jsonRaw(liveMega()) : json(List.of())).getUsdTwd().block())
                .isInstanceOf(UsdTwdUnavailableException.class);

        assertThatThrownBy(() -> service(request -> request.url().getPath().endsWith("/live")
                ? ClientResponse.create(HttpStatus.NOT_FOUND).build()
                : json(List.of(history("2026-08-13", "32.1", "32.2"))))
                .getUsdTwd().block()).isInstanceOf(UsdTwdUnavailableException.class);
    }

    @Test
    void transportHttpDecodeDuplicateOutOfRangeAndInvalidRatesFailClosed() {
        List<Function<org.springframework.web.reactive.function.client.ClientRequest, ClientResponse>> failures = List.of(
                request -> ClientResponse.create(HttpStatus.BAD_GATEWAY).build(),
                request -> ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{broken").build(),
                request -> request.url().getPath().endsWith("/live") ? jsonRaw(liveMega()) : json(List.of(
                        history("2026-08-13", "32.1", "32.2"),
                        history("2026-08-13", "32.1", "32.2"))),
                request -> request.url().getPath().endsWith("/live") ? jsonRaw(liveMega()) : json(List.of(
                        history("2025-08-12", "32.1", "32.2"))),
                request -> request.url().getPath().endsWith("/live") ? jsonRaw(liveMega()) : json(List.of(
                        history("2026-08-13", "0", "32.2"))));

        for (var failure : failures) {
            assertThatThrownBy(() -> service(failure).getUsdTwd().block())
                    .isInstanceOf(UsdTwdBadGatewayException.class);
        }
    }

    @Test
    void liveSourceTimestampAndStatusCombinationsFailClosed() {
        List<String> invalidLives = List.of(
                liveMega().replace("\"sourceUpdatedAt\":\"2026-08-13T15:10:12Z\"",
                        "\"sourceUpdatedAt\":null"),
                liveMega().replace("\"source\":\"MEGA_BANK\"", "\"source\":\"BAD\""),
                liveMega().replace("\"quoteStatus\":\"LIVE\"", "\"quoteStatus\":\"INDICATIVE\""),
                liveMega().replace("\"liveUpdateStatus\":\"ACTIVE\"", "\"liveUpdateStatus\":\"INACTIVE\""));
        for (String live : invalidLives) {
            assertThatThrownBy(() -> service(request -> request.url().getPath().endsWith("/live")
                    ? jsonRaw(live)
                    : json(List.of(history("2026-08-13", "32.1", "32.2"))))
                    .getUsdTwd().block()).isInstanceOf(UsdTwdBadGatewayException.class);
        }
    }

    private static UsdTwdPublicService service(
            Function<org.springframework.web.reactive.function.client.ClientRequest, ClientResponse> exchange) {
        WebClient client = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> reactor.core.publisher.Mono.just(exchange.apply(request)))
                .build();
        return new UsdTwdPublicService(client, CLOCK);
    }

    private static ClientResponse json(Object body) {
        try {
            return jsonRaw(MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ClientResponse jsonRaw(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private static String liveMega() {
        return "{\"pair\":\"USD/TWD\",\"baseCurrency\":\"USD\",\"quoteCurrency\":\"TWD\","
                + "\"date\":\"2026-08-13\",\"buyRate\":32.1000,\"sellRate\":32.2000,"
                + "\"source\":\"MEGA_BANK\",\"polledAt\":\"2026-08-13T15:10:14Z\","
                + "\"sourceUpdatedAt\":\"2026-08-13T15:10:12Z\","
                + "\"liveUpdateStatus\":\"ACTIVE\",\"quoteStatus\":\"LIVE\"}";
    }

    private static java.util.Map<String, Object> history(String date, String buy, String sell) {
        return java.util.Map.of(
                "rateDate", date,
                "buyRate", new java.math.BigDecimal(buy),
                "sellRate", new java.math.BigDecimal(sell));
    }
}
