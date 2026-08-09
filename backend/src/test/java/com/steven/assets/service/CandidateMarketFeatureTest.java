package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateMarketFeatureTest {
    @Test
    void availableAtIsConservativeAndDoesNotPromoteDisclosureOnly() {
        Instant available = Instant.parse("2026-08-08T08:00:00Z");
        CandidateMarketFeature feature = new CandidateMarketFeature(
                "SPX_RET5", new BigDecimal("1.2"), LocalDate.of(2026, 8, 7), available,
                "CLOSE_18_ET", "INDEX", "https://source", "EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        assertTrue(feature.availableAt(Instant.parse("2026-08-08T09:00:00Z")));
        assertFalse(feature.availableAt(Instant.parse("2026-08-08T07:00:00Z")));

        CandidateMarketFeature disclosure = new CandidateMarketFeature(
                "IXIC_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), available,
                "CLOSE_18_ET", "INDEX", null, "EQUITY", "MARKET_REGIME",
                CandidateMarketFeature.DISCLOSURE_ONLY, null);
        assertFalse(disclosure.availableAt(Instant.parse("2026-08-08T09:00:00Z")));
    }
}
