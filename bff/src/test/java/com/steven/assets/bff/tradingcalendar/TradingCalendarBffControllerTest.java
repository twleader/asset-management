package com.steven.assets.bff.tradingcalendar;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TradingCalendarBffControllerTest {

    private static final TradingCalendarYearWindow WINDOW = new TradingCalendarYearWindow(
            Clock.fixed(Instant.parse("2026-08-21T07:00:00Z"), ZoneOffset.UTC));

    @Test
    void outOfRangeReturns400WithoutAnyDownstreamRequest() {
        AtomicInteger calls = new AtomicInteger();
        WebClient downstream = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new AssertionError("範圍外年份不得呼叫 downstream"));
                }).build();

        ResponseEntity<Map<String, Object>> response =
                new TradingCalendarBffController(downstream, WINDOW).get(2024).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("minYear", 2025).containsEntry("maxYear", 2027);
        assertThat(response.getBody().get("availableYears")).isEqualTo(List.of(2025, 2026, 2027));
        assertThat(calls).hasValue(0);
    }

    @Test
    void omittedYearUsesTaipeiCurrentYearAndReturnsThreeYearWindowAndAvailability() {
        List<String> requests = new ArrayList<>();
        WebClient downstream = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    requests.add(request.url().toString());
                    if (request.url().getPath().endsWith("/holidays")) {
                        return json("{\"tw\":{\"2026-01-01\":\"元旦\"},"
                                + "\"us\":{\"2026-01-01\":\"New Year\"},"
                                + "\"uk\":{\"2026-01-01\":\"New Year\"}}");
                    }
                    return json("{\"twMarketOpen\":false}");
                }).build();

        ResponseEntity<Map<String, Object>> response =
                new TradingCalendarBffController(downstream, WINDOW).get(null).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("year", 2026)
                .containsEntry("minYear", 2025).containsEntry("maxYear", 2027);
        assertThat(response.getBody().get("availableYears")).isEqualTo(List.of(2025, 2026, 2027));
        assertThat(response.getBody().get("availability")).isEqualTo(Map.of(
                "tw", "AVAILABLE", "us", "AVAILABLE", "uk", "AVAILABLE"));
        assertThat(requests).hasSize(2)
                .anyMatch(uri -> uri.contains("/api/market-data/holidays?year=2026"))
                .anyMatch(uri -> uri.endsWith("/api/market-data/market-status"));
    }

    @Test
    void marketAvailabilityIsIndependentAndWholeHolidayFailureFailsClosed() {
        WebClient partial = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> request.url().getPath().endsWith("/holidays")
                        ? json("{\"tw\":{},\"us\":{\"2027-01-01\":\"New Year\"},\"uk\":{}}")
                        : json("{}"))
                .build();
        ResponseEntity<Map<String, Object>> partialResponse =
                new TradingCalendarBffController(partial, WINDOW).get(2027).block();
        assertThat(partialResponse.getBody().get("availability")).isEqualTo(Map.of(
                "tw", "UNAVAILABLE", "us", "AVAILABLE", "uk", "UNAVAILABLE"));

        WebClient failed = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> request.url().getPath().endsWith("/holidays")
                        ? Mono.error(new RuntimeException("downstream unavailable"))
                        : json("{}"))
                .build();
        ResponseEntity<Map<String, Object>> failedResponse =
                new TradingCalendarBffController(failed, WINDOW).get(2027).block();
        assertThat(failedResponse.getBody().get("holidays")).isEqualTo(Map.of());
        assertThat(failedResponse.getBody().get("availability")).isEqualTo(Map.of(
                "tw", "UNAVAILABLE", "us", "UNAVAILABLE", "uk", "UNAVAILABLE"));
    }

    @Test
    void exportForwardsOnlySubpathWithoutYearContract() {
        List<String> requests = new ArrayList<>();
        WebClient downstream = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> {
                    requests.add(request.url().toString());
                    return json("{\"years\":[2026,2027],\"results\":[]}");
                }).build();

        new TradingCalendarBffController(downstream, WINDOW).export("input/calendar").block();

        assertThat(requests).singleElement().satisfies(uri -> {
            assertThat(uri).contains("/api/trading-calendar-export/run", "subpath=input/calendar");
            assertThat(uri).doesNotContain("year=");
        });
    }

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
