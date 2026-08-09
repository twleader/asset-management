package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DividendCurrentStateProjectionServiceTest {

    private static final Instant DECISION = Instant.parse("2026-08-09T12:00:00Z");

    @Test
    void completeScopeOwnsFutureUpsertsAndCancellationAtDecisionDatePlus45() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        var event = new DividendCurrentStateRepository.Event(
                null, LocalDate.of(2026, 8, 20), new BigDecimal("0.27"), BigDecimal.ZERO,
                LocalDate.of(2026, 8, 28), null);
        var announcedDatePendingAmount = new DividendCurrentStateRepository.Event(
                2026, LocalDate.of(2026, 8, 21), null, null, null, null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                9L, "AAPL", "美股", "NASDAQ_DIVIDEND_CALENDAR",
                decisionDate, horizon, DECISION, List.of(event, announcedDatePendingAmount));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(eq("AAPL"), eq("美股"), eq(DECISION),
                any(LocalDate.class), any(LocalDate.class))).thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(
                "AAPL", "美股", decisionDate, decisionDate, horizon))
                .thenReturn(List.of(
                        new DividendCurrentStateRepository.ActiveFutureEvent(
                                7L, LocalDate.of(2026, 8, 20)),
                        new DividendCurrentStateRepository.ActiveFutureEvent(
                                8L, LocalDate.of(2026, 8, 21)),
                        new DividendCurrentStateRepository.ActiveFutureEvent(
                                9L, LocalDate.of(2026, 8, 22))));

        boolean projected = new DividendCurrentStateProjectionService(repository)
                .projectOne("AAPL", "美股", DECISION);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).findLatestComplete(eq("AAPL"), eq("美股"), eq(DECISION),
                from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(from.getValue().plusDays(45));
        verify(repository).upsertActiveEvent("AAPL", "美股", "NASDAQ_DIVIDEND_CALENDAR",
                new DividendCurrentStateRepository.ProjectedEvent(
                        2026, LocalDate.of(2026, 8, 20), new BigDecimal("0.27"),
                        BigDecimal.ZERO, LocalDate.of(2026, 8, 28), null));
        verify(repository).cancelActiveEvent(9L);
        verify(repository, never()).cancelActiveEvent(7L);
        verify(repository, never()).cancelActiveEvent(8L);
        assertThat(projected).isTrue();
    }

    @Test
    void partialHistoricalCanRestoreOccurredEventsButNeverProjectsOrCancelsFutureEvents() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        var occurred = new DividendCurrentStateRepository.Event(
                2026, LocalDate.of(2026, 8, 1), new BigDecimal("0.25"), BigDecimal.ZERO,
                null, null);
        var future = new DividendCurrentStateRepository.Event(
                2026, LocalDate.of(2026, 8, 20), new BigDecimal("0.27"), BigDecimal.ZERO,
                null, null);
        var partial = new DividendCurrentStateRepository.Snapshot(
                10L, "AAPL", "美股", "NASDAQ",
                LocalDate.of(2016, 8, 9), LocalDate.of(2026, 8, 31),
                DECISION, List.of(occurred, future));
        when(repository.findLatestHistorical("AAPL", "美股", DECISION, decisionDate))
                .thenReturn(Optional.of(partial));
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        boolean projected = new DividendCurrentStateProjectionService(repository)
                .projectOne("AAPL", "美股", DECISION);

        verify(repository).upsertHistoricalEvent("AAPL", "美股", "NASDAQ",
                new DividendCurrentStateRepository.ProjectedEvent(
                        2026, LocalDate.of(2026, 8, 1), new BigDecimal("0.25"),
                        BigDecimal.ZERO, null, null));
        verify(repository, never()).upsertActiveEvent("AAPL", "美股", "NASDAQ",
                new DividendCurrentStateRepository.ProjectedEvent(
                        2026, LocalDate.of(2026, 8, 20), new BigDecimal("0.27"),
                        BigDecimal.ZERO, null, null));
        verify(repository, never()).findActiveFutureEvents(any(), any(), any(), any(), any());
        verify(repository, never()).cancelActiveEvent(anyLong());
        assertThat(projected).isTrue();
    }

    @Test
    void completeSnapshotProjectsDistinctAmountsOnTheSameExDate() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        LocalDate exDate = LocalDate.of(2026, 8, 20);
        var first = new DividendCurrentStateRepository.Event(
                2026, exDate, new BigDecimal("0.27"), BigDecimal.ZERO, null, null);
        var second = new DividendCurrentStateRepository.Event(
                2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO, null, null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                11L, "AAPL", "美股", "NASDAQ", decisionDate, horizon, DECISION,
                List.of(first, second));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(new DividendCurrentStateProjectionService(repository)
                .projectOne("AAPL", "美股", DECISION)).isTrue();

        verify(repository).upsertActiveEvent("AAPL", "美股", "NASDAQ",
                new DividendCurrentStateRepository.ProjectedEvent(
                        2026, exDate, new BigDecimal("0.27"), BigDecimal.ZERO, null, null));
        verify(repository).upsertActiveEvent("AAPL", "美股", "NASDAQ",
                new DividendCurrentStateRepository.ProjectedEvent(
                        2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO, null, null));
    }

    @Test
    void completeRevisionCancelsOldAmountOnSameExDateInsteadOfDoubleCounting() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        LocalDate exDate = LocalDate.of(2026, 8, 20);
        var revised = new DividendCurrentStateRepository.Event(
                "new-key", 2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO,
                LocalDate.of(2026, 8, 28), null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                12L, "AAPL", "美股", "NASDAQ", decisionDate, horizon, DECISION,
                List.of(revised));
        when(repository.findLatestHistorical(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new DividendCurrentStateRepository.ActiveFutureEvent(
                        41L, "old-key", 2026, exDate, new BigDecimal("0.27"), BigDecimal.ZERO,
                        LocalDate.of(2026, 8, 28), null)));

        assertThat(new DividendCurrentStateProjectionService(repository)
                .projectOne("AAPL", "美股", DECISION)).isTrue();

        verify(repository).upsertActiveEvent("AAPL", "美股", "NASDAQ",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "new-key", 2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO,
                        LocalDate.of(2026, 8, 28), null));
        verify(repository).cancelActiveEvent(41L);
    }

    @Test
    void sameDateTwoEventsCancelOnlyTheOmittedCanonicalIdentity() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        LocalDate exDate = LocalDate.of(2026, 8, 20);
        var kept = new DividendCurrentStateRepository.Event(
                "cash-key", 2026, exDate, new BigDecimal("0.27"), BigDecimal.ZERO, null, null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                13L, "AAPL", "美股", "NASDAQ", decisionDate, horizon, DECISION,
                List.of(kept));
        when(repository.findLatestHistorical(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        new DividendCurrentStateRepository.ActiveFutureEvent(
                                51L, "cash-key", 2026, exDate, new BigDecimal("0.27"), BigDecimal.ZERO,
                                null, null),
                        new DividendCurrentStateRepository.ActiveFutureEvent(
                                52L, "stock-key", 2026, exDate, BigDecimal.ZERO, new BigDecimal("0.02"),
                                null, null)));

        assertThat(new DividendCurrentStateProjectionService(repository)
                .projectOne("AAPL", "美股", DECISION)).isTrue();

        verify(repository, never()).cancelActiveEvent(51L);
        verify(repository).cancelActiveEvent(52L);
    }

    @Test
    void providerCorrectionWithSameCanonicalKeyUpdatesOneIdentity() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        LocalDate exDate = LocalDate.of(2026, 8, 20);
        var event = new DividendCurrentStateRepository.Event(
                "stable-key", 2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO, null, null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                14L, "AAPL", "美股", "TPEx", decisionDate, horizon, DECISION, List.of(event));
        when(repository.findLatestHistorical(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new DividendCurrentStateRepository.ActiveFutureEvent(
                        61L, "stable-key", 2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO,
                        null, null)));

        assertThat(new DividendCurrentStateProjectionService(repository)
                .projectOne("AAPL", "美股", DECISION)).isTrue();

        verify(repository, never()).cancelActiveEvent(anyLong());
        verify(repository).upsertActiveEvent("AAPL", "美股", "TPEx",
                new DividendCurrentStateRepository.ProjectedEvent(
                        "stable-key", 2026, exDate, new BigDecimal("0.31"), BigDecimal.ZERO, null, null));
    }

    @Test
    void missingCompleteAndHistoricalEvidenceLeavesCurrentStateUntouched() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        boolean projected = new DividendCurrentStateProjectionService(repository)
                .projectOne("2330", "台股", Instant.parse("2026-08-09T02:00:00Z"));

        assertThat(projected).isFalse();
        verify(repository, never()).upsertActiveEvent(any(), any(), any(), any());
        verify(repository, never()).findActiveFutureEvents(any(), any(), any(), any(), any());
        verify(repository, never()).cancelActiveEvent(anyLong());
    }
}
