package com.steven.assets.integration.fubon;

import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FubonTradeSyncService} business logic (Requirement 120 / Task 385): local gate
 * short-circuit, calendar re-verification in {@code syncManual}, defense-in-depth re-gate in
 * {@code syncScheduledAfterCalendar}, owner/broker preflight, 385.6 field mapping and idempotency.
 */
@ExtendWith(MockitoExtension.class)
class FubonTradeSyncServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.UTC);

    @Mock FubonConfigState configState;
    @Mock FubonBrokerClient brokerClient;
    @Mock MarketDataService marketDataService;
    @Mock FubonSyncOwnerPort ownerPolicy;
    @Mock BrokerRepository brokerRepository;
    @Mock AssetTransactionRepository assetTransactionRepository;
    @Mock StockMasterService stockMasterService;
    @Mock FubonTradeWriter writer;

    private FubonTradeOutcomeCounters counters;
    private FubonTradeSyncService service;

    @BeforeEach
    void setUp() {
        counters = new FubonTradeOutcomeCounters();
        service = build(true);
        lenient().when(writer.insert(any(), any(), any())).thenAnswer(call ->
                new FubonTradeWriter.CommitResult(((List<?>) call.getArgument(2)).size(), 0));
    }

    private FubonTradeSyncService build(boolean tradeSyncEnabled) {
        return new FubonTradeSyncService(configState, brokerClient, marketDataService, ownerPolicy,
                brokerRepository, assetTransactionRepository, stockMasterService, writer, counters, CLOCK,
                tradeSyncEnabled);
    }

    // ---- localConfigGate / tradeSyncFeatureGate --------------------------------------------

    @Test
    void featureFlagDisabledStopsBeforeAnyAdapterOrPersistenceAccess() {
        FubonTradeSyncService disabled = build(false);

        FubonTradeSyncService.TradeSyncResult result = disabled.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.TRADE_SYNC_DISABLED);
        verifyNoInteractions(configState, brokerClient, marketDataService, ownerPolicy,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    @Test
    void simultaneousLiveAndTradeFlagsStillUseTheReadOnlyAdapter() {
        // LIVE is deliberately no longer a constructor input or local gate.  The
        // trade reader must continue through its own READY/calendar/owner gates.
        FubonTradeSyncService enabled = build(true);
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                new FubonDtos.TradeBatchResponse("batch-1", TODAY, TODAY,
                        "0123456789abcdef01234567", true, List.of())));

        FubonTradeSyncService.TradeSyncResult result = enabled.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.NO_NEW_TRADES);
        verify(brokerClient).readFilledTrades(TODAY, TODAY);
    }

    @Test
    void disabledConfigStateIsLocalAndCausesZeroDownstreamCalls() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.DISABLED));

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.DISABLED);
        verifyNoInteractions(brokerClient, marketDataService, ownerPolicy,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    @Test
    void misconfiguredConfigStateIsLocalAndCausesZeroDownstreamCalls() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.MISCONFIGURED));

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.MISCONFIGURED);
        verifyNoInteractions(brokerClient, marketDataService, ownerPolicy,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    // ---- syncManual: self-validates calendar, does not trust the caller --------------------

    @Test
    void syncManualCalendarUnknownStopsBeforeOwnerBrokerOrAdapterCalls() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.empty());

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.CALENDAR_UNKNOWN);
        verifyNoInteractions(brokerClient, ownerPolicy, brokerRepository,
                assetTransactionRepository, stockMasterService);
    }

    @Test
    void syncManualCalendarFalseStopsBeforeOwnerBrokerOrAdapterCalls() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(false));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonTradeOutcome.CALENDAR_UNKNOWN);
        verifyNoInteractions(brokerClient, ownerPolicy, brokerRepository,
                assetTransactionRepository, stockMasterService);
    }

    @Test
    void syncManualCalendarExceptionFailsClosedToCalendarUnknown() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenThrow(new IllegalStateException("unavailable"));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonTradeOutcome.CALENDAR_UNKNOWN);
        verifyNoInteractions(brokerClient, ownerPolicy, brokerRepository, assetTransactionRepository);
    }

    @Test
    void syncManualDelegatesToScheduledAfterCalendarOnKnownTradingDay() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(ownerPolicy.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(null, FubonSyncOwnerPort.Denial.NO_OWNER));

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.NO_OWNER);
        verify(ownerPolicy).preflight();
    }

    // ---- syncScheduledAfterCalendar: defense-in-depth re-gate -------------------------------

    @Test
    void scheduledAfterCalendarReVerifiesGateEvenWhenCalledDirectly() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.DISABLED));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.DISABLED);
        verifyNoInteractions(brokerClient, ownerPolicy, brokerRepository, assetTransactionRepository);
    }

    // ---- Dedicated owner denial ---------------------------------------------------------------

    @Test
    void dedicatedOwnerAbsenceReturnsNoOwnerBeforeBrokerOrAdapter() {
        readyConfigOnly();
        when(ownerPolicy.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(null, FubonSyncOwnerPort.Denial.NO_OWNER));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.NO_OWNER);
        verifyNoInteractions(brokerClient, brokerRepository, assetTransactionRepository);
    }

    @Test
    void dedicatedOwnerConfigMissingHasDistinctOutcomeReasonAndCounter() {
        readyConfigOnly();
        when(ownerPolicy.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(null,
                FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SYNC_OWNER_NOT_CONFIGURED);
        assertThat(result.reason()).isEqualTo("SYNC_OWNER_NOT_CONFIGURED");
        assertThat(result.counters().get(FubonTradeOutcome.SYNC_OWNER_NOT_CONFIGURED)).isEqualTo(1L);
        verifyNoInteractions(brokerClient, brokerRepository, assetTransactionRepository);
    }

    // ---- BROKER_MISSING: absent / inactive --------------------------------------------------

    @Test
    void fubonBrokerNotFoundReturnsBrokerMissing() {
        readyConfigOnly();
        admin();
        when(brokerRepository.findByCode("fubon")).thenReturn(Optional.empty());

        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome())
                .isEqualTo(FubonTradeOutcome.BROKER_MISSING);
        verifyNoInteractions(brokerClient, assetTransactionRepository);
    }

    @Test
    void fubonBrokerSoftDisabledReturnsBrokerMissing() {
        readyConfigOnly();
        admin();
        when(brokerRepository.findByCode("fubon")).thenReturn(Optional.of(
                BrokerEntity.builder().code("fubon").active(false).build()));

        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome())
                .isEqualTo(FubonTradeOutcome.BROKER_MISSING);
        verifyNoInteractions(brokerClient, assetTransactionRepository);
    }

    // ---- TRADE_FAILED / NO_NEW_TRADES --------------------------------------------------------

    @Test
    void adapterFailureReturnsTradeFailed() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.failure("TIMEOUT"));

        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome())
                .isEqualTo(FubonTradeOutcome.TRADE_FAILED);
        verifyNoInteractions(assetTransactionRepository, stockMasterService);
    }

    @Test
    void nullBodyReturnsTradeFailed() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(null));

        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome())
                .isEqualTo(FubonTradeOutcome.TRADE_FAILED);
    }

    @Test
    void emptyConfirmedBatchReturnsNoNewTrades() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                new FubonDtos.TradeBatchResponse("batch-1", TODAY, TODAY, "0123456789abcdef01234567", true, List.of())));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.NO_NEW_TRADES);
        verifyNoInteractions(assetTransactionRepository, stockMasterService);
    }

    // ---- DRY_RUN ------------------------------------------------------------------------------

    @Test
    void dryRunNeverWritesEvenWithNonEmptyPendingList() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 3, "12.345", "12.345", "F001"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F001")).thenReturn(false);

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, true);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.DRY_RUN);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.tradeCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isZero();
        verify(assetTransactionRepository, never()).save(any());
    }

    // ---- Name unresolved: skip row, do not abort batch ---------------------------------------

    @Test
    void nameUnresolvedSkipsThatRowButStillInsertsTheRest() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(batch(
                trade("9999", "Buy", 1, "10", "10", "F-UNRESOLVED"),
                trade("2330", "Buy", 3, "12.345", "12.345", "F002"))));
        when(stockMasterService.resolveNameLocalOnly("9999", "台股")).thenReturn("9999");
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F002")).thenReturn(false);

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.skippedNameUnresolvedCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        verify(writer).insert(eq(9L), eq(3L), any());
        verify(assetTransactionRepository, never())
                .existsByOwnerUserIdAndBrokerFilledNo(9L, "F-UNRESOLVED");
    }

    // ---- Already-exists: skip, do not overwrite -----------------------------------------------

    @Test
    void alreadyExistingFilledNoIsSkippedNotOverwritten() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 3, "12.345", "12.345", "F003"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F003")).thenReturn(true);

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.skippedExistingCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isZero();
        verify(assetTransactionRepository, never()).save(any());
    }

    // ---- Successful insert: exact 385.6 field mapping ------------------------------------------

    @Test
    void successfulInsertMapsEveryFieldPerSpec() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Sell", 3, "12.345", "12.345", "F004"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F004")).thenReturn(false);

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.insertedCount()).isEqualTo(1);
        ArgumentCaptor<List<FubonTradeWriter.PreparedTrade>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insert(eq(9L), eq(3L), captor.capture());
        FubonTradeWriter.PreparedTrade saved = captor.getValue().getFirst();
        assertThat(saved.transactionType()).isEqualTo("賣");
        assertThat(saved.stockName()).isEqualTo("台積電");
        assertThat(saved.stockCode()).isEqualTo("2330");
        assertThat(saved.tradeDate()).isEqualTo(TODAY);
        assertThat(saved.shares()).isEqualByComparingTo("3");
        assertThat(saved.price()).isEqualByComparingTo("12.345");
        // 12.345 * 3 = 37.035 -> HALF_UP scale 2 -> 37.04
        assertThat(saved.amount()).isEqualByComparingTo("37.04");
        assertThat(saved.filledNo()).isEqualTo("F004");
    }

    @Test
    void buySideMapsToChineseBuyLabel() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 1, "10", "10", "F005"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F005")).thenReturn(false);

        service.syncScheduledAfterCalendar(TODAY, false);

        ArgumentCaptor<List<FubonTradeWriter.PreparedTrade>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insert(eq(9L), eq(3L), captor.capture());
        assertThat(captor.getValue().getFirst().transactionType()).isEqualTo("買");
    }

    @Test
    void unexpectedTradeSideRejectsTheWholeBatchBeforeNameResolution() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Short", 1, "10", "10", "F006"))));
        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome()).isEqualTo(FubonTradeOutcome.TRADE_FAILED);
        verifyNoInteractions(writer, assetTransactionRepository, stockMasterService);
    }

    // ---- A committed writer result alone supplies the insert/duplicate counts ----------------

    @Test
    void committedDuplicateCountFromWriterIsAddedToTheResponse() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 1, "10", "10", "F007"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F007")).thenReturn(false);
        org.mockito.Mockito.doReturn(new FubonTradeWriter.CommitResult(0, 1)).when(writer).insert(eq(9L), eq(3L), any());

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.insertedCount()).isZero();
        assertThat(result.skippedExistingCount()).isEqualTo(1);
    }

    @ParameterizedTest @MethodSource("invalidBatches")
    void invalidBatchNeverReachesNamesDuplicateReadsOrWriter(FubonDtos.TradeBatchResponse body) {
        readyConfigOnly(); admin(); activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(body));
        var result = service.syncScheduledAfterCalendar(TODAY, false);
        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.TRADE_FAILED);
        assertThat(result.insertedCount()).isZero();
        verifyNoInteractions(stockMasterService, assetTransactionRepository, writer);
    }

    static Stream<FubonDtos.TradeBatchResponse> invalidBatches() {
        String fp = "0123456789abcdef01234567";
        var price = CanonicalFubonDecimal.parsePositive("12.345");
        var first = new FubonDtos.FilledTrade("2330", "Buy", 3, price, price, TODAY, "09:00", "first");
        List<FubonDtos.TradeBatchResponse> batches = new ArrayList<>(List.of(
                new FubonDtos.TradeBatchResponse("batch", TODAY.minusDays(1), TODAY, fp, false, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY.plusDays(1), fp, false, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch", null, TODAY, fp, false, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, "wrong-fingerprint", false, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, fp.toUpperCase(), false, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch?", TODAY, TODAY, fp, false, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, fp, true, List.of(first)),
                new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, fp, false, List.of()),
                new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, fp, true, null)));
        List<FubonDtos.FilledTrade> badRows = java.util.Arrays.asList(null,
                new FubonDtos.FilledTrade("0000", "Buy", 3, price, price, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("bad code", "Buy", 3, price, price, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Short", 3, price, price, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 0, price, price, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 10_000_000_000L, price, price, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, null, price, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, null, TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, CanonicalFubonDecimal.parseNonNegative("0"), TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, price, TODAY.minusDays(1), "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, price, TODAY.plusDays(1), "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, price, TODAY, "09:00", ""),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, price, TODAY, "09:00", "x".repeat(51)),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, price, TODAY, "", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, CanonicalFubonDecimal.parsePositive("12.3450001"), TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 3, price, CanonicalFubonDecimal.parsePositive("100000000000"), TODAY, "09:00", "second"),
                new FubonDtos.FilledTrade("0050", "Buy", 9_999_999_999L, price, CanonicalFubonDecimal.parsePositive("1000000000"), TODAY, "09:00", "second"));
        badRows.forEach(row -> batches.add(new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, fp,
                false, java.util.Arrays.asList(first, row))));
        return batches.stream();
    }

    @ParameterizedTest @ValueSource(strings = {"12.3450000000", "99999999999.999999"})
    void exactlyRepresentablePriceRetainsItsNumericValue(String price) {
        readyConfigOnly(); admin(); activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 1, price, price, "exact-price"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        ArgumentCaptor<List<FubonTradeWriter.PreparedTrade>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insert(eq(9L), eq(3L), captor.capture());
        assertThat(captor.getValue().getFirst().price()).isEqualByComparingTo(price);
    }

    @Test void unexpectedWriteFailureIsSanitizedAndNeverCountedAsDuplicateOrSuccess() {
        readyConfigOnly(); admin(); activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 1, "10", "10", "failed"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("private-row-value"))
                .when(writer).insert(any(), any(), any());
        assertThatThrownBy(() -> service.syncScheduledAfterCalendar(TODAY, false))
                .isInstanceOf(IllegalStateException.class).hasMessage("TRANSACTION_ROLLED_BACK").hasNoCause();
        assertThat(counters.snapshot().get(FubonTradeOutcome.SUCCESS)).isZero();
        assertThat(counters.snapshot().get(FubonTradeOutcome.ROLLED_BACK)).isEqualTo(1);
    }

    @Test void checkedBatchOwnsItsRowsInsteadOfSharingTheAdapterList() {
        var rows = new ArrayList<>(List.of(trade("2330", "Buy", 1, "10", "10", "first")));
        var batch = new FubonDtos.TradeBatchResponse("batch", TODAY, TODAY, "0123456789abcdef01234567", false, rows);
        rows.clear();
        assertThat(batch.trades()).hasSize(1);
        assertThatThrownBy(() -> batch.trades().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "2330"})
    void unusableLocalNameIsSkippedWithoutInventingANameOrOpeningAWriter(String name) {
        readyConfigOnly(); admin(); activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 1, "10", "10", "unnamed"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn(name);
        var result = service.syncScheduledAfterCalendar(TODAY, false);
        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.skippedNameUnresolvedCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isZero();
        verifyNoInteractions(writer, assetTransactionRepository);
    }

    // ---- helpers --------------------------------------------------------------------------

    /** For tests calling {@code syncScheduledAfterCalendar} directly: it never reads the calendar. */
    private void readyConfigOnly() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.READY));
    }

    private void admin() {
        when(ownerPolicy.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(9L, null));
    }

    private void activeBroker() {
        when(brokerRepository.findByCode("fubon")).thenReturn(Optional.of(
                BrokerEntity.builder().id(3L).code("fubon").active(true).build()));
    }

    private FubonConfigState.Snapshot config(FubonConfigState.State state) {
        return new FubonConfigState.Snapshot(
                state, state == FubonConfigState.State.READY ? "token" : null, state.name());
    }

    private FubonDtos.TradeBatchResponse batch(FubonDtos.FilledTrade... trades) {
        return new FubonDtos.TradeBatchResponse("batch-1", TODAY, TODAY, "0123456789abcdef01234567", false, List.of(trades));
    }

    private FubonDtos.FilledTrade trade(
            String stockCode, String side, long qty, String price, String avgPrice, String filledNo) {
        return new FubonDtos.FilledTrade(stockCode, side, qty,
                CanonicalFubonDecimal.parsePositive(price), CanonicalFubonDecimal.parsePositive(avgPrice),
                TODAY, "10:00:00", filledNo);
    }
}
