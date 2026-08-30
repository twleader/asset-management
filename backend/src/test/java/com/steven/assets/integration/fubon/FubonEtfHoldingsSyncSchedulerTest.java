package com.steven.assets.integration.fubon;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonEtfHoldingsSyncSchedulerTest {
    @Test
    void bothEntryPointsShareOneFlightAndReleaseAfterCompletion() throws Exception {
        var service = mock(FubonEtfHoldingsSyncService.class);
        var scheduler = new FubonEtfHoldingsSyncScheduler(service);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return null;
        }).when(service).syncScheduled();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var morning = executor.submit(scheduler::syncMorning);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                scheduler.syncAfternoon();
                verify(service, times(1)).syncScheduled();
            } finally { release.countDown(); }
            morning.get(5, TimeUnit.SECONDS);
        }
        scheduler.syncAfternoon();
        verify(service, times(2)).syncScheduled();
    }

    @Test
    void failedRoundReleasesSingleFlight() {
        var service = mock(FubonEtfHoldingsSyncService.class);
        var scheduler = new FubonEtfHoldingsSyncScheduler(service);
        doThrow(new IllegalStateException("failed round")).doNothing().when(service).syncScheduled();
        assertThatThrownBy(scheduler::syncMorning).isInstanceOf(IllegalStateException.class);
        scheduler.syncAfternoon();
        verify(service, times(2)).syncScheduled();
    }

    @Test
    void schedulesMatchTheTwoTaipeiWeekdayTimes() throws Exception {
        Scheduled morning = FubonEtfHoldingsSyncScheduler.class.getMethod("syncMorning").getAnnotation(Scheduled.class);
        Scheduled afternoon = FubonEtfHoldingsSyncScheduler.class.getMethod("syncAfternoon").getAnnotation(Scheduled.class);
        assertThat(morning.cron()).isEqualTo("0 50 8 * * MON-FRI");
        assertThat(afternoon.cron()).isEqualTo("0 30 15 * * MON-FRI");
        assertThat(morning.zone()).isEqualTo("Asia/Taipei");
        assertThat(afternoon.zone()).isEqualTo("Asia/Taipei");
    }
}
