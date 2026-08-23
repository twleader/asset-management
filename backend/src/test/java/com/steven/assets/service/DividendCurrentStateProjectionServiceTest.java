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
    void collapseMergesEnrichmentOntoKeeperAndCancelsBareDuplicate() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        when(repository.findActiveEventDetails("00881", "台股")).thenReturn(List.of(
                new DividendCurrentStateRepository.ActiveEventDetail(
                        151L, null, 2026, LocalDate.of(2026, 1, 20), new BigDecimal("2.65"),
                        null, LocalDate.of(2026, 2, 12), null, new BigDecimal("7.3878"),
                        new BigDecimal("35.87"), 3),
                new DividendCurrentStateRepository.ActiveEventDetail(
                        1024L, "2b6394", 2026, LocalDate.of(2026, 1, 20),
                        new BigDecimal("2.650000"), new BigDecimal("0.000000"),
                        null, null, null, null, null)));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("00881", "台股", DECISION);

        verify(repository).cancelActiveEvent(1024L);
        verify(repository, never()).cancelActiveEvent(151L);
        verify(repository).applyMergedEnrichment(151L, "2b6394", LocalDate.of(2026, 2, 12),
                null, new BigDecimal("7.3878"), new BigDecimal("35.87"), 3);
    }

    @Test
    void collapseCancelsEveryBareDuplicateInAThreeRowGroup() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        when(repository.findActiveEventDetails("00881", "台股")).thenReturn(List.of(
                new DividendCurrentStateRepository.ActiveEventDetail(
                        153L, null, 2025, LocalDate.of(2025, 1, 17), new BigDecimal("0.75"),
                        null, LocalDate.of(2025, 2, 18), null, new BigDecimal("3.0475"),
                        null, null),
                new DividendCurrentStateRepository.ActiveEventDetail(
                        1023L, "1ab2dc", 2025, LocalDate.of(2025, 1, 17),
                        new BigDecimal("0.750000"), new BigDecimal("0.000000"),
                        null, null, null, null, null),
                new DividendCurrentStateRepository.ActiveEventDetail(
                        1030L, "9f00aa", 2025, LocalDate.of(2025, 1, 17),
                        new BigDecimal("0.75"), null, null, null, null, null, null)));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("00881", "台股", DECISION);

        verify(repository).cancelActiveEvent(1023L);
        verify(repository).cancelActiveEvent(1030L);
        verify(repository, never()).cancelActiveEvent(153L);
        verify(repository).applyMergedEnrichment(153L, "1ab2dc", LocalDate.of(2025, 2, 18),
                null, new BigDecimal("3.0475"), null, null);
    }

    @Test
    void collapseRunsEvenWhenNoSnapshotEvidenceExists() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        when(repository.findActiveEventDetails("00881", "台股")).thenReturn(List.of(
                new DividendCurrentStateRepository.ActiveEventDetail(
                        151L, null, 2026, LocalDate.of(2026, 1, 20), new BigDecimal("2.65"),
                        null, LocalDate.of(2026, 2, 12), null, null, null, null),
                new DividendCurrentStateRepository.ActiveEventDetail(
                        1024L, "2b6394", 2026, LocalDate.of(2026, 1, 20),
                        new BigDecimal("2.65"), null, null, null, null, null, null)));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        boolean projected = new DividendCurrentStateProjectionService(repository)
                .projectOne("00881", "台股", DECISION);

        assertThat(projected).isFalse();
        verify(repository).cancelActiveEvent(1024L);
        verify(repository).applyMergedEnrichment(
                eq(151L), eq("2b6394"), eq(LocalDate.of(2026, 2, 12)), any(), any(), any(), any());
    }

    @Test
    void cancelPassKeepsSameDateSameAmountRowDespiteDifferentKeyAndPaymentDate() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        var authoritative = new DividendCurrentStateRepository.Event(
                "a31f09", 2026, LocalDate.of(2026, 8, 18), new BigDecimal("4.60"),
                BigDecimal.ZERO, null, null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                15L, "00881", "台股", "FinMind", decisionDate, horizon, DECISION,
                List.of(authoritative));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new DividendCurrentStateRepository.ActiveFutureEvent(
                        1122L, "d448df", 2026, LocalDate.of(2026, 8, 18),
                        new BigDecimal("4.600000"), null, LocalDate.of(2026, 9, 11), null)));

        assertThat(new DividendCurrentStateProjectionService(repository)
                .projectOne("00881", "台股", DECISION)).isTrue();

        verify(repository, never()).cancelActiveEvent(anyLong());
    }

    @Test
    void cancelPassStillCancelsDifferentAmountOnTheSameExDate() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        var authoritative = new DividendCurrentStateRepository.Event(
                "a31f09", 2026, LocalDate.of(2026, 8, 18), new BigDecimal("4.60"),
                BigDecimal.ZERO, null, null);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                16L, "00881", "台股", "FinMind", decisionDate, horizon, DECISION,
                List.of(authoritative));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new DividendCurrentStateRepository.ActiveFutureEvent(
                        999L, null, 2026, LocalDate.of(2026, 8, 18),
                        new BigDecimal("2.00"), BigDecimal.ZERO, null, null)));

        assertThat(new DividendCurrentStateProjectionService(repository)
                .projectOne("00881", "台股", DECISION)).isTrue();

        verify(repository).cancelActiveEvent(999L);
    }

    /**
     * Task 357／357.3d-0：純配股事件的 exDividendDate 為 null，withinScope() 若仍用裸
     * {@code exDividendDate() != null} 會把這類事件整批擋在投影閘門外；改用 anchorDate
     * 後必須實際通過並帶著 exRightsDate 落地成 ProjectedEvent（357.3a-0b：不得讓相容
     * 建構式把這個新欄位靜默吃成 null）。
     */
    @Test
    void pureStockEventWithNullExDividendDatePassesWithinScopeAndCarriesExRightsDate() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        LocalDate decisionDate = LocalDate.of(2026, 8, 9);
        LocalDate horizon = decisionDate.plusDays(45);
        LocalDate exRights = LocalDate.of(2026, 8, 20);
        var pureStock = new DividendCurrentStateRepository.Event(
                "rights-key", 2026, null, BigDecimal.ZERO, new BigDecimal("0.30"), null, null, exRights);
        var snapshot = new DividendCurrentStateRepository.Snapshot(
                20L, "2885", "台股", "FinMind", decisionDate, horizon, DECISION, List.of(pureStock));
        when(repository.findLatestHistorical(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findLatestComplete(eq("2885"), eq("台股"), eq(DECISION),
                any(LocalDate.class), any(LocalDate.class))).thenReturn(Optional.of(snapshot));
        when(repository.findActiveFutureEvents(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        boolean projected = new DividendCurrentStateProjectionService(repository)
                .projectOne("2885", "台股", DECISION);

        assertThat(projected).isTrue();
        ArgumentCaptor<DividendCurrentStateRepository.ProjectedEvent> captor =
                ArgumentCaptor.forClass(DividendCurrentStateRepository.ProjectedEvent.class);
        verify(repository).upsertActiveEvent(eq("2885"), eq("台股"), eq("FinMind"), captor.capture());
        assertThat(captor.getValue().exDividendDate()).isNull();
        assertThat(captor.getValue().exRightsDate()).isEqualTo(exRights);
        assertThat(captor.getValue().anchorDate()).isEqualTo(exRights);
    }

    /**
     * Task 357／357.3d-0c（回歸測試，真實資料）：2885 於 2022-08-12 與 2025-08-12 各有一筆
     * 純配股事件、{@code stock_dividend} 皆為 0.300000（運行中 DB 實測）。除息日拆欄後兩者的
     * exDividendDate 皆為 null；{@code relaxedIdentity()} 若仍用裸 exDividendDate 而非
     * anchorDate，兩者的 identity 會退化成相同的 {@code "NULL|0|0.3"} 而在
     * collapseDuplicateActiveEvents() 被誤判為同一事件、其中一筆被 cancel。改用 anchorDate
     * （= exRightsDate）後兩者的 anchorDate 分屬不同年份，identity 不再碰撞。
     */
    @Test
    void twoRealPureStockEventsWithSameAmountDifferentYearsAreNotCollapsedTogether() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        var event2022 = new DividendCurrentStateRepository.ActiveEventDetail(
                201L, "2885-2022-key", 2022, null, BigDecimal.ZERO, new BigDecimal("0.300000"),
                null, null, null, null, null, LocalDate.of(2022, 8, 12));
        var event2025 = new DividendCurrentStateRepository.ActiveEventDetail(
                202L, "2885-2025-key", 2025, null, BigDecimal.ZERO, new BigDecimal("0.300000"),
                null, null, null, null, null, LocalDate.of(2025, 8, 12));
        when(repository.findActiveEventDetails("2885", "台股"))
                .thenReturn(List.of(event2022, event2025));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("2885", "台股", DECISION);

        // 兩筆真實事件必須各自維持 ACTIVE、互不覆蓋——這正是本回歸測試要釘住的行為。
        verify(repository, never()).cancelActiveEvent(201L);
        verify(repository, never()).cancelActiveEvent(202L);
        verify(repository, never()).applyMergedEnrichment(
                anyLong(), any(), any(), any(), any(), any(), any());
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

    /**
     * Task 361.3h(1)：純配股事件的 keeper 判準——重現 2885 的實際情境。錯誤列
     * （ex_dividend_date=除權日、ex_rights_date=NULL）id 較小，正確列（NULL、除權日）id
     * 較大；361.2e 前的舊判準會退回「最小 id」而保留錯誤列，本測試釘住修正後保留正確列。
     */
    @Test
    void collapsePrefersDateAllocationConsistentPureStockRowOverSmallerIdMisplacedRow() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        var misplaced = new DividendCurrentStateRepository.ActiveEventDetail(
                1004L, "old-key", 2026, LocalDate.of(2026, 8, 18), BigDecimal.ZERO,
                new BigDecimal("0.400000"), null, null, null, null, null, null);
        var correct = new DividendCurrentStateRepository.ActiveEventDetail(
                1212L, "new-key", 2026, null, BigDecimal.ZERO, new BigDecimal("0.400000"),
                null, null, null, null, null, LocalDate.of(2026, 8, 18));
        when(repository.findActiveEventDetails("2885", "台股"))
                .thenReturn(List.of(misplaced, correct));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("2885", "台股", DECISION);

        verify(repository).cancelActiveEvent(1004L);
        verify(repository, never()).cancelActiveEvent(1212L);
        verify(repository).applyMergedEnrichment(
                eq(1212L), any(), any(), any(), any(), any(), any());
    }

    /** Task 361.3h(2)：對稱案例——純現金事件。 */
    @Test
    void collapsePrefersDateAllocationConsistentPureCashRowOverMisplacedRow() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        var misplaced = new DividendCurrentStateRepository.ActiveEventDetail(
                2001L, "old-key", 2026, null, new BigDecimal("1.800000"), BigDecimal.ZERO,
                null, null, null, null, null, LocalDate.of(2026, 7, 21));
        var correct = new DividendCurrentStateRepository.ActiveEventDetail(
                2002L, "new-key", 2026, LocalDate.of(2026, 7, 21),
                new BigDecimal("1.800000"), BigDecimal.ZERO, null, null, null, null, null, null);
        when(repository.findActiveEventDetails("2885", "台股"))
                .thenReturn(List.of(misplaced, correct));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("2885", "台股", DECISION);

        verify(repository).cancelActiveEvent(2001L);
        verify(repository, never()).cancelActiveEvent(2002L);
    }

    /**
     * Task 361.3h(3)：兩列皆自洽時，既有三層判準（發放日 → yieldPct → 最小 id）行為不變
     * ——沿用既有 {@code collapseMergesEnrichmentOntoKeeperAndCancelsBareDuplicate} 的資料
     * 形狀，兩列皆為現金事件且 ex_rights_date 皆為 null（自洽），有 cashPaymentDate／yieldPct
     * 那列仍應勝出。
     */
    @Test
    void collapseKeepsExistingThreeTierPreferenceWhenBothRowsAreDateAllocationConsistent() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        var enriched = new DividendCurrentStateRepository.ActiveEventDetail(
                151L, null, 2026, LocalDate.of(2026, 1, 20), new BigDecimal("2.65"),
                null, LocalDate.of(2026, 2, 12), null, new BigDecimal("7.3878"),
                new BigDecimal("35.87"), 3);
        var bare = new DividendCurrentStateRepository.ActiveEventDetail(
                1024L, "2b6394", 2026, LocalDate.of(2026, 1, 20),
                new BigDecimal("2.650000"), new BigDecimal("0.000000"),
                null, null, null, null, null);
        when(repository.findActiveEventDetails("00881", "台股")).thenReturn(List.of(enriched, bare));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("00881", "台股", DECISION);

        verify(repository).cancelActiveEvent(1024L);
        verify(repository, never()).cancelActiveEvent(151L);
    }

    /**
     * Task 361.3h(4)：同時配息又配股的事件（cash&gt;0 且 stock&gt;0）不受新判準影響——
     * 兩個日期欄本來就都該有值，不落入純配股／純現金分支，仍走既有三層判準。
     */
    @Test
    void collapseUnaffectedForCashAndStockComboEvent() {
        DividendCurrentStateRepository repository = mock(DividendCurrentStateRepository.class);
        var withPaymentDate = new DividendCurrentStateRepository.ActiveEventDetail(
                301L, "combo-a", 2026, LocalDate.of(2026, 7, 21), new BigDecimal("1.00"),
                new BigDecimal("0.20"), LocalDate.of(2026, 8, 5), null, null, null, null,
                LocalDate.of(2026, 7, 21));
        var bareDuplicate = new DividendCurrentStateRepository.ActiveEventDetail(
                302L, "combo-b", 2026, LocalDate.of(2026, 7, 21), new BigDecimal("1.000000"),
                new BigDecimal("0.200000"), null, null, null, null, null,
                LocalDate.of(2026, 7, 21));
        when(repository.findActiveEventDetails("2885", "台股"))
                .thenReturn(List.of(withPaymentDate, bareDuplicate));
        when(repository.findLatestHistorical(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findLatestComplete(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        new DividendCurrentStateProjectionService(repository)
                .projectOne("2885", "台股", DECISION);

        verify(repository).cancelActiveEvent(302L);
        verify(repository, never()).cancelActiveEvent(301L);
    }
}
