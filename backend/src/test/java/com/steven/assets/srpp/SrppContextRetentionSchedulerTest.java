package com.steven.assets.srpp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Requirement 163／Task 452.10：保留期（預設 7，小於 7 視為 7）。 */
class SrppContextRetentionSchedulerTest {
    private final SrppContextPackageRepository packages = mock(SrppContextPackageRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T19:25:00Z"), ZoneId.of("Asia/Taipei")); // 台北 9/25 03:25

    @Test
    void deletesPackagesOlderThanRetentionWindow() {
        new SrppContextRetentionScheduler(packages, 7, clock).purgeExpired();
        verify(packages).deleteTradingDateBefore(LocalDate.of(2026, 9, 18));
        verifyNoMoreInteractions(packages);
    }

    @Test
    void retentionBelowSevenIsClampedAndLongerIsHonoured() {
        assertThat(new SrppContextRetentionScheduler(packages, 1, clock).retentionDays()).isEqualTo(7);
        new SrppContextRetentionScheduler(packages, 30, clock).purgeExpired();
        verify(packages).deleteTradingDateBefore(LocalDate.of(2026, 8, 26));
    }
}
