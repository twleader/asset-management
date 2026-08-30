package com.steven.assets.integration.fubon;

import com.steven.assets.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature gate / configState / calendar three-layer short-circuit for the trade sync scheduler
 * (Requirement 120 / Task 385). Mirrors {@link FubonInventorySyncSchedulerTest}'s structure; the
 * only deliberate difference is the {@code syncScheduledAfterCalendar(today, false)} two-arg call.
 */
class FubonTradeSyncSchedulerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void cronAndZoneAreExact() throws Exception {
        Method method = FubonTradeSyncScheduler.class.getMethod("scheduledTradeSync");
        Scheduled[] schedules = method.getAnnotationsByType(Scheduled.class);
        assertThat(schedules).extracting(Scheduled::cron)
                .containsExactly("0 0,30 9-13 * * MON-FRI", "0 0 14 * * MON-FRI");
        assertThat(schedules).allMatch(s -> s.zone().equals("Asia/Taipei"));
        java.util.TreeSet<java.time.LocalTime> times = new java.util.TreeSet<>();
        for (Scheduled schedule : schedules) {
            var cron = org.springframework.scheduling.support.CronExpression.parse(schedule.cron());
            var next = cron.next(TODAY.atStartOfDay());
            while (next != null && next.toLocalDate().equals(TODAY)) {
                times.add(next.toLocalTime());
                next = cron.next(next);
            }
        }
        assertThat(times).containsExactly(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(9, 30),
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(10, 30), java.time.LocalTime.of(11, 0),
                java.time.LocalTime.of(11, 30), java.time.LocalTime.of(12, 0), java.time.LocalTime.of(12, 30),
                java.time.LocalTime.of(13, 0), java.time.LocalTime.of(13, 30), java.time.LocalTime.of(14, 0));
    }

    @Test
    void featureGatePrecedesConfigTokenAndCalendarReads() {
        FubonConfigState config = mock(FubonConfigState.class);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonTradeSyncService sync = mock(FubonTradeSyncService.class);
        when(sync.tradeSyncFeatureGate(false)).thenReturn(FubonTradeOutcome.TRADE_SYNC_DISABLED);

        new FubonTradeSyncScheduler(config, calendar, sync, CLOCK).scheduledTradeSync();

        verify(config, never()).snapshot();
        verify(calendar, never()).isTwTradingDayKnown(TODAY);
        verify(sync, never()).syncScheduledAfterCalendar(TODAY, false);
    }

    @Test
    void misconfiguredStopsBeforeCalendarRead() {
        FubonConfigState config = config(FubonConfigState.State.MISCONFIGURED);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonTradeSyncService sync = mock(FubonTradeSyncService.class);
        when(sync.tradeSyncFeatureGate(false)).thenReturn(null);

        new FubonTradeSyncScheduler(config, calendar, sync, CLOCK).scheduledTradeSync();

        verify(sync).localConfigOutcome(false, FubonConfigState.State.MISCONFIGURED);
        verify(calendar, never()).isTwTradingDayKnown(TODAY);
        verify(sync, never()).syncScheduledAfterCalendar(TODAY, false);
    }

    @Test
    void falseEmptyAndCalendarExceptionAllFailClosedBeforeAdapter() {
        for (Optional<Boolean> answer : new Optional[]{Optional.of(false), Optional.empty()}) {
            FubonConfigState config = config(FubonConfigState.State.READY);
            MarketDataService calendar = mock(MarketDataService.class);
            FubonTradeSyncService sync = mock(FubonTradeSyncService.class);
            when(sync.tradeSyncFeatureGate(false)).thenReturn(null);
            when(calendar.isTwTradingDayKnown(TODAY)).thenReturn(answer);

            new FubonTradeSyncScheduler(config, calendar, sync, CLOCK).scheduledTradeSync();

            verify(sync).calendarUnknown(false);
            verify(sync, never()).syncScheduledAfterCalendar(TODAY, false);
        }

        FubonConfigState config = config(FubonConfigState.State.READY);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonTradeSyncService sync = mock(FubonTradeSyncService.class);
        when(sync.tradeSyncFeatureGate(false)).thenReturn(null);
        when(calendar.isTwTradingDayKnown(TODAY)).thenThrow(new IllegalStateException("authority unavailable"));

        new FubonTradeSyncScheduler(config, calendar, sync, CLOCK).scheduledTradeSync();

        verify(sync).calendarUnknown(false);
        verify(sync, never()).syncScheduledAfterCalendar(TODAY, false);
    }

    @Test
    void knownTradingDayCallsAdapterOrchestrationOnceWithDryRunFalse() {
        FubonConfigState config = config(FubonConfigState.State.READY);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonTradeSyncService sync = mock(FubonTradeSyncService.class);
        when(sync.tradeSyncFeatureGate(false)).thenReturn(null);
        when(calendar.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));

        new FubonTradeSyncScheduler(config, calendar, sync, CLOCK).scheduledTradeSync();

        verify(sync).syncScheduledAfterCalendar(TODAY, false);
    }

    @Test
    void inFlightGuardDropsOverlappingTick() throws Exception {
        FubonConfigState config = config(FubonConfigState.State.READY);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonTradeSyncService sync = mock(FubonTradeSyncService.class);
        when(sync.tradeSyncFeatureGate(false)).thenReturn(null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(calendar.isTwTradingDayKnown(TODAY)).thenAnswer(ignored -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(true);
        });
        FubonTradeSyncScheduler scheduler = new FubonTradeSyncScheduler(config, calendar, sync, CLOCK);
        Thread first = Thread.ofVirtual().start(scheduler::scheduledTradeSync);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.scheduledTradeSync();
        release.countDown();
        first.join(2_000);

        verify(calendar).isTwTradingDayKnown(TODAY);
        verify(sync).syncScheduledAfterCalendar(TODAY, false);
    }

    private FubonConfigState config(FubonConfigState.State state) {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                state, state == FubonConfigState.State.READY ? "token" : null, state.name()));
        return config;
    }
}
