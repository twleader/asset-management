package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntradaySessionResolverTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 18);

    @Test
    void exDividendReferenceReplacesRawCloseFor00881() {
        var session = resolve("00881", "55.15", "50.55", "49.48");
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE);
        assertThat(session.comparisonPrice()).isEqualByComparingTo("50.55");
        assertThat(session.sessionReferencePrice()).isEqualByComparingTo("50.55");
        assertThat(session.sessionReferenceDate()).isEqualTo(DAY);
        assertThat(session.sessionReferenceSource()).isEqualTo("TWSE_MIS_Y");
        assertThat(session.change()).isEqualByComparingTo("-1.07");
        assertThat(session.changePercent()).isEqualByComparingTo("-2.116716");
    }

    @Test
    void exDividendReferenceReplacesRawCloseFor2885() {
        var session = resolve("2885", "68.30", "65.70", "64.00");
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE);
        assertThat(session.change()).isEqualByComparingTo("-1.70");
        assertThat(session.changePercent()).isEqualByComparingTo("-2.587519");
    }

    @Test
    void taiwanIndividualWithoutExactReferenceFailsClosedButOtherMarketsUseRawClose() {
        var noReference = IntradaySessionResolver.resolve("2885", "台股", DAY,
                ticks("64"), null, null, null, new BigDecimal("68.30"), "STOCK_PRICE_HISTORY");
        assertThat(noReference.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.UNAVAILABLE);
        assertThat(noReference.comparisonPrice()).isNull();
        assertThat(noReference.change()).isNull();

        var us = IntradaySessionResolver.resolve("VOO", "美股", DAY,
                ticks("500"), null, null, null, new BigDecimal("490"), "STOCK_PRICE_HISTORY");
        assertThat(us.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE);
        assertThat(us.change()).isEqualByComparingTo("10");
        assertThat(us.sessionReferencePrice()).isNull();
        assertThat(us.sessionReferenceDate()).isNull();
        assertThat(us.sessionReferenceSource()).isNull();
    }

    @Test
    void fubonPreviousCloseAndYahooDoNotQualifyAsSameSessionMisDY() {
        for (String unqualifiedSource : List.of("FUBON_PREVIOUS_CLOSE", "YAHOO")) {
            var session = IntradaySessionResolver.resolve("0050", "台股", DAY, ticks("101"),
                    new BigDecimal("100"), DAY, unqualifiedSource,
                    new BigDecimal("100"), "STOCK_PRICE_HISTORY");
            assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.UNAVAILABLE);
            assertThat(session.comparisonPrice()).isNull();
            assertThat(session.change()).isNull();
            assertThat(session.sessionReferencePrice()).isNull();
            assertThat(session.sessionReferenceDate()).isNull();
            assertThat(session.sessionReferenceSource()).isNull();
        }
    }

    @Test
    void normalReferenceMissingRawAndIndexCasesKeepTheirExplicitKinds() {
        var normal = resolve("0050", "100", "100", "101");
        assertThat(normal.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE);

        var missingRaw = IntradaySessionResolver.resolve("0050", "台股", DAY, ticks("101"),
                new BigDecimal("100"), DAY, "TWSE_MIS_Y", null, "STOCK_PRICE_HISTORY");
        assertThat(missingRaw.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.SESSION_REFERENCE);

        var mismatch = IntradaySessionResolver.resolve("0050", "台股", DAY, ticks("101"),
                new BigDecimal("100"), DAY.plusDays(1), "TWSE_MIS_Y",
                new BigDecimal("100"), "STOCK_PRICE_HISTORY");
        assertThat(mismatch.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.UNAVAILABLE);

        var index = IntradaySessionResolver.resolve("0000", "台股", DAY, ticks("20000"),
                null, null, null, new BigDecimal("19900"), "TWSE_INDEX_DAILY_HISTORY");
        assertThat(index.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE);
        assertThat(index.comparisonSource()).isEqualTo("TWSE_INDEX_DAILY_HISTORY");
    }

    @Test
    void emptyTicksAndZeroReferenceNeverDivide() {
        var empty = IntradaySessionResolver.resolve("0050", "台股", DAY, List.of(),
                BigDecimal.ZERO, DAY, "TWSE_MIS_Y", new BigDecimal("100"), "STOCK_PRICE_HISTORY");
        assertThat(empty.lastPrice()).isNull();
        assertThat(empty.change()).isNull();
        assertThat(empty.changePercent()).isNull();
    }

    private static HistoricalDataService.IntradaySession resolve(String code, String raw, String reference, String last) {
        return IntradaySessionResolver.resolve(code, "台股", DAY, ticks(last), new BigDecimal(reference), DAY,
                "TWSE_MIS_Y", new BigDecimal(raw), "STOCK_PRICE_HISTORY");
    }

    private static List<HistoricalDataService.IntradayTick> ticks(String last) {
        return List.of(new HistoricalDataService.IntradayTick(DAY + "T13:30:00", new BigDecimal(last)));
    }
}
