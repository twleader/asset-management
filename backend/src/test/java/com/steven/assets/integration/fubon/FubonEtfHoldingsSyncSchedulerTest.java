package com.steven.assets.integration.fubon;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FubonEtfHoldingsSyncSchedulerTest {
    @Test
    void applicationReadyReturnsWhileNamedVirtualDaemonThreadIsBlockedAndDuplicateEventIsIgnored() throws Exception {
        var service = mock(FubonEtfHoldingsSyncService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var name = new AtomicReference<String>();
        var virtual = new AtomicBoolean();
        var daemon = new AtomicBoolean();
        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            name.set(current.getName());
            virtual.set(current.isVirtual());
            daemon.set(current.isDaemon());
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return null;
        }).when(service).syncMissingOnStartup();
        var scheduler = new FubonEtfHoldingsSyncScheduler(service);

        Thread listener = Thread.ofVirtual().start(scheduler::onApplicationReady);
        listener.join(1_000);
        assertThat(listener.isAlive()).as("ApplicationReady listener must return immediately").isFalse();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.onApplicationReady();
        verify(service, times(1)).syncMissingOnStartup();
        assertThat(name).hasValue(FubonEtfHoldingsSyncScheduler.STARTUP_THREAD_NAME);
        assertThat(virtual).isTrue();
        assertThat(daemon).isTrue();
        release.countDown();
    }

    @Test
    void duplicateReadyEventsCreateOneBackgroundSourceAndOneStartupIntent() {
        var service = mock(FubonEtfHoldingsSyncService.class);
        var backgroundTask = new AtomicReference<Runnable>();
        var starts = new AtomicInteger();
        var scheduler = new FubonEtfHoldingsSyncScheduler(service, task -> {
            starts.incrementAndGet();
            backgroundTask.set(task);
        });

        scheduler.onApplicationReady();
        scheduler.onApplicationReady();
        scheduler.onApplicationReady();

        assertThat(starts).hasValue(1);
        backgroundTask.get().run();
        verify(service, times(1)).syncMissingOnStartup();
    }

    @Test
    void fullWinnerDrainsPendingStartupAfterFullForOpenClosedOrUnknownOutcome() throws Exception {
        for (String outcome : List.of("known-open", "closed", "unknown")) {
            var service = mock(FubonEtfHoldingsSyncService.class);
            var order = new ArrayList<String>();
            var fullEntered = new CountDownLatch(1);
            var fullRelease = new CountDownLatch(1);
            doAnswer(invocation -> {
                order.add("FULL-" + outcome);
                fullEntered.countDown();
                if (!fullRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                return null;
            }).when(service).syncScheduled();
            doAnswer(invocation -> { order.add("STARTUP"); return null; })
                    .when(service).syncMissingOnStartup();
            var startupTask = new AtomicReference<Runnable>();
            var scheduler = new FubonEtfHoldingsSyncScheduler(service, startupTask::set);
            scheduler.onApplicationReady();

            Thread full = Thread.ofVirtual().start(scheduler::syncMorning);
            assertThat(fullEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startupTask.get().run();
            fullRelease.countDown();
            full.join(5_000);

            assertThat(full.isAlive()).isFalse();
            assertThat(order).containsExactly("FULL-" + outcome, "STARTUP");
        }
    }

    @Test
    void startupWinnerDrainsNewFullIntentAfterStartup() throws Exception {
        var service = mock(FubonEtfHoldingsSyncService.class);
        var order = new ArrayList<String>();
        var startupEntered = new CountDownLatch(1);
        var startupRelease = new CountDownLatch(1);
        doAnswer(invocation -> {
            order.add("STARTUP");
            startupEntered.countDown();
            if (!startupRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return null;
        }).when(service).syncMissingOnStartup();
        doAnswer(invocation -> { order.add("FULL"); return null; }).when(service).syncScheduled();
        var startupTask = new AtomicReference<Runnable>();
        var scheduler = new FubonEtfHoldingsSyncScheduler(service, startupTask::set);
        scheduler.onApplicationReady();

        Thread startup = Thread.ofVirtual().start(startupTask.get());
        assertThat(startupEntered.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.syncAfternoon();
        startupRelease.countDown();
        startup.join(5_000);

        assertThat(startup.isAlive()).isFalse();
        assertThat(order).containsExactly("STARTUP", "FULL");
    }

    @Test
    void burstWhileConsumedKeepsOnePendingBitAndDoesNotLoseWakeup() throws Exception {
        var service = mock(FubonEtfHoldingsSyncService.class);
        var firstEntered = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!firstRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            }
            return null;
        }).when(service).syncScheduled();
        var scheduler = new FubonEtfHoldingsSyncScheduler(service, Runnable::run);

        Thread runner = Thread.ofVirtual().start(scheduler::syncMorning);
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 100; index++) scheduler.syncAfternoon();
        firstRelease.countDown();
        runner.join(5_000);

        assertThat(runner.isAlive()).isFalse();
        verify(service, times(2)).syncScheduled();
        verifyNoMoreInteractions(service);
    }

    @Test
    void actionFailureIsIsolatedDoesNotSelfScheduleAndLaterFullStillRuns() {
        var service = mock(FubonEtfHoldingsSyncService.class);
        doThrow(new IllegalStateException("failed round")).doNothing().when(service).syncScheduled();
        var scheduler = new FubonEtfHoldingsSyncScheduler(service, Runnable::run);

        scheduler.syncMorning();
        verify(service, times(1)).syncScheduled();
        scheduler.syncAfternoon();
        verify(service, times(2)).syncScheduled();
        verifyNoMoreInteractions(service);
    }

    @Test
    void schedulesAndLifecycleAnnotationMatchContracts() throws Exception {
        Scheduled morning = FubonEtfHoldingsSyncScheduler.class.getMethod("syncMorning").getAnnotation(Scheduled.class);
        Scheduled afternoon = FubonEtfHoldingsSyncScheduler.class.getMethod("syncAfternoon").getAnnotation(Scheduled.class);
        EventListener ready = FubonEtfHoldingsSyncScheduler.class.getMethod("onApplicationReady").getAnnotation(EventListener.class);
        assertThat(morning.cron()).isEqualTo("0 50 8 * * MON-FRI");
        assertThat(afternoon.cron()).isEqualTo("0 30 15 * * MON-FRI");
        assertThat(morning.zone()).isEqualTo("Asia/Taipei");
        assertThat(afternoon.zone()).isEqualTo("Asia/Taipei");
        assertThat(ready.value()).containsExactly(org.springframework.boot.context.event.ApplicationReadyEvent.class);
    }
}
