package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
import com.steven.assets.externalmaterials.service.MarketClock;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static com.steven.assets.externalmaterials.service.FubonMarketTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonScheduledMarketClientTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 28);
    private static final Instant NOW = Instant.parse("2026-08-28T05:40:05Z");
    private final MarketClock clock = mock(MarketClock.class);
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private FubonScheduledMarketClient client(int status) {
        when(clock.instant()).thenReturn(NOW);
        return new FubonScheduledMarketClient(new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> "fake-token"),
                clock, (uri, token, body, limit, timeout) -> {
            assertThat(uri.getHost()).isEqualTo("fake.invalid");
            assertThat(token).isEqualTo("fake-token");
            assertThat(timeout.toSeconds()).isEqualTo(30);
            assertThat(limit).isPositive();
            calls.incrementAndGet();
            return new FubonScheduledMarketClient.RawResponse(status, response.get().getBytes(StandardCharsets.UTF_8));
        });
    }
    @Test void typedTechnicalRetainsIndependentDatesSigned38DigitsAndNormalGroups() {
        var read = technical("2330", DAY, NOW.minusSeconds(1), "0");
        var old = read.groups().get("macd");
        read = group(read, "macd", new TechnicalGroup(old.status(), old.reason(), old.parameters(), DAY.minusDays(1), null, old.payload()));
        response.set(technicalJson(read).toString());
        var actual = client(200).technical("2330", DAY);
        assertThat(actual.groups().get("macd").sourceDate()).isEqualTo(DAY.minusDays(1));
        assertThat(actual.groups().get("macd").payload().get("macdLine")).isEqualTo("-12345678901234567890.123456789012345678");
        assertThat(actual.groups().get("kdj").payload().get("k")).isEqualTo("0");
        assertThat(calls).hasValue(1);
    }
    @Test void wrongGroupPrecisionOrParametersCannotPoisonHealthyPeerAndBudgetReasonsStayDistinct() {
        ObjectNode body = technicalJson(technical("2330", DAY, NOW.minusSeconds(1), "0"));
        ((ObjectNode)body.path("kdj").path("payload")).put("j", "1.0000000000000000001");
        ((ObjectNode)body.path("bb").path("parameters")).put("period", "20");
        response.set(body.toString());
        var read = client(200).technical("2330", DAY);
        assertThat(read.groups().get("kdj").status()).isEqualTo("SCHEMA_INVALID");
        assertThat(read.groups().get("bb").status()).isEqualTo("SCHEMA_INVALID");
        assertThat(read.groups().get("macd").available()).isTrue();
        response.set(technicalJson(group(technical("2330", DAY, NOW.minusSeconds(1), "0"), "bb",
                failure("bb", "HISTORY_BUDGET_EXHAUSTED"))).toString());
        assertThat(client(200).technical("2330", DAY).groups().get("bb").reason()).isEqualTo("HISTORY_BUDGET_EXHAUSTED");
    }

    @Test void officialHistoricalNotFoundIsAnEmptyNoDataWindow() {
        when(clock.instant()).thenReturn(NOW);
        response.set("{\"reason\":\"NO_DATA\"}");
        var client = new FubonScheduledMarketClient(
                new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> "fake-token"),
                clock, (uri, token, body, limit, timeout) -> {
                    calls.incrementAndGet();
                    return new FubonScheduledMarketClient.RawResponse(404, response.get().getBytes(StandardCharsets.UTF_8));
                });

        var daily = client.historicalDailyCandles("00719B", LocalDate.of(2016, 9, 30), LocalDate.of(2017, 9, 29));
        var minute = client.historicalIntradayCandles("00719B", LocalDate.of(2023, 5, 23), LocalDate.of(2023, 6, 22));

        assertThat(daily.status()).isEqualTo("NO_DATA");
        assertThat(daily.candles()).isEmpty();
        assertThat(minute.status()).isEqualTo("NO_DATA");
        assertThat(minute.candles()).isEmpty();
        assertThat(calls).hasValue(2);
    }
    @Test void identityFutureObservationDuplicateKeyAndExtraFieldRejectWholeEnvelope() {
        ObjectNode body = technicalJson(technical("9999", DAY, NOW.minusSeconds(1), "0"));
        response.set(body.toString());
        assertThatThrownBy(() -> client(200).technical("2330", DAY)).isInstanceOf(Unavailable.class);
        body.put("symbol", "2330").put("observedAt", NOW.plusSeconds(1).toString());
        response.set(body.toString());
        assertThatThrownBy(() -> client(200).technical("2330", DAY)).isInstanceOf(Unavailable.class);
        assertThatThrownBy(() -> FubonMarketJson.parse("{\"a\":1,\"a\":2}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FubonMarketJson.parse("{} {}")).isInstanceOf(IllegalArgumentException.class);
        body.put("observedAt", NOW.toString()).put("account", "fake-must-not-pass");
        response.set(body.toString());
        assertThatThrownBy(() -> client(200).technical("2330", DAY)).hasMessage("TECHNICAL_SCHEMA_INVALID");
    }

    @Test void historicalMinuteAcceptsCumulativeAverageOutsideBarAndInclusive1330() {
        String json = historicalMinute("2026-08-28T05:30:00.000000Z", "2026-08-27T13:30:00+08:00", "999", "AVAILABLE", null);
        var read = FubonMarketJson.historicalIntradayCandles(FubonMarketJson.parse(json), "2330", DAY.minusDays(1), DAY.minusDays(1), NOW);
        assertThat(read.candles()).hasSize(1);
        assertThat(read.candles().getFirst().average()).isEqualByComparingTo("999");
        assertThat(read.candles().getFirst().volume()).isEqualTo(123456L);
    }

    @Test void historicalMinuteRejectsBadSessionsWindowsOrderingAndUnknownFields() {
        assertThatThrownBy(() -> FubonMarketJson.historicalIntradayCandles(
                FubonMarketJson.parse(historicalMinute("2026-08-28T05:40:06.000000Z", "2026-08-27T13:30:00+08:00", "20", "AVAILABLE", null)),
                "2330", DAY.minusDays(1), DAY.minusDays(1), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FubonMarketJson.historicalIntradayCandles(
                FubonMarketJson.parse(historicalMinute("2026-08-28T05:30:00.000000Z", "2026-08-27T13:31:00+08:00", "20", "AVAILABLE", null)),
                "2330", DAY.minusDays(1), DAY.minusDays(1), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FubonMarketJson.historicalIntradayCandles(
                FubonMarketJson.parse(historicalMinute("2026-08-28T05:30:00.000000Z", "2026-08-27T13:30:00+08:00", "20", "AVAILABLE", "extra")),
                "2330", DAY.minusDays(1), DAY.minusDays(1), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FubonMarketJson.historicalIntradayCandles(
                FubonMarketJson.parse(historicalMinute("2026-08-28T05:30:00.000000Z", "2026-08-27T13:30:00+08:00", "20", "AVAILABLE", null)),
                "2330", DAY.minusDays(32), DAY.minusDays(1), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String historicalMinute(String observedAt, String timestamp, String average, String status, String extra) {
        String row = "{\"candleAt\":\"" + timestamp + "\",\"open\":\"10\",\"high\":\"11\",\"low\":\"9\",\"close\":\"10\",\"average\":\"" + average + "\",\"volume\":\"123456\"" + (extra == null ? "" : ",\"unexpected\":true") + "}";
        String rows = "AVAILABLE".equals(status) ? "[" + row + "]" : "[]";
        return "{\"schemaVersion\":1,\"symbol\":\"2330\",\"market\":\"台股\",\"provider\":\"FUBON_SDK\",\"queryFrom\":\"2026-08-27\",\"queryTo\":\"2026-08-27\",\"observedAt\":\"" + observedAt + "\",\"instrumentType\":\"EQUITY\",\"exchange\":\"TWSE\",\"sourceMarket\":null,\"timeframe\":\"1\",\"status\":\"" + status + "\",\"reason\":null,\"candles\":" + rows + "}";
    }
    @Test void disabledMakesZeroTokenReadsAndHttpAndConfigToStringNeverLeaks() {
        AtomicInteger tokens = new AtomicInteger();
        var state = new FubonMarketConfigState("false", "http://fake.invalid", "unused", p -> {
            tokens.incrementAndGet(); return "fake-secret";
        });
        var client = new FubonScheduledMarketClient(state, clock, (a,b,c,d,e) -> { throw new AssertionError("HTTP"); });
        assertThatThrownBy(() -> client.technical("2330", DAY)).hasMessage("DISABLED");
        assertThat(tokens).hasValue(0);
        var ready = new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> "fake-secret");
        assertThat(ready.snapshot().toString()).doesNotContain("fake-secret");
        assertThat(new FubonMarketConfigState("true", "http://fake.invalid/path", "unused", p -> "fake-secret").unavailableReason())
                .isEqualTo("MISCONFIGURED");
    }
    @Test void dividendBatchKeepsValidCashAndIsolatesOneMalformedSymbol() {
        response.set("""
                {"queryDate":"2026-08-28","observedAt":"2026-08-28T05:40:00Z","scopeFrom":"2025-10-12",
                 "scopeTo":"2026-10-12","provider":"FUBON_SDK","rows":[
                  {"symbol":"2330","status":"PARTIAL","usable":true,"reason":"STOCK_DIVIDEND_UNIT_UNVERIFIED","events":[
                    {"date":"2026-09-15","exchange":"TWSE","dividendType":"權息","year":2026,"cashDividend":"4.500000",
                     "stockDividend":null,"exDividendDate":"2026-09-15","exRightsDate":"2026-09-15",
                     "cashPaymentDate":null,"stockPaymentDate":null}]},
                  {"symbol":"0050","status":"PARTIAL","usable":true,"reason":null,"events":[]}]}
                """);
        var read = client(200).dividends(List.of("2330", "0050"), DAY);
        assertThat(read.rows().getFirst().events().getFirst().cashDividend()).isEqualByComparingTo("4.5");
        assertThat(read.rows().getFirst().events().getFirst().exRightsDate()).isEqualTo("2026-09-15");
        assertThat(read.rows().get(1).status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> client(200).dividends(List.of("2330", "2317"), DAY)).hasMessage("DIVIDEND_SCHEMA_INVALID");
        assertThatThrownBy(() -> client(200).dividends(List.of(), DAY)).hasMessage("INVALID_REQUEST");
    }
    @Test void real429StopsRunAndRawReasonNeverEscapes() {
        response.set("{\"secret\":\"fake-do-not-echo\"}");
        assertThatThrownBy(() -> client(429).technical("2330", DAY))
                .isInstanceOfSatisfying(Unavailable.class, e -> {
                    assertThat(e.reason()).isEqualTo("RATE_LIMITED");
                    assertThat(e.stopRun()).isTrue();
                    assertThat(e).hasMessageNotContaining("fake-do-not-echo");
        });
    }
    @Test void intradayTechnicalAloneUsesSeventySecondV1TransportTimeout() {
        when(clock.instant()).thenReturn(NOW);
        List<Duration> timeouts = new ArrayList<>();
        var client = new FubonScheduledMarketClient(
                new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> "fake-token"),
                clock,
                (uri, token, body, limit, timeout) -> {
                    timeouts.add(timeout);
                    return new FubonScheduledMarketClient.RawResponse(503, "{}".getBytes(StandardCharsets.UTF_8));
                });

        assertThatThrownBy(() -> client.basic("2330", DAY)).hasMessage("UPSTREAM_UNAVAILABLE");
        assertThatThrownBy(() -> client.intradayTechnical("2330", DAY)).hasMessage("UPSTREAM_UNAVAILABLE");

        assertThat(timeouts).containsExactly(Duration.ofSeconds(8), Duration.ofSeconds(70));
    }

    @Test void intradayTechnicalWaitsPastEightSecondsAndUsesExactRouteAndOperationIdentity() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst(FubonScheduledMarketClient.TOKEN_HEADER));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(8_500); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            byte[] response = "{\"reason\":\"UPSTREAM_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            when(clock.instant()).thenReturn(NOW);
            ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
            var config = new FubonMarketConfigState(
                    "true", "http://127.0.0.1:" + server.getAddress().getPort(), "unused", p -> "fake-token");
            var client = new FubonScheduledMarketClient(config, clock, writer);
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> client.intradayTechnical("2330", DAY)).hasMessage("UPSTREAM_UNAVAILABLE");

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt).toMillis()).isGreaterThanOrEqualTo(8_000);
            assertThat(method).hasValue("POST");
            assertThat(path).hasValue("/internal/market-data/intraday-technical-indicators/read");
            assertThat(token).hasValue("fake-token");
            assertThat(body).hasValue("{\"symbol\":\"2330\"}");
            verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), any(Throwable.class), eq(NOW));
            verifyNoMoreInteractions(writer);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
    @Test void task425ExactVolumeAndDailyContractsRejectWrongStatusAndPreserveCanonicalNumbers() {
        var volumes = FubonMarketJson.intradayVolumes(FubonMarketJson.parse("""
                {"schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK","sourceDate":"2026-08-28",
                 "observedAt":"2026-08-28T05:40:00.000000Z","instrumentType":"EQUITY","exchange":"TWSE","sourceMarket":"TSE",
                 "status":"OK","reason":null,"levels":[{"price":"950","volume":"123","bidVolume":"100","askVolume":null}]}
                """), "2330", DAY, NOW);
        assertThat(volumes.status()).isEqualTo("OK");
        assertThat(volumes.levels().getFirst().price()).isEqualByComparingTo("950");
        var daily = FubonMarketJson.historicalDailyCandles(FubonMarketJson.parse("""
                {"schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK","queryFrom":"2025-08-29","queryTo":"2026-08-28",
                 "observedAt":"2026-08-28T05:40:00.000000Z","instrumentType":"EQUITY","exchange":"TWSE","sourceMarket":"TSE",
                 "status":"OK","reason":null,"candles":[{"tradingDate":"2026-08-28","open":"950","high":"960","low":"945","close":"955","volume":"123","turnover":"117465","change":"5"}]}
                """), "2330", DAY.minusDays(364), DAY, NOW);
        assertThat(daily.candles().getFirst().close()).isEqualByComparingTo("955");
        assertThatThrownBy(() -> FubonMarketJson.intradayVolumes(FubonMarketJson.parse("""
                {"schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK","sourceDate":"2026-08-28",
                 "observedAt":"2026-08-28T05:40:00Z","instrumentType":"EQUITY","exchange":"TWSE","sourceMarket":"TSE",
                 "status":"OK","reason":null,"levels":[{"price":"950","volume":"123","bidVolume":"100","askVolume":null}]}
                """), "2330", DAY, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historicalDailyObservationIsReceiptTimeNotTheLastRequestedMarketDate() {
        LocalDate oldTo = DAY.minusDays(30), oldFrom = oldTo.minusDays(364);
        var daily = FubonMarketJson.historicalDailyCandles(FubonMarketJson.parse("""
                {"schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK","queryFrom":"2025-07-30","queryTo":"2026-07-29",
                 "observedAt":"2026-08-28T05:40:00.000000Z","instrumentType":"EQUITY","exchange":"TWSE","sourceMarket":"TSE",
                 "status":"OK","reason":null,"candles":[{"tradingDate":"2026-07-29","open":"950","high":"960","low":"945","close":"955","volume":"123","turnover":"117465","change":"5"}]}
                """), "2330", oldFrom, oldTo, NOW);
        assertThat(daily.observedAt()).isEqualTo(NOW.minusSeconds(5));
    }
    @Test void responseLimitCancelsSubscriptionBeforeAccumulatingOversizedBody() {
        var subscriber = new FubonScheduledMarketClient.LimitedBody(8);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[9])));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasRootCauseMessage("RESPONSE_TOO_LARGE");
    }
    @Test void stockEvidenceMustBeActualPositiveTradeWithConsistentMetadataAndEpochUnits() {
        Instant time = Instant.parse("2026-08-28T02:15:00Z");
        ObjectNode body = stock("2330", time);
        String id = "2330:" + body.get("tradeTimeMicros").longValue();
        assertThat(FubonMarketJson.stock(body, id, time.plusSeconds(1)).price()).isEqualByComparingTo("123.5");
        body.put("tradeSize", false);
        ObjectNode wrongType = body;
        assertThatThrownBy(() -> FubonMarketJson.stock(wrongType, id, time.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        body = stock("2330", time); body.put("tradeTimeMicros", time.toEpochMilli());
        ObjectNode wrongUnit = body;
        assertThatThrownBy(() -> FubonMarketJson.stock(wrongUnit, "2330:" + time.toEpochMilli(), time.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode conflicting = stock("2330", time).put("highPrice", "120");
        assertThatThrownBy(() -> FubonMarketJson.stock(conflicting, id, time.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FubonMarketJson.stock(stock("2330", time), id, Instant.parse("2026-08-28T05:30:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
