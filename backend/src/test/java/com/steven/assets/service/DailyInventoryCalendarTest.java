package com.steven.assets.service;

import com.steven.assets.service.srpp.us.UsReportCalendar;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyInventoryCalendarTest {
    MarketDataService data = mock(MarketDataService.class);
    DailyInventoryCalendarAdapter calendar = new DailyInventoryCalendarAdapter(data, new UsReportCalendar(data));
    @Test void taiwanBoundaryHolidayAndUnknownNeverBecomeWeekdayFallback() {
        var d = LocalDate.of(2026,11,6);
        when(data.isTradingDayKnown("台股",d)).thenReturn(Optional.of(true));
        assertThat(calendar.dueSession("台股",Instant.parse("2026-11-06T11:29:59Z"))).isEmpty();
        verifyNoInteractions(data);
        var session = calendar.dueSession("台股",Instant.parse("2026-11-06T11:30:00Z")).orElseThrow();
        assertThat(session.close()).isEqualTo(Instant.parse("2026-11-06T05:30:00Z"));
        assertThat(session.captureWindow(Instant.parse("2026-11-06T15:59:59Z"))).isTrue();
        assertThat(session.captureWindow(Instant.parse("2026-11-06T16:00:00Z"))).isFalse();
        when(data.isTradingDayKnown("台股",d)).thenReturn(Optional.of(false));
        assertThat(calendar.dueSession("台股",Instant.parse("2026-11-06T11:30:00Z"))).isEmpty();
        when(data.isTradingDayKnown("台股",d)).thenReturn(Optional.empty());
        assertThatThrownBy(()->calendar.dueSession("台股",Instant.parse("2026-11-06T11:30:00Z"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void usWinterSummerAndEarlyCloseUseNewYorkSessionAndDate() {
        assertDue("2026-11-05", "2026-11-06T03:00:00Z", "2026-11-05T21:00:00Z");
        assertDue("2026-07-06", "2026-07-07T02:00:00Z", "2026-07-06T20:00:00Z");
        assertDue("2026-11-27", "2026-11-28T00:00:00Z", "2026-11-27T18:00:00Z");
        when(data.isTradingDayCachedOnly("美股",LocalDate.of(2026,11,6))).thenReturn(Optional.of(false));
        assertThat(calendar.dueSession("美股",Instant.parse("2026-11-07T03:00:00Z"))).isEmpty();
        assertThatThrownBy(()->calendar.dueSession("美股",Instant.parse("2029-11-06T03:00:00Z"))).isInstanceOf(RuntimeException.class);
        when(data.isTradingDayCachedOnly("美股",LocalDate.of(2026,11,5))).thenReturn(Optional.empty());
        assertThatThrownBy(()->calendar.dueSession("美股",Instant.parse("2026-11-06T03:00:00Z"))).isInstanceOf(RuntimeException.class);
    }
    void assertDue(String day,String at,String close) {
        var date=LocalDate.parse(day);
        when(data.isTradingDayCachedOnly("美股",date)).thenReturn(Optional.of(true));
        assertThat(calendar.dueSession("美股",Instant.parse(at).minusSeconds(1))).isEmpty();
        var s=calendar.dueSession("美股",Instant.parse(at)).orElseThrow();
        assertThat(s.tradingDate()).isEqualTo(date);
        assertThat(s.close()).isEqualTo(Instant.parse(close));
    }
}
