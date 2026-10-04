package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static com.steven.assets.externalmaterials.service.FubonMarketTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonTechnicalIndicatorSyncServiceTest {
    static final LocalDate DAY = LocalDate.of(2026, 8, 28);
    static final Instant NOW = Instant.parse("2026-08-28T05:40:00Z");
    final FubonMarketRunGate gate = mock(FubonMarketRunGate.class);
    final FubonRadarScope radar = mock(FubonRadarScope.class);
    final FubonMarketDataPort client = mock(FubonMarketDataPort.class);
    final FubonTechnicalCachePort cache = mock(FubonTechnicalCachePort.class);
    final AtomicLong nanos = new AtomicLong();
    final List<Long> starts = new CopyOnWriteArrayList<>();
    FubonTechnicalIndicatorSyncService service(List<String> symbols) {
        when(gate.today()).thenReturn(DAY); when(gate.sameDay(DAY)).thenReturn(true);
        when(radar.current(Integer.MAX_VALUE)).thenReturn(symbols);
        when(client.technical(anyString(), eq(DAY))).thenAnswer(invocation -> {
            return technical(invocation.getArgument(0), DAY, NOW, "0");
        });
        when(cache.write(any())).thenReturn(new FubonTechnicalCache.Write(Map.of("kdj", "WRITTEN", "macd", "WRITTEN", "bb", "WRITTEN")));
        return new FubonTechnicalIndicatorSyncService("true", gate, radar, client, cache,
                new FubonTechnicalIndicatorSyncService.Timing() {
                    public long nanos() { return nanos.get(); }
                    public void sleep(Duration wait) { nanos.addAndGet(wait.toNanos()); }
                    public void dispatched(long at) { starts.add(at); }
                });
    }
    @Test void moreThanTwentySymbolsUseOneSharedPaceAndAllFinishNormally() {
        List<String> codes = IntStream.range(0, 25).mapToObj(i -> String.valueOf(2300 + i)).toList();
        var result = service(codes).sync(false);
        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.processedCount()).isEqualTo(25);
        assertThat(result.writtenCount()).isEqualTo(25);
        assertThat(starts.get(20) - starts.getFirst()).isGreaterThanOrEqualTo(Duration.ofSeconds(90).toNanos());
        for (int i = 1; i < starts.size(); i++) assertThat(starts.get(i) - starts.get(i - 1))
                .isGreaterThanOrEqualTo(Duration.ofMillis(3100).toNanos());
    }
    @Test void trueVendor429StopsRemainingSymbolsAndCurrentPartialGroupsAreNotHidden() {
        var service = service(List.of("2330", "2331", "2332"));
        when(client.technical("2330", DAY)).thenReturn(group(technical("2330", DAY, NOW, "0"), "bb", failure("bb", "RATE_LIMITED")));
        var result = service.sync(false);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.reason()).isEqualTo("RATE_LIMITED");
        assertThat(result.processedCount()).isBetween(1, 2);
        assertThat(result.skippedCount()).isGreaterThanOrEqualTo(1);
        verify(client, never()).technical("2332", DAY);
    }
    @Test void localBudgetMakesOnlyOneDelayedRetryAndDoesNotStopHealthyLaterSymbol() {
        var service = service(List.of("2330", "2331"));
        when(client.technical("2330", DAY)).thenReturn(
                group(technical("2330", DAY, NOW, "0"), "bb", failure("bb", "HISTORY_BUDGET_EXHAUSTED")),
                technical("2330", DAY, NOW.plusSeconds(60), "0"));
        assertThat(service.sync(false).outcome()).isEqualTo("SUCCESS");
        verify(client, times(2)).technical("2330", DAY);
        verify(client).technical("2331", DAY);
        assertThat(nanos.get()).isGreaterThanOrEqualTo(Duration.ofSeconds(60).toNanos());
    }
    @Test void sameDayConflictIsPartialWithVisibleGroupOutcomeAndDryRunHasNoCacheInteraction() {
        var service = service(List.of("2330"));
        when(cache.write(any())).thenReturn(new FubonTechnicalCache.Write(Map.of(
                "kdj", "CONFLICT_NO_SOURCE_REVISION", "macd", "UNCHANGED", "bb", "UNCHANGED")));
        var result = service.sync(false);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.partialCount()).isEqualTo(1);
        assertThat(result.groupWriteOutcomes()).containsEntry("kdj:CONFLICT_NO_SOURCE_REVISION", 1);
        clearInvocations(cache);
        assertThat(service.sync(true).outcome()).isEqualTo("DRY_RUN");
        verifyNoInteractions(cache);
    }
    @Test void preCloseGateAppliesToSchedulerManualAndDryRunWithoutSourceOrCache() {
        var access = mock(FubonMarketAccess.class);
        var calendar = mock(MarketCalendar.class); when(calendar.isTwTradingDayKnown(DAY)).thenReturn(Optional.of(true));
        var clock = mock(MarketClock.class); when(clock.instant()).thenReturn(Instant.parse("2026-08-28T05:39:00Z"));
        var service = new FubonTechnicalIndicatorSyncService("true", new FubonMarketRunGate(access, calendar, clock), radar, client, cache);
        assertThat(service.sync(false).outcome()).isEqualTo("BEFORE_CLOSE");
        assertThat(service.sync(true).outcome()).isEqualTo("BEFORE_CLOSE");
        service.scheduled();
        assertThat(service.lastResult().outcome()).isEqualTo("BEFORE_CLOSE");
        verifyNoInteractions(client, cache, radar);
    }
    @Test void dayChangeDuringSourceReadPreventsAnyCacheAttempt() {
        var service = service(List.of("2330"));
        when(client.technical("2330", DAY)).thenAnswer(invocation -> {
            when(gate.sameDay(DAY)).thenReturn(false);
            return technical("2330", DAY, NOW, "0");
        });
        assertThat(service.sync(false).reason()).isEqualTo("STALE_QUERY");
        verifyNoInteractions(cache);
    }
    @Test void twoWorkersActuallyOverlapButNeverExceedTwoInflightSymbols() {
        var service = service(List.of("2330", "2331", "2332", "2333"));
        var firstTwo = new CountDownLatch(2);
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        when(client.technical(anyString(), eq(DAY))).thenAnswer(invocation -> {
            int concurrency = active.incrementAndGet();
            maximum.accumulateAndGet(concurrency, Math::max);
            try {
                firstTwo.countDown();
                assertThat(firstTwo.await(2, TimeUnit.SECONDS)).isTrue();
                return technical(invocation.getArgument(0), DAY, NOW, "0");
            } finally { active.decrementAndGet(); }
        });
        assertThat(service.sync(false).outcome()).isEqualTo("SUCCESS");
        assertThat(maximum).hasValue(2);
    }
    @Test void wholeRunDeadlinePreventsAResponseThatArrivesTooLateFromWriting() {
        var service = service(List.of("2330"));
        when(client.technical("2330", DAY)).thenAnswer(invocation -> {
            nanos.set(Duration.ofMinutes(30).toNanos());
            return technical("2330", DAY, NOW, "0");
        });
        var result = service.sync(false);
        assertThat(result.outcome()).isNotEqualTo("SUCCESS");
        assertThat(result.reason()).isEqualTo("RUN_DEADLINE");
        verifyNoInteractions(cache);
    }
    @Test void cancellationInterruptsInflightWorkerAndDoesNotAllowLateCommit() throws Exception {
        var service = service(List.of("2330", "2331"));
        var entered = new CountDownLatch(1);
        when(client.technical(anyString(), eq(DAY))).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { throw new Unavailable("INTERRUPTED", true); }
            throw new AssertionError("unreachable");
        });
        var result = new java.util.concurrent.atomic.AtomicReference<FubonTechnicalIndicatorSyncService.Result>();
        Thread run = Thread.ofPlatform().start(() -> result.set(service.sync(false)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        run.interrupt(); run.join(3000);
        assertThat(run.isAlive()).isFalse();
        assertThat(result.get().reason()).isEqualTo("INTERRUPTED");
        verifyNoInteractions(cache);
    }

    @Test void failedOrConflictedTechnicalWriterMakesPartialOutcomeWithoutRedisProjection() {
        for (var status : List.of(FubonMarketDataHistoryStore.Status.FAILED,
                FubonMarketDataHistoryStore.Status.CONFLICT_NO_SOURCE_REVISION)) {
            FubonMarketDataHistoryStore history = mock(FubonMarketDataHistoryStore.class);
            FubonTechnicalV2CacheRepository v2Cache = mock(FubonTechnicalV2CacheRepository.class);
            UUID capture = UUID.randomUUID();
            var profiles = TECHNICAL_PROFILES.stream().map(profile -> new TechnicalProfileRead(
                    profile.profileId(), "AVAILABLE", null, profile.parameters(), NOW,
                    List.of(new TechnicalHistory(DAY, null, profile.payloadFields().stream()
                            .collect(java.util.stream.Collectors.toMap(field -> field, field -> "1")))))).toList();
            var technical = new TechnicalBundle(capture, "2330", DAY.minusDays(TECHNICAL_MAX_SPAN_DAYS), DAY, profiles);
            when(gate.today()).thenReturn(DAY); when(gate.sameDay(DAY)).thenReturn(true);
            when(radar.current(Integer.MAX_VALUE)).thenReturn(List.of("2330"));
            when(client.technicalV2("2330", DAY)).thenReturn(technical);
            when(history.persistTechnical(technical)).thenReturn(new FubonMarketDataHistoryStore.TechnicalResult(
                    status, false, 0, 0, status == FubonMarketDataHistoryStore.Status.FAILED ? 0 : 1, capture));
            when(client.basic("2330", DAY)).thenReturn(new StockBasicRead("2330", DAY, NOW, "TWSE",
                    null, "test", null, null, null, null, true, "NORMAL", null, 1000, "TWD"));
            when(history.persistBasic(any())).thenReturn(new FubonMarketDataHistoryStore.BasicResult(
                    FubonMarketDataHistoryStore.Status.UNCHANGED));
            when(client.candles("2330", DAY)).thenReturn(new IntradayCandlesRead("2330", DAY, NOW, "TWSE",
                    null, 1, "NO_DATA", "NO_DATA", List.of()));
            var service = new FubonTechnicalIndicatorSyncService("true", gate, radar, client, cache,
                    history, v2Cache, new FubonTechnicalIndicatorSyncService.Timing() {
                public long nanos() { return 0; }
                public void sleep(Duration duration) { }
            });
            var result = service.sync(false);
            assertThat(result.outcome()).isEqualTo("PARTIAL");
            assertThat(result.partialCount()).isEqualTo(1);
            assertThat(result.groupWriteOutcomes()).containsEntry("technicalDb:" + status, 1);
            verifyNoInteractions(v2Cache);
            clearInvocations(client, history);
        }
    }
}
