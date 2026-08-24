package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 372：新 readonly outcome 需保留舊 getTicks 的 silent-skip 語意，且絕不可 refresh／寫入。 */
class IntradayTickStoreReadOnlyOutcomeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 24);

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ListOperations<String, String> listOperations;
    private IntradayTickStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOperations);
        store = new IntradayTickStore(redis);
    }

    @Test
    void validSpecifiedBucketReturnsDataWithoutAnyWriteOrRefresh() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of(
                "{\"t\":\"2026-08-24T09:00:00\",\"p\":\"100.50\"}",
                "{\"t\":\"2026-08-24T09:01:00\",\"p\":\"101.00\"}"));

        IntradayTickStore.TickReadOutcome result = store.readTicksOutcome("2330", "台股", DATE);

        assertThat(result.readStatus()).isEqualTo(IntradayTickStore.TickReadStatus.DATA);
        assertThat(result.tradingDate()).isEqualTo(DATE);
        assertThat(result.ticks()).containsExactly(
                new IntradayTickStore.TickPoint("2026-08-24T09:00:00", new BigDecimal("100.50")),
                new IntradayTickStore.TickPoint("2026-08-24T09:01:00", new BigDecimal("101.00")));
        verify(redis).opsForList();
        verify(listOperations).range(anyString(), eq(0L), eq(-1L));
        verifyNoMoreInteractions(redis, listOperations);
    }

    @Test
    void emptyAndUnavailableRemainDistinctTypedOutcomes() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());

        IntradayTickStore.TickReadOutcome empty = store.readTicksOutcome("2330", "台股", DATE);

        assertThat(empty.readStatus()).isEqualTo(IntradayTickStore.TickReadStatus.EMPTY);
        assertThat(empty.ticks()).isEmpty();

        reset(redis, listOperations);
        when(redis.opsForList()).thenReturn(listOperations);
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenThrow(new IllegalStateException("redis down"));

        IntradayTickStore.TickReadOutcome unavailable = store.readTicksOutcome("2330", "台股", DATE);

        assertThat(unavailable.readStatus()).isEqualTo(IntradayTickStore.TickReadStatus.UNAVAILABLE);
        assertThat(unavailable.ticks()).isEmpty();
        verify(redis).opsForList();
        verify(listOperations).range(anyString(), eq(0L), eq(-1L));
        verifyNoMoreInteractions(redis, listOperations);
    }

    @Test
    void anyMalformedRowRejectsPartialDataButLegacyReaderStillSilentlySkipsIt() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of(
                "{\"t\":\"2026-08-24T09:00:00\",\"p\":\"100\"}",
                "{\"t\":\"2026-08-25T09:01:00\",\"p\":\"101\"}"));

        IntradayTickStore.TickReadOutcome strict = store.readTicksOutcome("2330", "台股", DATE);

        assertThat(strict.readStatus()).isEqualTo(IntradayTickStore.TickReadStatus.MALFORMED);
        assertThat(strict.ticks()).isEmpty();

        // The original compatibility reader keeps its established lenient shape; Task 372 must not alter it.
        List<IntradayTickStore.TickPoint> legacy = store.getTicks("2330", "台股", DATE);
        assertThat(legacy).containsExactly(
                new IntradayTickStore.TickPoint("2026-08-24T09:00:00", new BigDecimal("100")),
                new IntradayTickStore.TickPoint("2026-08-25T09:01:00", new BigDecimal("101")));
    }
}
