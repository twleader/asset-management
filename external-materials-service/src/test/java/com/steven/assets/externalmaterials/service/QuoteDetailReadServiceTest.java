package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuoteDetailReadServiceTest {

    @Test
    void matchingCacheRevisionIsPureReadAndDoesNotNeedFullDbSnapshot() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        TwQuoteDetailFetchClient.QuoteDetailResult snapshot = snapshot("FUBON_BOOKS");
        when(cache.find("2330", "台股")).thenReturn(Optional.of(new QuoteDetailCache.CachedSnapshot("7", snapshot)));
        when(store.findRevision("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.RevisionLookup.found(7L));

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result).isSameAs(snapshot);
        verify(cache).find("2330", "台股");
        verify(store).findRevision("2330", "台股");
        verify(store, never()).findCanonical("2330", "台股");
        verify(store, never()).persist(org.mockito.ArgumentMatchers.any());
        verify(cache, never()).writeStrictNewer(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cacheMissReadsFullCurrentDbCanonicalWithoutRepairingEitherSink() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        var snapshot = new IntradayOrderBookSnapshotStore.CanonicalSnapshot(snapshot("YAHOO_TW"), 8L);
        when(cache.find("2330", "台股")).thenReturn(Optional.empty());
        when(store.findRevision("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.RevisionLookup.found(8L));
        when(store.findCanonical("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.CanonicalLookup.found(snapshot));

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result).isSameAs(snapshot.snapshot());
        verify(store).findCanonical("2330", "台股");
        verify(store, never()).persist(org.mockito.ArgumentMatchers.any());
        verify(cache, never()).writeStrictNewer(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleRedisRevisionReturnsNewerDbCanonicalAndNeverRepairsRequestTimeCache() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        var stale = snapshot("FUBON_BOOKS");
        var canonical = new IntradayOrderBookSnapshotStore.CanonicalSnapshot(snapshot("YAHOO_TW"), 8L);
        when(cache.find("2330", "台股")).thenReturn(Optional.of(new QuoteDetailCache.CachedSnapshot("7", stale)));
        when(store.findRevision("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.RevisionLookup.found(8L));
        when(store.findCanonical("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.CanonicalLookup.found(canonical));

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result).isSameAs(canonical.snapshot());
        assertThat(result.source()).isEqualTo("YAHOO_TW");
        verify(cache, never()).writeStrictNewer(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeCanonicalCommittedAfterHeaderRevisionLookupStillWinsOverTheEarlierRevision() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        var newerCanonical = new IntradayOrderBookSnapshotStore.CanonicalSnapshot(snapshot("YAHOO_TW"), 9L);
        when(cache.find("2330", "台股")).thenReturn(Optional.empty());
        // Simulates r=8 being read, then a producer committing full r=9 before the full read.
        when(store.findRevision("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.RevisionLookup.found(8L));
        when(store.findCanonical("2330", "台股"))
                .thenReturn(IntradayOrderBookSnapshotStore.CanonicalLookup.found(newerCanonical));

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result).isSameAs(newerCanonical.snapshot());
        assertThat(result.available()).isTrue();
        assertThat(result.source()).isEqualTo("YAHOO_TW");
        verify(cache, never()).writeStrictNewer(org.mockito.ArgumentMatchers.any());
        verify(store, never()).persist(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dbRevisionFailureNeverServesFreshnessUnknownRedisPayload() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        when(cache.find("2330", "台股")).thenReturn(Optional.of(new QuoteDetailCache.CachedSnapshot("8", snapshot("YAHOO_TW"))));
        when(store.findRevision("2330", "台股")).thenReturn(IntradayOrderBookSnapshotStore.RevisionLookup.failed());

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result.available()).isFalse();
        assertThat(result.source()).isNull();
        verify(store, never()).findCanonical("2330", "台股");
    }

    @Test
    void unsupportedMarketAndIndexTouchNeitherRedisNorPostgres() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailReadService service = new QuoteDetailReadService(cache, store);

        assertThat(service.read("AAPL", "美股").supported()).isFalse();
        assertThat(service.read("0000", "台股").supported()).isFalse();
        assertThat(service.read(null, "台股").supported()).isFalse();
        verifyNoInteractions(cache, store);
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(String source) {
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(101 - level), (long) level,
                        BigDecimal.valueOf(101 + level), (long) (level + 10)))
                .toList();
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", "台積電", "台股", true, true, source, null,
                Instant.parse("2026-08-26T03:00:00.123456Z"), Instant.parse("2026-08-26T03:00:01Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(98), BigDecimal.valueOf(100),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10L, null,
                BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
