package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataFetchServiceTwAuthorityRetryTest {

    private static final Map<String, String> DGPA = Map.of("2027-01-01", "DGPA holiday");
    private static final Map<String, String> TWSE = Map.of("2027-01-02", "TWSE closure");

    @Test
    void twseSuccessNeverTouchesDgpaAndBothPublicReadsShareInstalledEntry() {
        TwTyphoonClosureService closures = knownClosures();
        DgpaCalendarAuthority dgpa = mock(DgpaCalendarAuthority.class);
        SequencedService service = new SequencedService(closures, dgpa, new MutableClock(), TWSE);

        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(TWSE);
        assertThat(service.getTwHolidaysKnown(2027)).contains(TWSE);
        assertThat(service.twseAttempts).isEqualTo(1);
        verify(dgpa, never()).fetchHolidays(2027);
    }

    @Test
    void emptyInitialSlotIsNeverPoisonedAndNextReadCanRecoverThroughDgpa() {
        DgpaCalendarAuthority dgpa = mock(DgpaCalendarAuthority.class);
        when(dgpa.fetchHolidays(2027)).thenReturn(Optional.empty(), Optional.of(DGPA));
        SequencedService service = new SequencedService(
                knownClosures(), dgpa, new MutableClock(), Map.of(), Map.of());

        assertThat(service.getTwHolidays(2027)).isEmpty();
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        assertThat(service.twseAttempts).isEqualTo(2);
    }

    @Test
    void closureOnlyCannotPromoteMissingBaseToKnownAuthority() {
        TwTyphoonClosureService closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        when(closures.closuresForYear(2027)).thenReturn(Map.of("2027-08-01", "臨時休市"));
        DgpaCalendarAuthority dgpa = mock(DgpaCalendarAuthority.class);
        when(dgpa.fetchHolidays(2027)).thenReturn(Optional.empty());
        SequencedService service = new SequencedService(
                closures, dgpa, new MutableClock(), Map.of());

        assertThat(service.getTwHolidaysKnown(2027)).isEmpty();
    }

    @Test
    void unknownClosureCalendarFailsBeforeAnnualAuthorityIsRead() {
        TwTyphoonClosureService closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(false);
        DgpaCalendarAuthority dgpa = mock(DgpaCalendarAuthority.class);
        SequencedService service = new SequencedService(closures, dgpa, new MutableClock(), TWSE);

        assertThat(service.getTwHolidaysKnown(2027)).isEmpty();
        assertThat(service.twseAttempts).isZero();
        verify(closures).loadFromDb();
        verify(closures, never()).closuresForYear(2027);
    }

    @Test
    void provisionalEntryLivesSixHoursThenAtomicallyUpgradesToPermanentTwse() {
        MutableClock clock = new MutableClock();
        DgpaCalendarAuthority dgpa = mock(DgpaCalendarAuthority.class);
        when(dgpa.fetchHolidays(2027)).thenReturn(Optional.of(DGPA));
        SequencedService service = new SequencedService(
                knownClosures(), dgpa, clock, Map.of(), TWSE);

        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        clock.advance(Duration.ofHours(5));
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        assertThat(service.twseAttempts).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(TWSE);
        clock.advance(Duration.ofDays(365));
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(TWSE);
        assertThat(service.twseAttempts).isEqualTo(2);
        verify(dgpa).fetchHolidays(2027);
    }

    @Test
    void expiredDualFailureKeepsLastGoodDgpaAndRetriesOnlyAfterTenMinutes() {
        MutableClock clock = new MutableClock();
        DgpaCalendarAuthority dgpa = mock(DgpaCalendarAuthority.class);
        when(dgpa.fetchHolidays(2027))
                .thenReturn(Optional.of(DGPA), Optional.empty(), Optional.empty());
        SequencedService service = new SequencedService(
                knownClosures(), dgpa, clock, Map.of(), Map.of(), Map.of());

        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        clock.advance(Duration.ofHours(7));
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        int afterFailure = service.twseAttempts;

        clock.advance(Duration.ofMinutes(9));
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        assertThat(service.twseAttempts).isEqualTo(afterFailure);

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
        assertThat(service.twseAttempts).isEqualTo(afterFailure + 1);
    }

    @ParameterizedTest(name = "emptySlot={0}, dgpaCompletesFirst={1}")
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void sourceAwareCasAlwaysEndsAtTwseForBothCompletionOrders(
            boolean emptySlot, boolean dgpaCompletesFirst) throws Exception {
        MutableClock clock = new MutableClock();
        ThreadLocal<RacePlan> activePlan = new ThreadLocal<>();
        RaceDgpaAuthority dgpa = new RaceDgpaAuthority(activePlan);
        RaceService service = new RaceService(knownClosures(), dgpa, clock, activePlan);

        if (!emptySlot) {
            dgpa.defaultResponse = Optional.of(DGPA);
            assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(DGPA);
            clock.advance(Duration.ofHours(7));
        }

        RacePlan dgpaPlan = new RacePlan(2027, Map.of(), Optional.of(DGPA));
        RacePlan twsePlan = new RacePlan(2027, TWSE, Optional.empty());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Map<String, String>> dgpaFuture = executor.submit(() -> service.readWithPlan(dgpaPlan));
            Future<Map<String, String>> twseFuture = executor.submit(() -> service.readWithPlan(twsePlan));
            assertThat(dgpaPlan.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(twsePlan.started.await(2, TimeUnit.SECONDS)).isTrue();

            RacePlan first = dgpaCompletesFirst ? dgpaPlan : twsePlan;
            RacePlan second = dgpaCompletesFirst ? twsePlan : dgpaPlan;
            first.release.countDown();
            (dgpaCompletesFirst ? dgpaFuture : twseFuture).get(2, TimeUnit.SECONDS);
            second.release.countDown();
            dgpaFuture.get(2, TimeUnit.SECONDS);
            twseFuture.get(2, TimeUnit.SECONDS);
        }

        int dgpaCallsAfterRace = dgpa.calls.get();
        assertThat(service.getTwHolidays(2027)).containsExactlyEntriesOf(TWSE);
        assertThat(dgpa.calls).hasValue(dgpaCallsAfterRace);
    }

    @Test
    void differentYearsRefreshIndependentlyWhileOneYearRemainsBlocked() throws Exception {
        ThreadLocal<RacePlan> activePlan = new ThreadLocal<>();
        RaceDgpaAuthority dgpa = new RaceDgpaAuthority(activePlan);
        RaceService service = new RaceService(knownClosures(), dgpa, new MutableClock(), activePlan);
        RacePlan blocked2027 = new RacePlan(2027, TWSE, Optional.empty());
        RacePlan free2028 = new RacePlan(2028, Map.of("2028-01-03", "TWSE 2028"), Optional.empty());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Map<String, String>> first = executor.submit(() -> service.readWithPlan(blocked2027));
            Future<Map<String, String>> second = executor.submit(() -> service.readWithPlan(free2028));
            assertThat(blocked2027.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(free2028.started.await(2, TimeUnit.SECONDS)).isTrue();

            free2028.release.countDown();
            assertThat(second.get(2, TimeUnit.SECONDS)).containsKey("2028-01-03");
            assertThat(first.isDone()).isFalse();
            blocked2027.release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).containsExactlyEntriesOf(TWSE);
        }
    }

    private static TwTyphoonClosureService knownClosures() {
        TwTyphoonClosureService closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        when(closures.closuresForYear(anyInt())).thenReturn(Map.of());
        return closures;
    }

    private static final class SequencedService extends MarketDataFetchService {
        private int twseAttempts;
        private final Deque<Map<String, String>> responses = new ArrayDeque<>();

        @SafeVarargs
        private SequencedService(TwTyphoonClosureService closures,
                                 DgpaCalendarAuthority dgpa,
                                 Clock clock,
                                 Map<String, String>... responses) {
            super(mock(StockSourceQuery.class), closures, "", dgpa, clock);
            for (Map<String, String> response : responses) this.responses.addLast(response);
        }

        @Override
        Map<String, String> fetchTwHolidaysFromTwse(int year) {
            twseAttempts++;
            return responses.isEmpty() ? Map.of() : responses.removeFirst();
        }
    }

    private static final class RaceService extends MarketDataFetchService {
        private final ThreadLocal<RacePlan> activePlan;

        private RaceService(TwTyphoonClosureService closures,
                            DgpaCalendarAuthority dgpa,
                            Clock clock,
                            ThreadLocal<RacePlan> activePlan) {
            super(mock(StockSourceQuery.class), closures, "", dgpa, clock);
            this.activePlan = activePlan;
        }

        private Map<String, String> readWithPlan(RacePlan plan) {
            activePlan.set(plan);
            try {
                return getTwHolidays(plan.year);
            } finally {
                activePlan.remove();
            }
        }

        @Override
        Map<String, String> fetchTwHolidaysFromTwse(int year) {
            RacePlan plan = activePlan.get();
            if (plan == null) return Map.of();
            assertThat(year).isEqualTo(plan.year);
            plan.started.countDown();
            try {
                if (!plan.release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("race release timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return plan.twse;
        }
    }

    private static final class RaceDgpaAuthority implements DgpaCalendarAuthority {
        private final ThreadLocal<RacePlan> activePlan;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Optional<Map<String, String>> defaultResponse = Optional.empty();

        private RaceDgpaAuthority(ThreadLocal<RacePlan> activePlan) {
            this.activePlan = activePlan;
        }

        @Override
        public Optional<Map<String, String>> fetchHolidays(int year) {
            calls.incrementAndGet();
            RacePlan plan = activePlan.get();
            return plan == null ? defaultResponse : plan.dgpa;
        }
    }

    private static final class RacePlan {
        private final int year;
        private final Map<String, String> twse;
        private final Optional<Map<String, String>> dgpa;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private RacePlan(int year, Map<String, String> twse, Optional<Map<String, String>> dgpa) {
            this.year = year;
            this.twse = twse;
            this.dgpa = dgpa;
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-08-21T00:00:00Z"));

        private void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
