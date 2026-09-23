package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradingRadarRefreshJobServiceTest {
    private final MutableClock clock = new MutableClock();
    private final ManualExecutor executor = new ManualExecutor();
    private final CurrentUserContext context = mock(CurrentUserContext.class);
    private final TradingRadarRefreshService refresh = mock(TradingRadarRefreshService.class);
    private final AtomicLong owner = new AtomicLong(7);
    private final TradingRadarRefreshJobService jobs = new TradingRadarRefreshJobService(refresh, context, clock, executor);

    TradingRadarRefreshJobServiceTest() {
        when(context.hasUser()).thenReturn(true);
        when(context.getEffectiveUserId()).thenAnswer(call -> owner.get());
        when(refresh.refreshForOwner(anyLong())).thenReturn(outcome("FETCHED"));
    }

    @AfterEach void close() { jobs.close(); }

    @Test void submitOnlyQueuesAndRepeatedOwnerReturnsSameJobWithoutProviderCalls() {
        var first = jobs.start();
        assertThat(UUID.fromString(first.jobId()).version()).isEqualTo(4);
        assertThat(first.status()).isEqualTo("QUEUED");
        assertThat(first.createdAt()).isEqualTo("2026-09-23T08:00Z");
        assertThat(first.completedAt()).isNull();
        assertThat(first.priceRefresh()).isNull();
        assertThat(jobs.start()).isEqualTo(first);
        assertThat(jobs.get(first.jobId())).isEqualTo(first);
        assertThat(executor.tasks).hasSize(1);
        verifyNoInteractions(refresh);
    }

    @ParameterizedTest @ValueSource(strings = {"FETCHED", "CLOSED_SYNCED", "COOLDOWN", "BUSY", "TIMEOUT", "FAILED", "SKIPPED_PENDING_CLOSE"})
    void preservesEveryFailSoftOutcomeWithoutConfusingItWithJobFailure(String outcome) {
        when(refresh.refreshForOwner(7L)).thenReturn(outcome(outcome));
        var queued = jobs.start();
        clearInvocations(context);
        executor.runNext();
        verifyNoInteractions(context);
        var completed = jobs.get(queued.jobId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.priceRefresh()).isEqualTo(outcome(outcome).priceRefresh());
        assertThat(completed.completedAt()).isNotNull();
        verify(refresh).refreshForOwner(7L);
        verify(refresh, never()).refreshAndGet();
        assertThat(jobs.start().jobId()).isNotEqualTo(queued.jobId());
    }

    @Test void authenticationMalformedAndForeignIdsHaveDistinctTypedFailuresWithoutWork() {
        var own = jobs.start();
        owner.set(8);
        assertThatThrownBy(() -> jobs.get(own.jobId())).isInstanceOf(NoSuchElementException.class)
                .hasMessage("找不到更新工作");
        assertThatThrownBy(() -> jobs.get(UUID.randomUUID().toString())).isInstanceOf(NoSuchElementException.class)
                .hasMessage("找不到更新工作");
        assertThatThrownBy(() -> jobs.get("1-1-1-1-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.get("invalid")).isInstanceOf(IllegalArgumentException.class);
        when(context.hasUser()).thenReturn(false);
        assertThatThrownBy(jobs::start).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> jobs.get(own.jobId())).isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(refresh);
    }

    @Test void queueRejectionDoesNotKeepAnUnacceptedJobOrInvokeProvider() {
        for (int i = 0; i < 8; i++) { owner.set(i + 1); jobs.start(); }
        owner.set(99);
        assertThatThrownBy(jobs::start).isInstanceOf(TradingRadarRefreshUnavailableException.class);
        verifyNoInteractions(refresh);
        executor.runNext();
        assertThat(jobs.start().status()).isEqualTo("QUEUED");
        verify(refresh, times(1)).refreshForOwner(anyLong());
    }

    @Test void registryIsBoundedAndOnlyExpiredFinishedJobsAreReclaimed() {
        for (int i = 1; i <= 128; i++) {
            owner.set(i);
            jobs.start();
            executor.runNext();
        }
        owner.set(129);
        assertThatThrownBy(jobs::start).isInstanceOf(TradingRadarRefreshUnavailableException.class);
        clock.advance(300);
        assertThat(jobs.start().status()).isEqualTo("QUEUED");
        verify(refresh, times(128)).refreshForOwner(anyLong());
    }

    @Test void completedRecordExpiresAfterFiveMinutesAndRestartLosesIds() {
        var queued = jobs.start();
        executor.runNext();
        clock.advance(299);
        assertThat(jobs.get(queued.jobId()).status()).isEqualTo("COMPLETED");
        clock.advance(1);
        assertThatThrownBy(() -> jobs.get(queued.jobId())).isInstanceOf(NoSuchElementException.class);
        try (var restarted = new AutoClosingService(refresh, context, clock)) {
            assertThatThrownBy(() -> restarted.service.get(queued.jobId())).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test void queuedDeadlineFailsWithoutStartingAndReleasesOwnerSafely() {
        var queued = jobs.start();
        clock.advance(45);
        assertThat(jobs.get(queued.jobId()).status()).isEqualTo("FAILED");
        var replacement = jobs.start();
        assertThat(replacement.jobId()).isNotEqualTo(queued.jobId());
        executor.runNext();
        verifyNoInteractions(refresh);
        executor.runNext();
        verify(refresh).refreshForOwner(7L);
    }

    @Test void workerChecksQueueDeadlineEvenWhenNobodyPolls() {
        var queued = jobs.start();
        clock.advance(45);
        executor.runNext();
        verifyNoInteractions(refresh);
        assertThat(jobs.get(queued.jobId()).status()).isEqualTo("FAILED");
    }

    @Test void runningDeadlineCannotReleaseFenceOrPublishLateSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(refresh.refreshForOwner(7L)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return outcome("FETCHED");
        });
        var queued = jobs.start();
        Thread worker = new Thread(executor::runNext);
        worker.start();
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.get(queued.jobId()).status()).isEqualTo("RUNNING");
            clock.advance(45);
            assertThat(jobs.get(queued.jobId()).status()).isEqualTo("FAILED");
            assertThat(jobs.start().jobId()).isEqualTo(queued.jobId());
            clock.advance(301);
            assertThat(jobs.start().jobId()).isEqualTo(queued.jobId());
            assertThat(jobs.get(queued.jobId()).priceRefresh()).isNull();
        } finally {
            release.countDown();
            worker.join(1500);
        }
        assertThat(worker.isAlive()).isFalse();
        verify(refresh).refreshForOwner(7L);
        // Publishing a late success would reset completedAt and incorrectly retain this record.
        assertThatThrownBy(() -> jobs.get(queued.jobId())).isInstanceOf(NoSuchElementException.class);
        assertThat(jobs.start().jobId()).isNotEqualTo(queued.jobId());
    }

    @Test void unexpectedWorkerFailureIsSanitizedAndDoesNotKeepOwnerFence() {
        when(refresh.refreshForOwner(7L)).thenThrow(new IllegalStateException("secret provider detail"));
        var queued = jobs.start();
        executor.runNext();
        var failed = jobs.get(queued.jobId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.priceRefresh()).isNull();
        assertThat(failed.toString()).doesNotContain("secret", "owner");
        assertThat(jobs.start().jobId()).isNotEqualTo(queued.jobId());
    }

    @Test void shutdownCancelsQueueAndRejectsFutureSubmissions() {
        var queued = jobs.start();
        jobs.close();
        assertThat(jobs.get(queued.jobId()).status()).isEqualTo("FAILED");
        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.tasks).isEmpty();
        assertThatThrownBy(jobs::start).isInstanceOf(TradingRadarRefreshUnavailableException.class);
        verifyNoInteractions(refresh);
    }

    private static TradingRadarDto.RefreshResponse outcome(String outcome) {
        return new TradingRadarDto.RefreshResponse(new TradingRadarDto.PriceRefresh(outcome, true, 123));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-23T08:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;
        @Override public void execute(Runnable task) {
            if (shutdown || tasks.size() >= 8) throw new RejectedExecutionException();
            tasks.add(task);
        }
        void runNext() { tasks.remove().run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            var pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }

    private static final class AutoClosingService implements AutoCloseable {
        final TradingRadarRefreshJobService service;
        AutoClosingService(TradingRadarRefreshService refresh, CurrentUserContext context, Clock clock) {
            service = new TradingRadarRefreshJobService(refresh, context, clock, new ManualExecutor());
        }
        @Override public void close() { service.close(); }
    }
}
