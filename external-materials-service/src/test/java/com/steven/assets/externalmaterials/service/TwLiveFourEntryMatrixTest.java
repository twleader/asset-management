package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Entry-level matrix: strategy selection and known gate are proved at all four actual callers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwLiveFourEntryMatrixTest {

    enum Entry { SCHEDULED, WARMUP, REFRESH_ALL, TW_RADAR }
    enum Mode { ENABLED, DISABLED }
    enum Scenario {
        OPEN_SUCCESS,
        OPEN_PROVIDER_FAILURE,
        DATE_AUTHORITY_FALSE,
        OFF_HOURS,
        AUTHORITY_EMPTY,
        AUTHORITY_THROW
    }

    Stream<Arguments> matrix() {
        return Stream.of(Entry.values()).flatMap(entry ->
                Stream.of(Mode.values()).flatMap(mode ->
                        Stream.of(Scenario.values()).map(scenario -> Arguments.of(entry, mode, scenario))));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("matrix")
    void everyEntryUsesOneKnownGateAndNeverFallsBack(
            Entry entry,
            Mode mode,
            Scenario scenario) {
        PriceFetchClient mis = mock(PriceFetchClient.class);
        FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
        PriceCacheWriter writer = mock(PriceCacheWriter.class);
        StockSourceQuery source = mock(StockSourceQuery.class);
        MarketCalendar calendar = mock(MarketCalendar.class);
        Instant now = scenario == Scenario.OFF_HOURS
                ? Instant.parse("2026-08-21T06:00:00Z")
                : Instant.parse("2026-08-21T02:00:00Z");
        MarketClock clock = spy(new MarketClock(calendar, Clock.fixed(now, ZoneOffset.UTC)));
        TaiexIndexPoller taiex = mock(TaiexIndexPoller.class);
        TwLiveQuoteOutcomeCounters counters = new TwLiveQuoteOutcomeCounters();
        PriceFetchClient.PriceResult price = priceResult();
        ProviderTimedPriceObservation observation = observation(price);

        switch (scenario) {
            case OPEN_SUCCESS, OPEN_PROVIDER_FAILURE ->
                    when(calendar.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.of(true));
            case DATE_AUTHORITY_FALSE ->
                    when(calendar.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.of(false));
            case AUTHORITY_EMPTY ->
                    when(calendar.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.empty());
            case AUTHORITY_THROW -> when(calendar.isTwTradingDayKnown(any(LocalDate.class)))
                    .thenThrow(new IllegalStateException("calendar unavailable"));
            case OFF_HOURS -> { /* the time boundary returns known false without consulting authority */ }
        }
        when(source.findRecentClose(anyString(), anyString())).thenReturn(Optional.empty());
        when(source.findMaxTradingDate(anyString(), anyString())).thenReturn(Optional.empty());
        addCodeForEveryCollector(source);

        if (scenario == Scenario.OPEN_SUCCESS) {
            when(mis.getStockPrice("2330", "台股")).thenReturn(Optional.of(price));
            when(fubon.fetch(anyList())).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                    FubonNormalizedQuoteClient.BatchStatus.SUCCESS,
                    List.of(observation), 1, 0));
            when(writer.writeProviderTimed(any(), eq(true), eq(true)))
                    .thenReturn(new ProviderWriteResult(ProviderWriteOutcome.WRITTEN, false));
        } else if (scenario == Scenario.OPEN_PROVIDER_FAILURE) {
            when(mis.getStockPrice("2330", "台股")).thenReturn(Optional.empty());
            when(fubon.fetch(anyList())).thenReturn(new FubonNormalizedQuoteClient.BatchResult(
                    FubonNormalizedQuoteClient.BatchStatus.SERVICE_UNAVAILABLE,
                    List.of(), 1, 1));
        }

        TwLiveQuoteProvider selected = mode == Mode.ENABLED
                ? new FubonTwLiveQuoteProvider(fubon, writer, source, counters)
                : new ExistingTwLiveQuoteProvider(mis, writer, source, counters);
        TwLiveQuoteDispatcher dispatcher = new TwLiveQuoteDispatcher(clock, selected, counters);
        PricePoller poller = new PricePoller(clientOrMock(mis), writer, source, clock, dispatcher, Runnable::run);

        invoke(entry, poller, taiex, source, clock);

        boolean open = scenario == Scenario.OPEN_SUCCESS || scenario == Scenario.OPEN_PROVIDER_FAILURE;
        if (mode == Mode.ENABLED && open) verify(fubon).fetch(List.of("2330"));
        else verify(fubon, never()).fetch(anyList());
        if (mode == Mode.DISABLED && open) verify(mis).getStockPrice("2330", "台股");
        else verify(mis, never()).getStockPrice(anyString(), eq("台股"));

        if (scenario == Scenario.OPEN_SUCCESS && mode == Mode.ENABLED) {
            verify(writer).writeProviderTimed(observation, true, true);
            verify(writer, never()).write(any(), eq(false));
        } else if (scenario == Scenario.OPEN_SUCCESS) {
            verify(writer).write(price, false);
            verify(writer, never()).writeProviderTimed(any(), eq(true), eq(true));
        } else {
            verify(writer, never()).write(any(), eq(false));
            verify(writer, never()).writeProviderTimed(any(), eq(true), eq(true));
        }
        verify(clock, never()).isTwMarketOpen();

        if (scenario == Scenario.DATE_AUTHORITY_FALSE || scenario == Scenario.OFF_HOURS) {
            assertThat(counters.snapshot().get(TwLiveQuoteOutcomeCounters.Outcome.MARKET_CLOSED)).isEqualTo(1);
        } else if (scenario == Scenario.AUTHORITY_EMPTY || scenario == Scenario.AUTHORITY_THROW) {
            assertThat(counters.snapshot().get(TwLiveQuoteOutcomeCounters.Outcome.MARKET_UNKNOWN)).isEqualTo(1);
        }
        if (scenario == Scenario.OFF_HOURS) {
            verify(calendar, never()).isTwTradingDayKnown(any(LocalDate.class));
        }
    }

    private static PriceFetchClient clientOrMock(PriceFetchClient client) {
        return client;
    }

    private static void invoke(
            Entry entry,
            PricePoller poller,
            TaiexIndexPoller taiex,
            StockSourceQuery source,
            MarketClock clock) {
        switch (entry) {
            case SCHEDULED -> poller.scheduledTwIntradayUpdate();
            case WARMUP -> poller.warmCacheOnStartup();
            case REFRESH_ALL -> poller.refreshAll();
            case TW_RADAR -> new TwRadarRefreshService(poller, taiex, source, clock).refresh();
        }
    }

    private static void addCodeForEveryCollector(StockSourceQuery source) {
        doAnswer(invocation -> {
            Set<String> tw = invocation.getArgument(0);
            tw.add("2330");
            return null;
        }).when(source).collectHeldStockCodes(any(), any(), any());
        doAnswer(invocation -> {
            Set<String> tw = invocation.getArgument(0);
            tw.add("2330");
            return null;
        }).when(source).collectAllStockCodes(any(), any(), any());
        doAnswer(invocation -> {
            Set<String> tw = invocation.getArgument(0);
            tw.add("2330");
            return null;
        }).when(source).collectTwRadarCodes(any());
    }

    private static PriceFetchClient.PriceResult priceResult() {
        return new PriceFetchClient.PriceResult(
                "2330", "台股", new BigDecimal("100.1"), null, null,
                "FUBON_INTRADAY", "台積電", new BigDecimal("100.0"), new BigDecimal("100.2"),
                new BigDecimal("100.0"), new BigDecimal("99.5"),
                new BigDecimal("101.0"), new BigDecimal("99.0"), 54_538L);
    }

    private static ProviderTimedPriceObservation observation(PriceFetchClient.PriceResult result) {
        return new ProviderTimedPriceObservation(
                result, LocalDate.of(2026, 8, 21), Instant.parse("2026-08-21T05:00:00Z"));
    }
}
