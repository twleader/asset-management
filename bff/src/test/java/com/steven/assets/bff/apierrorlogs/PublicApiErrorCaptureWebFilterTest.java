package com.steven.assets.bff.apierrorlogs;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicApiErrorCaptureWebFilterTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Test void final_matched_5xx_uses_advice_captured_raw_throwable_and_its_boundary_time() {
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        ApiErrorLogDiagnosticRenderer renderer = new ApiErrorLogDiagnosticRenderer("");
        PublicApiErrorCaptureWebFilter filter = new PublicApiErrorCaptureWebFilter(ingest, renderer, Clock.fixed(NOW, ZoneOffset.UTC));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/quotes").build());
        RuntimeException raw = new RuntimeException("raw producer failure");
        Instant capturedAt = NOW.minusSeconds(9);
        exchange.getAttributes().put(PublicApiErrorCaptureWebFilter.CAPTURE_ATTRIBUTE,
                new PublicApiErrorCaptureWebFilter.Capture(raw, capturedAt));
        WebFilterChain chain = ignored -> { exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY); return Mono.empty(); };

        filter.filter(exchange, chain).block();

        // Task 421／Requirement 143: ingest(...) grew from 5 to 7 params (httpStatus, dedupeKey); a
        // filter-driven capture always passes the real final status and a null dedupeKey (it never
        // dedupes — every request is a distinct one-off event, unlike the gateway-log tailer).
        verify(ingest).ingest(eq("OPEN_QUOTES_LIST"), eq("即時報價清單"), contains("raw producer failure"),
                contains("raw producer failure"), eq(capturedAt), eq(HttpStatus.BAD_GATEWAY.value()), eq((String) null));
    }

    /**
     * Task 421／Requirement 143: this used to also cover a captured 4xx (`/api/quotes` + BAD_REQUEST +
     * capture=true) under the same {@code verifyNoInteractions(ingest)}. That sub-case now records (see
     * {@link #captured_4xx_on_matched_route_records_once_with_real_status()} below), so only the two
     * still-zero-row cases remain here: a successful 204 (no Throwable ever captured — the
     * `GET /api/quotes/one` contractual cache-miss) and a 404 on a path outside the 14-route allowlist
     * (the filter never even resolves an {@code Operation} for it).
     */
    @Test void successful_204_and_unknown_404_produce_no_row() {
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        PublicApiErrorCaptureWebFilter filter = new PublicApiErrorCaptureWebFilter(ingest, new ApiErrorLogDiagnosticRenderer(""), Clock.fixed(NOW, ZoneOffset.UTC));
        assertNoRecord(filter, "/api/quotes/one", HttpStatus.NO_CONTENT, false);
        assertNoRecord(filter, "/api/not-catalogued", HttpStatus.NOT_FOUND, true);
        verifyNoInteractions(ingest);
    }

    /** The sub-case moved out of the old combined zero-row test: a captured Throwable + final 4xx now records once with the real status. */
    @Test void captured_4xx_on_matched_route_records_once_with_real_status() {
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        PublicApiErrorCaptureWebFilter filter = new PublicApiErrorCaptureWebFilter(ingest, new ApiErrorLogDiagnosticRenderer(""), Clock.fixed(NOW, ZoneOffset.UTC));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/quotes").build());
        PublicApiErrorCaptureWebFilter.capture(exchange, new IllegalArgumentException("caller input"));

        filter.filter(exchange, ignored -> { exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST); return Mono.empty(); }).block();

        verify(ingest).ingest(eq("OPEN_QUOTES_LIST"), eq("即時報價清單"), anyString(), anyString(), any(Instant.class),
                eq(HttpStatus.BAD_REQUEST.value()), eq((String) null));
        verifyNoMoreInteractions(ingest);
    }

    /**
     * Proves the 4xx branch actually checks "was a Throwable captured", not just "is this a 4xx".
     * A controller that responds with a 4xx non-exceptionally (never calling
     * {@link PublicApiErrorCaptureWebFilter#capture(org.springframework.web.server.ServerWebExchange, Throwable)})
     * must remain zero-row even on a matched route.
     */
    @Test void uncaptured_4xx_from_controller_produces_no_row() {
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        PublicApiErrorCaptureWebFilter filter = new PublicApiErrorCaptureWebFilter(ingest, new ApiErrorLogDiagnosticRenderer(""), Clock.fixed(NOW, ZoneOffset.UTC));
        assertNoRecord(filter, "/api/quotes", HttpStatus.BAD_REQUEST, false);
        verifyNoInteractions(ingest);
    }

    @Test void unhandled_error_is_only_captured_until_the_framework_decides_the_final_status() {
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        PublicApiErrorCaptureWebFilter filter = new PublicApiErrorCaptureWebFilter(ingest,
                new ApiErrorLogDiagnosticRenderer(""), Clock.fixed(NOW, ZoneOffset.UTC));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/quotes").build());

        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class, () ->
                filter.filter(exchange, ignored -> Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST))).block());

        verifyNoInteractions(ingest);
    }

    private static void assertNoRecord(PublicApiErrorCaptureWebFilter filter, String path, HttpStatus status, boolean capture) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        if (capture) PublicApiErrorCaptureWebFilter.capture(exchange, new IllegalArgumentException("caller input"));
        filter.filter(exchange, ignored -> { exchange.getResponse().setStatusCode(status); return Mono.empty(); }).block();
    }
}
