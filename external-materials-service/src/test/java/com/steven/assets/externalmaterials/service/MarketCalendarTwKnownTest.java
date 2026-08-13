package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketCalendarTwKnownTest {

    @Test
    void weekendIsKnownClosedWithoutDependingOnRemoteCalendars() {
        MarketDataFetchService marketData = mock(MarketDataFetchService.class);
        MarketCalendar calendar = new MarketCalendar(marketData);
        assertThat(calendar.isTwTradingDayKnown(LocalDate.of(2026, 8, 15)))
                .contains(false);

        verify(marketData, never()).getTwHolidaysKnown(2026);
    }

    @Test
    void unknownAuthorityIsThrottledThenRecoversToKnownAndEligible() {
        MarketDataFetchService marketData = mock(MarketDataFetchService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T02:00:00Z"));
        MarketCalendar calendar = new MarketCalendar(marketData, clock);
        LocalDate date = LocalDate.of(2026, 8, 13);
        when(marketData.getTwHolidaysKnown(2026))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(Map.of("2026-01-01", "holiday")));

        assertThat(calendar.isTwTradingDayKnown(date)).isEmpty();
        assertThat(calendar.isTwTradingDayKnown(date)).isEmpty();
        verify(marketData).getTwHolidaysKnown(2026);

        clock.set(Instant.parse("2026-08-13T02:00:30Z"));
        assertThat(calendar.isTwTradingDayKnown(date)).contains(true);
        assertThat(new BankFxTradingSessionPolicy(calendar, clock).eligibleSources())
                .containsExactly(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK);
    }

    @Test
    void annualCalendarUnavailableFailsClosedAndKnownCalendarDistinguishesOpenFromClosed() {
        MarketDataFetchService marketData = mock(MarketDataFetchService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T02:00:00Z"));
        MarketCalendar calendar = new MarketCalendar(marketData, clock);
        when(marketData.getTwHolidaysKnown(2026)).thenReturn(Optional.empty());
        assertThat(calendar.isTwTradingDayKnown(LocalDate.of(2026, 8, 13))).isEmpty();

        clock.set(Instant.parse("2026-08-13T02:00:30Z"));
        when(marketData.getTwHolidaysKnown(2026)).thenReturn(
                Optional.of(Map.of("2026-08-13", "operator closure")));
        assertThat(calendar.isTwTradingDayKnown(LocalDate.of(2026, 8, 13))).contains(false);
        assertThat(calendar.isTwTradingDayKnown(LocalDate.of(2026, 8, 14))).contains(true);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Taipei");
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
