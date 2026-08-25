package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TwFubonOrderBookRoundWriterTest {

    @Test
    void appliedFubonSnapshotPersistsThenWritesOnlyItsCanonicalValueToDedicatedCache() {
        ExecutorService executor = inlineExecutor();
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        var snapshot = snapshot("2026-08-21T05:00:00Z");
        when(store.isPersistable(snapshot)).thenReturn(true);
        when(store.persist(snapshot)).thenReturn(IntradayOrderBookSnapshotStore.PersistResult.applied(snapshot));

        new TwFubonOrderBookRoundWriter(executor, store, cache).submit(Map.of("2330", snapshot));

        verify(store).persist(snapshot);
        verify(cache).writeStrictNewer(snapshot);
    }

    @Test
    void staleInputCanRepairDedicatedCacheOnlyFromReadCanonicalRow() {
        ExecutorService executor = inlineExecutor();
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        var stale = snapshot("2026-08-21T05:00:00Z");
        var canonical = snapshot("2026-08-21T05:00:00.000001Z");
        when(store.isPersistable(stale)).thenReturn(true);
        when(store.persist(stale)).thenReturn(IntradayOrderBookSnapshotStore.PersistResult.stale(canonical));

        new TwFubonOrderBookRoundWriter(executor, store, cache).submit(Map.of("2330", stale));

        verify(cache).writeStrictNewer(canonical);
        verify(cache, never()).writeStrictNewer(stale);
    }

    @Test
    void dbFailureHasNoRedisSideEffect() {
        ExecutorService executor = inlineExecutor();
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        var snapshot = snapshot("2026-08-21T05:00:00Z");
        when(store.isPersistable(snapshot)).thenReturn(true);
        when(store.persist(snapshot)).thenReturn(IntradayOrderBookSnapshotStore.PersistResult.failed());

        new TwFubonOrderBookRoundWriter(executor, store, cache).submit(Map.of("2330", snapshot));

        verify(store).persist(snapshot);
        verifyNoInteractions(cache);
    }

    @Test
    void busyWorkerDropsTheNextRoundInsteadOfBuildingAWaitingQueue() {
        ExecutorService executor = mock(ExecutorService.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        var snapshot = snapshot("2026-08-21T05:00:00Z");
        TwFubonOrderBookRoundWriter writer = new TwFubonOrderBookRoundWriter(executor, store, cache);

        writer.submit(Map.of("2330", snapshot));
        writer.submit(Map.of("2330", snapshot));

        verify(executor, times(1)).execute(any(Runnable.class));
        verifyNoInteractions(store, cache);
    }

    private static ExecutorService inlineExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(String sourceTime) {
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(100 - level), (long) level,
                        BigDecimal.valueOf(100 + level), (long) (level + 10)))
                .toList();
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", "台積電", "台股", true, true, "FUBON_BOOKS", null,
                Instant.parse(sourceTime), Instant.parse("2026-08-21T05:00:01Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(98), BigDecimal.valueOf(100),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10L, null,
                BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
