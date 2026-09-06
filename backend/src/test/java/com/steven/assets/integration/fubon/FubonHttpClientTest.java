package com.steven.assets.integration.fubon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.apierrorlog.ApiErrorLogRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void readBankBalanceSendsNoBodyAndAcceptsZeroAvailableBalance() {
        server.createContext("/internal/bank-balance/read", exchange -> respond(exchange, 200,
                FubonAccountingFixtures.bankJson("123456")));
        var result = client(FubonConfigState.State.READY, "shared-token").readBankBalance();
        assertThat(result.success()).isTrue();
        assertThat(result.body().queryDate()).isEqualTo(FubonAccountingFixtures.DATE);
        assertThat(result.body().accountFingerprint()).hasSize(24);
        assertThat(result.body().balance().value()).isEqualByComparingTo("123456");
        assertThat(result.body().availableBalance().value()).isZero();
        assertThat(seenToken.get()).isEqualTo("shared-token");
        assertThat(seenBody.get()).isEmpty();
    }

    @Test
    void readSettlementSendsNoBodyAndParsesNegativePayableAndZeroReceivable() {
        server.createContext("/internal/settlement/read", exchange -> respond(exchange, 200,
                FubonAccountingFixtures.settlementJson(FubonAccountingFixtures.SETTLEMENT_ROW)));
        var result = client(FubonConfigState.State.READY, "shared-token").readSettlement();
        assertThat(result.success()).isTrue();
        assertThat(result.body().coverageStatus()).isEqualTo("SDK_RANGE_3D_RETURNED_ROWS");
        assertThat(result.body().details()).hasSize(1);
        assertThat(result.body().details().getFirst().buySettlement().value()).isEqualByComparingTo("-1002");
        assertThat(result.body().details().getFirst().sellSettlement().value()).isZero();
        assertThat(seenToken.get()).isEqualTo("shared-token");
        assertThat(seenBody.get()).isEmpty();
    }

    @Test
    void readSettlementParsesLegalEmptyDetailsArray() {
        server.createContext("/internal/settlement/read", exchange -> respond(exchange, 200,
                FubonAccountingFixtures.settlementJson("")));
        var result = client(FubonConfigState.State.READY, "shared-token").readSettlement();
        assertThat(result.success()).isTrue();
        assertThat(result.body().details()).isEmpty();
    }

    @Test
    void readRealizedGainsSendsNoBodyAndParsesZeroProfitAndTypedSourceDate() {
        server.createContext("/internal/realized-gains/read", exchange -> respond(exchange, 200,
                FubonAccountingFixtures.realizedJson(FubonAccountingFixtures.REALIZED_ROW)));
        var result = client(FubonConfigState.State.READY, "shared-token").readRealizedGains();
        assertThat(result.success()).isTrue();
        assertThat(result.body().rows()).hasSize(1);
        var row = result.body().rows().getFirst();
        assertThat(row.filledPrice().value()).isEqualByComparingTo("123.5");
        assertThat(row.realizedProfit().value()).isZero();
        assertThat(row.realizedLoss().value()).isEqualByComparingTo("20");
        assertThat(row.sourceDate()).isEqualTo(FubonAccountingFixtures.DATE.minusDays(1));
        assertThat(seenToken.get()).isEqualTo("shared-token");
        assertThat(seenBody.get()).isEmpty();
    }

    @Test
    void readRealizedGainsParsesLegalEmptyRowsArray() {
        server.createContext("/internal/realized-gains/read", exchange -> respond(exchange, 200,
                FubonAccountingFixtures.realizedJson("")));
        var result = client(FubonConfigState.State.READY, "shared-token").readRealizedGains();
        assertThat(result.success()).isTrue();
        assertThat(result.body().rows()).isEmpty();
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

    @Test
    void invalidLocalInputDoesNotStartBrokerIoOrCreateAnApiErrorLog() {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "shared-token", "READY"));
        ApiErrorLogRecorder recorder = mock(ApiErrorLogRecorder.class);
        WebClient noIo = WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("invalid local input must not start broker I/O");
        }).build();
        FubonHttpClient client = new FubonHttpClient(noIo, config, Duration.ofSeconds(2), FubonAccountingFixtures.CLOCK, recorder);

        assertThat(client.readTwQuotes(null).reason()).isEqualTo("INVALID_REQUEST");
        assertThat(client.readEtfHoldings(java.util.Arrays.asList("0050", null)).reason()).isEqualTo("INVALID_REQUEST");
        assertThat(client.readFilledTrades(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 6)).reason()).isEqualTo("INVALID_REQUEST");

        verifyNoInteractions(recorder);
    }

    private FubonHttpClient client(FubonConfigState.State state, String token) {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, token, state.name()));
        return new FubonHttpClient(WebClient.builder().baseUrl(baseUrl).build(), config, Duration.ofSeconds(2), FubonAccountingFixtures.CLOCK);
    }

    @Test
    void etfEndpointUsesExactTokenAndPreservesNormalizedStringEnvelope() {
        server.createContext("/internal/market-data/etf-holdings", exchange -> respond(exchange, 200, """
                {"batchId":"batch","holdings":[{"stockCode":"0050","status":"SUCCESS","reason":null,
                "rawResponseJson":"{\\"schemaVersion\\":1,\\"stockCode\\":\\"0050\\",\\"sourceDate\\":null,\\"holdings\\":[]}"}],"counters":{}}
                """));
        var result = client(FubonConfigState.State.READY, "shared-token").readEtfHoldings(java.util.List.of("0050"));
        assertThat(result.success()).isTrue();
        assertThat(result.body().holdings().getFirst().rawResponseJson()).contains("\"schemaVersion\":1");
        assertThat(seenToken.get()).isEqualTo("shared-token");
        assertThat(seenBody.get()).isEqualTo("{\"codes\":[\"0050\"]}");
    }

    @Test
    void etfDisabledMisconfiguredMalformedAnd503AreTypedFailures() {
        server.createContext("/internal/market-data/etf-holdings", exchange -> respond(exchange, 200, "not-json"));
        assertThat(client(FubonConfigState.State.DISABLED, null).readEtfHoldings(java.util.List.of("0050")).reason()).isEqualTo("DISABLED");
        assertThat(client(FubonConfigState.State.MISCONFIGURED, null).readEtfHoldings(java.util.List.of("0050")).reason()).isEqualTo("MISCONFIGURED");
        assertThat(requestCount).hasValue(0);
        assertThat(client(FubonConfigState.State.READY, "token").readEtfHoldings(java.util.List.of("0050")).reason()).isEqualTo("TRANSPORT_OR_SCHEMA_FAILURE");
        server.removeContext("/internal/market-data/etf-holdings");
        server.createContext("/internal/market-data/etf-holdings", exchange -> respond(exchange, 503, "secret-provider-body"));
        var result = client(FubonConfigState.State.READY, "token").readEtfHoldings(java.util.List.of("0050"));
        assertThat(result.reason()).isEqualTo("ADAPTER_5XX");
        assertThat(result.body()).isNull();
    }

    @Test
    void etfTimeoutIsBoundedAndDoesNotExposeException() {
        var config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "token", null));
        var neverResponds = WebClient.builder().exchangeFunction(request -> reactor.core.publisher.Mono.never()).build();
        var result = new FubonHttpClient(neverResponds, config, Duration.ofMillis(10)).readEtfHoldings(java.util.List.of("0050"));
        assertThat(result.reason()).isEqualTo("TRANSPORT_OR_SCHEMA_FAILURE");
        assertThat(result.body()).isNull();
    }

    @Test
    void fullFiftyEtfBatchLargerThanDefaultCodecLimitRemainsReadable() throws Exception {
        List<String> codes = IntStream.rangeClosed(1, 50).mapToObj(i -> String.format("00%03d", i)).toList();
        String constituents = IntStream.rangeClosed(1, 100).mapToObj(i ->
                "{\"stockCode\":\"" + (2000 + i) + "\",\"stockName\":\"成分公司股份有限公司\",\"weight\":\"1\",\"shares\":\"530358242\"}")
                .collect(java.util.stream.Collectors.joining(","));
        var rows = codes.stream().map(code -> new FubonDtos.EtfHoldingsItem(code, "SUCCESS", null,
                "{\"schemaVersion\":1,\"stockCode\":\"" + code + "\",\"sourceDate\":\"2026-08-27\",\"holdings\":[" + constituents + "]}"))
                .toList();
        String body = new ObjectMapper().writeValueAsString(new FubonDtos.EtfHoldingsBatchResponse("batch", rows));
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(256 * 1024).isLessThan(FubonHttpClient.ETF_MAX_RESPONSE_BYTES);
        server.createContext("/internal/market-data/etf-holdings", exchange -> respond(exchange, 200, body));
        var result = client(FubonConfigState.State.READY, "token").readEtfHoldings(codes);
        assertThat(result.success()).isTrue();
        assertThat(result.body().holdings()).hasSize(50);
        assertThat(FubonEtfHoldingsParser.parse(codes.getFirst(), result.body().holdings().getFirst().rawResponseJson())
                .orElseThrow().holdings()).hasSize(100);
    }

    @Test
    void etfResponseOverItsFiniteLimitFailsClosedWithoutReturningPartialRows() {
        String body = "{\"batchId\":\"" + "x".repeat(FubonHttpClient.ETF_MAX_RESPONSE_BYTES) + "\",\"holdings\":[]}";
        server.createContext("/internal/market-data/etf-holdings", exchange -> respond(exchange, 200, body));
        var result = client(FubonConfigState.State.READY, "token").readEtfHoldings(List.of("0050"));
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("TRANSPORT_OR_SCHEMA_FAILURE");
        assertThat(result.body()).isNull();
    }

    @Test
    void etfCodecLimitDoesNotBroadenPortfolioResponseLimit() {
        String body = "{\"batchId\":\"" + "x".repeat(300 * 1024) + "\",\"positions\":[]}";
        server.createContext("/internal/portfolio/read", exchange -> respond(exchange, 200, body));
        var result = client(FubonConfigState.State.READY, "token").readPortfolio();
        assertThat(result.success()).isFalse();
        assertThat(result.body()).isNull();
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
