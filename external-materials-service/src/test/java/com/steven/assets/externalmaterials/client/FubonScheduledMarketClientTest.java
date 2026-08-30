package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.externalmaterials.service.MarketClock;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
