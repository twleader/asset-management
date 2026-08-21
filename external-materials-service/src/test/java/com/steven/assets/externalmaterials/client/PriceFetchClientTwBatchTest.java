package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceFetchClientTwBatchTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void over320FailsClosedWithoutHttpAndPreservesAllCapacityEvidence() throws Exception {
        HttpClient http = mock(HttpClient.class);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 321; i++) codes.add(String.format("X%03d", i));

        PriceFetchClient.TwQuoteBatchSummary result = client(http).fetchTwBatch(codes);

        assertThat(result.missingCodes()).containsExactlyElementsOf(codes);
        assertThat(result.capacityRejectedCodes()).containsExactlyElementsOf(codes);
        assertThat(result.httpRequests()).isZero();
        assertThat(result.requestFailures()).isZero();
        verify(http, never()).send(any(), any());
    }

    @Test
    void fortyOneCodesAreDeterministicallyChunkedAndRetriedOnlyOnce() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"rtcode\":\"0000\",\"rtmessage\":\"OK\",\"msgArray\":[]}");
        CopyOnWriteArrayList<String> uris = new CopyOnWriteArrayList<>();
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            uris.add(invocation.<HttpRequest>getArgument(0).uri().toString());
            return response;
        });
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 41; i++) codes.add(String.format("C%02d", i));

        PriceFetchClient.TwQuoteBatchSummary result = client(http).fetchTwBatch(codes);

        assertThat(result.missingCodes()).containsExactlyElementsOf(codes);
        assertThat(result.httpRequests()).isEqualTo(4);
        assertThat(result.requestFailures()).isZero();
        assertThat(uris).hasSize(4);
        assertThat(uris.stream().filter(uri -> uri.contains("tse_C40.tw")).count()).isEqualTo(2);
        assertThat(uris.stream().filter(uri -> uri.contains("tse_C00.tw")).count()).isEqualTo(2);
    }

    @Test
    void onlyResolvedIsTerminalAndSecondWaveUsesOriginalUnresolvedOrder() throws Exception {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger sends = new AtomicInteger();
        List<String> uris = new CopyOnWriteArrayList<>();
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            uris.add(invocation.<HttpRequest>getArgument(0).uri().toString());
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            if (sends.getAndIncrement() == 0) {
                when(response.body()).thenReturn(envelope(
                        row("0056", "52.30", "12:04:00"),
                        "{\"c\":\"00713\",\"z\":\"-\"}"));
            } else {
                when(response.body()).thenReturn(envelope(row("00713", "61.55", "12:05:00")));
            }
            return response;
        });

        PriceFetchClient.TwQuoteBatchSummary result = client(http)
                .fetchTwBatch(List.of("0056", "00713"));

        assertThat(result.resolved().keySet()).containsExactly("0056", "00713");
        assertThat(result.httpRequests()).isEqualTo(2);
        assertThat(uris.get(0)).contains("tse_0056.tw").contains("tse_00713.tw");
        assertThat(uris.get(1)).doesNotContain("0056").contains("tse_00713.tw%7Cotc_00713.tw");
    }

    @Test
    void malformedEnvelopeCountsAsRequestFailureOnBothWaves() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"rtcode\":\"9999\",\"rtmessage\":\"upstream busy\",\"msgArray\":[]}");
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);

        PriceFetchClient.TwQuoteBatchSummary result = client(http).fetchTwBatch(Set.of("0056"));

        assertThat(result.missingCodes()).containsExactly("0056");
        assertThat(result.httpRequests()).isEqualTo(2);
        assertThat(result.requestFailures()).isEqualTo(2);
    }

    @Test
    void classificationsAreMutuallyExclusiveAndForeignDoesNotEnterPartition() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(envelope(
                row("0056", "52.30", "12:04:00"),
                "{\"c\":\"00713\",\"z\":\"-\"}",
                "{\"c\":\"00878\",\"z\":\"bad\"}",
                "{\"c\":\"FOREIGN\",\"z\":\"10\"}"));
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);

        PriceFetchClient.TwQuoteBatchSummary result = client(http)
                .fetchTwBatch(List.of("0056", "00713", "00878", "00919"));

        assertThat(result.resolved().keySet()).containsExactly("0056");
        assertThat(result.noTradeCodes()).containsExactly("00713");
        assertThat(result.invalidCodes()).containsExactly("00878");
        assertThat(result.missingCodes()).containsExactly("00919");
        // The fixed fixture also echoes first-wave-resolved 0056 during the unresolved-only retry;
        // both are foreign to that second request and remain evidence outside the requested partition.
        assertThat(result.foreignCodes()).containsExactly("FOREIGN", "0056");
        assertThat(result.requestedCount()).isEqualTo(4);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void slowEarlierChunkDoesNotDiscardLaterChunkCompletedBeforeGlobalDeadline() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        AtomicBoolean blockFirstAttempt = new AtomicBoolean(true);
        ThreadLocal<Boolean> fastChunk = ThreadLocal.withInitial(() -> false);
        CopyOnWriteArrayList<String> uris = new CopyOnWriteArrayList<>();
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            String uri = invocation.<HttpRequest>getArgument(0).uri().toString();
            uris.add(uri);
            fastChunk.set(uri.contains("tse_C40.tw"));
            if (uri.contains("tse_C00.tw") && blockFirstAttempt.getAndSet(false)) {
                firstSendStarted.countDown();
                // Production cancel(true)+shutdownNow must interrupt this deadline-overrun worker.
                new CountDownLatch(1).await();
            }
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(uri.contains("tse_C40.tw")
                    ? envelope(row("C40", "40.00", "12:04:00"))
                    : envelope());
            return response;
        });

        Thread coordinator = Thread.currentThread();
        AtomicInteger coordinatorReads = new AtomicInteger();
        LongSupplier nanoTime = () -> {
            if (Boolean.TRUE.equals(fastChunk.get())) {
                fastChunk.set(false);
                return 4 * SECOND;
            }
            if (Thread.currentThread() != coordinator) return 11 * SECOND;
            return switch (coordinatorReads.getAndIncrement()) {
                case 0 -> 0L;          // wave 1 deadline = 5s
                case 1 -> 6 * SECOND;  // first slow Future reaches the shared deadline
                case 2 -> 10 * SECOND; // wave 2 deadline = 15s
                default -> 11 * SECOND;
            };
        };
        AtomicInteger executors = new AtomicInteger();
        Supplier<ExecutorService> executorFactory = () -> executors.getAndIncrement() == 0
                ? new FirstAsyncThenDirectExecutor(firstSendStarted)
                : new DirectExecutorService();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 41; i++) codes.add(String.format("C%02d", i));

        PriceFetchClient.TwQuoteBatchSummary result = client(http, nanoTime, executorFactory)
                .fetchTwBatch(codes);

        assertThat(result.resolved()).containsKey("C40");
        assertThat(result.missingCodes()).containsExactlyElementsOf(codes.subList(0, 40));
        assertThat(uris.stream().filter(uri -> uri.contains("tse_C40.tw")).count()).isEqualTo(1);
        assertThat(result.httpRequests()).isEqualTo(3);
        assertThat(result.requestFailures()).isEqualTo(1);
    }

    @Test
    void chunkActuallyCompletedAfterGlobalDeadlineIsMissingAndFailed() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(envelope(row("0056", "52.30", "12:04:00")));
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);
        long[] timeline = {0L, 6 * SECOND, 10 * SECOND, 16 * SECOND};
        AtomicInteger reads = new AtomicInteger();
        LongSupplier nanoTime = () -> timeline[reads.getAndIncrement()];

        PriceFetchClient.TwQuoteBatchSummary result = client(
                http, nanoTime, DirectExecutorService::new).fetchTwBatch(List.of("0056"));

        assertThat(result.resolved()).isEmpty();
        assertThat(result.missingCodes()).containsExactly("0056");
        assertThat(result.httpRequests()).isEqualTo(2);
        assertThat(result.requestFailures()).isEqualTo(2);
        verify(http, org.mockito.Mockito.times(2)).send(any(), any());
    }

    private static String row(String code, String price, String time) {
        return "{\"c\":\"" + code + "\",\"z\":\"" + price
                + "\",\"d\":\"20260821\",\"t\":\"" + time
                + "\",\"tlong\":\"1787293800000\"}";
    }

    private static String envelope(String... rows) {
        return "{\"rtcode\":\"0000\",\"rtmessage\":\"OK\",\"msgArray\":["
                + String.join(",", rows) + "]}";
    }

    private static PriceFetchClient client(HttpClient http) {
        return client(http, System::nanoTime,
                () -> Executors.newFixedThreadPool(PriceFetchClient.MAX_CONCURRENT_CHUNKS,
                        Thread.ofVirtual().factory()));
    }

    private static PriceFetchClient client(
            HttpClient http, LongSupplier nanoTime, Supplier<ExecutorService> executorFactory) {
        return new PriceFetchClient(
                "", http, Clock.fixed(Instant.parse("2026-08-21T06:00:00Z"), ZoneOffset.UTC), null,
                duration -> { }, nanoTime, executorFactory);
    }

    private static class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static final class FirstAsyncThenDirectExecutor extends DirectExecutorService {
        private final CountDownLatch firstSendStarted;
        private final List<Thread> workers = new CopyOnWriteArrayList<>();
        private final AtomicInteger submissions = new AtomicInteger();

        private FirstAsyncThenDirectExecutor(CountDownLatch firstSendStarted) {
            this.firstSendStarted = firstSendStarted;
        }

        @Override
        public void execute(Runnable command) {
            if (submissions.getAndIncrement() == 0) {
                Thread worker = Thread.ofVirtual().name("test-slow-first-chunk").start(command);
                workers.add(worker);
                try {
                    firstSendStarted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            } else {
                command.run();
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            workers.forEach(Thread::interrupt);
            return super.shutdownNow();
        }
    }
}
