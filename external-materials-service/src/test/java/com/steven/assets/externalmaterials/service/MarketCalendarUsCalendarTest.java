package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
/** Keep the external-materials market gate in sync with business-services. */
class MarketCalendarUsCalendarTest {

    private final MarketCalendar calendar = new MarketCalendar(null);

    @Test
    void juneteenthOnlyClosesNyseFrom2022() {
        assertThat(calendar.isUsTradingDay(LocalDate.of(2021, 6, 18))).isTrue();
        assertThat(calendar.isUsTradingDay(LocalDate.of(2021, 6, 21))).isTrue();
        assertThat(calendar.isUsTradingDay(LocalDate.of(2022, 6, 20))).isFalse();
    }

    @Test
    void includesKnownExceptionalNyseClosure() {
        assertThat(calendar.isUsTradingDay(LocalDate.of(2018, 12, 5))).isFalse();
    }

    @Test
    void doesNotObserveSaturdayNewYearOnFriday() {
        assertThat(calendar.isUsTradingDay(LocalDate.of(2021, 12, 31))).isTrue();
        assertThat(calendar.isUsTradingDay(LocalDate.of(2027, 12, 31))).isTrue();
        assertThat(calendar.isUsTradingDay(LocalDate.of(2023, 1, 2))).isFalse();
    }
}
