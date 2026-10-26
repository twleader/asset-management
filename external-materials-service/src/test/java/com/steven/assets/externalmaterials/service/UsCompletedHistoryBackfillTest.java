package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import com.steven.assets.externalmaterials.client.ExchangeRateFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.HistoricalBar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsCompletedHistoryBackfillTest {
    final PriceFetchClient fetch = mock(PriceFetchClient.class);
    final StockSourceQuery store = mock(StockSourceQuery.class);
    final HistoricalBackfillService service = new HistoricalBackfillService(fetch,
            mock(ExchangeRateFetchClient.class), mock(CommodityFetchClient.class), store,
            mock(MarketDataFetchService.class));
    final LocalDate today = LocalDate.now(MarketClock.US_ZONE);
    final LocalDate since = today.minusYears(10);

    void covered(String code) {
        when(store.findMaxTradingDate(code, "美股")).thenReturn(Optional.of(today.minusDays(4)));
        when(store.findMinTradingDate(code, "美股")).thenReturn(Optional.of(since.minusDays(1)));
    }

    HistoricalBar bar(LocalDate day) {
        return new HistoricalBar(day, new BigDecimal("100"), new BigDecimal("102"),
                new BigDecimal("99"), new BigDecimal("101"), 0L, "VOO");
    }

    @Test void recentOneDayGapIsFetchedWithOverlapAndOnlyExactCompletedDatesAreWritten() {
        covered("VOO");
        when(fetch.fetchUsHistoricalRange("VOO", today.minusDays(7), today.minusDays(1)))
                .thenReturn(List.of(bar(today.minusDays(8)), bar(today.minusDays(3)),
                        bar(today), bar(today.plusDays(1))));
        when(store.upsertIncompleteUsHistory(eq("VOO"), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        assertThat(service.backfillUsStock("VOO", since, null)).isEqualTo(1);
        verify(store).upsertIncompleteUsHistory(eq("VOO"), eq(today.minusDays(3)), any(), any(), any(), any(), eq(0L));
        verify(store, times(1)).upsertIncompleteUsHistory(any(), any(), any(), any(), any(), any(), any());
        verify(store, never()).upsertHistory(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void oldIncompleteRowInBoundedTwoYearHistoryReopensFetchBeforeMaxDate() {
        covered("GOOGL");
        LocalDate gap = today.minusMonths(6);
        when(store.findEarliestIncompleteUsHistory("GOOGL", today.minusYears(2), today.minusDays(1)))
                .thenReturn(Optional.of(gap));
        when(fetch.fetchUsHistoricalRange("GOOGL", gap, today.minusDays(1))).thenReturn(List.of());
        assertThat(service.backfillUsStock("GOOGL", since, null)).isZero();
        verify(fetch).fetchUsHistoricalRange("GOOGL", gap, today.minusDays(1));
        verify(store, never()).upsertIncompleteUsHistory(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void singleDayClosedRangeIsFetchedAndOutOfRangeVendorRowsAreIgnored() {
        when(fetch.fetchUsHistoricalRange("VOO", today.minusDays(3), today.minusDays(3)))
                .thenReturn(List.of(bar(today.minusDays(3)), bar(today.minusDays(2))));
        service.backfillUsStock("VOO", today.minusDays(3), today.minusDays(3));
        verify(fetch).fetchUsHistoricalRange("VOO", today.minusDays(3), today.minusDays(3));
        verify(store, times(1)).upsertIncompleteUsHistory(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void requestedFutureOnlyRangePerformsNoVendorOrWrite() {
        service.backfillUsStock("VOO", today, today.plusDays(1));
        verifyNoInteractions(fetch);
        verify(store, never()).upsertIncompleteUsHistory(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void atomicStoreRejectionDoesNotBecomeSuccessCount() {
        covered("VOO");
        when(fetch.fetchUsHistoricalRange(eq("VOO"), any(), any())).thenReturn(List.of(bar(today.minusDays(1))));
        assertThat(service.backfillUsStock("VOO", since, null)).isZero();
    }

    @Test void heldUsEtfsAreIncludedEvenIfLookthroughCollectionFailsAndOneCodeFails() {
        doAnswer(call -> { call.<Set<String>>getArgument(1).addAll(List.of("VOO", "VT")); return null; })
                .when(store).collectAllHeldCodes(anySet(), anySet(), anySet());
        when(store.collectLatestSnapshotHoldingsWithValue()).thenThrow(new IllegalStateException("source unavailable"));
        when(store.findMaxTradingDate("VOO", "美股")).thenThrow(new IllegalStateException("first code failed"));
        when(fetch.fetchUsHistoricalRange(eq("VT"), any(), any())).thenReturn(List.of());
        service.runDailyLookthroughBackfill();
        verify(fetch).fetchUsHistoricalRange(eq("VT"), any(), any());
        verify(fetch, never()).fetchTwHistoricalRange(any(), any(), any());
        verify(fetch, never()).fetchUkHistoricalRange(any(), any(), any());
    }
    @Test void unrelatedTaiwanFailureCannotSkipHeldUsEtfs() {
        var isolated = spy(service);
        doAnswer(call -> { call.<Set<String>>getArgument(1).add("VOO"); return null; })
                .when(store).collectAllHeldCodes(anySet(), anySet(), anySet());
        doAnswer(call -> { call.<Set<String>>getArgument(0).add("2330"); return null; })
                .when(isolated).collectLookthroughTopConstituents(anySet(), anySet());
        when(fetch.fetchTwHistoricalRange(eq("2330"), any(), any())).thenThrow(new IllegalStateException("TW failed"));
        when(fetch.fetchUsHistoricalRange(eq("VOO"), any(), any())).thenReturn(List.of());
        isolated.runDailyLookthroughBackfill();
        verify(fetch).fetchUsHistoricalRange(eq("VOO"), any(), any());
    }

}
