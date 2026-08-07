package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Task 292 基本面公式守門：這些錯誤都會靜默產生「很合理」的假分數。 */
class FundamentalAnalysisServiceTest {

    @Test
    void cumulativeFinancialsMustBeConvertedToStandaloneQuarter() {
        assertEquals(new BigDecimal("1.20"),
                FundamentalAnalysisService.standaloneValue(1, new BigDecimal("1.20"), null));
        assertEquals(new BigDecimal("0.80"),
                FundamentalAnalysisService.standaloneValue(2, new BigDecimal("2.00"), new BigDecimal("1.20")));
        assertNull(FundamentalAnalysisService.standaloneValue(3, new BigDecimal("3.00"), null),
                "Q2 缺漏時不得把 Q3 年度累計值當單季");
    }

    @Test
    void epsYoyUsesTwoSetsOfFourStandaloneQuartersAndRejectsNonPositiveBase() {
        assertEquals(new BigDecimal("100.0000"), FundamentalAnalysisService.epsYoyPct(List.of(
                bd(2), bd(2), bd(2), bd(2), bd(1), bd(1), bd(1), bd(1))));
        assertNull(FundamentalAnalysisService.epsYoyPct(List.of(
                bd(1), bd(1), bd(1), bd(1), bd(0), bd(0), bd(0), bd(0))));
    }

    @Test
    void approximateRoeAndThreeMonthRevenuePreserveNullSemantics() {
        assertEquals(new BigDecimal("10.0000"), FundamentalAnalysisService.approximateRoePct(
                List.of(bd(25), bd(25), bd(25), bd(25)), bd(1000)));
        assertNull(FundamentalAnalysisService.approximateRoePct(
                java.util.Arrays.asList(bd(25), null, bd(25), bd(25)), bd(1000)));
        assertEquals(new BigDecimal("10.0000"), FundamentalAnalysisService.threeMonthAverage(
                List.of(bd(5), bd(10), bd(15))));
        assertNull(FundamentalAnalysisService.threeMonthAverage(
                java.util.Arrays.asList(bd(5), null, bd(15))));
    }

    @Test
    void asOfSelectionUsesLatestVisibleRevisionAndRejectsFutureKnowledge() {
        Instant jan10 = Instant.parse("2026-01-10T00:00:00Z");
        Instant feb10 = Instant.parse("2026-02-10T00:00:00Z");
        List<TestRow> rows = List.of(
                new TestRow("original", List.of(), jan10, jan10),
                new TestRow("revision", List.of(), jan10, feb10),
                new TestRow("future-source", List.of(), feb10, jan10));

        var january = FundamentalAnalysisService.latestAsOf(
                rows, Instant.parse("2026-01-20T00:00:00Z"), ignored -> "EXCHANGE|2025Q4");
        var february = FundamentalAnalysisService.latestAsOf(
                rows, Instant.parse("2026-02-20T00:00:00Z"), ignored -> "EXCHANGE|2025Q4");

        assertEquals(List.of("original"), january.stream().map(TestRow::value).toList());
        assertEquals(List.of("revision"), february.stream().map(TestRow::value).toList());
    }

    @Test
    void providerPriorityUsesFirstCompleteProviderFamilyWithoutCrossProviderMixing() {
        record ProviderRow(String provider, String value) {}
        List<ProviderRow> rows = List.of(
                new ProviderRow("FINMIND", "finmind"),
                new ProviderRow("EXCHANGE", "official"),
                new ProviderRow("YAHOO", "yahoo"));

        String selected = FundamentalAnalysisService.firstProviderValue(
                rows, ProviderRow::provider,
                sameProvider -> sameProvider.get(0).value());

        assertEquals("official", selected);
    }

    @Test
    void staleHigherPriorityProviderFallsThroughToFreshLowerPriorityProvider() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 15);

        List<FundamentalAnalysisService.FinancialRow> financials = new ArrayList<>();
        financials.addAll(financialRows("EXCHANGE", 2026, 1));
        financials.addAll(financialRows("FINMIND", 2026, 2));
        FundamentalAnalysisService.Factor eps = FundamentalAnalysisService.firstProviderValue(
                financials, FundamentalAnalysisService.FinancialRow::provider,
                rows -> service.epsFactor(rows.stream()
                        .sorted(FundamentalAnalysisService.FinancialRow.DESC).toList(), decision));

        List<FundamentalAnalysisService.RevenueRow> revenues = List.of(
                revenueRow("EXCHANGE", 2026, 6), revenueRow("EXCHANGE", 2026, 5),
                revenueRow("EXCHANGE", 2026, 4), revenueRow("FINMIND", 2026, 7),
                revenueRow("FINMIND", 2026, 6), revenueRow("FINMIND", 2026, 5));
        FundamentalAnalysisService.Factor revenue = FundamentalAnalysisService.firstProviderValue(
                revenues, FundamentalAnalysisService.RevenueRow::provider,
                rows -> service.revenueFactor(rows.stream()
                        .sorted(FundamentalAnalysisService.RevenueRow.DESC).toList(), decision));

        List<FundamentalAnalysisService.ValuationRow> valuations = new ArrayList<>();
        valuations.addAll(valuationRows("EXCHANGE", LocalDate.of(2026, 8, 1)));
        valuations.addAll(valuationRows("FINMIND", LocalDate.of(2026, 8, 14)));
        FundamentalAnalysisService.Factor pe = service.peFactor(valuations, decision);

        assertEquals("FINMIND", eps.provider());
        assertEquals("FINMIND", revenue.provider());
        assertEquals("FINMIND", pe.provider());
    }

    private record TestRow(
            String value,
            List<String> urls,
            Instant availableAt,
            Instant observedAt) implements FundamentalAnalysisService.SourcedRow {}

    private static List<FundamentalAnalysisService.FinancialRow> financialRows(
            String provider, int latestYear, int latestQuarter) {
        List<FundamentalAnalysisService.FinancialRow> rows = new ArrayList<>();
        int latest = latestYear * 4 + latestQuarter;
        for (int i = 0; i < 9; i++) {
            int period = latest - i;
            int year = Math.floorDiv(period - 1, 4);
            int quarter = Math.floorMod(period - 1, 4) + 1;
            rows.add(new FundamentalAnalysisService.FinancialRow(
                    year, quarter, BigDecimal.valueOf(quarter * 2L),
                    BigDecimal.valueOf(quarter * 100L), BigDecimal.valueOf(10_000),
                    provider, List.of("https://example.test/financial"), Instant.EPOCH, Instant.EPOCH));
        }
        return rows;
    }

    private static FundamentalAnalysisService.RevenueRow revenueRow(
            String provider, int year, int month) {
        return new FundamentalAnalysisService.RevenueRow(
                year, month, "半導體業", BigDecimal.TEN, provider,
                List.of("https://example.test/revenue"), Instant.EPOCH, Instant.EPOCH);
    }

    private static List<FundamentalAnalysisService.ValuationRow> valuationRows(
            String provider, LocalDate latest) {
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    latest.minusDays(i), BigDecimal.valueOf(15 + i / 100.0), false, provider,
                    List.of("https://example.test/valuation"), Instant.EPOCH, Instant.EPOCH));
        }
        return rows;
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
