package com.steven.assets.bff.gdptwse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class PublicMarketIndexControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebTestClient client = controllerClient(request -> Mono.just(jsonResponse(List.of())));

    @Test
    void returnsImmutableRecordSchemaAndDefaultsWhenDataIsMissing() {
        client.get()
                .uri("/api/public/market-index")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.market").isEqualTo("TWSE")
                .jsonPath("$.marketLabel").isEqualTo("台股大盤")
                .jsonPath("$.range").isEqualTo("1y")
                .jsonPath("$.rangeLabel").isEqualTo("1 年")
                .jsonPath("$.mode").isEqualTo("DAILY")
                .jsonPath("$.labels.length()").isEqualTo(0)
                .jsonPath("$.closes.length()").isEqualTo(0)
                .jsonPath("$.ma5.length()").isEqualTo(0)
                .jsonPath("$.ma20.length()").isEqualTo(0)
                .jsonPath("$.ma60.length()").isEqualTo(0)
                .jsonPath("$.ma240.length()").isEqualTo(0)
                .jsonPath("$.volumes.length()").isEqualTo(0)
                .jsonPath("$.turnovers.length()").isEqualTo(0)
                .jsonPath("$.hasVolume").isEqualTo(false)
                .jsonPath("$.supportedMarkets.length()").isEqualTo(9)
                .jsonPath("$.supportedRanges.length()").isEqualTo(8);

        assertThat(MarketIndexChartDto.Response.class.isRecord()).isTrue();
    }

    @Test
    void invalidQueryReturnsProblemDetail400WithReceivedAndEveryLegalValue() {
        client.get()
                .uri(uri -> uri.path("/api/public/market-index")
                        .queryParam("market", "BAD")
                        .queryParam("range", "1y")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(value -> assertThat(value.toString())
                        .contains("BAD", "[TWSE, DJI, SPX, IXIC, SOX, FTSE, DAX, KOSPI, N225]"));

        client.get()
                .uri(uri -> uri.path("/api/public/market-index")
                        .queryParam("market", "TWSE")
                        .queryParam("range", "bad")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(value -> assertThat(value.toString())
                        .contains("bad", "[d, 1m, 3m, 6m, 1y, 2y, 5y, 10y]"));
    }

    @Test
    void malformedTradeValueIsPublic502ButLegacyCompleteEmpty200() {
        WebTestClient fixture = controllerClient(request -> Mono.just(jsonResponse(List.of(
                dailyRow("2026-08-11", "100.00", "not-a-number")))));

        fixture.get()
                .uri("/api/public/market-index?market=TWSE&range=1y")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.detail").value(value -> assertThat(value.toString())
                        .contains("tradeValue", "not-a-number"));

        assertLegacyDailyEmpty(fixture);
    }

    @Test
    void publicTurnoversAreExactJsonNumbersOrNull() {
        WebTestClient fixture = controllerClient(request -> Mono.just(jsonResponse(List.of(
                dailyRow("2026-08-10", "100.00", 0.1d),
                dailyRow("2026-08-11", "101.00", null)))));

        fixture.get()
                .uri("/api/public/market-index?market=TWSE&range=1m")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turnovers[0]").isEqualTo(0.1)
                .jsonPath("$.turnovers[1]").isEmpty();
    }

    @Test
    void transportHttpAndDecodeFailuresStayFailSoftOnlyAtFetchBoundary() {
        Map<String, Function<ClientRequest, Mono<ClientResponse>>> failures = new LinkedHashMap<>();
        failures.put("transport", request -> Mono.error(new IOException("connection refused")));
        failures.put("http", request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"unavailable\"}")
                .build()));
        failures.put("decode", request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{broken-json")
                .build()));

        failures.forEach((name, failure) -> {
            WebTestClient fixture = controllerClient(failure);
            assertPublicDailyEmpty(fixture);
            assertLegacyDailyEmpty(fixture);
        });
    }

    @Test
    void decodedInvalidClosePointPropagatesAs5xxAtBothEntrypoints() {
        WebTestClient fixture = controllerClient(request -> Mono.just(jsonResponse(List.of(
                dailyRow("2026-08-11", "not-a-close", "1000")))));

        fixture.get()
                .uri("/api/public/market-index?market=TWSE&range=1y")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectStatus().isEqualTo(500);

        fixture.get()
                .uri("/api/bff/gdp-twse/index-daily?market=TWSE&years=10")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectStatus().isEqualTo(500);
    }

    @Test
    void intradaySerializesLocalDateAsIsoAndLegacyKeepsStringShape() {
        List<Map<String, Object>> daily = List.of(dailyRow("2026-08-10", "100.00", "1000"));
        List<Map<String, Object>> intraday = List.of(
                intradayRow("2026-08-11T09:00:00", "101.00"),
                intradayRow("2026-08-11T13:30:00", "102.00"));
        WebTestClient fixture = controllerClient(request -> Mono.just(
                "/api/index-intraday".equals(request.url().getPath())
                        ? jsonResponse(intraday)
                        : jsonResponse(daily)));

        fixture.get()
                .uri("/api/public/market-index?market=TWSE&range=d")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tradingDate").isEqualTo("2026-08-11")
                .jsonPath("$.labels[0]").isEqualTo("09:00")
                .jsonPath("$.labels[1]").isEqualTo("13:30")
                .jsonPath("$.previousClose").isEqualTo(100.0)
                .jsonPath("$.change").isEqualTo(2.0)
                .jsonPath("$.volumes.length()").isEqualTo(2)
                .jsonPath("$.turnovers.length()").isEqualTo(2);

        fixture.get()
                .uri("/api/bff/gdp-twse/index-intraday?market=TWSE")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tradingDate").isEqualTo("2026-08-11")
                .jsonPath("$.times[0]").isEqualTo("09:00")
                .jsonPath("$.closes[1]").isEqualTo(102.0);
    }

    private static void assertPublicDailyEmpty(WebTestClient fixture) {
        fixture.get()
                .uri("/api/public/market-index?market=TWSE&range=1y")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.market").isEqualTo("TWSE")
                .jsonPath("$.range").isEqualTo("1y")
                .jsonPath("$.mode").isEqualTo("DAILY")
                .jsonPath("$.labels.length()").isEqualTo(0)
                .jsonPath("$.closes.length()").isEqualTo(0)
                .jsonPath("$.ma5.length()").isEqualTo(0)
                .jsonPath("$.ma20.length()").isEqualTo(0)
                .jsonPath("$.ma60.length()").isEqualTo(0)
                .jsonPath("$.ma240.length()").isEqualTo(0)
                .jsonPath("$.volumes.length()").isEqualTo(0)
                .jsonPath("$.turnovers.length()").isEqualTo(0)
                .jsonPath("$.hasVolume").isEqualTo(false)
                .consumeWith(result -> {
                    Map<String, Object> body = readBody(result.getResponseBody());
                    assertThat(body).containsKeys(
                            "tradingDate", "previousClose", "lastClose", "change", "changePercent");
                    assertThat(body.get("tradingDate")).isNull();
                    assertThat(body.get("previousClose")).isNull();
                    assertThat(body.get("lastClose")).isNull();
                    assertThat(body.get("change")).isNull();
                    assertThat(body.get("changePercent")).isNull();
                });
    }

    private static void assertLegacyDailyEmpty(WebTestClient fixture) {
        fixture.get()
                .uri("/api/bff/gdp-twse/index-daily?market=TWSE&years=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.dates.length()").isEqualTo(0)
                .jsonPath("$.closes.length()").isEqualTo(0)
                .jsonPath("$.ma5.length()").isEqualTo(0)
                .jsonPath("$.ma20.length()").isEqualTo(0)
                .jsonPath("$.ma60.length()").isEqualTo(0)
                .jsonPath("$.ma240.length()").isEqualTo(0)
                .jsonPath("$.volumes.length()").isEqualTo(0)
                .jsonPath("$.turnovers.length()").isEqualTo(0)
                .jsonPath("$.hasVolume").isEqualTo(false);
    }

    private static WebTestClient controllerClient(
            Function<ClientRequest, Mono<ClientResponse>> exchangeFunction) {
        WebClient downstream = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(exchangeFunction::apply)
                .build();
        MarketIndexChartService service = new MarketIndexChartService(downstream);
        return WebTestClient
                .bindToController(
                        new PublicMarketIndexController(service),
                        new GdpTwseBffController(downstream, service))
                .controllerAdvice(new PublicMarketIndexExceptionAdvice())
                .build();
    }

    private static ClientResponse jsonResponse(Object body) {
        try {
            return ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(OBJECT_MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBody(byte[] body) {
        try {
            return OBJECT_MAPPER.readValue(body, Map.class);
        } catch (IOException ex) {
            throw new AssertionError("回應不是合法 JSON", ex);
        }
    }

    private static Map<String, Object> dailyRow(String date, Object close, Object tradeValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tradingDate", date);
        row.put("closePoint", close);
        row.put("tradeVolume", 100L);
        row.put("tradeValue", tradeValue);
        return row;
    }

    private static Map<String, Object> intradayRow(String time, Object close) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", time);
        row.put("close", close);
        return row;
    }
}
