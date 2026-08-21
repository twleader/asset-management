package com.steven.assets.integration.fubon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FubonHttpClientTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsExactTokenAndTypedDryReadBodyThenParsesCanonicalResponse() {
        server.createContext("/internal/portfolio/read", exchange -> respond(exchange, 200, """
                {"batchId":"batch-1","queryDate":"2026-08-21","accountFingerprint":"abcdefabcdefabcd",
                 "emptyConfirmed":true,"positions":[],"reason":null,"counters":{"DRY_RUN":1}}
                """));
        FubonHttpClient client = client(FubonConfigState.State.READY, "shared-token");

        FubonDtos.CallResult<FubonDtos.PortfolioResponse> result = client.readPortfolio();

        assertThat(result.success()).isTrue();
        assertThat(result.body().emptyConfirmed()).isTrue();
        assertThat(result.body().counters()).containsEntry("DRY_RUN", 1L);
        assertThat(seenToken.get()).isEqualTo("shared-token");
        assertThat(seenBody.get()).isEqualTo("{\"dryRun\":true}");
    }

    @Test
    void invalidJsonAndHttpAuthAreTypedFailuresWithoutReturningBody() {
        server.createContext("/internal/market-data/tw-quotes", exchange -> respond(exchange, 200, "not-json"));
        FubonHttpClient client = client(FubonConfigState.State.READY, "shared-token");
        FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> invalid = client.readTwQuotes(java.util.List.of("2330"));
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.reason()).isEqualTo("TRANSPORT_OR_SCHEMA_FAILURE");
        assertThat(invalid.body()).isNull();

        server.removeContext("/internal/market-data/tw-quotes");
        server.createContext("/internal/market-data/tw-quotes", exchange -> respond(exchange, 403, "secret body"));
        FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> forbidden = client.readTwQuotes(java.util.List.of("2330"));
        assertThat(forbidden.success()).isFalse();
        assertThat(forbidden.reason()).isEqualTo("ADAPTER_AUTH_REJECTED");
        assertThat(forbidden.body()).isNull();
    }

    @Test
    void disabledAndMissingTokenAreLocalAndSendZeroHttp() {
        server.createContext("/internal/portfolio/read", exchange -> respond(exchange, 500, "must-not-call"));

        assertThat(client(FubonConfigState.State.DISABLED, null).readPortfolio().reason())
                .isEqualTo("DISABLED");
        assertThat(client(FubonConfigState.State.MISCONFIGURED, null).readPortfolio().reason())
                .isEqualTo("MISCONFIGURED");
        assertThat(requestCount).hasValue(0);
    }

    private FubonHttpClient client(FubonConfigState.State state, String token) {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, token, state.name()));
        return new FubonHttpClient(WebClient.builder().baseUrl(baseUrl).build(), config, Duration.ofSeconds(2));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        requestCount.incrementAndGet();
        seenToken.set(exchange.getRequestHeaders().getFirst(FubonHttpClient.TOKEN_HEADER));
        seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
