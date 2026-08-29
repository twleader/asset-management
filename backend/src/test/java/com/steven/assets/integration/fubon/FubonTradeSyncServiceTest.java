package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock UserAdminService userAdminService;
    @Mock BrokerRepository brokerRepository;
    @Mock AssetTransactionRepository assetTransactionRepository;
    @Mock StockMasterService stockMasterService;

    private FubonTradeOutcomeCounters counters;
    private FubonTradeSyncService service;

    @BeforeEach
    void setUp() {
        counters = new FubonTradeOutcomeCounters();
        service = build(true, false);
    }

    private FubonTradeSyncService build(boolean tradeSyncEnabled, boolean liveQuotesEnabled) {
        return new FubonTradeSyncService(configState, brokerClient, marketDataService, userAdminService,
                brokerRepository, assetTransactionRepository, stockMasterService, counters, CLOCK,
                tradeSyncEnabled, liveQuotesEnabled);
    }

    // ---- localConfigGate / tradeSyncFeatureGate --------------------------------------------

    @Test
    void featureFlagDisabledStopsBeforeAnyAdapterOrPersistenceAccess() {
        FubonTradeSyncService disabled = build(false, false);

        FubonTradeSyncService.TradeSyncResult result = disabled.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.TRADE_SYNC_DISABLED);
        verifyNoInteractions(configState, brokerClient, marketDataService, userAdminService,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    @Test
    void liveQuoteCapacityConflictStopsBeforeAnyAdapterOrPersistenceAccess() {
        FubonTradeSyncService conflict = build(true, true);

        FubonTradeSyncService.TradeSyncResult result = conflict.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.TRADE_SYNC_CAPACITY_CONFLICT);
        verifyNoInteractions(configState, brokerClient, marketDataService, userAdminService,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    @Test
    void disabledConfigStateIsLocalAndCausesZeroDownstreamCalls() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.DISABLED));

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.DISABLED);
        verifyNoInteractions(brokerClient, marketDataService, userAdminService,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    @Test
    void misconfiguredConfigStateIsLocalAndCausesZeroDownstreamCalls() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.MISCONFIGURED));

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.MISCONFIGURED);
        verifyNoInteractions(brokerClient, marketDataService, userAdminService,
                brokerRepository, assetTransactionRepository, stockMasterService);
    }

    // ---- syncManual: self-validates calendar, does not trust the caller --------------------

    @Test
    void syncManualCalendarUnknownStopsBeforeOwnerBrokerOrAdapterCalls() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.empty());

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.CALENDAR_UNKNOWN);
        verifyNoInteractions(brokerClient, userAdminService, brokerRepository,
                assetTransactionRepository, stockMasterService);
    }

    @Test
    void syncManualCalendarFalseStopsBeforeOwnerBrokerOrAdapterCalls() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(false));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonTradeOutcome.CALENDAR_UNKNOWN);
        verifyNoInteractions(brokerClient, userAdminService, brokerRepository,
                assetTransactionRepository, stockMasterService);
    }

    @Test
    void syncManualCalendarExceptionFailsClosedToCalendarUnknown() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenThrow(new IllegalStateException("unavailable"));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonTradeOutcome.CALENDAR_UNKNOWN);
        verifyNoInteractions(brokerClient, userAdminService, brokerRepository, assetTransactionRepository);
    }

    @Test
    void syncManualDelegatesToScheduledAfterCalendarOnKnownTradingDay() {
        readyConfigOnly();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(userAdminService.configuredAdmin()).thenReturn(Optional.empty());

        FubonTradeSyncService.TradeSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.NO_OWNER);
        verify(userAdminService).configuredAdmin();
    }

    // ---- syncScheduledAfterCalendar: defense-in-depth re-gate -------------------------------

    @Test
    void scheduledAfterCalendarReVerifiesGateEvenWhenCalledDirectly() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.DISABLED));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.DISABLED);
        verifyNoInteractions(brokerClient, userAdminService, brokerRepository, assetTransactionRepository);
    }

    // ---- NO_OWNER: absent / inactive / non-admin --------------------------------------------

    @Test
    void configuredAdminAbsentReturnsNoOwner() {
        readyConfigOnly();
        when(userAdminService.configuredAdmin()).thenReturn(Optional.empty());

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.NO_OWNER);
        verifyNoInteractions(brokerClient, brokerRepository, assetTransactionRepository);
    }

    @Test
    void configuredAdminPresentButInactiveReturnsNoOwner() {
        readyConfigOnly();
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(AppUser.builder()
                .id(9L).role(AppUser.ROLE_ADMIN).status("SUSPENDED").build()));

        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome())
                .isEqualTo(FubonTradeOutcome.NO_OWNER);
        verifyNoInteractions(brokerClient, brokerRepository, assetTransactionRepository);
    }

    @Test
    void configuredAdminPresentButNotAdminRoleReturnsNoOwner() {
        readyConfigOnly();
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(AppUser.builder()
                .id(9L).role("USER").status(AppUser.STATUS_ACTIVE).build()));

        assertThat(service.syncScheduledAfterCalendar(TODAY, false).outcome())
                .isEqualTo(FubonTradeOutcome.NO_OWNER);
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
                new FubonDtos.TradeBatchResponse("batch-1", TODAY, TODAY, "fp", true, List.of())));

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
        verify(assetTransactionRepository, times(1)).save(any());
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
        when(assetTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.insertedCount()).isEqualTo(1);
        ArgumentCaptor<AssetTransaction> captor = ArgumentCaptor.forClass(AssetTransaction.class);
        verify(assetTransactionRepository).save(captor.capture());
        AssetTransaction saved = captor.getValue();
        assertThat(saved.getOwnerUserId()).isEqualTo(9L);
        assertThat(saved.getTransactionType()).isEqualTo("賣");
        assertThat(saved.getAssetType()).isEqualTo("股票");
        assertThat(saved.getAssetName()).isEqualTo("台積電");
        assertThat(saved.getAssetCode()).isEqualTo("2330");
        assertThat(saved.getMarket()).isEqualTo("台股");
        assertThat(saved.getCurrency()).isEqualTo("TWD");
        assertThat(saved.getChannel()).isEqualTo("富邦證券");
        assertThat(saved.getTradeDate()).isEqualTo(TODAY);
        assertThat(saved.getShares()).isEqualByComparingTo("3");
        assertThat(saved.getPrice()).isEqualByComparingTo("12.345");
        // 12.345 * 3 = 37.035 -> HALF_UP scale 2 -> 37.04
        assertThat(saved.getAmount()).isEqualByComparingTo("37.04");
        assertThat(saved.getFee()).isNull();
        assertThat(saved.getTransactionTax()).isNull();
        assertThat(saved.getExchangeRate()).isNull();
        assertThat(saved.getNotes()).isNull();
        assertThat(saved.getSource()).isEqualTo("FUBON_SYNC");
        assertThat(saved.getBrokerFilledNo()).isEqualTo("F004");
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
        when(assetTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncScheduledAfterCalendar(TODAY, false);

        ArgumentCaptor<AssetTransaction> captor = ArgumentCaptor.forClass(AssetTransaction.class);
        verify(assetTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getTransactionType()).isEqualTo("買");
    }

    @Test
    void unexpectedTradeSideThrowsInsteadOfSilentlyTreatingAsSell() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Short", 1, "10", "10", "F006"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F006")).thenReturn(false);

        assertThatThrownBy(() -> service.syncScheduledAfterCalendar(TODAY, false))
                .isInstanceOf(IllegalStateException.class);
        verify(assetTransactionRepository, never()).save(any());
    }

    // ---- Unique-index race: caught, treated as already-exists, not thrown ---------------------

    @Test
    void concurrentUniqueIndexRaceOnSaveIsTreatedAsAlreadyExisting() {
        readyConfigOnly();
        admin();
        activeBroker();
        when(brokerClient.readFilledTrades(TODAY, TODAY)).thenReturn(FubonDtos.CallResult.success(
                batch(trade("2330", "Buy", 1, "10", "10", "F007"))));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(9L, "F007")).thenReturn(false);
        when(assetTransactionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        FubonTradeSyncService.TradeSyncResult result = service.syncScheduledAfterCalendar(TODAY, false);

        assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
        assertThat(result.insertedCount()).isZero();
        assertThat(result.skippedExistingCount()).isEqualTo(1);
    }

    // ---- helpers --------------------------------------------------------------------------

    /** For tests calling {@code syncScheduledAfterCalendar} directly: it never reads the calendar. */
    private void readyConfigOnly() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.READY));
    }

    private void admin() {
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(AppUser.builder()
                .id(9L).role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()));
    }

    private void activeBroker() {
        when(brokerRepository.findByCode("fubon")).thenReturn(Optional.of(
                BrokerEntity.builder().code("fubon").active(true).build()));
    }

    private FubonConfigState.Snapshot config(FubonConfigState.State state) {
        return new FubonConfigState.Snapshot(
                state, state == FubonConfigState.State.READY ? "token" : null, state.name());
    }

    private FubonDtos.TradeBatchResponse batch(FubonDtos.FilledTrade... trades) {
        return new FubonDtos.TradeBatchResponse("batch-1", TODAY, TODAY, "fp", false, List.of(trades));
    }

    private FubonDtos.FilledTrade trade(
            String stockCode, String side, long qty, String price, String avgPrice, String filledNo) {
        return new FubonDtos.FilledTrade(stockCode, side, qty,
                CanonicalFubonDecimal.parsePositive(price), CanonicalFubonDecimal.parsePositive(avgPrice),
                TODAY, "10:00:00", filledNo);
    }
}
