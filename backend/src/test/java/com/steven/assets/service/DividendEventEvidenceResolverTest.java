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

    /**
     * Task 357／357.3d-1b：這是交易雷達「下一配息」證據本身。純配股事件的
     * exDividendDate 為 null，改用 anchorDate（{@code min(exDividendDate,
     * exRightsDate)}，SQL 側為 {@code LEAST}）後才能通過 resolve() 的區間過濾與排序，否則這類事件永遠不會
     * 成為「下一配息」（Requirement 94 的頭號承諾）。
     */
    @Test
    void pureStockEventWithNullExDividendDateStillBecomesTheNextEvent() {
        LocalDate exRights = DECISION_DATE.plusDays(5);
        var pureStock = new DividendEventEvidenceResolver.Event(
                null, BigDecimal.ZERO, new BigDecimal("0.30"), null, null,
                Instant.parse("2026-08-06T00:00:00Z"), "FINMIND", List.of(), exRights);
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "FINMIND", DECISION_DATE, DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, true, List.of(pureStock));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION, sessions20());

        assertEquals(DividendEventEvidenceResolver.Status.AVAILABLE, result.status());
        assertEquals(exRights, result.nextEvent().anchorDate());
        assertNull(result.nextEvent().exDividendDate());
        assertEquals(exRights, result.nextEvent().exRightsDate());
        assertEquals(1, result.eventsWithinTwentySessions());
    }

    /**
     * 357.5a：解析「下一次尚未發生的配息事件」時，必須是同一次事件的四個日期一併輸出，
     * 不得四個日期各自往後找最近的一個。構造一筆「除息在前、除權在後、發放更後」的事件，
     * 斷言四個值同屬該筆。
     */
    @Test
    void nextEventCarriesAllFourDatesFromTheSameRealEvent() {
        LocalDate exDividend = DECISION_DATE.plusDays(3);
        LocalDate exRights = DECISION_DATE.plusDays(5);
        LocalDate cashPay = DECISION_DATE.plusDays(30);
        LocalDate stockPay = DECISION_DATE.plusDays(40);
        var event = new DividendEventEvidenceResolver.Event(
                exDividend, new BigDecimal("2.00"), new BigDecimal("1.00"), cashPay, stockPay,
                Instant.parse("2026-08-06T00:00:00Z"), "FINMIND", List.of(), exRights);
        var observation = new DividendEventEvidenceResolver.SnapshotObservation(
                "FINMIND", DECISION_DATE, DECISION_DATE.plusDays(45),
                Instant.parse("2026-08-06T00:00:00Z"), null,
                DividendEventEvidenceResolver.Status.AVAILABLE, true, List.of(event));

        var result = DividendEventEvidenceResolver.resolve(
                List.of(observation), DECISION_DATE, DECISION, sessions20());

        assertEquals(exDividend, result.nextEvent().exDividendDate());
        assertEquals(exRights, result.nextEvent().exRightsDate());
        assertEquals(cashPay, result.nextEvent().cashPaymentDate());
        assertEquals(stockPay, result.nextEvent().stockPaymentDate());
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
