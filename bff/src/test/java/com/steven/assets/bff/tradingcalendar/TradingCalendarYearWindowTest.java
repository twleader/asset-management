package com.steven.assets.bff.tradingcalendar;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TradingCalendarYearWindowTest {
    @Test
    void fixedClockAlwaysExposesOnlyTaipeiCurrentYearWindow() {
        TradingCalendarYearWindow window = new TradingCalendarYearWindow(
                Clock.fixed(Instant.parse("2026-12-31T17:00:00Z"), ZoneOffset.UTC)); // 台北已是 2027-01-01

        assertThat(window.currentYear()).isEqualTo(2027);
        assertThat(window.availableYears()).containsExactly(2026, 2027, 2028);
        assertThat(window.accepts(2025)).isFalse();
        assertThat(window.accepts(2028)).isTrue();
        assertThat(window.accepts(2029)).isFalse();
    }
}
