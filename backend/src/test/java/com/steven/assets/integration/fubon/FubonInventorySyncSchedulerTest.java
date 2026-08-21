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

class FubonInventorySyncSchedulerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void cronAndZoneAreExact() throws Exception {
        Method method = FubonInventorySyncScheduler.class.getMethod("scheduledInventorySync");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 5,35 9-13 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Taipei");
    }

    @Test
    void disabledSkipsCalendarAndAdapterOrchestration() {
        FubonConfigState config = config(FubonConfigState.State.DISABLED);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonInventorySyncService sync = mock(FubonInventorySyncService.class);
        FubonInventorySyncScheduler scheduler = new FubonInventorySyncScheduler(config, calendar, sync, CLOCK);

        scheduler.scheduledInventorySync();

        verify(sync).localConfigOutcome(false, FubonConfigState.State.DISABLED);
        verify(calendar, never()).isTwTradingDayKnown(TODAY);
        verify(sync, never()).syncScheduledAfterCalendar(TODAY);
    }

    @Test
    void falseEmptyAndCalendarExceptionAllFailClosedBeforeAdapter() {
        for (Optional<Boolean> answer : new Optional[]{Optional.of(false), Optional.empty()}) {
            FubonConfigState config = config(FubonConfigState.State.READY);
            MarketDataService calendar = mock(MarketDataService.class);
            FubonInventorySyncService sync = mock(FubonInventorySyncService.class);
            when(calendar.isTwTradingDayKnown(TODAY)).thenReturn(answer);
            new FubonInventorySyncScheduler(config, calendar, sync, CLOCK).scheduledInventorySync();
            verify(sync).calendarUnknown(false);
            verify(sync, never()).syncScheduledAfterCalendar(TODAY);
        }

        FubonConfigState config = config(FubonConfigState.State.READY);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonInventorySyncService sync = mock(FubonInventorySyncService.class);
        when(calendar.isTwTradingDayKnown(TODAY)).thenThrow(new IllegalStateException("authority unavailable"));
        new FubonInventorySyncScheduler(config, calendar, sync, CLOCK).scheduledInventorySync();
        verify(sync).calendarUnknown(false);
        verify(sync, never()).syncScheduledAfterCalendar(TODAY);
    }

    @Test
    void knownTradingDayCallsAdapterOrchestrationOnce() {
        FubonConfigState config = config(FubonConfigState.State.READY);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonInventorySyncService sync = mock(FubonInventorySyncService.class);
        when(calendar.isTwTradingDayKnown(TODAY)).thenReturn(Optional.of(true));

        new FubonInventorySyncScheduler(config, calendar, sync, CLOCK).scheduledInventorySync();

        verify(sync).syncScheduledAfterCalendar(TODAY);
    }

    @Test
    void inFlightGuardDropsOverlappingTick() throws Exception {
        FubonConfigState config = config(FubonConfigState.State.READY);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonInventorySyncService sync = mock(FubonInventorySyncService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(calendar.isTwTradingDayKnown(TODAY)).thenAnswer(ignored -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(true);
        });
        FubonInventorySyncScheduler scheduler = new FubonInventorySyncScheduler(config, calendar, sync, CLOCK);
        Thread first = Thread.ofVirtual().start(scheduler::scheduledInventorySync);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.scheduledInventorySync();
        release.countDown();
        first.join(2_000);

        verify(calendar).isTwTradingDayKnown(TODAY);
        verify(sync).syncScheduledAfterCalendar(TODAY);
    }

    private FubonConfigState config(FubonConfigState.State state) {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                state, state == FubonConfigState.State.READY ? "token" : null, state.name()));
        return config;
    }
}
