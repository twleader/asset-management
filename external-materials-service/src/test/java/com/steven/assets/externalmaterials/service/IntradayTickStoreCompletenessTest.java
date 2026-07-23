package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntradayTickStoreCompletenessTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @Test
    void emptyTicksAreIncomplete() {
        assertThat(IntradayTickStore.isIncompleteForSession(List.of(), "美股", DATE)).isTrue();
    }

    @Test
    void onTimeTicksWithoutLargeGapAreComplete() {
        assertThat(IntradayTickStore.isIncompleteForSession(List.of(
                tick("09:30"), tick("09:35"), tick("09:50")), "美股", DATE)).isFalse();
    }

    @Test
    void lateFirstTickIsIncomplete() {
        assertThat(IntradayTickStore.isIncompleteForSession(
                List.of(tick("11:47"), tick("11:52")), "美股", DATE)).isTrue();
    }

    @Test
    void internalGapOverFifteenMinutesIsIncomplete() {
        assertThat(IntradayTickStore.isIncompleteForSession(
                List.of(tick("09:30"), tick("09:35"), tick("09:51")), "美股", DATE)).isTrue();
    }

    @Test
    void oneOnTimeTickIsNotIncompleteByCountAlone() {
        assertThat(IntradayTickStore.isIncompleteForSession(
                List.of(tick("09:35")), "美股", DATE)).isFalse();
    }

    private IntradayTickStore.TickPoint tick(String time) {
        return new IntradayTickStore.TickPoint(DATE + "T" + time, BigDecimal.ONE);
    }
}
