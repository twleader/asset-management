package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for the NYSE calendar used by historical session alignment. */
class MarketDataServiceUsCalendarTest {

    private final MarketDataService service = new MarketDataService("http://unused", null, null);

    @Test
    void juneteenthOnlyClosesNyseFrom2022() {
        Map<String, String> preJuneteenth = service.getUsHolidays(2021);
        Map<String, String> firstJuneteenth = service.getUsHolidays(2022);

        assertThat(preJuneteenth).doesNotContainKey("2021-06-18");
        assertThat(preJuneteenth).doesNotContainKey("2021-06-21");
        assertThat(service.isUsTradingDay(LocalDate.of(2021, 6, 18))).isTrue();
        assertThat(service.isUsTradingDay(LocalDate.of(2021, 6, 21))).isTrue();
        assertThat(firstJuneteenth).containsEntry("2022-06-20", "Juneteenth");
        assertThat(service.isUsTradingDay(LocalDate.of(2022, 6, 20))).isFalse();
    }

    @Test
    void includesKnownExceptionalNyseClosures() {
        assertThat(service.getUsHolidays(2018))
                .containsEntry("2018-12-05", "NYSE closure - President George H.W. Bush funeral");
        assertThat(service.isUsTradingDay(LocalDate.of(2018, 12, 5))).isFalse();
    }

    @Test
    void doesNotObserveSaturdayNewYearOnFriday() {
        assertThat(service.isUsTradingDay(LocalDate.of(2021, 12, 31))).isTrue();
        assertThat(service.isUsTradingDay(LocalDate.of(2027, 12, 31))).isTrue();
        assertThat(service.isUsTradingDay(LocalDate.of(2023, 1, 2))).isFalse();
    }
}
