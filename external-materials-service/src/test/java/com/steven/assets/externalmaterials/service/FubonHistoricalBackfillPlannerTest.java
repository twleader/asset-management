package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FubonHistoricalBackfillPlannerTest {
    @Test void dailyWindowsContainAtMost365DaysAndMinuteWindowsAtMost31() {
        LocalDate from = LocalDate.of(2016, 9, 30), to = LocalDate.of(2026, 9, 29);
        LocalDate floor = LocalDate.of(2025, 9, 30);
        List<FubonHistoricalBackfillPlanner.Window> windows = FubonHistoricalBackfillPlanner.windows(List.of("2330"), from, to, floor);
        var daily = windows.stream().filter(w -> w.dataset().equals("DAILY_CANDLE")).toList();
        assertThat(daily.getFirst().from()).isEqualTo(from);
        assertThat(daily.getFirst().to()).isEqualTo(from.plusDays(364));
        assertThat(daily.get(1).from()).isEqualTo(daily.getFirst().to().plusDays(1));
        assertThat(daily.getLast().to()).isEqualTo(to);
        assertThat(daily).allSatisfy(w -> assertThat(w.to().toEpochDay() - w.from().toEpochDay()).isLessThanOrEqualTo(364));
        var minute = windows.stream().filter(w -> w.dataset().equals("INTRADAY_CANDLE_1M")).toList();
        assertThat(minute.getFirst().from()).isEqualTo(floor);
        assertThat(minute).allSatisfy(w -> assertThat(w.to().toEpochDay() - w.from().toEpochDay()).isLessThanOrEqualTo(30));
        assertThat(minute.getFirst().to()).isEqualTo(floor.plusDays(30));
        assertThat(minute.get(1).from()).isEqualTo(minute.getFirst().to().plusDays(1));
        assertThat(minute.getLast().to()).isEqualTo(to);
    }

    @Test void symbolsRemainInInputOrderAndRangesMustNotExceedTenYears() {
        var windows = FubonHistoricalBackfillPlanner.windows(List.of("2317", "2330"), LocalDate.of(2023, 5, 22), LocalDate.of(2023, 6, 1), LocalDate.of(2023, 1, 1));
        assertThat(windows).extracting(FubonHistoricalBackfillPlanner.Window::symbol).containsSubsequence("2317", "2330");
        assertThat(windows).filteredOn(w -> w.dataset().equals("INTRADAY_CANDLE_1M"))
                .extracting(FubonHistoricalBackfillPlanner.Window::from).containsOnly(LocalDate.of(2023, 5, 23));
        assertThatThrownBy(() -> FubonHistoricalBackfillPlanner.windows(List.of("2330"), LocalDate.of(2010, 1, 1), LocalDate.of(2021, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void expiredResumeRangeKeepsDailyWindowsButPlansNoMinuteRefill() {
        var windows=FubonHistoricalBackfillPlanner.windows(List.of("2330"),LocalDate.of(2016,9,30),
                LocalDate.of(2025,10,8),LocalDate.of(2025,10,10));
        assertThat(windows).isNotEmpty().allSatisfy(w->assertThat(w.dataset()).isEqualTo("DAILY_CANDLE"));
    }
}
