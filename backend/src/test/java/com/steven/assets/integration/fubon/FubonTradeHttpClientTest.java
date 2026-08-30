package com.steven.assets.integration.fubon;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Synthetic loopback HTTP server exercises the real WebClient decoder, never a broker. */
class FubonTradeHttpClientTest {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    private static final String VALID = """
            {"batchId":"batch-1","startDate":"2026-08-28","endDate":"2026-08-28",
             "accountFingerprint":"0123456789abcdef01234567","emptyConfirmed":false,"trades":[
             {"stockCode":"2330","side":"Sell","filledQty":3,"filledPrice":"12.345",
              "filledAvgPrice":"12.345","filledDate":"2026-08-28","filledTime":"09:00:00","filledNo":"F-1"}]}
            """;
    private final AtomicReference<String> response = new AtomicReference<>(VALID);
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestToken = new AtomicReference<>();
    private HttpServer server;
    private FubonHttpClient client;

    @BeforeEach void startSyntheticServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/trades/read", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestToken.set(exchange.getRequestHeaders().getFirst(FubonHttpClient.TOKEN_HEADER));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "synthetic-token", "READY"));
        client = new FubonHttpClient(WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                config, Duration.ofSeconds(2));
    }

    @AfterEach void stopSyntheticServer() { if (server != null) server.stop(0); }

    @Test void completeResponseDecodesExactQuantityAndIsoDatesWithoutChangingRequestShape() {
        var result = client.readFilledTrades(DATE, DATE);
        assertThat(result.success()).isTrue();
        assertThat(result.body().startDate()).isEqualTo(DATE);
        var row = result.body().trades().getFirst();
        assertThat(row.filledDate()).isEqualTo(DATE);
        assertThat(row.filledQty()).isEqualTo(3);
        assertThat(row.filledAvgPrice().value()).isEqualByComparingTo("12.345");
        assertThat(requestBody.get()).isEqualTo("{\"startDate\":\"2026-08-28\",\"endDate\":\"2026-08-28\"}");
        assertThat(requestToken.get()).isEqualTo("synthetic-token");
    }

    @ParameterizedTest @MethodSource("malformedResponses")
    void malformedResponseCannotBeCoercedIntoAValidBatch(String json) {
        response.set(json);
        var result = client.readFilledTrades(DATE, DATE);
        assertThat(result.success()).isFalse();
        assertThat(result.body()).isNull();
        assertThat(result.reason()).isEqualTo("TRANSPORT_OR_SCHEMA_FAILURE");
    }

    static Stream<String> malformedResponses() {
        return Stream.of(
                "{", VALID + "{}", VALID.replace("\"batchId\":\"batch-1\",", ""),
                VALID.replace("\"batchId\":\"batch-1\"", "\"batchId\":123"),
                VALID.replace("\"batchId\":\"batch-1\"", "\"batchId\":null"),
                VALID.replace("\"startDate\":\"2026-08-28\"", "\"startDate\":[2026,8,28]"),
                VALID.replace("\"filledDate\":\"2026-08-28\"", "\"filledDate\":[2026,8,28]"),
                VALID.replace("\"filledDate\":\"2026-08-28\"", "\"filledDate\":\"2026-02-30\""),
                VALID.replace("\"filledDate\":\"2026-08-28\"", "\"filledDate\":\"20260828\""),
                VALID.replace("\"filledDate\":\"2026-08-28\"", "\"filledDate\":\"2026-8-28\""),
                VALID.replace("\"side\":\"Sell\"", "\"side\":\"Buy\",\"side\":\"Sell\""),
                VALID.replace("\"side\":\"Sell\"", "\"side\":\"Sell\",\"unrecognized\":true"),
                VALID.replace("\"emptyConfirmed\":false", "\"emptyConfirmed\":\"false\""),
                VALID.replace("\"emptyConfirmed\":false", "\"emptyConfirmed\":0"),
                VALID.replace("\"emptyConfirmed\":false", "\"emptyConfirmed\":null"),
                VALID.replace("\"emptyConfirmed\":false,", ""),
                VALID.replace("\"filledQty\":3", "\"filledQty\":3.0"),
                VALID.replace("\"filledQty\":3", "\"filledQty\":3.1"),
                VALID.replace("\"filledQty\":3", "\"filledQty\":\"3\""),
                VALID.replace("\"filledQty\":3", "\"filledQty\":true"),
                VALID.replace("\"filledQty\":3", "\"filledQty\":null"),
                VALID.replace("\"filledQty\":3,", ""),
                VALID.replace("\"filledQty\":3", "\"filledQty\":0"),
                VALID.replace("\"filledQty\":3", "\"filledQty\":10000000000"),
                VALID.replace("\"filledPrice\":\"12.345\"", "\"filledPrice\":12.345"),
                VALID.replace("\"filledPrice\":\"12.345\"", "\"filledPrice\":\"0\""),
                VALID.replace("\"filledPrice\":\"12.345\"", "\"filledPrice\":\"1e2\""),
                VALID.replace("\"filledAvgPrice\":\"12.345\",", ""),
                VALID.replace("\"filledNo\":\"F-1\"", "\"filledNo\":123"),
                VALID.replace("\"filledTime\":\"09:00:00\"", "\"filledTime\":null"));
    }
}
