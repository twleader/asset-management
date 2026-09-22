package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TransitProcessingDatePolicyTest {
    @Test
    void taipeiMidnightExpiresTodayWithoutWaitingForUtcMidnight() {
        Clock before = Clock.fixed(Instant.parse("2026-09-21T15:59:59Z"), ZoneOffset.UTC);
        Clock after = Clock.fixed(Instant.parse("2026-09-21T16:00:00Z"), ZoneOffset.UTC);
        LocalDate due = LocalDate.of(2026, 9, 22);
        assertThat(TransitProcessingDatePolicy.isDue("TRANSIT_TWD", due,
                TransitProcessingDatePolicy.today(before))).isFalse();
        assertThat(TransitProcessingDatePolicy.isDue("TRANSIT_TWD", due,
                TransitProcessingDatePolicy.today(after))).isTrue();
    }

    @Test
    void onlyDatedTransitAmountsAtOrBeforeTodayExpire() {
        LocalDate today = LocalDate.of(2026, 9, 22);
        for (String currency : new String[]{"TRANSIT_TWD", "TRANSIT_USD"}) {
            assertThat(TransitProcessingDatePolicy.isDue(currency, today.minusDays(1), today)).isTrue();
            assertThat(TransitProcessingDatePolicy.isDue(currency, today, today)).isTrue();
            assertThat(TransitProcessingDatePolicy.isDue(currency, today.plusDays(1), today)).isFalse();
            assertThat(TransitProcessingDatePolicy.isDue(currency, null, today)).isFalse();
        }
        assertThat(TransitProcessingDatePolicy.isDue("TWD", today.minusDays(1), today)).isFalse();
        assertThat(TransitProcessingDatePolicy.isDue("USD", today, today)).isFalse();
        assertThat(TransitProcessingDatePolicy.isDue(null, today, today)).isFalse();
    }
}
