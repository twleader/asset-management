package com.steven.assets.externalmaterials.service;

import java.time.Instant;
import java.time.LocalDate;

/** Fixed calendar-year policy, shared by minute readers, writers and cleanup. */
public final class FubonMinuteRetentionFloor {
    private FubonMinuteRetentionFloor() {}
    public static LocalDate at(Instant executionTime) {
        return executionTime.atZone(MarketClock.TW_ZONE).toLocalDate().minusYears(1);
    }
    public static LocalDate today() { return at(Instant.now()); }
}
