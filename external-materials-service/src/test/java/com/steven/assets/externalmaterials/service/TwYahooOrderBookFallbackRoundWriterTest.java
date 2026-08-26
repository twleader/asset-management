package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwYahooOrderBookFallbackRoundWriterTest {

    @Test
    void stableFortyCandidatesUseEightCodeFairCursorAndTryEveryCodeWithinFiveAcceptedRounds() {
        ExecutorService executor = inlineExecutor();
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        when(yahoo.probeTw(any())).thenReturn(TwQuoteDetailFetchClient.YahooProbeOutcome.transientOrInvalid());
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor, yahoo, store, cache);
        List<String> candidates = new ArrayList<>();
        for (int value = 1; value <= 40; value++) candidates.add(String.format("%04d", value));

        for (int round = 0; round < 5; round++) writer.submit(candidates);

        org.mockito.ArgumentCaptor<String> codes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(yahoo, times(40)).probeTw(codes.capture());
        assertThat(codes.getAllValues()).containsExactlyInAnyOrderElementsOf(candidates);
        verify(yahoo, never()).probeTwo(any());
        verify(store, never()).persist(any());
        verify(cache, never()).writeStrictNewer(any());
    }

    @Test
    void structuralMissIsTheOnlyPathToSecondSuffixAndEightCodesStayWithinSixteenProbes() {
        ExecutorService executor = inlineExecutor();
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        when(yahoo.probeTw(any())).thenReturn(TwQuoteDetailFetchClient.YahooProbeOutcome.structuralMiss());
        when(yahoo.probeTwo(any())).thenReturn(TwQuoteDetailFetchClient.YahooProbeOutcome.transientOrInvalid());
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor, yahoo,
                mock(IntradayOrderBookSnapshotStore.class), mock(QuoteDetailCache.class));

        writer.submit(List.of("2330", "2317", "2454", "2303", "2881", "2882", "2884", "0050", "006208"));

        verify(yahoo, times(TwYahooOrderBookFallbackRoundWriter.CODES_PER_ROUND)).probeTw(any());
        verify(yahoo, times(TwYahooOrderBookFallbackRoundWriter.CODES_PER_ROUND)).probeTwo(any());
        assertThat(TwYahooOrderBookFallbackRoundWriter.MAX_PROBES_PER_ROUND).isEqualTo(16);
        assertThat(TwYahooOrderBookFallbackRoundWriter.MAX_CONCURRENT_PROBES).isEqualTo(4);
        assertThat(TwQuoteDetailFetchClient.PROBE_TIMEOUT).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(TwYahooOrderBookFallbackRoundWriter.ROUND_TIMEOUT_SECONDS).isEqualTo(9L);
    }

    @Test
    void defensiveCandidateNormalizationNeverExpandsPastTheDispatchersFortyCodeWindow() {
        ExecutorService executor = inlineExecutor();
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        when(yahoo.probeTw(any())).thenReturn(TwQuoteDetailFetchClient.YahooProbeOutcome.transientOrInvalid());
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor, yahoo,
                mock(IntradayOrderBookSnapshotStore.class), mock(QuoteDetailCache.class));
        List<String> candidates = java.util.stream.IntStream.rangeClosed(1, 41)
                .mapToObj(value -> String.format("%04d", value)).toList();

        writer.submit(candidates);

        verify(yahoo, times(TwYahooOrderBookFallbackRoundWriter.CODES_PER_ROUND)).probeTw(any());
        verify(yahoo, never()).probeTw("0041");
        assertThat(TwYahooOrderBookFallbackRoundWriter.MAX_CANDIDATES_PER_ROUND).isEqualTo(40);
    }

    @Test
    void foundYahooSnapshotPersistsThenCachesOnlyDbReturnedRevision() {
        ExecutorService executor = inlineExecutor();
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        var found = snapshot("YAHOO_TW");
        var canonical = new IntradayOrderBookSnapshotStore.CanonicalSnapshot(found, 4L);
        when(yahoo.probeTw("2330")).thenReturn(TwQuoteDetailFetchClient.YahooProbeOutcome.found(found));
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        when(store.isPersistable(found)).thenReturn(true);
        when(store.persist(found)).thenReturn(IntradayOrderBookSnapshotStore.PersistResult.applied(canonical));
        QuoteDetailCache cache = mock(QuoteDetailCache.class);

        new TwYahooOrderBookFallbackRoundWriter(executor, yahoo, store, cache).submit(List.of("2330"));

        verify(store).persist(found);
        verify(cache).writeStrictNewer(canonical);
    }

    @Test
    void busyWorkerDropsSecondRoundInsteadOfBuildingAQueue() {
        ExecutorService executor = mock(ExecutorService.class);
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor,
                mock(TwQuoteDetailFetchClient.class), mock(IntradayOrderBookSnapshotStore.class), mock(QuoteDetailCache.class));

        writer.submit(List.of("2330"));
        writer.submit(List.of("2317"));

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void rejectedSubmissionDoesNotConsumeTheNextFairCursorWindow() {
        ExecutorService executor = mock(ExecutorService.class);
        AtomicInteger submissions = new AtomicInteger();
        doAnswer(invocation -> {
            if (submissions.getAndIncrement() == 0) throw new RejectedExecutionException("busy");
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        when(yahoo.probeTw(any())).thenReturn(TwQuoteDetailFetchClient.YahooProbeOutcome.transientOrInvalid());
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor, yahoo,
                mock(IntradayOrderBookSnapshotStore.class), mock(QuoteDetailCache.class));

        List<String> candidates = List.of("0001", "0002", "0003", "0004", "0005", "0006", "0007", "0008", "0009");
        writer.submit(candidates);
        writer.submit(candidates);

        verify(yahoo).probeTw("0001");
        verify(yahoo).probeTw("0008");
        verify(yahoo, never()).probeTw("0009");
    }

    @Test
    void workerNeverStartsMoreThanFourOutboundProbesAtOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(8);
        doAnswer(invocation -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            firstWave.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("probe release timed out");
                return TwQuoteDetailFetchClient.YahooProbeOutcome.transientOrInvalid();
            } finally {
                active.decrementAndGet();
                completed.countDown();
            }
        }).when(yahoo).probeTw(any());
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor, yahoo,
                mock(IntradayOrderBookSnapshotStore.class), mock(QuoteDetailCache.class));
        try {
            writer.submit(List.of("2330", "2317", "2454", "2303", "2881", "2882", "2884", "0050"));
            assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(TwYahooOrderBookFallbackRoundWriter.MAX_CONCURRENT_PROBES);
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValueLessThanOrEqualTo(TwYahooOrderBookFallbackRoundWriter.MAX_CONCURRENT_PROBES);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void slowProbesAreCancelledAtTheRoundDeadlineAndProductionKeepsThatDeadlineAtNineSeconds() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        CountDownLatch workerFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            Thread.ofVirtual().start(() -> {
                try {
                    invocation.getArgument(0, Runnable.class).run();
                } finally {
                    workerFinished.countDown();
                }
            });
            return null;
        }).when(executor).execute(any(Runnable.class));
        TwQuoteDetailFetchClient yahoo = mock(TwQuoteDetailFetchClient.class);
        CountDownLatch firstFourStarted = new CountDownLatch(4);
        CountDownLatch interrupted = new CountDownLatch(4);
        CountDownLatch neverCompletesWithoutCancellation = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstFourStarted.countDown();
            try {
                neverCompletesWithoutCancellation.await();
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return TwQuoteDetailFetchClient.YahooProbeOutcome.transientOrInvalid();
        }).when(yahoo).probeTw(any());
        Duration testDeadline = Duration.ofMillis(150);
        TwYahooOrderBookFallbackRoundWriter writer = new TwYahooOrderBookFallbackRoundWriter(executor, yahoo,
                mock(IntradayOrderBookSnapshotStore.class), mock(QuoteDetailCache.class), testDeadline);

        long started = System.nanoTime();
        writer.submit(List.of("2330", "2317", "2454", "2303", "2881", "2882", "2884", "0050"));

        try {
            assertThat(firstFourStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(workerFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(testDeadline)
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(TwYahooOrderBookFallbackRoundWriter.ROUND_TIMEOUT_SECONDS).isEqualTo(9L);
        } finally {
            neverCompletesWithoutCancellation.countDown();
        }
    }

    private static ExecutorService inlineExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(String source) {
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(101 - level), (long) level,
                        BigDecimal.valueOf(101 + level), (long) (level + 10))).toList();
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", "台積電", "台股", true, true, source, null,
                Instant.parse("2026-08-26T03:00:00Z"), Instant.parse("2026-08-26T03:00:01Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(98), BigDecimal.valueOf(100),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10L, null,
                BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
