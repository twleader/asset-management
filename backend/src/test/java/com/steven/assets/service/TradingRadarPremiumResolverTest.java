package com.steven.assets.service;

import com.steven.assets.model.EtfNavHistory;
import com.steven.assets.model.EtfNavObservation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingRadarPremiumResolverTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 8);

    @Test
    void priorDbPremiumIsProvenanceOnlyAndCannotEnterTodaysFactor() {
        EtfNavHistory old = nav(LocalDate.of(2026, 8, 7), "4.5", "POSTGRES");

        TradingRadarPremiumResolver.Observation result = TradingRadarPremiumResolver.resolve(
                TARGET, null, null, List.of(old));

        assertNull(result.value());
        assertNull(result.asOfDate());
        assertTrue(result.stale());
    }

    @Test
    void exactDatedDbObservationIsAcceptedWhenRedisIsMissing() {
        EtfNavHistory exact = nav(TARGET, "1.25", "TWSE_NAV");

        TradingRadarPremiumResolver.Observation result = TradingRadarPremiumResolver.resolve(
                TARGET, null, exact, List.of());

        assertEquals(new BigDecimal("1.25"), result.value());
        assertEquals(TARGET, result.asOfDate());
        assertEquals("TWSE_NAV", result.source());
        assertFalse(result.stale());
    }

    @Test
    void futureOrMalformedRedisDateIsNotAccepted() {
        PriceQueryService.EtfNav live = new PriceQueryService.EtfNav(
                "2330", "台股", new BigDecimal("2"), new BigDecimal("2"),
                "20260809", "REDIS");

        TradingRadarPremiumResolver.Observation result = TradingRadarPremiumResolver.resolve(
                TARGET, live, null, List.of());

        assertNull(result.value());
        assertTrue(result.stale());
    }

    @Test
    void signalAt1800CannotSeeObservationFirstKnownAt1830ButLaterSignalCan() {
        Instant observedAt = Instant.parse("2026-08-08T10:30:00Z"); // 18:30 Taipei
        EtfNavObservation observation = observation(
                TARGET, "1.25", observedAt, observedAt, 1L);

        var early = TradingRadarPremiumResolver.resolveAsOf(
                "台股", TARGET, Instant.parse("2026-08-08T10:00:00Z"), List.of(observation));
        var later = TradingRadarPremiumResolver.resolveAsOf(
                "台股", TARGET, observedAt, List.of(observation));

        assertEquals(TradingRadarPremiumResolver.DecisionStatus.MISSING, early.status());
        assertNull(early.value());
        assertEquals(TradingRadarPremiumResolver.DecisionStatus.AVAILABLE, later.status());
        assertEquals(new BigDecimal("1.25"), later.value());
        assertEquals(observedAt, later.availableAt());
    }

    @Test
    void sameDateLaterRevisionDoesNotLeakAndHistoryUsesLatestRevisionKnownAsOf() {
        Instant first = Instant.parse("2026-08-08T10:30:00Z");
        Instant revision = Instant.parse("2026-08-08T11:30:00Z");
        EtfNavObservation original = observation(TARGET, "1.25", first, first, 1L);
        EtfNavObservation later = observation(TARGET, "9.99", revision, revision, 2L);

        var resolved = TradingRadarPremiumResolver.resolveAsOf(
                "台股", TARGET, Instant.parse("2026-08-08T11:00:00Z"),
                List.of(original, later));
        List<BigDecimal> history = TradingRadarPremiumResolver.premiumHistoryAsOf(
                "台股", TARGET, Instant.parse("2026-08-08T11:00:00Z"),
                List.of(original, later), 20);

        assertEquals(new BigDecimal("1.25"), resolved.value());
        assertEquals(List.of(new BigDecimal("1.25")), history);
    }

    @Test
    void usPremiumIsExplicitNotApplicableEvenWhenObservationExists() {
        Instant observed = Instant.parse("2026-08-08T22:30:00Z");
        var result = TradingRadarPremiumResolver.resolveAsOf(
                "美股", TARGET, observed.plusSeconds(1),
                List.of(observation(TARGET, "0.20", observed, observed, 1L)));

        assertEquals(TradingRadarPremiumResolver.DecisionStatus.NOT_APPLICABLE, result.status());
        assertNull(result.value());
    }

    private static EtfNavHistory nav(LocalDate date, String premium, String source) {
        return EtfNavHistory.builder()
                .stockCode("00695B").market("台股").navDate(date)
                .nav(new BigDecimal("100")).premiumDiscountPct(new BigDecimal(premium))
                .source(source).build();
    }

    private static EtfNavObservation observation(
            LocalDate date, String premium, Instant observedAt, Instant availableAt, long id) {
        return EtfNavObservation.builder()
                .id(id).stockCode("00695B").market("台股").navDate(date)
                .nav(new BigDecimal("100")).premiumDiscountPct(new BigDecimal(premium))
                .pctOrigin("OFFICIAL").source("TWSE")
                .observedAt(observedAt).availableAt(availableAt)
                .availabilityBasis("OBSERVED_AT_NO_PUBLISHED_TIMESTAMP").build();
    }
}
