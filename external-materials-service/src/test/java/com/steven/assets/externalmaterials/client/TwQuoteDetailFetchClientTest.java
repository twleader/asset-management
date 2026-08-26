package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwQuoteDetailFetchClientTest {

    private static final Instant NOW = Instant.parse("2026-08-26T03:05:00Z");

    @Test
    void matchingTwoExchangeOnTwUrlIsFoundWithStrictCompleteFiveLevels() {
        ScriptedTransport transport = new ScriptedTransport(response(200, html(data("TWO", "2330", true))));

        TwQuoteDetailFetchClient.YahooProbeOutcome outcome = client(transport).probeTw("2330");

        assertThat(outcome.kind()).isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.FOUND);
        assertThat(outcome.snapshot().source()).isEqualTo("YAHOO_TW");
        assertThat(outcome.snapshot().marketStatus()).isEqualTo("OPEN");
        assertThat(outcome.snapshot().levels()).hasSize(5);
        assertThat(transport.urls).containsExactly("https://tw.stock.yahoo.com/quote/2330.TW");
    }

    @Test
    void onlyHttp404IsStructuralMissAndCompatibilityFetchThenUsesTwo() {
        ScriptedTransport transport = new ScriptedTransport(response(404, "not found"), response(404, "not found"),
                response(200, html(data("TWO", "2330", true))));
        TwQuoteDetailFetchClient client = client(transport);

        assertThat(client.probeTw("2330").kind())
                .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.STRUCTURAL_MISS);
        assertThat(client.fetch("2330", "台股").available()).isTrue();
        assertThat(transport.urls).containsExactly(
                "https://tw.stock.yahoo.com/quote/2330.TW",
                "https://tw.stock.yahoo.com/quote/2330.TW",
                "https://tw.stock.yahoo.com/quote/2330.TWO");
    }

    @Test
    void non404AndAnyStrictValidationFailureAreTransientOrInvalid() {
        for (String malformed : List.of(
                data("TAI", "9999", true),
                data("TAI", "2330", false),
                data("TAI", "2330", true).replace("\"marketStatus\":\"open\"", "\"marketStatus\":\"closed\""),
                data("TAI", "2330", true).replace("\"bidVolK\":1", "\"bidVolK\":0"),
                data("TAI", "2330", true).replace("\"bid\":99", "\"bid\":100"),
                data("TAI", "2330", true).replace("2026-08-26T03:00:00Z", "2026-08-25T03:00:00Z"))) {
            ScriptedTransport transport = new ScriptedTransport(response(200, html(malformed)));
            assertThat(client(transport).probeTw("2330").kind())
                    .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.TRANSIENT_OR_INVALID);
        }
        assertThat(client(new ScriptedTransport(response(429, "busy"))).probeTw("2330").kind())
                .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.TRANSIENT_OR_INVALID);
    }

    @Test
    void bodyCapAcceptsTwoMibMinusOneAndRejectsTwoMibPlusOneWithoutParsing() {
        String valid = html(data("TAI", "2330", true));
        String under = valid + " ".repeat(TwQuoteDetailFetchClient.MAX_BODY_BYTES - valid.getBytes(StandardCharsets.UTF_8).length);
        String over = valid + " ".repeat(TwQuoteDetailFetchClient.MAX_BODY_BYTES + 1 - valid.getBytes(StandardCharsets.UTF_8).length);

        assertThat(client(new ScriptedTransport(response(200, under))).probeTw("2330").kind())
                .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.FOUND);
        assertThat(client(new ScriptedTransport(response(200, over))).probeTw("2330").kind())
                .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.TRANSIENT_OR_INVALID);
    }

    @Test
    void invalidCodeHasZeroOutboundCallsAndMarkerScannerRejectsDuplicateMarker() {
        ScriptedTransport transport = new ScriptedTransport();
        TwQuoteDetailFetchClient client = client(transport);

        assertThat(client.probeTw("AAPL").kind())
                .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.TRANSIENT_OR_INVALID);
        assertThat(transport.urls).isEmpty();
        assertThat(TwQuoteDetailFetchClient.extractQuoteData("x\"quote\":{\"data\":{} x\"quote\":{\"data\":{}"))
                .isNull();
    }

    @Test
    void slowJdkBodyReadReturnsAtDeadlineAndClosesWithoutWaitingForTheReaderThread() throws Exception {
        CloseUnblocksButInterruptDoesNotInputStream body = new CloseUnblocksButInterruptDoesNotInputStream();
        long started = System.nanoTime();

        assertThatThrownBy(() -> TwQuoteDetailFetchClient.JdkTransport.readAtMost(
                body, TwQuoteDetailFetchClient.MAX_BODY_BYTES, Duration.ofMillis(75)))
                .isInstanceOf(TimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(body.closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void timedOutPhysicalCallsRetainTheFourGlobalSlotsUntilTheyActuallyEnd() throws Exception {
        InterruptIgnoringTransport transport = new InterruptIgnoringTransport();
        TwQuoteDetailFetchClient client = new TwQuoteDetailFetchClient(transport, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(100));
        ExecutorService callers = Executors.newFixedThreadPool(5);
        List<Future<TwQuoteDetailFetchClient.YahooProbeOutcome>> outcomes = new ArrayList<>();
        try {
            long started = System.nanoTime();
            for (int index = 0; index < 5; index++) {
                outcomes.add(callers.submit(() -> client.probeTw("2330")));
            }
            assertThat(transport.firstFourStarted.await(1, TimeUnit.SECONDS)).isTrue();
            for (Future<TwQuoteDetailFetchClient.YahooProbeOutcome> outcome : outcomes) {
                assertThat(outcome.get(2, TimeUnit.SECONDS).kind())
                        .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.TRANSIENT_OR_INVALID);
            }

            // The fifth caller and a successor probe cannot turn a timed-out logical request into
            // a fifth physical request while the four interrupt-ignoring transports are still live.
            assertThat(transport.calls).hasValue(4);
            assertThat(transport.active).hasValue(4);
            assertThat(client.probeTw("2330").kind())
                    .isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.TRANSIENT_OR_INVALID);
            assertThat(transport.calls).hasValue(4);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

            transport.release.countDown();
            assertThat(transport.allPhysicalCallsEnded.await(2, TimeUnit.SECONDS)).isTrue();
            TwQuoteDetailFetchClient.YahooProbeOutcome recovered = null;
            long recoveryDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < recoveryDeadline) {
                recovered = client.probeTw("2330");
                if (recovered.kind() == TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.FOUND) break;
                Thread.yield();
            }
            assertThat(recovered).isNotNull();
            assertThat(recovered.kind()).isEqualTo(TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.FOUND);
            assertThat(transport.calls).hasValue(5);
        } finally {
            transport.release.countDown();
            callers.shutdownNow();
        }
    }

    private static TwQuoteDetailFetchClient client(ScriptedTransport transport) {
        return new TwQuoteDetailFetchClient(transport, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TwQuoteDetailFetchClient.RawResponse response(int status, String body) {
        return new TwQuoteDetailFetchClient.RawResponse(status, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static String html(String json) {
        return "root.App={undefined,\"quote\":{\"data\":" + json + "},tail:undefined}";
    }

    private static String data(String exchange, String code, boolean complete) {
        StringBuilder levels = new StringBuilder("[");
        int count = complete ? 5 : 4;
        for (int index = 0; index < count; index++) {
            if (index > 0) levels.append(',');
            levels.append("{\"bid\":").append(100 - index)
                    .append(",\"bidVolK\":").append(index + 1)
                    .append(",\"ask\":").append(101 + index)
                    .append(",\"askVolK\":").append(index + 11).append('}');
        }
        levels.append(']');
        return "{\"systexId\":\"" + code + "\",\"symbolName\":\"台積電\","
                + "\"currency\":\"TWD\",\"exchange\":\"" + exchange + "\","
                + "\"marketStatus\":\"open\",\"regularMarketTime\":\"2026-08-26T03:00:00Z\","
                + "\"price\":{\"raw\":100},\"regularMarketPreviousClose\":{\"raw\":99},"
                + "\"regularMarketOpen\":{\"raw\":99},\"regularMarketDayHigh\":{\"raw\":101},"
                + "\"regularMarketDayLow\":{\"raw\":98},\"orderbook\":" + levels + "}";
    }

    private static final class ScriptedTransport implements TwQuoteDetailFetchClient.Transport {
        private final ArrayDeque<TwQuoteDetailFetchClient.RawResponse> responses = new ArrayDeque<>();
        private final List<String> urls = new ArrayList<>();

        private ScriptedTransport(TwQuoteDetailFetchClient.RawResponse... values) {
            for (TwQuoteDetailFetchClient.RawResponse value : values) responses.add(value);
        }

        @Override
        public TwQuoteDetailFetchClient.RawResponse get(java.net.URI endpoint, java.time.Duration timeout) {
            urls.add(endpoint.toString());
            assertThat(timeout).isEqualTo(TwQuoteDetailFetchClient.PROBE_TIMEOUT);
            return responses.removeFirst();
        }
    }

    /** A hostile stream that proves the timeout path does not join a reader that ignores interrupt. */
    private static final class CloseUnblocksButInterruptDoesNotInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            while (closed.getCount() > 0) {
                try {
                    closed.await();
                } catch (InterruptedException ignored) {
                    // Deliberately remain blocked until close(); this models a stalled body reader.
                }
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /** A transport which remains physically active after FutureTask cancellation. */
    private static final class InterruptIgnoringTransport implements TwQuoteDetailFetchClient.Transport {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final CountDownLatch firstFourStarted = new CountDownLatch(4);
        private final CountDownLatch allPhysicalCallsEnded = new CountDownLatch(4);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public TwQuoteDetailFetchClient.RawResponse get(java.net.URI endpoint, Duration timeout) {
            calls.incrementAndGet();
            active.incrementAndGet();
            firstFourStarted.countDown();
            try {
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately keep the physical transport live after cancellation.
                    }
                }
                return response(200, html(data("TAI", "2330", true)));
            } finally {
                active.decrementAndGet();
                allPhysicalCallsEnded.countDown();
            }
        }
    }
}
