package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonMarketCalendarPeekTest {
    @Test void coldOrUnknownClosurePeekDoesNoAuthorityOrDbLoad() {
        var closures = mock(TwTyphoonClosureService.class);
        var dgpa = mock(DgpaCalendarAuthority.class);
        var store = mock(StockSourceQuery.class);
        var service = new MarketDataFetchService(store, closures, "", dgpa);
        assertThat(service.peekTwHolidaysKnown(2026)).isEmpty();
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        assertThat(service.peekTwHolidaysKnown(2026)).isEmpty();
        verify(closures, never()).loadFromDb();
        verifyNoInteractions(store, dgpa);
    }
    @Test void warmPeekSharesAuthorityAndExpiredProvisionalDoesNotRefreshOrFallback() {
        var instant = new AtomicReference<>(Instant.parse("2026-08-28T05:40:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return instant.get(); }
        };
        var closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        when(closures.closuresForYear(2026)).thenReturn(Map.of("2026-08-28", "closure"));
        var dgpa = mock(DgpaCalendarAuthority.class);
        when(dgpa.fetchHolidays(2026)).thenReturn(Optional.of(Map.of("2026-01-01", "holiday")));
        var service = new MarketDataFetchService(mock(StockSourceQuery.class), closures, "", dgpa, clock) {
            @Override Map<String, String> fetchTwHolidaysFromTwse(int year) { return Map.of(); }
        };
        service.getTwHolidaysKnown(2026);
        clearInvocations(dgpa, closures);
        var calendar = new MarketCalendar(service, clock);
        assertThat(calendar.peekTwTradingDayKnown(LocalDate.of(2026, 8, 28))).contains(false);
        assertThat(calendar.peekTwTradingDayKnown(LocalDate.of(2026, 8, 27))).contains(true);
        instant.set(instant.get().plus(Duration.ofHours(7)));
        assertThat(calendar.peekTwTradingDayKnown(LocalDate.of(2026, 8, 27))).isEmpty();
        verifyNoInteractions(dgpa);
        verify(closures, never()).loadFromDb();
    }
}
