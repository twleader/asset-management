package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DividendPersisterAppendOnlyTest {

    @Test
    void historicalAndUpcomingObservationsAreBothAppendedWithoutCurrentStateWrites() {
        DividendFetchClient client = mock(DividendFetchClient.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        DividendSnapshotStore snapshots = mock(DividendSnapshotStore.class);
        var historicalEvent = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.25"), BigDecimal.ZERO,
                "2026-08-01", null, "2026-08-07", null);
        var upcomingEvent = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("0.27"), BigDecimal.ZERO,
                "2026-08-20", null, "2026-08-28", null);
        var historical = new DividendFetchClient.DividendFetchResult(
                "NASDAQ", List.of(historicalEvent), DividendFetchClient.FetchStatus.PARTIAL,
                LocalDate.of(2016, 8, 9), LocalDate.of(2026, 8, 9), Instant.now(),
                "historical provider 未證明 upcoming 45-day scope");
        var upcoming = new DividendFetchClient.DividendFetchResult(
                "NASDAQ_DIVIDEND_CALENDAR", List.of(upcomingEvent),
                DividendFetchClient.FetchStatus.COMPLETE,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 23), Instant.now(), null);
        when(client.fetchObservations("AAPL", "美股", 10))
                .thenReturn(List.of(historical, upcoming));
        when(snapshots.record(eq("AAPL"), eq("美股"), any(), any(Instant.class)))
                .thenReturn(new DividendSnapshotStore.PersistResult(7L, "hash", false, "PARTIAL"),
                        new DividendSnapshotStore.PersistResult(8L, "hash2", true, "COMPLETE"));

        int count = new DividendPersister(client, source, snapshots).syncOne("AAPL", "美股");

        assertEquals(2, count);
        verify(snapshots).record(eq("AAPL"), eq("美股"), eq(historical), any(Instant.class));
        verify(snapshots).record(eq("AAPL"), eq("美股"), eq(upcoming), any(Instant.class));
        verifyNoInteractions(source);
    }

    @Test
    void partialObservationIsStillPersistedForAudit() {
        DividendFetchClient client = mock(DividendFetchClient.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        DividendSnapshotStore snapshots = mock(DividendSnapshotStore.class);
        var fetched = new DividendFetchClient.DividendFetchResult(
                "FinMind", List.of(), DividendFetchClient.FetchStatus.PARTIAL,
                LocalDate.now().minusYears(1), LocalDate.now(), null, "historical only");
        when(client.fetchObservations("2330", "台股", 10)).thenReturn(List.of(fetched));
        when(snapshots.record(eq("2330"), eq("台股"), eq(fetched), any(Instant.class)))
                .thenReturn(new DividendSnapshotStore.PersistResult(8L, "hash", false, "PARTIAL"));

        int count = new DividendPersister(client, source, snapshots).syncOne("2330", "台股");

        assertEquals(0, count);
        verify(snapshots).record(eq("2330"), eq("台股"), eq(fetched), any(Instant.class));
        verifyNoInteractions(source);
    }
}
