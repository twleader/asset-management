package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FubonTaiexIndexIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void appliedCanonicalRowProjectsOnlyAfterStoreResultAndPreservesMicroseconds() {
        FubonTaiexIndexStore store = mock(FubonTaiexIndexStore.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        FubonTaiexIndexIngestionService service = service(store, writer, source);
        Instant provider = NOW.plusNanos(123_000);
        FubonTaiexIndexStore.CanonicalIndex canonical = canonical(provider, "22345.67");
        when(store.persist(any())).thenReturn(new FubonTaiexIndexStore.PersistResult(
                FubonTaiexIndexStore.PersistStatus.APPLIED, canonical));
        when(source.loadRecentTaiexCloses(2)).thenReturn(List.of(
                new StockSourceQuery.ClosePoint(LocalDate.of(2026, 8, 26), new BigDecimal("22000.00"))));
        when(writer.writeTaiwanIndexLive(any())).thenReturn(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        assertThat(service.ingest(event(provider, "22345.67")))
                .isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.DB_APPLIED);

        ArgumentCaptor<FubonTaiexIndexStore.Candidate> candidate = ArgumentCaptor.forClass(FubonTaiexIndexStore.Candidate.class);
        verify(store).persist(candidate.capture());
        assertThat(candidate.getValue().providerUpdatedAt()).isEqualTo(provider);
        assertThat(candidate.getValue().indexPoint()).isEqualByComparingTo("22345.67");
        ArgumentCaptor<PriceResult> cache = ArgumentCaptor.forClass(PriceResult.class);
        verify(writer).writeTaiwanIndexLive(cache.capture());
        assertThat(cache.getValue().freshnessInstant()).isEqualTo(provider);
        assertThat(cache.getValue().source()).isEqualTo("FUBON_INDICES");
        assertThat(cache.getValue().highPrice()).isNull();
        assertThat(cache.getValue().lowPrice()).isNull();
        assertThat(cache.getValue().volume()).isNull();
    }

    @Test
    void rawEqualInputDoesNotRepairUnlessThePriorAppliedCacheProjectionFailed() {
        FubonTaiexIndexStore store = mock(FubonTaiexIndexStore.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        FubonTaiexIndexIngestionService service = service(store, writer, source);
        Instant provider = NOW.plusNanos(456_000);
        FubonTaiexIndexStore.CanonicalIndex canonical = canonical(provider, "22345.67");
        when(source.loadRecentTaiexCloses(2)).thenReturn(List.of());

        when(store.persist(any())).thenReturn(new FubonTaiexIndexStore.PersistResult(
                FubonTaiexIndexStore.PersistStatus.STALE_OR_EQUAL, canonical));
        assertThat(service.ingest(event(provider, "99999.99")))
                .isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.DB_STALE_OR_EQUAL);
        verify(writer, never()).writeTaiwanIndexLive(any());

        when(store.persist(any())).thenReturn(
                new FubonTaiexIndexStore.PersistResult(FubonTaiexIndexStore.PersistStatus.APPLIED, canonical),
                new FubonTaiexIndexStore.PersistResult(FubonTaiexIndexStore.PersistStatus.STALE_OR_EQUAL, canonical));
        when(writer.writeTaiwanIndexLive(any())).thenReturn(
                PriceCacheWriter.CacheWriteOutcome.FAILED,
                PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        assertThat(service.ingest(event(provider, "22345.67")))
                .isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.DB_APPLIED);
        assertThat(service.ingest(event(provider, "1.00")))
                .isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.CANONICAL_CACHE_REPAIR);
        ArgumentCaptor<PriceResult> writes = ArgumentCaptor.forClass(PriceResult.class);
        verify(writer, times(2)).writeTaiwanIndexLive(writes.capture());
        assertThat(writes.getAllValues()).allSatisfy(row ->
                assertThat(row.price()).isEqualByComparingTo("22345.67"));
    }

    @Test
    void rejectedFutureOrWrongIdentityHasZeroDbAndRedisSideEffects() {
        FubonTaiexIndexStore store = mock(FubonTaiexIndexStore.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        FubonTaiexIndexIngestionService service = service(store, writer, source);

        assertThat(service.ingest(event(NOW.plusSeconds(31), "22345.67")))
                .isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.REJECTED);
        assertThat(service.ingest(new FubonTaiexIndexEvent("OTHER", "TWSE", "INDEX", "22345.67",
                NOW.toEpochMilli() * 1_000L))).isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.REJECTED);

        verify(store, never()).persist(any());
        verify(writer, never()).writeTaiwanIndexLive(any());
    }

    @Test
    void dbFailureNeverCallsRedisWriter() {
        FubonTaiexIndexStore store = mock(FubonTaiexIndexStore.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        FubonTaiexIndexIngestionService service = service(store, writer, source);
        when(store.persist(any())).thenReturn(new FubonTaiexIndexStore.PersistResult(
                FubonTaiexIndexStore.PersistStatus.FAILED, null));

        assertThat(service.ingest(event(NOW, "22345.67")))
                .isEqualTo(FubonTaiexIndexIngestionService.IngestionOutcome.DB_FAILED);
        verify(writer, never()).writeTaiwanIndexLive(any());
    }

    private static FubonTaiexIndexIngestionService service(
            FubonTaiexIndexStore store, PriceCacheWriter writer, StockSourceQuery source) {
        return new FubonTaiexIndexIngestionService(store, writer, source, CLOCK, "IR0001");
    }

    private static FubonTaiexIndexEvent event(Instant instant, String index) {
        long micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
        return new FubonTaiexIndexEvent("IR0001", "TWSE", "INDEX", index, micros);
    }

    private static FubonTaiexIndexStore.CanonicalIndex canonical(Instant instant, String index) {
        return new FubonTaiexIndexStore.CanonicalIndex("0000", "IR0001", "TWSE",
                instant.atZone(MarketClock.TW_ZONE).toLocalDate(), instant, new BigDecimal(index), "FUBON_INDICES");
    }
}
