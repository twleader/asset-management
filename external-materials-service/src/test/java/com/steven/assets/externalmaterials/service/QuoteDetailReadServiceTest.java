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
    void cacheHitIsPureReadWithoutDbOrAnyWriterCall() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        TwQuoteDetailFetchClient.QuoteDetailResult snapshot = snapshot();
        when(cache.find("2330", "台股")).thenReturn(Optional.of(snapshot));
        when(store.isPersistable(snapshot)).thenReturn(true);

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result).isSameAs(snapshot);
        verify(cache).find("2330", "台股");
        verify(store).isPersistable(snapshot);
        verify(store, never()).find("2330", "台股");
        verify(store, never()).persist(snapshot);
        verify(cache, never()).writeStrictNewer(snapshot);
    }

    @Test
    void cacheMissReadsCanonicalDbWithoutRepairingEitherSink() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        TwQuoteDetailFetchClient.QuoteDetailResult snapshot = snapshot();
        when(cache.find("2330", "台股")).thenReturn(Optional.empty());
        when(store.find("2330", "台股")).thenReturn(Optional.of(snapshot));

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result).isSameAs(snapshot);
        verify(store).find("2330", "台股");
        verify(store, never()).persist(snapshot);
        verify(cache, never()).writeStrictNewer(snapshot);
    }

    @Test
    void unsupportedMarketAndIndexTouchNeitherRedisNorPostgres() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        QuoteDetailReadService service = new QuoteDetailReadService(cache, store);

        var foreign = service.read("AAPL", "美股");
        var index = service.read("0000", "台股");
        var missingCode = service.read(null, "台股");

        assertThat(foreign.supported()).isFalse();
        assertThat(foreign.available()).isFalse();
        assertThat(foreign.source()).isNull();
        assertThat(foreign.marketStatus()).isEqualTo("UNKNOWN");
        assertThat(foreign.levels()).isEmpty();
        assertThat(index.supported()).isFalse();
        assertThat(missingCode.supported()).isFalse();
        verifyNoInteractions(cache, store);
    }

    @Test
    void cacheAndDbMissIsTypedUnavailableWithoutYahooSource() {
        QuoteDetailCache cache = mock(QuoteDetailCache.class);
        IntradayOrderBookSnapshotStore store = mock(IntradayOrderBookSnapshotStore.class);
        when(cache.find("2330", "台股")).thenReturn(Optional.empty());
        when(store.find("2330", "台股")).thenReturn(Optional.empty());

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result.supported()).isTrue();
        assertThat(result.available()).isFalse();
        assertThat(result.source()).isNull();
        assertThat(result.marketStatus()).isEqualTo("UNKNOWN");
        assertThat(result.levels()).isEmpty();
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot() {
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(100 - level), (long) level,
                        BigDecimal.valueOf(100 + level), (long) (level + 10)))
                .toList();
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", "台積電", "台股", true, true, "FUBON_BOOKS", null,
                Instant.parse("2026-08-21T05:00:00.123456Z"), Instant.parse("2026-08-21T05:00:01Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(98), BigDecimal.valueOf(100),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10L, null,
                BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
