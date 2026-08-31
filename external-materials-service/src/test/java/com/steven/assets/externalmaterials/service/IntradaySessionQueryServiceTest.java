package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntradaySessionQueryServiceTest {
    private final MarketClock marketClock = mock(MarketClock.class);
    private final IntradayTickStore ticks = mock(IntradayTickStore.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final SessionReferencePriceStore references = mock(SessionReferencePriceStore.class);
    private final PriceFetchClient priceFetch = mock(PriceFetchClient.class);
    private ExecutorService async;

    @AfterEach
    void closeExecutor() {
        if (async != null) async.shutdownNow();
    }

    @Test
    void springSelectsTheProductionConstructorInsteadOfThePackagePrivateTestSeam() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MarketClock.class, () -> marketClock);
            context.registerBean(IntradayTickStore.class, () -> ticks);
            context.registerBean(StockSourceQuery.class, () -> source);
            context.registerBean(SessionReferencePriceStore.class, () -> references);
            context.registerBean(PriceFetchClient.class, () -> priceFetch);
            context.register(IntradaySessionQueryService.class);

            context.refresh();

            assertThat(context.getBean(IntradaySessionQueryService.class)).isNotNull();
        }
    }

    @Test
    void cacheHitDoesNotFetchAndPreservesTargetBucket() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        var evidence = new SessionReferencePriceStore.SessionReferencePrice(new BigDecimal("50.55"),
                SessionReferencePriceStore.TWSE_MIS_Y, Instant.parse("2025-01-02T05:00:00Z"));
        when(ticks.getTicks("00881", "台股", day)).thenReturn(List.of(
                new IntradayTickStore.TickPoint("2025-01-02T09:00:00", new BigDecimal("49.48"))));
        when(references.get("00881", "台股", day)).thenReturn(Optional.of(evidence));

        var session = service(at(day), Runnable::run).query("00881", "台股", day);

        assertThat(session.tradingDate()).isEqualTo(day);
        assertThat(session.sessionReferencePrice()).isEqualByComparingTo("50.55");
        verifyNoInteractions(priceFetch);
    }

    @Test
    void exactMissStoresOnlyQualifiedTwseMisReferenceAndMismatchFailsClosed() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        when(marketClock.isTradingDay("台股", day)).thenReturn(true);
        when(ticks.getTicks("00881", "台股", day)).thenReturn(List.of());
        when(references.get(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(priceFetch.fetchTwSessionReference("00881", day)).thenReturn(Optional.of(
                new PriceFetchClient.TwSessionReference("00881", day, new BigDecimal("50.55"), "TWSE_MIS_Y")));

        var exact = service(at(day), Runnable::run).query("00881", "台股", day);

        assertThat(exact.sessionReferencePrice()).isEqualByComparingTo("50.55");
        verify(references).put(org.mockito.ArgumentMatchers.eq("00881"), org.mockito.ArgumentMatchers.eq("台股"),
                org.mockito.ArgumentMatchers.eq(day), any());

        reset(references, priceFetch);
        when(references.get(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(priceFetch.fetchTwSessionReference("00881", day)).thenReturn(Optional.of(
                new PriceFetchClient.TwSessionReference("00881", day.plusDays(1), new BigDecimal("50.55"), "TWSE_MIS_Y")));
        var mismatch = service(at(day), Runnable::run).query("00881", "台股", day);
        assertThat(mismatch.sessionReferencePrice()).isNull();
        verify(references, never()).put(anyString(), anyString(), any(), any());
    }

    @Test
    void fubonPreviousCloseAndYahooAreNotCandidatesForThisExactMisFact() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        when(marketClock.isTradingDay("台股", day)).thenReturn(true);
        when(ticks.getTicks("00881", "台股", day)).thenReturn(List.of());
        when(references.get(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(priceFetch.fetchTwSessionReference("00881", day)).thenReturn(Optional.empty());

        var session = service(at(day), Runnable::run).query("00881", "台股", day);

        assertThat(session.sessionReferencePrice()).isNull();
        verify(priceFetch).fetchTwSessionReference("00881", day);
        verify(references, never()).put(anyString(), anyString(), any(), any());
        // The service has no Fubon/Yahoo client dependency: only the qualified TWSE_MIS_Y type can enter here.
    }

    @Test
    void completeCurrentTickBucketStillSelfHealsReferenceWithoutRefreshingTicks() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        when(marketClock.isTradingDay("台股", day)).thenReturn(true);
        when(ticks.getTicks("00881", "台股", day)).thenReturn(List.of(
                new IntradayTickStore.TickPoint("2025-01-02T09:00:00", new BigDecimal("50")),
                new IntradayTickStore.TickPoint("2025-01-02T09:15:00", new BigDecimal("50.10"))));
        when(references.get("00881", "台股", day)).thenReturn(Optional.empty());
        when(priceFetch.fetchTwSessionReference("00881", day)).thenReturn(Optional.of(
                new PriceFetchClient.TwSessionReference("00881", day, new BigDecimal("50.55"), "TWSE_MIS_Y")));

        var session = service(at(day), Runnable::run).query("00881", "台股", day);

        assertThat(session.sessionReferencePrice()).isEqualByComparingTo("50.55");
        verify(references).get("00881", "台股", day);
        verify(priceFetch).fetchTwSessionReference("00881", day);
        verify(references).put(org.mockito.ArgumentMatchers.eq("00881"), org.mockito.ArgumentMatchers.eq("台股"),
                org.mockito.ArgumentMatchers.eq(day), any());
        assertThat(java.util.Arrays.stream(IntradaySessionQueryService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)).doesNotContain(IntradayTickRefresher.class);
        verify(ticks).getTicks("00881", "台股", day);
        verifyNoMoreInteractions(ticks);
    }

    @Test
    void invalidTaiwanCodeAndIndexSkipReferenceCacheAndExternalFetch() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        when(ticks.getTicks("0000", "台股", day)).thenReturn(List.of());
        when(ticks.getTicks("00881;bad", "台股", day)).thenReturn(List.of());

        service(at(day), Runnable::run).query("0000", "台股", day);
        service(at(day), Runnable::run).query("00881;bad", "台股", day);

        verifyNoInteractions(references, priceFetch);
    }

    @Test
    void historicalCacheMissNeverStartsAReferenceProviderFetch() {
        LocalDate historical = LocalDate.of(2025, 1, 2);
        LocalDate today = historical.plusDays(1);
        when(references.get("00881", "台股", historical)).thenReturn(Optional.empty());

        var session = service(at(today), Runnable::run).query("00881", "台股", historical);

        assertThat(session.sessionReferencePrice()).isNull();
        verify(references).get("00881", "台股", historical);
        verifyNoInteractions(priceFetch);
        verify(references, never()).put(anyString(), anyString(), any(), any());
    }

    @Test
    void sameKeyConcurrentMissUsesOneFetchAndCooldownThenRetriesWithInjectableClock() throws Exception {
        LocalDate day = LocalDate.of(2025, 1, 2);
        MutableClock clock = new MutableClock(at(day).millis());
        when(marketClock.isTradingDay("台股", day)).thenReturn(true);
        when(references.get(anyString(), anyString(), any())).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(priceFetch.fetchTwSessionReference("00881", day)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return Optional.empty();
        });
        async = Executors.newFixedThreadPool(2);
        var service = service(clock, async);
        Future<?> one = async.submit(() -> service.query("00881", "台股", day));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        Future<?> two = async.submit(() -> service.query("00881", "台股", day));
        release.countDown();
        one.get(1, TimeUnit.SECONDS);
        two.get(1, TimeUnit.SECONDS);
        assertThat(calls.get()).isEqualTo(1);
        service.query("00881", "台股", day);
        assertThat(calls.get()).isEqualTo(1);
        clock.advance(61_000);
        service.query("00881", "台股", day);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void capOf256FailsClosedForNewKeyWithoutStarting257thFetch() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        MutableClock clock = new MutableClock(at(day).millis());
        when(marketClock.isTradingDay("台股", day)).thenReturn(true);
        when(references.get(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(priceFetch.fetchTwSessionReference(anyString(), any())).thenReturn(Optional.empty());
        var service = service(clock, Runnable::run);
        for (int i = 0; i < 256; i++) service.query("%05d".formatted(10_000 + i), "台股", day);
        service.query("10256", "台股", day);
        verify(priceFetch, times(256)).fetchTwSessionReference(anyString(), org.mockito.ArgumentMatchers.eq(day));
    }

    @Test
    void defaultTradingDayKeepsTodayBucketWhenTodayTicksAreAlreadyComplete() {
        LocalDate today = LocalDate.of(2025, 1, 2);
        var todayTicks = List.of(new IntradayTickStore.TickPoint(today + "T09:00:00", new BigDecimal("20000")));
        when(marketClock.isTradingDay("台股", today)).thenReturn(true);
        when(ticks.getTicks("0000", "台股", today)).thenReturn(todayTicks);
        var session = service(at(today), Runnable::run).query("0000", "台股", null);
        assertThat(session.tradingDate()).isEqualTo(today);
        assertThat(session.ticks()).isEqualTo(todayTicks);
        verifyNoInteractions(source);
        verifyNoInteractions(references, priceFetch);
    }

    @Test
    void emptyTodayFallsBackToMaxDateWithoutRefreshingTicks() {
        LocalDate today = LocalDate.of(2025, 1, 2);
        LocalDate max = today.minusDays(1);
        var oldTicks = List.of(new IntradayTickStore.TickPoint(max + "T13:30:00", new BigDecimal("19900")));
        when(marketClock.isTradingDay("台股", today)).thenReturn(true);
        when(ticks.getTicks("0000", "台股", today)).thenReturn(List.of());
        when(source.findMaxTradingDate("0000", "台股")).thenReturn(Optional.of(max));
        when(ticks.getTicks("0000", "台股", max)).thenReturn(oldTicks);
        var service = service(at(today), Runnable::run);
        assertThat(service.query("0000", "台股", null).tradingDate()).isEqualTo(max);
        verify(ticks).getTicks("0000", "台股", today);
        verify(ticks).getTicks("0000", "台股", max);
        verify(source).findMaxTradingDate("0000", "台股");
        verifyNoInteractions(references, priceFetch);
    }

    @Test
    void missingOrIncompleteTickBucketsAreReadOnlyAndCannotReachTickRefresherOrProviders() {
        LocalDate day = LocalDate.of(2025, 1, 2);
        when(ticks.getTicks("0000", "台股", day)).thenReturn(List.of());

        var session = service(at(day), Runnable::run).query("0000", "台股", day);

        assertThat(session.ticks()).isEmpty();
        assertThat(java.util.Arrays.stream(IntradaySessionQueryService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .doesNotContain(IntradayTickRefresher.class);
        verify(ticks).getTicks("0000", "台股", day);
        verifyNoMoreInteractions(ticks); // no append/replace or second post-refresh read
        verifyNoInteractions(source, references, priceFetch);
    }

    private IntradaySessionQueryService service(Clock clock, Executor executor) {
        return new IntradaySessionQueryService(marketClock, ticks, source, references, priceFetch,
                clock, Duration.ofMillis(300), Duration.ofSeconds(60), executor);
    }

    private static Clock at(LocalDate day) {
        return Clock.fixed(day.atTime(10, 0).atZone(MarketClock.TW_ZONE).toInstant(), ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;
        MutableClock(long millis) { this.millis = new AtomicLong(millis); }
        void advance(long by) { millis.addAndGet(by); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }
}
