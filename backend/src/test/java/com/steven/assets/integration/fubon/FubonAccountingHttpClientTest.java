package com.steven.assets.integration.fubon;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real HTTP codec and method/header/body boundary, independent of Mockito DTO construction. */
class FubonAccountingHttpClientTest {
    private HttpServer server;
    private FubonHttpClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String response;
    private volatile int status = 200;
    private volatile String method;
    private volatile String token;
    private volatile byte[] requestBody;
    private final FubonConfigState config = mock(FubonConfigState.class);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/", exchange -> {
            calls.incrementAndGet();
            method = exchange.getRequestMethod();
            token = exchange.getRequestHeaders().getFirst(FubonHttpClient.TOKEN_HEADER);
            requestBody = exchange.getRequestBody().readAllBytes();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only-token", "READY"));
        client = new FubonHttpClient(WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                config, Duration.ofSeconds(2), CLOCK);
    }
    @AfterEach void stop() { server.stop(0); }

    @ParameterizedTest @ValueSource(strings = {"bank", "settlement", "realized"})
    void normalizedEnvelopesUseExactPostTokenAndNoBody(String endpoint) {
        response = valid(endpoint);
        assertThat(read(endpoint).success()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(method).isEqualTo("POST"); assertThat(token).isEqualTo("test-only-token");
        assertThat(requestBody).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"bank", "settlement", "realized"})
    void configDisabledAndMisconfiguredPerformZeroHttp(String endpoint) {
        for (var state : List.of(FubonConfigState.State.DISABLED, FubonConfigState.State.MISCONFIGURED)) {
            when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));
            assertThat(read(endpoint).reason()).isEqualTo(state.name());
        }
        assertThat(calls.get()).isZero();
    }
    @ParameterizedTest @ValueSource(ints = {401, 403, 400, 503})
    void remoteFailuresNeverEchoBody(int responseStatus) {
        status = responseStatus; response = "raw-account-token-sensitive";
        for (String endpoint : List.of("bank", "settlement", "realized")) {
            var result = read(endpoint);
            assertThat(result.success()).isFalse(); assertThat(result.body()).isNull();
            assertThat(result.reason()).doesNotContain("raw", "token", "sensitive");
            if (status == 401 || status == 403) assertThat(result.reason()).isEqualTo("ADAPTER_AUTH_REJECTED");
        }
    }
    @ParameterizedTest @ValueSource(strings = {"bank", "settlement", "realized"})
    void accountingRetainsFiniteDefaultCodecLimitDespiteLargeEtfClient(String endpoint) {
        response = valid(endpoint).replace(FINGERPRINT, "x".repeat(300 * 1024));
        var result = read(endpoint);
        assertThat(result.success()).isFalse(); assertThat(result.body()).isNull();
        assertThat(result.reason()).isEqualTo("TRANSPORT_OR_SCHEMA_FAILURE");
    }

    static Stream<String> invalidBank() {
        String bank = bankJson("100");
        return Stream.concat(Stream.of("0", "true", "null", "\"-1\"", "\"+1\"", "\"01\"", "\"1e3\"", "\"NaN\"", "\" 1\"",
                        "\"100000000000000000000\"", "\"0.00000000001\"", "\"1000000000000000000\"")
                .map(amount -> bank.replace("\"balance\":\"100\"", "\"balance\":" + amount)), Stream.of(
                bank.replace(FINGERPRINT, "abcd"), bank.replace(FINGERPRINT, FINGERPRINT.toUpperCase()),
                bank.replace("TWD", "USD"), bank.replace("2026-08-28", "2026-02-30"),
                bank.replace("2026-08-28T00:00:01Z", "2026-08-28T00:00:31Z"),
                bank.replace("2026-08-28T00:00:01Z", "2026-08-27T00:00:01Z"),
                bank.replace("2026-08-28T00:00:01Z", "2026-08-28T00:00:60Z"),
                bank.replace("2026-08-28T00:00:01Z", "2026-08-28T00:00:01+08:00"),
                bank.replace("\"queryDate\":\"2026-08-28\",", ""),
                bank.replace("\"currency\":\"TWD\"", "\"currency\":12"),
                bank.replace("\"availableBalance\":\"0\"", "\"availableBalance\":false"),
                bank.replace("{", "{\"account\":\"raw-sensitive\","),
                bank.replace("{", "{\"currency\":\"USD\","), bank + "{}"));
    }
    @ParameterizedTest @MethodSource("invalidBank")
    void malformedBankJsonFailsClosed(String json) {
        response = json; var result = client.readBankBalance();
        assertThat(result.success()).isFalse(); assertThat(result.body()).isNull();
        assertThat(result.reason()).doesNotContain("raw-sensitive");
    }
    @Test void explicitZeroBankBalanceIsValid() {
        response = bankJson("0"); assertThat(client.readBankBalance().body().balance().value()).isZero();
    }
    @Test void settlementNoDataHasAllNullableAmountsWithoutInventedZeros() {
        response = settlementJson(NO_DATA_ROW); var result = client.readSettlement();
        assertThat(result.success()).isTrue();
        assertThat(result.body().details().getFirst().status()).isEqualTo("NO_DATA_OBSERVED");
        assertThat(result.body().details().getFirst().totalSettlementAmount()).isNull();
    }
    static Stream<String> invalidSettlement() {
        return Stream.of(
                settlementJson(SETTLEMENT_ROW.replace("\"buyFee\":\"2\"", "\"buyFee\":null")),
                settlementJson(SETTLEMENT_ROW.replace("\"buySettlement\":\"-1002\"", "\"buySettlement\":\"1002\"")),
                settlementJson(SETTLEMENT_ROW.replace("\"sellSettlement\":\"0\"", "\"sellSettlement\":\"-1\"")),
                settlementJson(SETTLEMENT_ROW.replace("\"totalSettlementAmount\":\"-1002\"", "\"totalSettlementAmount\":\"-1000\"")),
                settlementJson(SETTLEMENT_ROW.replace("\"buyFee\":\"2\"", "\"buyFee\":\"2.0\"")),
                settlementJson(SETTLEMENT_ROW.replace("\"buyFee\":\"2\"", "\"buyFee\":true")),
                settlementJson(SETTLEMENT_ROW.replace("2026-09-01", "2026-08-27")),
                settlementJson(SETTLEMENT_ROW.replace("sourceQueryDate\":\"2026-08-28", "sourceQueryDate\":\"2026-08-29")),
                settlementJson(SETTLEMENT_ROW.replace("2026-09-01", "2026-08-28")),
                settlementJson(SETTLEMENT_ROW + "," + SETTLEMENT_ROW),
                settlementJson(NO_DATA_ROW.replace("\"buyFee\":null", "\"buyFee\":\"0\"")),
                settlementJson(NO_DATA_ROW.replace("\"currency\":null", "\"currency\":\"TWD\"")),
                settlementJson(SETTLEMENT_ROW).replace("UNVERIFIED", "VERIFIED"),
                "{\"details\":[]}");
    }
    @ParameterizedTest @MethodSource("invalidSettlement")
    void settlementCannotGuessSignsCoverageOrDateCompleteness(String json) {
        response = json; var result = client.readSettlement();
        assertThat(result.success()).isFalse(); assertThat(result.body()).isNull();
    }
    static Stream<String> invalidRealized() {
        return Stream.of(
                REALIZED_ROW.replace("\"orderType\":\"Stock\",", ""),
                REALIZED_ROW.replace("Stock", "DayTrade"), REALIZED_ROW.replace("Sell", "Buy"),
                REALIZED_ROW.replace("2330", "0000"), REALIZED_ROW.replace("2026-08-27", "2026-08-29"),
                REALIZED_ROW.replace("\"filledQty\":1000", "\"filledQty\":0"),
                REALIZED_ROW.replace("\"filledQty\":1000", "\"filledQty\":1000.0"),
                REALIZED_ROW.replace("\"filledQty\":1000", "\"filledQty\":\"1000\""),
                REALIZED_ROW.replace("\"filledQty\":1000", "\"filledQty\":true"),
                REALIZED_ROW.replace("\"filledQty\":1000", "\"filledQty\":10000000000"),
                REALIZED_ROW.replace("\"filledPrice\":\"123.5\"", "\"filledPrice\":\"0\""),
                REALIZED_ROW.replace("\"filledPrice\":\"123.5\"", "\"filledPrice\":123.5"),
                REALIZED_ROW.replace("\"realizedProfit\":\"0\"", "\"realizedProfit\":\"-1\""),
                REALIZED_ROW.replace("\"realizedProfit\":\"0\"", "\"realizedProfit\":\"1\""),
                REALIZED_ROW.replace("\"realizedLoss\":\"20\"", "\"realizedLoss\":\"20.0\""),
                REALIZED_ROW.replace("\"realizedLoss\":\"20\"", "\"realizedLoss\":false"))
                .map(FubonAccountingFixtures::realizedJson);
    }
    @ParameterizedTest @MethodSource("invalidRealized")
    void realizedRejectsMissingTypeFractionalQuantityAndInvalidAccounting(String json) {
        response = json; var result = client.readRealizedGains();
        assertThat(result.success()).isFalse(); assertThat(result.body()).isNull();
    }
    private String valid(String endpoint) {
        return switch (endpoint) {
            case "bank" -> bankJson("100");
            case "settlement" -> settlementJson(SETTLEMENT_ROW);
            case "realized" -> realizedJson(REALIZED_ROW);
            default -> throw new AssertionError(endpoint);
        };
    }
    private FubonDtos.CallResult<?> read(String endpoint) {
        return switch (endpoint) {
            case "bank" -> client.readBankBalance();
            case "settlement" -> client.readSettlement();
            case "realized" -> client.readRealizedGains();
            default -> throw new AssertionError(endpoint);
        };
    }
}
