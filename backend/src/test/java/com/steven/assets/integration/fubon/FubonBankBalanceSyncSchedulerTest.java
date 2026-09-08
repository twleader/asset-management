package com.steven.assets.integration.fubon;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Four fixed cron slots and single-flight guard for the bank balance sync scheduler
 * (Requirement 128 / Task 393). Unlike {@link FubonTradeSyncSchedulerTest} / the inventory
 * scheduler's test, there is no calendar gate to verify here at all -- the feature-gate /
 * configState short-circuit is entirely delegated to {@link FubonBankBalanceSyncService
 * #syncScheduled()}, which is asserted separately in {@link FubonBankBalanceSyncServiceTest}.
 */
class FubonBankBalanceSyncSchedulerTest {

    @Test
    void fourCronExpressionsAndZoneAreExact() throws Exception {
        Method method = FubonBankBalanceSyncScheduler.class.getMethod("scheduledBankBalanceSync");
        Scheduled[] scheduled = method.getAnnotationsByType(Scheduled.class);
        assertThat(scheduled).hasSize(4);
        assertThat(scheduled).extracting(Scheduled::cron).containsExactlyInAnyOrder(
                "0 0 8 * * *", "0 20 9 * * *", "0 20 14 * * *", "0 0 22 * * *");
        assertThat(scheduled).allSatisfy(annotation -> assertThat(annotation.zone()).isEqualTo("Asia/Taipei"));
    }

    @Test
    void delegatesExactlyOnceToServiceSyncScheduled() {
        FubonBankBalanceSyncService sync = mock(FubonBankBalanceSyncService.class);
        when(sync.syncScheduled()).thenReturn(FubonBankBalanceOutcome.SUCCESS);

        new FubonBankBalanceSyncScheduler(sync).scheduledBankBalanceSync();

        verify(sync, times(1)).syncScheduled();
    }

    @Test
    void featureGateOrConfigStateShortCircuitInsideServiceStillLetsSchedulerReturnCleanly() {
        FubonBankBalanceSyncService sync = mock(FubonBankBalanceSyncService.class);
        when(sync.syncScheduled()).thenReturn(FubonBankBalanceOutcome.BANK_BALANCE_SYNC_DISABLED);

        new FubonBankBalanceSyncScheduler(sync).scheduledBankBalanceSync();

        verify(sync, times(1)).syncScheduled();
    }

    @Test
    void inFlightGuardDropsOverlappingTick() throws Exception {
        FubonBankBalanceSyncService sync = mock(FubonBankBalanceSyncService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(sync.syncScheduled()).thenAnswer(ignored -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FubonBankBalanceOutcome.SUCCESS;
        });
        FubonBankBalanceSyncScheduler scheduler = new FubonBankBalanceSyncScheduler(sync);

        Thread first = Thread.ofVirtual().start(scheduler::scheduledBankBalanceSync);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.scheduledBankBalanceSync();
        release.countDown();
        first.join(2_000);

        verify(sync, times(1)).syncScheduled();
    }
}
