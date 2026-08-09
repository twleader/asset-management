package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DividendEventEvidenceResolverTest {

    private static final LocalDate DECISION_DATE = LocalDate.of(2026, 8, 7);
    private static final Instant DECISION = Instant.parse("2026-08-07T08:00:00Z");

    @Test
    void sameDayEventIsNotFutureAndKnownAtCannotLeakFromAfterDecision() {
        var sameDay = new DividendEventEvidenceResolver.Event(
                DECISION_DATE, BigDecimal.ONE, BigDecimal.ZERO, null, null,
                Instant.parse("2026-08-06T00:00:00Z"), "TEST");
        var futureKnown = new DividendEventEvidenceResolver.Event(
                DECISION_DATE.plusDays(3), BigDecimal.ONE, BigDecimal.ZERO, null, null,
                Instant.parse("2026-08-08T00:00:00Z"), "TEST");
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TEST", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, List.of(sameDay, futureKnown));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION,
                sessions20());

        assertEquals(DividendEventEvidenceResolver.Status.EMPTY_COMPLETE, result.status());
        assertNull(result.nextEvent());
    }

    @Test
    void laterObservationIsNotUsableOrEvenReportedStaleForEarlierDecision() {
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TEST", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-08T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, List.of());
        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION,
                List.of(DECISION_DATE.plusDays(1)));
        assertEquals(DividendEventEvidenceResolver.Status.MISSING, result.status());
    }

    @Test
    void completeObservationAvailableAsOfButWithExpiredScopeIsStale() {
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TEST", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(20),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.EMPTY_COMPLETE, List.of());

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION,
                List.of(DECISION_DATE.plusDays(1)));

        assertEquals(DividendEventEvidenceResolver.Status.STALE, result.status());
        assertEquals("TEST", result.provider());
    }

    @Test
    void partialObservationAsOfIsReportedButCannotClaimFutureEvents() {
        var event = new DividendEventEvidenceResolver.Event(
                DECISION_DATE.plusDays(2), BigDecimal.ONE, BigDecimal.ZERO,
                null, null, Instant.parse("2026-08-06T00:00:00Z"), "HISTORICAL");
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "HISTORICAL", DECISION_DATE.minusYears(1), DECISION_DATE,
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.PARTIAL, List.of(event));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION,
                List.of(DECISION_DATE.plusDays(1), DECISION_DATE.plusDays(2)));

        assertEquals(DividendEventEvidenceResolver.Status.PARTIAL, result.status());
        assertNull(result.nextEvent());
        assertEquals(0, result.eventsWithinFiveSessions());
        assertEquals(0, result.eventsWithinTwentySessions());
    }

    @Test
    void futureEventUsesNextSessionsExcludingDecisionDate() {
        var event = new DividendEventEvidenceResolver.Event(
                DECISION_DATE.plusDays(2), BigDecimal.ONE, BigDecimal.ZERO, null, null,
                null, "TEST");
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TEST", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, List.of(event));
        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION,
                sessions20());
        assertEquals(DividendEventEvidenceResolver.Status.AVAILABLE, result.status());
        assertEquals(1, result.eventsWithinFiveSessions());
        assertEquals(1, result.eventsWithinTwentySessions());
        assertEquals(Instant.parse("2026-08-06T00:00:00Z"), result.knownAt());
    }

    @Test
    void availableEventCarriesSnapshotSourceUrlsThroughResolution() {
        List<String> urls = List.of("https://official.example/calendar", "https://official.example/calendar");
        var event = new DividendEventEvidenceResolver.Event(
                DECISION_DATE.plusDays(2), BigDecimal.ONE, BigDecimal.ZERO, null, null,
                null, "NASDAQ_DIVIDEND_CALENDAR", urls);
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "NASDAQ_DIVIDEND_CALENDAR", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, List.of(event));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION, sessions20());

        assertEquals(urls.subList(0, 1), result.sourceUrls());
        assertEquals(urls.subList(0, 1), result.nextEvent().sourceUrls());
    }

    @Test
    void officialCalendarWinsOverNewerFallbackObservation() {
        var official = new DividendEventEvidenceResolver.SnapshotObservation(
                "NASDAQ_DIVIDEND_CALENDAR", DECISION_DATE.minusDays(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE,
                List.of(new DividendEventEvidenceResolver.Event(
                        DECISION_DATE.plusDays(2), BigDecimal.ONE, BigDecimal.ZERO,
                        null, null, null, "NASDAQ_DIVIDEND_CALENDAR")));
        var newerFallback = new DividendEventEvidenceResolver.SnapshotObservation(
                "NASDAQ+Yahoo Finance", DECISION_DATE.minusDays(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-07T07:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE,
                List.of(new DividendEventEvidenceResolver.Event(
                        DECISION_DATE.plusDays(1), new BigDecimal("9.99"), BigDecimal.ZERO,
                        null, null, null, "NASDAQ+Yahoo Finance")));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(newerFallback, official), DECISION_DATE, DECISION, sessions20());

        assertEquals(DividendEventEvidenceResolver.Status.AVAILABLE, result.status());
        assertEquals("NASDAQ_DIVIDEND_CALENDAR", result.provider());
        assertEquals(BigDecimal.ONE, result.nextEvent().cashDividend());
    }

    @Test
    void finMindFallbackWinsOnlyWhenOfficialIsNotComplete() {
        var officialPartial = new DividendEventEvidenceResolver.SnapshotObservation(
                "TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST", DECISION_DATE, DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.PARTIAL, false, List.of());
        var fallback = new DividendEventEvidenceResolver.SnapshotObservation(
                "FinMind[TaiwanStockDividend+TaiwanStockDividendResult]",
                DECISION_DATE, DECISION_DATE.plusDays(45), Instant.parse("2026-08-07T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE,
                List.of(new DividendEventEvidenceResolver.Event(
                        DECISION_DATE.plusDays(3), BigDecimal.ONE, BigDecimal.ZERO,
                        null, null, null, "FinMind")));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(officialPartial, fallback), DECISION_DATE, DECISION, sessions20());

        assertEquals(DividendEventEvidenceResolver.Status.AVAILABLE, result.status());
        assertEquals("FinMind[TaiwanStockDividend+TaiwanStockDividendResult]", result.provider());
    }

    @Test
    void twentiethMarketSessionIsCountedWithoutCalendarFallback() {
        List<LocalDate> sessions = sessions20();
        LocalDate twentieth = sessions.get(19);
        var event = new DividendEventEvidenceResolver.Event(
                twentieth, BigDecimal.ONE, BigDecimal.ZERO, null, null,
                DECISION.minusSeconds(3600), "TEST");
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TEST", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, List.of(event));
        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION, sessions);
        assertEquals(DividendEventEvidenceResolver.Status.AVAILABLE, result.status());
        assertEquals(1, result.eventsWithinTwentySessions());
    }

    @Test
    void insufficientMarketSessionsIsPartialAndNeverCalendarDayWindow() {
        var event = new DividendEventEvidenceResolver.Event(
                DECISION_DATE.plusDays(2), BigDecimal.ONE, BigDecimal.ZERO, null, null,
                DECISION.minusSeconds(3600), "TEST");
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TEST", DECISION_DATE.minusYears(1), DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, List.of(event));
        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION,
                List.of(DECISION_DATE.plusDays(1), DECISION_DATE.plusDays(2)));
        assertEquals(DividendEventEvidenceResolver.Status.PARTIAL, result.status());
        assertEquals(0, result.eventsWithinFiveSessions());
        assertEquals(0, result.eventsWithinTwentySessions());
    }

    @Test
    void completeStatusCannotOverrideFalseCompletenessFlag() {
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "BROKEN", DECISION_DATE, DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, false, List.of());

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION, sessions20());

        assertEquals(DividendEventEvidenceResolver.Status.MISSING, result.status());
    }

    @Test
    void knownFutureDateWithUnknownAmountIsPartialDisclosureAndNeverRiskEvent() {
        var pending = new DividendEventEvidenceResolver.Event(
                DECISION_DATE.plusDays(2), null, BigDecimal.ZERO, null, null,
                Instant.parse("2026-08-06T00:00:00Z"), "TWSE");
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "TWSE", DECISION_DATE, DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, true, List.of(pending));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION, sessions20());

        assertEquals(DividendEventEvidenceResolver.Status.PARTIAL, result.status());
        assertEquals(DECISION_DATE.plusDays(2), result.nextEvent().exDividendDate());
        assertEquals(0, result.eventsWithinFiveSessions());
        assertEquals(0, result.eventsWithinTwentySessions());
    }

    private static List<LocalDate> sessions20() {
        List<LocalDate> out = new java.util.ArrayList<>();
        LocalDate date = DECISION_DATE.plusDays(1);
        while (out.size() < 20) {
            if (date.getDayOfWeek().getValue() <= 5) out.add(date);
            date = date.plusDays(1);
        }
        return out;
    }
}
