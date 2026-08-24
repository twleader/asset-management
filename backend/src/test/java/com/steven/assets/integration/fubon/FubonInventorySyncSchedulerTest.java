package com.steven.assets.integration.fubon;

import com.steven.assets.service.MarketDataService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void featureGatePrecedesConfigTokenAndCalendarReads() {
        FubonConfigState config = mock(FubonConfigState.class);
        MarketDataService calendar = mock(MarketDataService.class);
        FubonInventorySyncService sync = mock(FubonInventorySyncService.class);
        when(sync.inventoryFeatureGate(false)).thenReturn(mock(FubonDtos.SyncResponse.class));
        new FubonInventorySyncScheduler(config, calendar, sync, CLOCK).scheduledInventorySync();
        verify(config, never()).snapshot();
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
    void realDgpaProxyAuthorizesWorkdayButHolidayAndUnknownStopBeforeQuoteWritePath() throws Exception {
        LocalDate dgpaWorkday = LocalDate.of(2027, 1, 4);
        LocalDate dgpaHoliday = LocalDate.of(2027, 1, 1);
        LocalDate unknownDay = LocalDate.of(2027, 1, 5);
        AtomicReference<String> responseBody = new AtomicReference<>(
                "{\"2027-01-01\":\"DGPA provisional holiday\"}");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tw-holidays", exchange -> {
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            MarketDataService realCalendar = new MarketDataService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), null);
            FubonConfigState config = config(FubonConfigState.State.READY);
            FubonInventorySyncService sync = mock(FubonInventorySyncService.class);

            new FubonInventorySyncScheduler(config, realCalendar, sync,
                    Clock.fixed(Instant.parse("2027-01-04T04:00:00Z"), ZoneOffset.UTC))
                    .scheduledInventorySync();
            verify(sync).syncScheduledAfterCalendar(dgpaWorkday);

            new FubonInventorySyncScheduler(config, realCalendar, sync,
                    Clock.fixed(Instant.parse("2027-01-01T04:00:00Z"), ZoneOffset.UTC))
                    .scheduledInventorySync();
            verify(sync, never()).syncScheduledAfterCalendar(dgpaHoliday);

            responseBody.set("{}");
            new FubonInventorySyncScheduler(config, realCalendar, sync,
                    Clock.fixed(Instant.parse("2027-01-05T04:00:00Z"), ZoneOffset.UTC))
                    .scheduledInventorySync();
            verify(sync, never()).syncScheduledAfterCalendar(unknownDay);
            verify(sync, times(2)).calendarUnknown(false);
        } finally {
            server.stop(0);
        }
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
