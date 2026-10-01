package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FubonHistoricalBackfillRunnerTest {
    @Test void dailyConflictStopsBeforeProjectingOrPersistingLaterFacts() {
        var client = mock(FubonMarketDataPort.class);
        var dailyFacts = mock(FubonHistoricalDailyCandleStore.class);
        var stockHistory = mock(StockSourceQuery.class);
        var runner = runner(mock(MarketCalendar.class), client, dailyFacts, stockHistory, mock(JdbcTemplate.class));
        LocalDate day = LocalDate.of(2026, 8, 28);
        var first = candle(day, "10");
        var conflicting = candle(day.minusDays(1), "11");
        var trailing = candle(day.minusDays(2), "12");
        var read = new HistoricalDailyCandlesRead("2330", day.minusDays(2), day, Instant.parse("2026-08-28T06:00:00Z"),
                "TWSE", "TSE", "OK", null, List.of(first, conflicting, trailing));
        when(client.historicalDailyCandles("2330", day.minusDays(2), day)).thenReturn(read);
        when(dailyFacts.persist(read, first)).thenReturn(new FubonHistoricalDailyCandleStore.Result(FubonHistoricalDailyCandleStore.Status.WRITTEN));
        when(dailyFacts.persist(read, conflicting)).thenReturn(new FubonHistoricalDailyCandleStore.Result(FubonHistoricalDailyCandleStore.Status.CONFLICT_NO_SOURCE_REVISION));

        var result = runner.process(new FubonHistoricalBackfillPlanner.Window("DAILY_CANDLE", "2330", day.minusDays(2), day));

        assertThat(result.status()).isEqualTo("CONFLICT");
        assertThat(result.providerRows()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.conflicts()).isEqualTo(1);
        verify(dailyFacts, times(1)).persist(read, first);
        verify(dailyFacts, times(1)).persist(read, conflicting);
        verify(dailyFacts, never()).persist(read, trailing);
        verify(stockHistory, times(1)).upsertFubonHistoricalDailyCandle("2330", day, first.open(), first.high(), first.low(), first.close(), first.volume());
        verifyNoMoreInteractions(stockHistory);
    }

    @Test void completedDaySearchPassesFifteenClosedDaysAndStopsOnUnknownCalendar() {
        var calendar = mock(MarketCalendar.class);
        var runner = runner(calendar, mock(FubonMarketDataPort.class), mock(FubonHistoricalDailyCandleStore.class),
                mock(StockSourceQuery.class), mock(JdbcTemplate.class));
        LocalDate today = LocalDate.of(2026, 9, 30);
        when(calendar.isTwTradingDayKnown(any(LocalDate.class))).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            long age = today.toEpochDay() - date.toEpochDay();
            return Optional.of(age == 16 ? Boolean.TRUE : Boolean.FALSE);
        });

        assertThat(runner.latestCompletedBefore(today)).isEqualTo(today.minusDays(16));
        verify(calendar, times(16)).isTwTradingDayKnown(any(LocalDate.class));

        reset(calendar);
        when(calendar.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> runner.latestCompletedBefore(today)).hasMessage("CALENDAR_UNKNOWN");
        verify(calendar, times(1)).isTwTradingDayKnown(any(LocalDate.class));
    }

    @Test void successfulOutcomeRequiresEveryPlannedWindowToHaveResolvedLatestAttempt() {
        var one = new FubonHistoricalBackfillPlanner.Window("DAILY_CANDLE", "2330", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
        var two = new FubonHistoricalBackfillPlanner.Window("INTRADAY_CANDLE_1M", "2330", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
        var oneKey = new FubonHistoricalBackfillReceiptStore.WindowKey(one.dataset(), one.symbol(), one.from(), one.to());
        var twoKey = new FubonHistoricalBackfillReceiptStore.WindowKey(two.dataset(), two.symbol(), two.from(), two.to());
        var attempts = Map.of(oneKey, new FubonHistoricalBackfillReceiptStore.Attempt(1, oneKey, 1, "COMPLETE"));

        assertThat(FubonHistoricalBackfillRunner.allPlannedWindowsResolved(List.of(one, two), attempts)).isFalse();
        var resolved = Map.of(oneKey, attempts.get(oneKey), twoKey,
                new FubonHistoricalBackfillReceiptStore.Attempt(2, twoKey, 1, "NO_DATA"));
        assertThat(FubonHistoricalBackfillRunner.allPlannedWindowsResolved(List.of(one, two), resolved)).isTrue();
    }

    @Test void missingAndRepeatedCommandValuesFailClosedWithoutNullDereference() {
        var runner = runner(mock(MarketCalendar.class), mock(FubonMarketDataPort.class),
                mock(FubonHistoricalDailyCandleStore.class), mock(StockSourceQuery.class), mock(JdbcTemplate.class));
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[]{"--fubon-historical-backfill"})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HISTORICAL_BACKFILL_COMMAND_OR_FEATURE_GATE_INVALID");
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[]{
                "--fubon-historical-backfill=run", "--fubon-historical-backfill=run"})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HISTORICAL_BACKFILL_COMMAND_OR_FEATURE_GATE_INVALID");
    }

    @Test void dedicatedOneShotEntryFailsWithNonzeroExitWhenCommandIsMissing() {
        var client = mock(FubonMarketDataPort.class);
        var receipts = mock(FubonHistoricalBackfillReceiptStore.class);
        var lock = mock(FubonHistoricalBackfillCampaignLock.class);
        var runner = new FubonHistoricalBackfillRunner("true", "true", mock(FubonMarketConfigState.class),
                mock(FubonRadarScope.class), mock(MarketCalendar.class), client,
                mock(FubonHistoricalDailyCandleStore.class), mock(FubonMarketDataHistoryStore.class),
                mock(StockSourceQuery.class), receipts, lock, mock(JdbcTemplate.class), new ObjectMapper());

        runner.run(new DefaultApplicationArguments(new String[]{}));

        assertThat(runner.getExitCode()).isEqualTo(2);
        verifyNoInteractions(client, receipts, lock);
    }

    @Test void busyCampaignStopsBeforeLoadingReceiptsOrCallingVendor() {
        var lock = mock(FubonHistoricalBackfillCampaignLock.class);
        when(lock.tryAcquire(any())).thenReturn(Optional.empty());
        var calendar = mock(MarketCalendar.class);
        when(calendar.isTwTradingDayKnown(any())).thenReturn(Optional.of(true));
        var client = mock(FubonMarketDataPort.class);
        var receipts = mock(FubonHistoricalBackfillReceiptStore.class);
        var runner = new FubonHistoricalBackfillRunner("true", "false", mock(FubonMarketConfigState.class), mock(FubonRadarScope.class),
                calendar, client, mock(FubonHistoricalDailyCandleStore.class), mock(FubonMarketDataHistoryStore.class),
                mock(StockSourceQuery.class), receipts, lock, mock(JdbcTemplate.class), new ObjectMapper());

        runner.run(new DefaultApplicationArguments(new String[]{"--fubon-historical-backfill=run",
                "--spring.profiles.active=postgres", "--resume=7eb3bece-7585-49e2-83dc-d8c8eea128ee"}));

        assertThat(runner.getExitCode()).isEqualTo(2);
        verify(lock).tryAcquire(UUID.fromString("7eb3bece-7585-49e2-83dc-d8c8eea128ee"));
        verifyNoInteractions(receipts, client);
    }

    @Test void rejectsUnexpectedSpringProfileForOneShotRunner() {
        var runner = runner(mock(MarketCalendar.class), mock(FubonMarketDataPort.class),
                mock(FubonHistoricalDailyCandleStore.class), mock(StockSourceQuery.class), mock(JdbcTemplate.class));

        runner.run(new DefaultApplicationArguments(new String[]{"--fubon-historical-backfill=run",
                "--spring.profiles.active=dev", "--resume=7eb3bece-7585-49e2-83dc-d8c8eea128ee"}));

        assertThat(runner.getExitCode()).isEqualTo(2);
    }

    private static FubonHistoricalBackfillRunner runner(MarketCalendar calendar, FubonMarketDataPort client,
            FubonHistoricalDailyCandleStore dailyFacts, StockSourceQuery stockHistory, JdbcTemplate jdbc) {
        return runner(calendar, client, dailyFacts, stockHistory, mock(JdbcTemplate.class), mock(FubonHistoricalBackfillCampaignLock.class));
    }

    private static FubonHistoricalBackfillRunner runner(MarketCalendar calendar, FubonMarketDataPort client,
            FubonHistoricalDailyCandleStore dailyFacts, StockSourceQuery stockHistory, JdbcTemplate ignored,
            FubonHistoricalBackfillCampaignLock lock) {
        return new FubonHistoricalBackfillRunner("true", "false", mock(FubonMarketConfigState.class), mock(FubonRadarScope.class), calendar,
                client, dailyFacts, mock(FubonMarketDataHistoryStore.class), stockHistory,
                mock(FubonHistoricalBackfillReceiptStore.class), lock, ignored, new ObjectMapper());
    }

    private static HistoricalDailyCandle candle(LocalDate date, String value) {
        BigDecimal price = new BigDecimal(value);
        return new HistoricalDailyCandle(date, price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE), price,
                1L, price, BigDecimal.ZERO);
    }
}
