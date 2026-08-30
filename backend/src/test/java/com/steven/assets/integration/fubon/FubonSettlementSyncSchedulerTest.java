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
 * Four fixed cron slots and single-flight guard for the settlement sync scheduler
 * (Requirement 129 / Task 394). Structurally identical to
 * {@link FubonBankBalanceSyncSchedulerTest}: there is no calendar gate to verify here at all --
 * the feature-gate / configState short-circuit is entirely delegated to
 * {@link FubonSettlementSyncService#syncScheduled()}, which is asserted separately in
 * {@link FubonSettlementSyncServiceTest}.
 */
class FubonSettlementSyncSchedulerTest {

    @Test
    void fourCronExpressionsAndZoneAreExact() throws Exception {
        Method method = FubonSettlementSyncScheduler.class.getMethod("scheduledSettlementSync");
        Scheduled[] scheduled = method.getAnnotationsByType(Scheduled.class);
        assertThat(scheduled).hasSize(4);
        assertThat(scheduled).extracting(Scheduled::cron).containsExactlyInAnyOrder(
                "0 0 8 * * *", "0 45 13 * * *", "0 30 19 * * *", "0 0 22 * * *");
        assertThat(scheduled).allSatisfy(annotation -> assertThat(annotation.zone()).isEqualTo("Asia/Taipei"));
    }

    @Test
    void delegatesExactlyOnceToServiceSyncScheduled() {
        FubonSettlementSyncService sync = mock(FubonSettlementSyncService.class);
        when(sync.syncScheduled()).thenReturn(FubonSettlementOutcome.SUCCESS);

        new FubonSettlementSyncScheduler(sync).scheduledSettlementSync();

        verify(sync, times(1)).syncScheduled();
    }

    @Test
    void featureGateOrConfigStateShortCircuitInsideServiceStillLetsSchedulerReturnCleanly() {
        FubonSettlementSyncService sync = mock(FubonSettlementSyncService.class);
        when(sync.syncScheduled()).thenReturn(FubonSettlementOutcome.SETTLEMENT_SYNC_DISABLED);

        new FubonSettlementSyncScheduler(sync).scheduledSettlementSync();

        verify(sync, times(1)).syncScheduled();
    }

    @Test
    void inFlightGuardDropsOverlappingTick() throws Exception {
        FubonSettlementSyncService sync = mock(FubonSettlementSyncService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(sync.syncScheduled()).thenAnswer(ignored -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return FubonSettlementOutcome.SUCCESS;
        });
        FubonSettlementSyncScheduler scheduler = new FubonSettlementSyncScheduler(sync);

        Thread first = Thread.ofVirtual().start(scheduler::scheduledSettlementSync);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.scheduledSettlementSync();
        release.countDown();
        first.join(2_000);

        verify(sync, times(1)).syncScheduled();
    }
}
