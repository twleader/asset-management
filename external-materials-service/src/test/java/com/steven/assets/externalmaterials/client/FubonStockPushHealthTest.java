package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.model.marketdata.FubonMarketJson;
import com.steven.assets.externalmaterials.service.MarketClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonStockPushHealthTest {
    private static String status(long epoch, long scope, boolean ready) {
        return "{\"schemaVersion\":1,\"connectionEpoch\":" + epoch + ",\"scopeVersion\":" + scope
                + ",\"desiredSymbols\":[\"2330\"],\"confirmedSymbols\":[\"2330\"],\"ready\":" + ready + "}";
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    private static final class Body extends InputStream {
        private final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();
        volatile boolean closed;
        void frame(String frame) { for (byte value : frame.getBytes(StandardCharsets.UTF_8)) bytes.add(value & 255); }
        void error() { bytes.add(-2); }
        void status(String value) { frame("event: stock-status\ndata: " + value + "\n\n"); }
        @Override public int read() throws IOException {
            try { int value = bytes.take(); if (value == -2) throw new IOException("TEST_READ_FAILURE"); return value; } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            int first = read(); if (first == -1) return -1;
            target[offset] = (byte) first; int count = 1;
            while (count < length) {
                Integer value = bytes.poll(); if (value == null) break;
                if (value < 0) { bytes.add(value); break; }
                target[offset + count++] = (byte) value.intValue();
            }
            return count;
        }
        @Override public void close() { closed = true; bytes.add(-1); }
    }
    private FubonStockPushStreamClient client(Body body, AtomicLong time) {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-28T02:15:01Z"));
        var config = new FubonMarketConfigState("true", "http://fake.invalid", "unused", path -> "fake-token");
        return new FubonStockPushStreamClient("true", config, clock,
                (uri, token) -> new FubonStockPushStreamClient.Response(200, body, "text/event-stream"),
                delay -> new CountDownLatch(1).await(), null, time::get);
    }
    @Test void connectionAloneIsNotReadyAndFreshStatusIsExactScopeLeased() throws Exception {
        Body body = new Body(); AtomicLong time = new AtomicLong(); var stream = client(body, time);
        try {
            stream.start(event -> fail("No quote expected"));
            await(() -> "CONNECTED".equals(stream.state()));
            assertThat(stream.isReadyFor(Set.of("2330"))).isFalse();
            assertThat(stream.recoveryIdentity()).isNull();
            body.status(status(1, 0, true));
            await(() -> stream.isReadyFor(Set.of("2330")));
            assertThat(stream.isReadyFor(Set.of("0050"))).isFalse();
            assertThat(stream.recoveryIdentity()).isEqualTo("1:1");
            time.set(TimeUnit.SECONDS.toNanos(14));
            assertThat(stream.isReadyFor(Set.of("2330"))).isTrue();
            time.set(TimeUnit.SECONDS.toNanos(15));
            assertThat(stream.isReadyFor(Set.of("2330"))).isFalse();
            assertThat(stream.recoveryIdentity()).isNull();
            stream.checkIdle();
            assertThat(body.closed).isTrue();
            assertThat(stream.state()).isEqualTo("RECONNECTING");
        } finally { stream.stop(); }
    }
    @Test void periodicStatusKeepsQuietMarketReadyAndExplicitUnreadyRevokesIt() throws Exception {
        Body body = new Body(); AtomicLong time = new AtomicLong(); var stream = client(body, time);
        try {
            stream.start(event -> fail("No quote expected"));
            body.status(status(3, 2, true)); await(() -> stream.isReadyFor(Set.of("2330")));
            time.set(TimeUnit.SECONDS.toNanos(10)); body.status(status(3, 2, false));
            await(() -> !stream.isReadyFor(Set.of("2330")));
            time.set(TimeUnit.SECONDS.toNanos(20)); body.status(status(3, 2, true));
            await(() -> stream.isReadyFor(Set.of("2330")));
            time.set(TimeUnit.SECONDS.toNanos(30));
            assertThat(stream.isReadyFor(Set.of("2330"))).isTrue();
            stream.stop(); assertThat(stream.isReadyFor(Set.of("2330"))).isFalse();
            assertThat(stream.recoveryIdentity()).isNull();
        } finally { stream.stop(); }
    }
    @Test void eofAndMalformedQuoteImmediatelyRevokePriorHealth() throws Exception {
        for (boolean eof : new boolean[]{false, true}) {
            Body body = new Body(); AtomicLong time = new AtomicLong(); var stream = client(body, time);
            try {
                stream.start(event -> {}); body.status(status(1, 0, true));
                await(() -> stream.isReadyFor(Set.of("2330")));
                assertThat(stream.recoveryIdentity()).isEqualTo("1:1");
                if (eof) body.close(); else body.frame("event: stock-price\nid: 2330:1\ndata: {}\n\n");
                await(() -> !stream.isReadyFor(Set.of("2330")));
                await(() -> (eof ? "RECONNECTING" : "INVALID_EVENT").equals(stream.state()));
                assertThat(stream.recoveryIdentity()).isNull();
            } finally { stream.stop(); }
        }
    }
    @Test void malformedOrRegressingStatusClosesBodyAndRevokesReadiness() throws Exception {
        for (String bad : new String[]{status(1, 2, true), status(2, 0, true), status(2, 3, true).replace("[\"2330\"]", "[\"2330\",\"0050\"]"),
                status(2, 3, true).replace("\"ready\":true", "\"ready\":true,\"other\":0")}) {
            Body body = new Body(); var stream = client(body, new AtomicLong());
            try {
                stream.start(event -> {}); body.status(status(2, 2, true));
                await(() -> stream.isReadyFor(Set.of("2330")));
                body.status(bad);
                await(() -> !stream.isReadyFor(Set.of("2330")) && body.closed);
                assertThat(stream.state()).isEqualTo("RECONNECTING");
            } finally { stream.stop(); }
        }
    }
    @Test void stoppedOldBodyCannotInvalidateNewGeneration() throws Exception {
        CountDownLatch releaseOld = new CountDownLatch(1), oldReading = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        InputStream old = new InputStream() {
            @Override public int read() {
                oldReading.countDown();
                boolean released = false;
                while (!released) { try { releaseOld.await(); released = true; } catch (InterruptedException ignored) { } }
                return -1;
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        Body fresh = new Body(); AtomicLong time = new AtomicLong();
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-28T02:15:01Z"));
        var config = new FubonMarketConfigState("true", "http://fake.invalid", "unused", path -> "fake-token");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var stream = new FubonStockPushStreamClient("true", config, clock,
                (uri, token) -> new FubonStockPushStreamClient.Response(200, calls.incrementAndGet() == 1 ? old : fresh, "text/event-stream"),
                delay -> new CountDownLatch(1).await(), null, time::get);
        try {
            stream.start(event -> {}); assertThat(oldReading.await(2, TimeUnit.SECONDS)).isTrue();
            stream.stop(); stream.start(event -> {});
            fresh.status(status(1, 0, true)); await(() -> stream.isReadyFor(Set.of("2330")));
            assertThat(stream.recoveryIdentity()).isEqualTo("2:1");
            releaseOld.countDown(); await(() -> closes.get() >= 2);
            assertThat(stream.isReadyFor(Set.of("2330"))).isTrue();
            assertThat(fresh.closed).isFalse();
        } finally { releaseOld.countDown(); stream.stop(); }
    }
    @Test void statusMustHaveNoIdAndPendingAckIsNeverReady() throws Exception {
        Body body = new Body(); var stream = client(body, new AtomicLong());
        try {
            stream.start(event -> {});
            body.status(status(1, 0, false).replace("\"confirmedSymbols\":[\"2330\"]", "\"confirmedSymbols\":[]"));
            await(() -> "CONNECTED".equals(stream.state()));
            assertThat(stream.isReadyFor(Set.of("2330"))).isFalse();
            body.status(status(1, 0, true)); await(() -> stream.isReadyFor(Set.of("2330")));
            body.frame("event: stock-status\nid: forbidden\ndata: " + status(1, 0, true) + "\n\n");
            await(() -> !stream.isReadyFor(Set.of("2330")) && body.closed);
        } finally { stream.stop(); }
    }
    @Test void recoveryIdentityChangesOnSdkEpochAndSseBodyEvenWhenEpochIsReused() throws Exception {
        Body first = new Body(), second = new Body();
        AtomicLong time = new AtomicLong();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-28T02:15:01Z"));
        var config = new FubonMarketConfigState("true", "http://fake.invalid", "unused", path -> "fake-token");
        var stream = new FubonStockPushStreamClient("true", config, clock,
                (uri, token) -> new FubonStockPushStreamClient.Response(200, calls.incrementAndGet() == 1 ? first : second, "text/event-stream"),
                delay -> {}, null, time::get);
        try {
            stream.start(event -> {}); first.status(status(1, 0, true));
            await(() -> "1:1".equals(stream.recoveryIdentity()));
            first.status(status(2, 0, true)); await(() -> "1:2".equals(stream.recoveryIdentity()));
            first.close(); await(() -> calls.get() == 2);
            assertThat(stream.recoveryIdentity()).isNull();
            second.status(status(1, 0, true)); await(() -> "2:1".equals(stream.recoveryIdentity()));
            assertThat(stream.isReadyFor(Set.of("2330"))).isTrue();
        } finally { stream.stop(); }
    }
    @Test void readErrorAndRejectedReconnectHaveNoRecoveryIdentity() throws Exception {
        for (boolean rejected : new boolean[]{false, true}) {
            Body first = new Body();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch settled = new CountDownLatch(1);
            MarketClock clock = mock(MarketClock.class);
            when(clock.instant()).thenReturn(Instant.parse("2026-08-28T02:15:01Z"));
            var config = new FubonMarketConfigState("true", "http://fake.invalid", "unused", path -> "fake-token");
            var stream = new FubonStockPushStreamClient("true", config, clock,
                    (uri, token) -> calls.incrementAndGet() == 1
                            ? new FubonStockPushStreamClient.Response(200, first, "text/event-stream")
                            : new FubonStockPushStreamClient.Response(503, new ByteArrayInputStream(new byte[0]), "text/event-stream"),
                    delay -> {
                        if (!rejected || calls.get() > 1) { settled.countDown(); new CountDownLatch(1).await(); }
                    }, null, () -> 0L);
            try {
                stream.start(event -> {}); first.status(status(1, 0, true));
                await(() -> stream.isReadyFor(Set.of("2330")));
                if (rejected) first.close(); else first.error();
                assertThat(settled.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(stream.recoveryIdentity()).isNull();
                assertThat(stream.isReadyFor(Set.of("2330"))).isFalse();
                assertThat(stream.state()).isEqualTo("RECONNECTING");
            } finally { stream.stop(); }
        }
    }
    @Test void statusSchemaRejectsOverflowWrongTypesUnsortedDuplicatesAndIncompleteReady() {
        String good = status(1, 0, true);
        assertThat(FubonMarketJson.stockStatus(FubonMarketJson.parse(good)).ready()).isTrue();
        for (String bad : new String[]{good.replace("\"connectionEpoch\":1", "\"connectionEpoch\":9223372036854775808"),
                good.replace("\"scopeVersion\":0", "\"scopeVersion\":-1"),
                good.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
                good.replace("\"ready\":true", "\"ready\":1"),
                good.replace("[\"2330\"]", "[\"2330\",\"2330\"]"),
                good.replace("\"confirmedSymbols\":[\"2330\"]", "\"confirmedSymbols\":[]")}) {
            assertThatThrownBy(() -> FubonMarketJson.stockStatus(FubonMarketJson.parse(bad))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
