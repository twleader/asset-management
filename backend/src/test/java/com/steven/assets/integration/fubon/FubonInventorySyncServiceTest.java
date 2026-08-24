package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FubonInventorySyncServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

    @Mock FubonConfigState configState;
    @Mock FubonBrokerClient brokerClient;
    @Mock MarketDataService marketDataService;
    @Mock UserAdminService userAdminService;
    @Mock AssetSnapshotRepository snapshotRepository;
    @Mock BrokerRepository brokerRepository;
    @Mock StockRepository stockRepository;
    @Mock FubonInventoryWriter writer;

    private FubonOutcomeCounters counters;
    private FubonInventorySyncService service;

    @BeforeEach
    void setUp() {
        counters = new FubonOutcomeCounters();
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.UTC);
        service = new FubonInventorySyncService(configState, brokerClient, marketDataService,
                userAdminService, snapshotRepository, brokerRepository, stockRepository,
                writer, counters, clock);
    }

    @Test
    void disabledIsLocalAndCausesZeroHttpCalendarOrDatabaseCalls() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.DISABLED, null));

        FubonDtos.SyncResponse result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.DISABLED);
        verifyNoInteractions(brokerClient, marketDataService, userAdminService, snapshotRepository,
                brokerRepository, stockRepository, writer);
    }

    @Test
    void inventoryFlagDisabledStopsBeforeAnyAdapterOrPersistenceAccess() {
        FubonInventorySyncService disabled = configured(false, false);
        FubonDtos.SyncResponse result = disabled.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonOutcome.INVENTORY_SYNC_DISABLED);
        verifyNoInteractions(configState, brokerClient, marketDataService, userAdminService, snapshotRepository,
                brokerRepository, stockRepository, writer);
    }

    @Test
    void simultaneousLiveAndInventoryFlagsStopBeforeAnyAdapterOrPersistenceAccess() {
        FubonInventorySyncService conflict = configured(true, true);
        FubonDtos.SyncResponse result = conflict.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonOutcome.INVENTORY_SYNC_CAPACITY_CONFLICT);
        verifyNoInteractions(configState, brokerClient, marketDataService, userAdminService, snapshotRepository,
                brokerRepository, stockRepository, writer);
    }

    @Test
    void calendarUnknownStopsAfterAccountingAndNeverCallsQuoteOrDatabase() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.empty());

        FubonDtos.SyncResponse result = service.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.CALENDAR_UNKNOWN);
        assertThat(result.positionCount()).isEqualTo(1);
        verify(brokerClient, never()).readTwQuotes(anyList());
        verifyNoInteractions(userAdminService, snapshotRepository, brokerRepository, stockRepository, writer);
    }

    @Test
    void stalePortfolioQueryDateFailsBeforeCalendarFingerprintIsNeverEchoedUnsafely() {
        ready();
        FubonDtos.PortfolioResponse stale = new FubonDtos.PortfolioResponse(
                "batch-1", TODAY.minusDays(1), "abcdefabcdefabcd", false,
                List.of(position("2330")), null, Map.of());
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(stale));

        FubonDtos.SyncResponse result = service.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.RECONCILE_FAILED);
        assertThat(result.reason()).isEqualTo("INVALID_PORTFOLIO_BATCH");
        verifyNoInteractions(marketDataService, userAdminService, snapshotRepository,
                brokerRepository, stockRepository, writer);
    }

    @Test
    void validDryRunUsesSameDateFubonQuoteAndDoesNoDatabaseReadOrWrite() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(brokerClient.readTwQuotes(List.of("2330")))
                .thenReturn(FubonDtos.CallResult.success(quoteBatch(quote(TODAY, "2330"))));

        FubonDtos.SyncResponse result = service.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.DRY_RUN);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.positionCount()).isEqualTo(1);
        assertThat(result.replaceCount()).isZero();
        verifyNoInteractions(userAdminService, snapshotRepository, brokerRepository, stockRepository, writer);
    }

    private FubonInventorySyncService configured(boolean inventoryEnabled, boolean liveEnabled) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.UTC);
        return new FubonInventorySyncService(configState, brokerClient, marketDataService, userAdminService,
                snapshotRepository, brokerRepository, stockRepository, writer, counters, clock,
                inventoryEnabled, liveEnabled);
    }

    @Test
    void partialQuoteBatchFailsClosedWithoutOwnerOrWriterCalls() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(brokerClient.readTwQuotes(List.of("2330"))).thenReturn(FubonDtos.CallResult.success(
                new FubonDtos.QuoteBatchResponse("quote-batch", List.of(
                        new FubonDtos.QuoteItem("2330", "FAILURE", "QUOTE_TIMEOUT", null)))));

        FubonDtos.SyncResponse result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.QUOTE_FAILED);
        verifyNoInteractions(userAdminService, snapshotRepository, brokerRepository, stockRepository, writer);
    }

    @Test
    void priorTradingDateQuoteIsRejectedEvenWhenCalendarIsTrue() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(brokerClient.readTwQuotes(List.of("2330")))
                .thenReturn(FubonDtos.CallResult.success(quoteBatch(quote(TODAY.minusDays(1), "2330"))));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonOutcome.QUOTE_FAILED);
        verifyNoInteractions(writer);
    }

    @Test
    void futureQuoteTimestampIsRejectedEvenWhenItsTaipeiDateMatches() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        FubonDtos.Quote base = quote(TODAY, "2330");
        FubonDtos.Quote future = new FubonDtos.Quote(
                base.stockCode(), base.stockName(), base.market(), base.actualPrice(), base.previousClose(),
                base.openPrice(), base.highPrice(), base.lowPrice(), base.buyPrice(), base.sellPrice(),
                base.volume(), Instant.parse("2026-08-21T04:00:31Z"), base.tradingDate(),
                base.source(), base.closed(), base.quoteStatus());
        when(brokerClient.readTwQuotes(List.of("2330")))
                .thenReturn(FubonDtos.CallResult.success(quoteBatch(future)));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonOutcome.QUOTE_FAILED);
        verifyNoInteractions(writer);
    }

    @Test
    void commitPreflightsConfiguredActiveAdminTodaySnapshotAndBrokerBeforeWriter() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(brokerClient.readTwQuotes(List.of("2330")))
                .thenReturn(FubonDtos.CallResult.success(quoteBatch(quote(TODAY, "2330"))));
        AppUser admin = AppUser.builder().id(9L).email("admin@example.invalid")
                .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build();
        AssetSnapshot snapshot = AssetSnapshot.builder().id(7L).ownerUserId(9L).snapshotDate(TODAY).build();
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(admin));
        when(snapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L))
                .thenReturn(Optional.of(snapshot));
        when(brokerRepository.findByCode("fubon")).thenReturn(Optional.of(
                BrokerEntity.builder().code("fubon").active(true).build()));
        when(writer.replace(eq(9L), eq(7L), eq(TODAY), anyList(), eq(false)))
                .thenReturn(new FubonInventoryWriter.CommitResult(7L, 1, false));

        FubonDtos.SyncResponse result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.SUCCESS);
        assertThat(result.snapshotId()).isEqualTo(7L);
        assertThat(result.replaceCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FubonInventoryWriter.PreparedPosition>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).replace(eq(9L), eq(7L), eq(TODAY), captor.capture(), eq(false));
        assertThat(captor.getValue()).singleElement().satisfies(position -> {
            assertThat(position.stockCode()).isEqualTo("2330");
            assertThat(position.shares()).isEqualTo(3);
            assertThat(position.costPrice()).isEqualByComparingTo("12.345");
            assertThat(position.actualPrice()).isEqualByComparingTo("20");
            assertThat(position.stockName()).isEqualTo("台積電");
        });
    }

    @Test
    void explicitEmptyCommitDoesNotCallQuoteAndReturnsEmptyCleared() {
        ready();
        FubonDtos.PortfolioResponse empty = new FubonDtos.PortfolioResponse(
                "batch-empty", TODAY, "abcdefabcdefabcd", true, List.of(), null);
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(empty));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        AppUser admin = AppUser.builder().id(9L).role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build();
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(admin));
        when(snapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L)).thenReturn(Optional.of(
                AssetSnapshot.builder().id(7L).ownerUserId(9L).snapshotDate(TODAY).build()));
        when(brokerRepository.findByCode("fubon")).thenReturn(Optional.of(
                BrokerEntity.builder().code("fubon").active(true).build()));
        when(writer.replace(9L, 7L, TODAY, List.of(), true))
                .thenReturn(new FubonInventoryWriter.CommitResult(7L, 0, true));

        FubonDtos.SyncResponse result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonOutcome.EMPTY_CLEARED);
        verify(brokerClient, never()).readTwQuotes(anyList());
    }

    @Test
    void noActiveConfiguredAdminReturnsNoOwnerAndNeverOpensWriterTransaction() {
        ready();
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(portfolio(TODAY)));
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
        when(brokerClient.readTwQuotes(List.of("2330")))
                .thenReturn(FubonDtos.CallResult.success(quoteBatch(quote(TODAY, "2330"))));
        when(userAdminService.configuredAdmin()).thenReturn(Optional.empty());

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonOutcome.NO_OWNER);
        verify(writer, never()).replace(any(), any(), any(), anyList(), anyBoolean());
    }

    private void ready() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.READY, "shared-token"));
    }

    private FubonConfigState.Snapshot config(FubonConfigState.State state, String token) {
        return new FubonConfigState.Snapshot(state, token, state.name());
    }

    private FubonDtos.PortfolioResponse portfolio(LocalDate date) {
        return new FubonDtos.PortfolioResponse(
                "batch-1", date, "abcdefabcdefabcd", false, List.of(position("2330")), null);
    }

    private FubonDtos.Position position(String code) {
        return new FubonDtos.Position(code, 3, CanonicalFubonDecimal.parsePositive("12.345"));
    }

    private FubonDtos.QuoteBatchResponse quoteBatch(FubonDtos.Quote quote) {
        return new FubonDtos.QuoteBatchResponse("quote-batch", List.of(
                new FubonDtos.QuoteItem(quote.stockCode(), "SUCCESS", null, quote)));
    }

    private FubonDtos.Quote quote(LocalDate date, String code) {
        Instant updatedAt = date.equals(TODAY)
                ? Instant.parse("2026-08-21T03:00:00Z")
                : Instant.parse("2026-08-20T03:00:00Z");
        return new FubonDtos.Quote(code, "台積電", "台股",
                CanonicalFubonDecimal.parsePositive("20"),
                CanonicalFubonDecimal.parsePositive("19"),
                CanonicalFubonDecimal.parsePositive("19.5"),
                CanonicalFubonDecimal.parsePositive("21"),
                CanonicalFubonDecimal.parsePositive("19"),
                CanonicalFubonDecimal.parsePositive("19.9"),
                CanonicalFubonDecimal.parsePositive("20.1"),
                100L, updatedAt, date, "FUBON_INTRADAY", false, "LIVE");
    }
}
