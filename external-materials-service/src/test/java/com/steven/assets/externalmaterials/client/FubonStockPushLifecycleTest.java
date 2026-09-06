package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonStockPushLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-08-28T02:15:01Z");
    private MarketClock clock() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW); when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true)); return clock;
    }
    private FubonMarketConfigState config() {
        return new FubonMarketConfigState("true", "http://fake.invalid", "unused", path -> "fake-token");
    }
    @Test void disabledAndMissingTokenPerformZeroTransportCallsWithoutTouchingIndex() {
        var tokens = new AtomicInteger(); var requests = new AtomicInteger();
        var state = new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> { tokens.incrementAndGet(); return null; });
        var disabled = new FubonStockPushStreamClient("false", state, clock(), (a,b) -> { requests.incrementAndGet(); return null; }, millis -> {});
        disabled.start(e -> {});
        assertThat(tokens).hasValue(0); assertThat(requests).hasValue(0); assertThat(disabled.isRunning()).isFalse();
        var missing = new FubonStockPushStreamClient("true", state, clock(), (a,b) -> { requests.incrementAndGet(); return null; }, millis -> {});
        missing.start(e -> {});
        assertThat(tokens).hasValue(1); assertThat(requests).hasValue(0); assertThat(missing.state()).isEqualTo("MISCONFIGURED");
    }
    @Test void repeatedDesiredSetRenewsWithoutReconnectAndCloseClearsOnlyStockWhileIndexStillConsumes() throws Exception {
        var clock = clock();
        var stockIn = new PipedInputStream(); var stockOut = new PipedOutputStream(stockIn);
        var indexIn = new PipedInputStream(); var indexOut = new PipedOutputStream(indexIn);
        var stockOpens = new AtomicInteger(); var indexOpens = new AtomicInteger();
        var stockOpened = new CountDownLatch(1);
        var indexSeen = new CountDownLatch(1);
        var ingestion = mock(FubonTaiexIndexIngestionService.class);
        when(ingestion.ingest(any())).thenAnswer(invocation -> { indexSeen.countDown(); return null; });
        var index = new FubonTaiexIndexStreamClient(true, true, "IX0001", "http://fake.invalid", "unused", ingestion,
                (uri, token) -> { indexOpens.incrementAndGet(); return new FubonTaiexIndexStreamClient.RawResponse(200, indexIn); },
                millis -> new CountDownLatch(1).await(), path -> "fake-token");
        var stock = new FubonStockPushStreamClient("true", config(), clock,
                (uri, token) -> { stockOpens.incrementAndGet(); stockOpened.countDown();
                    return new FubonStockPushStreamClient.Response(200, stockIn, "text/event-stream"); },
                millis -> new CountDownLatch(1).await());
        var radar = mock(FubonRadarScope.class); when(radar.current(300)).thenReturn(List.of("2330"));
        var port = mock(FubonMarketDataPort.class);
        var consumer = new FubonStockPushConsumer(clock, radar, mock(PriceCacheWriter.class));
        var manager = new FubonStockPushSubscriptionManager("true", config(), clock, radar, port, stock, consumer);
        try {
            index.start(); manager.refreshSubscriptions();
            assertThat(stockOpened.await(2, TimeUnit.SECONDS)).isTrue();
            manager.refreshSubscriptions();
            assertThat(stockOpens).hasValue(1);
            verify(port, times(2)).subscriptions(List.of("2330"));
            when(radar.current(300)).thenReturn(List.of("0050"));
            manager.refreshSubscriptions();
            assertThat(stockOpens).hasValue(1);
            verify(port).subscriptions(List.of("0050"));
            when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(false));
            manager.refreshSubscriptions();
            assertThat(stock.isRunning()).isFalse();
            verify(port).subscriptions(List.of());
            assertThat(index.isRunning()).isTrue();
            indexOut.write(("event: taiex-index\nid: 1787883300000000\ndata: "
                    + "{\"symbol\":\"IX0001\",\"exchange\":\"TWSE\",\"type\":\"INDEX\",\"index\":\"23000\",\"time\":1787883300000000}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            indexOut.flush();
            assertThat(indexSeen.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(indexOpens).hasValue(1);
        } finally {
            manager.shutdown(); index.stop(); stockOut.close(); indexOut.close();
        }
    }
    @Test void oversizeOrDuplicateFramesAreDroppedAndReconnectUsesBoundedBackoff() throws Exception {
        var calls = new AtomicInteger(); var events = new AtomicInteger();
        List<Long> waits = new CopyOnWriteArrayList<>(); var done = new CountDownLatch(1);
        var stream = new FubonStockPushStreamClient("true", config(), clock(), (uri, token) -> {
            int n = calls.incrementAndGet();
            return new FubonStockPushStreamClient.Response(n == 1 ? 503 : 200,
                    new ByteArrayInputStream(("data: " + "x".repeat(17000) + "\n\n").getBytes(StandardCharsets.UTF_8)),
                    "text/event-stream");
        }, delay -> {
            waits.add(delay);
            if (waits.size() >= 2) { done.countDown(); new CountDownLatch(1).await(); }
        });
        try {
            stream.start(e -> events.incrementAndGet());
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(waits).containsExactly(250L, 500L);
            assertThat(events).hasValue(0);
        } finally { stream.stop(); }
    }
    @Test void malformedTargetFrameIsRecordedOnceAndNeverReachesTheConsumer() throws Exception {
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        CountDownLatch reachedBackoff = new CountDownLatch(1);
        AtomicInteger events = new AtomicInteger();
        String body = "event: stock-price\nid: 2330:1\n\n";
        var stream = new FubonStockPushStreamClient("true", config(), clock(),
                (uri, token) -> new FubonStockPushStreamClient.Response(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "text/event-stream"),
                delay -> { reachedBackoff.countDown(); throw new InterruptedException(); }, writer);
        try {
            stream.start(event -> events.incrementAndGet());
            assertThat(reachedBackoff.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasValue(0);
            verify(writer).record(eq("FUBON_STOCK_PUSH_STREAM"), eq("個股推播串流"), any(Throwable.class), any());
        } finally { stream.stop(); }
    }
    @Test void restartOpensANewFailureIntervalAfterStop() throws Exception {
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        CountDownLatch firstBackoff = new CountDownLatch(1);
        CountDownLatch secondBackoff = new CountDownLatch(1);
        AtomicInteger backoffs = new AtomicInteger();
        String body = "event: stock-price\nid: 2330:1\n\n";
        var stream = new FubonStockPushStreamClient("true", config(), clock(),
                (uri, token) -> new FubonStockPushStreamClient.Response(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "text/event-stream"),
                delay -> {
                    if (backoffs.incrementAndGet() == 1) firstBackoff.countDown(); else secondBackoff.countDown();
                    new CountDownLatch(1).await();
                }, writer);
        try {
            stream.start(event -> { });
            assertThat(firstBackoff.await(2, TimeUnit.SECONDS)).isTrue();
            stream.stop();
            stream.start(event -> { });
            assertThat(secondBackoff.await(2, TimeUnit.SECONDS)).isTrue();
            verify(writer, times(2)).record(eq("FUBON_STOCK_PUSH_STREAM"), eq("個股推播串流"), any(Throwable.class), any());
        } finally { stream.stop(); }
    }
    @Test void managerEmptyOverLimitDisabledAndUnknownCalendarNeverBroadensScope() {
        var clock = clock(); var access = mock(FubonMarketAccess.class);
        var radar = mock(FubonRadarScope.class); var port = mock(FubonMarketDataPort.class);
        var stream = mock(FubonStockPushStream.class); var consumer = mock(FubonStockPushConsumer.class);
        var disabled = new FubonStockPushSubscriptionManager("false", access, clock, radar, port, stream, consumer);
        disabled.refreshSubscriptions();
        verifyNoInteractions(access, radar, port);
        var manager = new FubonStockPushSubscriptionManager("true", access, clock, radar, port, stream, consumer);
        when(radar.current(300)).thenReturn(List.of());
        manager.refreshSubscriptions();
        assertThat(manager.state().outcome()).isEqualTo("NO_SYMBOLS");
        when(radar.current(300)).thenThrow(new FubonMarketData.Unavailable("SUBSCRIPTION_LIMIT"));
        manager.refreshSubscriptions();
        assertThat(manager.state().outcome()).isEqualTo("SUBSCRIPTION_LIMIT");
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.empty());
        manager.refreshSubscriptions();
        assertThat(manager.state().outcome()).isEqualTo("CALENDAR_UNKNOWN");
        verifyNoInteractions(port);
    }
}
