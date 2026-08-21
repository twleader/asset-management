package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketClockTwKnownTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    @Test
    void onlyKnownTradingDayInsideHalfOpenSessionAuthorizesLive() {
        MarketCalendar calendar = mock(MarketCalendar.class);
        when(calendar.isTwTradingDayKnown(DATE)).thenReturn(Optional.of(true));

        assertThat(clockAt("2026-08-21T01:00:00Z", calendar).isTwMarketOpenKnown())
                .contains(true); // 09:00 Taipei
        assertThat(clockAt("2026-08-21T05:29:59Z", calendar).isTwMarketOpenKnown())
                .contains(true);
        assertThat(clockAt("2026-08-21T05:30:00Z", calendar).isTwMarketOpenKnown())
                .contains(false);
        assertThat(clockAt("2026-08-21T00:59:59Z", calendar).isTwMarketOpenKnown())
                .contains(false);
    }

    @Test
    void authorityFalseEmptyAndThrowStayDistinctAndFailClosed() {
        MarketCalendar closed = mock(MarketCalendar.class);
        when(closed.isTwTradingDayKnown(DATE)).thenReturn(Optional.of(false));
        assertThat(clockAt("2026-08-21T02:00:00Z", closed).isTwMarketOpenKnown()).contains(false);

        MarketCalendar unknown = mock(MarketCalendar.class);
        when(unknown.isTwTradingDayKnown(DATE)).thenReturn(Optional.empty());
        assertThat(clockAt("2026-08-21T02:00:00Z", unknown).isTwMarketOpenKnown()).isEmpty();

        MarketCalendar throwing = mock(MarketCalendar.class);
        when(throwing.isTwTradingDayKnown(DATE)).thenThrow(new IllegalStateException("authority unavailable"));
        assertThat(clockAt("2026-08-21T02:00:00Z", throwing).isTwMarketOpenKnown()).isEmpty();
    }

    @Test
    void legacyFailOpenBooleanCannotAuthorizeKnownGate() {
        MarketCalendar calendar = mock(MarketCalendar.class);
        when(calendar.isTwTradingDay(DATE)).thenReturn(true);
        when(calendar.isTwTradingDayKnown(DATE)).thenReturn(Optional.empty());
        MarketClock clock = clockAt("2026-08-21T02:00:00Z", calendar);

        assertThat(clock.isTwMarketOpen()).isTrue();
        assertThat(clock.isTwMarketOpenKnown()).isEmpty();
    }

    private static MarketClock clockAt(String instant, MarketCalendar calendar) {
        return new MarketClock(calendar, Clock.fixed(Instant.parse(instant), MarketClock.TW_ZONE));
    }
}
