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

        verify(ingest).ingest(eq("OPEN_QUOTES_LIST"), eq("即時報價清單"), contains("raw producer failure"),
                contains("raw producer failure"), eq(capturedAt));
    }

    @Test void captured_4xx_and_successful_204_and_unknown_404_produce_no_row() {
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        PublicApiErrorCaptureWebFilter filter = new PublicApiErrorCaptureWebFilter(ingest, new ApiErrorLogDiagnosticRenderer(""), Clock.fixed(NOW, ZoneOffset.UTC));
        assertNoRecord(filter, "/api/quotes", HttpStatus.BAD_REQUEST, true);
        assertNoRecord(filter, "/api/quotes/one", HttpStatus.NO_CONTENT, false);
        assertNoRecord(filter, "/api/not-catalogued", HttpStatus.NOT_FOUND, true);
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
