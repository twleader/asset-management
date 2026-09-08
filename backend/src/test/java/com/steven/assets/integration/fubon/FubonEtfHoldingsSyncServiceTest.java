package com.steven.assets.integration.fubon;

import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import com.steven.assets.repository.FubonEtfHoldingsSnapshotRepository;
import com.steven.assets.repository.StockHoldingRepository;
import com.steven.assets.service.MarketDataService;
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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FubonEtfHoldingsSyncServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-21T01:00:00Z"), ZoneOffset.UTC);
    @Mock FubonConfigState configState;
    @Mock MarketDataService marketDataService;
    @Mock FubonBrokerClient brokerClient;
    @Mock FubonEtfHoldingsWriter writer;
    @Mock FubonEtfHoldingsSnapshotRepository snapshotRepository;
    @Mock StockHoldingRepository stockHoldingRepository;
    private FubonEtfHoldingsSyncService service;

    @BeforeEach
    void setUp() { service = build(true); }

    private FubonEtfHoldingsSyncService build(boolean enabled) {
        return new FubonEtfHoldingsSyncService(enabled, configState, marketDataService,
                brokerClient, writer, snapshotRepository, stockHoldingRepository, CLOCK);
    }

    private void readyConfig() {
        when(configState.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "token", null));
    }

    private void ready() {
        readyConfig();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));
    }

    @Test
    void disabledFeatureDoesNotEvenReadConfig() {
        build(false).syncScheduled();
        build(false).syncMissingOnStartup();
        verifyNoInteractions(configState, marketDataService, brokerClient, writer, snapshotRepository, stockHoldingRepository);
    }

    @Test
    void disabledOrMisconfiguredStopsBeforeCalendarOrDatabase() {
        for (var state : List.of(FubonConfigState.State.DISABLED, FubonConfigState.State.MISCONFIGURED)) {
            when(configState.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));
            service.syncScheduled();
            service.syncMissingOnStartup();
        }
        verifyNoInteractions(marketDataService, brokerClient, writer, snapshotRepository, stockHoldingRepository);
    }

    @Test
    void closedUnknownAndFailedCalendarAllStopBeforeRadarAndAdapter() {
        ready();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(false), Optional.empty())
                .thenThrow(new IllegalStateException("calendar unavailable"));
        service.syncScheduled(); service.syncScheduled(); service.syncScheduled();
        verifyNoInteractions(brokerClient, writer, stockHoldingRepository);
    }

    @Test
    void emptyOrUnreadableRadarSendsZeroAdapterCalls() {
        ready();
        when(stockHoldingRepository.findTwRadarCandidateCodes()).thenReturn(List.of())
                .thenThrow(new IllegalStateException("db unavailable"));
        service.syncScheduled(); service.syncScheduled();
        verifyNoInteractions(brokerClient, writer);
    }

    @Test
    void startupEmptyOrUnreadableRadarSkipsProjectionCalendarAndProvider() {
        readyConfig();
        when(stockHoldingRepository.findTwRadarCandidateCodes()).thenReturn(List.of())
                .thenThrow(new IllegalStateException("db unavailable"));
        service.syncMissingOnStartup();
        service.syncMissingOnStartup();
        verify(snapshotRepository, never()).findSuccessfulEtfStockCodes(anyCollection());
        verify(marketDataService, never()).isTwTradingDayKnown(any());
        verifyNoInteractions(brokerClient, writer);
    }

    @Test
    void startupAllSuccessfulUsesOneProjectionAndDoesNotRewriteRows() {
        readyConfig();
        radarCodes("0050", "00719B");
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection()))
                .thenReturn(Set.of("0050", "00719B"));
        service.syncMissingOnStartup();
        verify(snapshotRepository).findSuccessfulEtfStockCodes(argThat(codes ->
                codes.size() == 2 && codes.containsAll(List.of("0050", "00719B"))));
        verify(marketDataService, never()).isTwTradingDayKnown(any());
        verifyNoInteractions(brokerClient, writer);
    }

    @Test
    void startupRetriesFailureRowAndQueriesMissingRowOnly() {
        readyConfig();
        radarCodes("0050", "00719B", "0056", "006208");
        // 0050 represents a successful non-empty payload; 00719B a successful legal empty payload.
        // A prior failure row (0056) is intentionally absent from the success-only projection, as is
        // the code with no row (006208).
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection()))
                .thenReturn(Set.of("0050", "00719B"));
        when(brokerClient.readEtfHoldings(List.of("0056", "006208"))).thenReturn(
                FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("batch",
                        List.of(success("0056"), success("006208")))));

        service.syncMissingOnStartup();

        verify(snapshotRepository, times(1)).findSuccessfulEtfStockCodes(anyCollection());
        verify(brokerClient).readEtfHoldings(List.of("0056", "006208"));
        assertThat(saved(2)).containsOnlyKeys("0056", "006208");
        verify(marketDataService, never()).isTwTradingDayKnown(any());
    }

    @Test
    void startupProjectionFailureFailsClosed() {
        readyConfig();
        radarCodes("0050");
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection()))
                .thenThrow(new IllegalStateException("private database error"));
        service.syncMissingOnStartup();
        verifyNoInteractions(brokerClient, writer);
        verify(marketDataService, never()).isTwTradingDayKnown(any());
    }

    @Test
    void startupProviderFailurePersistsTypedFailureWithoutSelfRetry() {
        readyConfig();
        radarCodes("0050");
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection())).thenReturn(Set.of());
        when(brokerClient.readEtfHoldings(List.of("0050")))
                .thenReturn(FubonDtos.CallResult.failure("ETF_HOLDINGS_TIMEOUT"));
        service.syncMissingOnStartup();
        assertFailure(saved(1).get("0050"), "ETF_HOLDINGS_TIMEOUT");
        verify(brokerClient, times(1)).readEtfHoldings(anyList());
    }

    @Test
    void startupPersistsLegalEmptyHoldingsAsSuccess() {
        readyConfig();
        radarCodes("00719B");
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection())).thenReturn(Set.of());
        response(new FubonDtos.EtfHoldingsItem("00719B", "SUCCESS", null, emptyPayload("00719B")));
        service.syncMissingOnStartup();
        var row = saved(1).get("00719B");
        assertThat(row.getSuccess()).isTrue();
        assertThat(row.getRawResponseJson()).isEqualTo(emptyPayload("00719B"));
        assertThat(row.getReason()).isNull();
    }

    @Test
    void successfulFullThenPendingStartupUsesProjectionAndMakesZeroSecondProviderCall() {
        ready();
        radarCodes("0050");
        response(success("0050"));
        service.syncScheduled();
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection())).thenReturn(Set.of("0050"));

        service.syncMissingOnStartup();

        verify(brokerClient, times(1)).readEtfHoldings(anyList());
        verify(writer, times(1)).save(any());
    }

    @Test
    void closedFullDoesNotPreventPendingStartupFromFillingGapWithoutCalendar() {
        readyConfig();
        when(marketDataService.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(false));
        radarCodes("0050");
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection())).thenReturn(Set.of());
        response(success("0050"));

        service.syncScheduled();
        service.syncMissingOnStartup();

        verify(marketDataService, times(1)).isTwTradingDayKnown(TODAY);
        verify(brokerClient, times(1)).readEtfHoldings(List.of("0050"));
        assertThat(saved(1).get("0050").getSuccess()).isTrue();
    }

    @Test
    void fullProviderFailureCanBeRetriedImmediatelyByPendingStartupIntent() {
        ready();
        radarCodes("0050", "0056");
        when(brokerClient.readEtfHoldings(List.of("0050", "0056")))
                .thenReturn(FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("full", List.of(
                        success("0050"),
                        new FubonDtos.EtfHoldingsItem("0056", "FAILURE", "ETF_HOLDINGS_TIMEOUT", null)))));
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection())).thenReturn(Set.of("0050"));
        when(brokerClient.readEtfHoldings(List.of("0056")))
                .thenReturn(FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("startup",
                        List.of(success("0056")))));

        service.syncScheduled();
        service.syncMissingOnStartup();

        ArgumentCaptor<FubonEtfHoldingsSnapshot> rows = ArgumentCaptor.forClass(FubonEtfHoldingsSnapshot.class);
        verify(writer, times(3)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(FubonEtfHoldingsSnapshot::getEtfStockCode)
                .containsExactly("0050", "0056", "0056");
        assertThat(rows.getAllValues()).extracting(FubonEtfHoldingsSnapshot::getSuccess)
                .containsExactly(true, false, true);
    }

    @Test
    void fullWriterFailureCanBeRetriedImmediatelyByPendingStartupIntent() {
        ready();
        radarCodes("0050", "0056");
        when(brokerClient.readEtfHoldings(List.of("0050", "0056")))
                .thenReturn(FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("full",
                        List.of(success("0050"), success("0056")))));
        when(snapshotRepository.findSuccessfulEtfStockCodes(anyCollection())).thenReturn(Set.of("0050"));
        when(brokerClient.readEtfHoldings(List.of("0056")))
                .thenReturn(FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("startup",
                        List.of(success("0056")))));
        AtomicInteger writesFor0056 = new AtomicInteger();
        doAnswer(invocation -> {
            FubonEtfHoldingsSnapshot row = invocation.getArgument(0);
            if ("0056".equals(row.getEtfStockCode()) && writesFor0056.getAndIncrement() == 0) {
                throw new IllegalStateException("first write fails");
            }
            return null;
        }).when(writer).save(any());

        service.syncScheduled();
        service.syncMissingOnStartup();

        assertThat(writesFor0056).hasValue(2);
        verify(brokerClient).readEtfHoldings(List.of("0050", "0056"));
        verify(brokerClient).readEtfHoldings(List.of("0056"));
    }

    @Test
    void radarKeepsOnlyWellFormedEtfsAndExcludesIndexDuplicatesAndOrdinaryStocks() {
        when(stockHoldingRepository.findTwRadarCandidateCodes())
                .thenReturn(Arrays.asList("0000", null, "00bad", "0050", "0050", "2330", "00981A"));
        when(marketDataService.isEtf("0050", "台股")).thenReturn(true);
        when(marketDataService.isEtf("00981A", "台股")).thenReturn(true);
        assertThat(service.collectTwRadarEtfCodes()).containsExactly("0050", "00981A");
        verifyNoInteractions(configState, brokerClient, writer);
    }

    @Test
    void storesValidatedPayloadAndOneRoundTimestamp() {
        ready(); radarCodes("0050"); response(success("0050"));
        service.syncScheduled();
        var row = saved(1).get("0050");
        assertThat(row.getSuccess()).isTrue();
        assertThat(row.getReason()).isNull();
        assertThat(row.getRawResponseJson()).isEqualTo(payload("0050"));
        assertThat(row.getMarket()).isEqualTo("台股");
        assertThat(row.getFetchedAt()).isEqualTo(CLOCK.instant());
        assertThat(row.getUpdatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void individualProviderFailureDoesNotBlockOtherResults() {
        ready(); radarCodes("0050", "0056", "006208");
        response(success("0050"), new FubonDtos.EtfHoldingsItem("0056", "FAILURE", "ETF_HOLDINGS_TIMEOUT", null), success("006208"));
        service.syncScheduled();
        var rows = saved(3);
        assertThat(rows.get("0050").getSuccess()).isTrue();
        assertFailure(rows.get("0056"), "ETF_HOLDINGS_TIMEOUT");
        assertThat(rows.get("006208").getSuccess()).isTrue();
    }

    @Test
    void wholeBatchFailureAndTransportExceptionPersistFailureForEveryRequestedCode() {
        ready(); radarCodes("0050", "0056");
        when(brokerClient.readEtfHoldings(anyList())).thenReturn(FubonDtos.CallResult.failure("MISCONFIGURED"))
                .thenThrow(new IllegalStateException("secret provider message"));
        service.syncScheduled();
        saved(2).values().forEach(row -> assertFailure(row, "MISCONFIGURED"));
        clearInvocations(writer);
        service.syncScheduled();
        saved(2).values().forEach(row -> assertFailure(row, "TRANSPORT_OR_SCHEMA_FAILURE"));
    }

    @Test
    void missingDuplicateAndWrongPayloadAreFailuresWithoutTrustingEarlierRows() {
        ready(); radarCodes("0050", "0056", "006208", "00981A");
        response(success("0050"), success("0050"),
                new FubonDtos.EtfHoldingsItem("006208", "SUCCESS", null, payload("0050")), success("00981A"));
        service.syncScheduled();
        var rows = saved(4);
        assertFailure(rows.get("0050"), "DUPLICATE_RESULT");
        assertFailure(rows.get("0056"), "MISSING_RESULT");
        assertFailure(rows.get("006208"), "INVALID_NORMALIZED_PAYLOAD");
        assertThat(rows.get("00981A").getSuccess()).isTrue();
    }

    @Test
    void extraCodeRejectsBatchAndIsNeverWritten() {
        ready(); radarCodes("0050", "0056"); response(success("0050"), success("0056"), success("006208"));
        service.syncScheduled();
        var rows = saved(2);
        assertThat(rows).containsOnlyKeys("0050", "0056");
        rows.values().forEach(row -> assertFailure(row, "INVALID_BATCH_RESPONSE"));
    }

    @Test
    void invalidStatusAndMixedSuccessFailureFieldsCannotPersistSuccess() {
        ready(); radarCodes("0050", "0056", "006208");
        response(new FubonDtos.EtfHoldingsItem("0050", "UNKNOWN", null, payload("0050")),
                new FubonDtos.EtfHoldingsItem("0056", "FAILURE", "ETF_HOLDINGS_TIMEOUT", payload("0056")),
                new FubonDtos.EtfHoldingsItem("006208", "SUCCESS", "has-error", payload("006208")));
        service.syncScheduled();
        saved(3).values().forEach(row -> assertFailure(row, "INVALID_RESULT"));
    }

    @Test
    void untrustedProviderReasonIsSanitized() {
        ready(); radarCodes("0050");
        response(new FubonDtos.EtfHoldingsItem("0050", "FAILURE", "secret account=123", null));
        service.syncScheduled();
        assertFailure(saved(1).get("0050"), "ETF_HOLDINGS_FAILED");
    }

    @Test
    void databaseFailureForOneCodeDoesNotPoisonTheRest() {
        ready(); radarCodes("0050", "0056", "006208");
        response(success("0050"), success("0056"), success("006208"));
        List<String> committed = new ArrayList<>();
        doAnswer(invocation -> {
            FubonEtfHoldingsSnapshot row = invocation.getArgument(0);
            if ("0056".equals(row.getEtfStockCode())) throw new IllegalStateException("private db error");
            committed.add(row.getEtfStockCode());
            return null;
        }).when(writer).save(any());
        service.syncScheduled();
        assertThat(saved(3)).containsOnlyKeys("0050", "0056", "006208");
        assertThat(committed).containsExactly("0050", "006208");
    }

    @Test
    void over50CodesUseBoundedBatchesAndPersistEveryCode() {
        ready();
        List<String> codes = IntStream.rangeClosed(1, 53).mapToObj(i -> String.format("00%03d", i)).toList();
        radarCodes(codes.toArray(String[]::new));
        when(brokerClient.readEtfHoldings(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("batch", batch.stream().map(this::success).toList()));
        });
        service.syncScheduled();
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(brokerClient, times(14)).readEtfHoldings(batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(4));
        assertThat(batches.getAllValues().getLast()).hasSize(1);
        assertThat(batches.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(codes);
        assertThat(saved(53)).containsOnlyKeys(codes.toArray(String[]::new));
    }

    private void radarCodes(String... codes) {
        when(stockHoldingRepository.findTwRadarCandidateCodes()).thenReturn(List.of(codes));
        for (String code : codes) when(marketDataService.isEtf(code, "台股")).thenReturn(true);
    }

    private String payload(String code) {
        return "{\"schemaVersion\":1,\"stockCode\":\"" + code
                + "\",\"sourceDate\":\"2026-08-20\",\"holdings\":[{\"stockCode\":\"2330\",\"stockName\":\"台積電\",\"weight\":\"58.82\",\"shares\":null}]}";
    }

    private String emptyPayload(String code) {
        return "{\"schemaVersion\":1,\"stockCode\":\"" + code
                + "\",\"sourceDate\":null,\"holdings\":[]}";
    }

    private FubonDtos.EtfHoldingsItem success(String code) {
        return new FubonDtos.EtfHoldingsItem(code, "SUCCESS", null, payload(code));
    }

    private void response(FubonDtos.EtfHoldingsItem... items) {
        when(brokerClient.readEtfHoldings(anyList())).thenReturn(FubonDtos.CallResult.success(new FubonDtos.EtfHoldingsBatchResponse("batch", List.of(items))));
    }

    private Map<String, FubonEtfHoldingsSnapshot> saved(int count) {
        ArgumentCaptor<FubonEtfHoldingsSnapshot> captor = ArgumentCaptor.forClass(FubonEtfHoldingsSnapshot.class);
        verify(writer, times(count)).save(captor.capture());
        return captor.getAllValues().stream().collect(Collectors.toMap(FubonEtfHoldingsSnapshot::getEtfStockCode, row -> row));
    }

    private void assertFailure(FubonEtfHoldingsSnapshot row, String reason) {
        assertThat(row.getSuccess()).isFalse();
        assertThat(row.getReason()).isEqualTo(reason);
        assertThat(row.getRawResponseJson()).isNull();
    }
}
