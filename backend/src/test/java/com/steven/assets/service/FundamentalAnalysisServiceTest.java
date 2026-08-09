package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 292 基本面公式守門：這些錯誤都會靜默產生「很合理」的假分數。Task 293 新增美股市場閘門守門。 */
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

    // ── Task 293：美股市場閘門守門 ──────────────────────────────────────────

    /** 測試 (d)：market="英股" 或任意非白名單字串維持既有「不適用」行為，不得被誤放行。 */
    @Test
    void resolveRejectsNonWhitelistedMarketLikeUkStock() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);

        var resolved = service.resolve("VOD", "Vodafone", "英股", Instant.parse("2026-08-08T02:00:00Z"));

        assertFalse(resolved.input().applicable());
        assertFalse(resolved.snapshot().applicable());
    }

    /**
     * 測試 (e)＋(i)：美股個股 coverage 上限為 3（EPS／ROE／PE，無月營收／產業因子），且
     * PROVIDERS 清單確實含 SEC_EDGAR、firstProviderValue() 能選中 provider=SEC_EDGAR 的列
     * 組出非 null 的 EPS／ROE。revenueContribution／industryContribution 恆為 null，
     * 不得為了湊滿 coverage=4 虛構假的營收因子。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void usMarketCoverageCapsAtThreeAndNeverFabricatesRevenueOrIndustry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Instant decisionInstant = Instant.parse("2026-08-08T02:00:00Z");

        List<FundamentalAnalysisService.FinancialRow> financials = financialRows("SEC_EDGAR", 2026, 2);
        List<FundamentalAnalysisService.ValuationRow> valuations = valuationRows("YAHOO", LocalDate.of(2026, 8, 8));

        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("stock_financial_quarter")),
                any(RowMapper.class), any(Object[].class))).thenReturn(financials);
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("stock_valuation_daily")),
                any(RowMapper.class), any(Object[].class))).thenReturn(valuations);
        // 月營收／產業彙總刻意不 stub：Mockito 對 List 回傳型別的預設值即為空 list，
        // 藉此同時斷言「美股輪次未曾產生任何營收列」不需要額外程式碼特例。

        var service = new FundamentalAnalysisService(jdbc, new ObjectMapper(), mock(PublicInfoEvidenceResolver.class));

        var resolved = service.resolve("AAPL", "Apple", "美股", decisionInstant);

        assertTrue(resolved.input().applicable());
        assertNull(resolved.input().revenueContribution());
        assertNull(resolved.input().industryContribution());
        assertEquals(3, resolved.snapshot().coverage());
        assertNull(resolved.snapshot().revenueYoy3mPct());
        assertNull(resolved.snapshot().industryRevenueYoyPct());
        assertEquals("SEC_EDGAR", resolved.snapshot().epsProvider());
    }

    /** 測試 (k)：resolveInputsForBacktest() 對 market="美股" 也能組出非 unavailable 的結果（不再卡在獨立閘門）。 */
    @Test
    void resolveInputsForBacktestAllowsUsMarketThroughIndependentGate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FundamentalAnalysisService service =
                new FundamentalAnalysisService(jdbc, new ObjectMapper(), mock(PublicInfoEvidenceResolver.class));
        Instant decision = Instant.parse("2026-08-08T02:00:00Z");

        var result = service.resolveInputsForBacktest("AAPL", "美股", List.of(decision));

        assertTrue(result.get(decision).applicable());
    }

    /**
     * 測試 (p)：resolveInputsForBacktest() 對台股與美股的 ETF 代碼皆維持 unavailable，
     * 且 null stockCode 也維持 unavailable——確認 293.8 只替換了市場判斷子句，
     * 沒有連帶丟掉 {@code stockCode != null} 與 {@code !isEtf(...)} 兩個既有子句。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resolveInputsForBacktestKeepsNullCodeAndEtfGuardsForBothMarkets() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        FundamentalAnalysisService service =
                new FundamentalAnalysisService(jdbc, new ObjectMapper(), mock(PublicInfoEvidenceResolver.class));
        Instant decision = Instant.parse("2026-08-08T02:00:00Z");

        // "0050" 靠既有 00 開頭字首捷徑直接判定為 ETF，不必依賴上面的 DB stub。
        var twEtf = service.resolveInputsForBacktest("0050", "台股", List.of(decision));
        // "VOO" 走 DB 查詢（上面已 stub 回 true）。
        var usEtf = service.resolveInputsForBacktest("VOO", "美股", List.of(decision));
        var nullCode = service.resolveInputsForBacktest(null, "美股", List.of(decision));

        assertFalse(twEtf.get(decision).applicable());
        assertFalse(usEtf.get(decision).applicable());
        assertFalse(nullCode.get(decision).applicable());
    }

    // ── Task 300：近似 ROE 標度放緩（斜率 5 → 10）──────────────────────────

    /**
     * 測試 (a)(b)(c)(d)(e)：roeFactor() 斜率 10 下的 ROE→contribution 映射。
     * (c) 與 (e) 是門檻邊界值：(c) −0.8 對應 fundamentalDeteriorating() 的 severe 線，
     * (e) −0.5 對應其 peLoss 組合線；兩門檻本身不變（本任務不動），只有其對應的 ROE 水位變了。
     */
    @Test
    void roeFactorAppliesSlopeOfTenCenteredAtTenPercent() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2025, 12, 31);

        assertEquals(1.0, service.roeFactor(roeQuarters(bd(50), bd(1000)), decision).contribution(), 1e-9,
                "(a) ROE 20% → +1.0");
        assertEquals(0.0, service.roeFactor(roeQuarters(bd(25), bd(1000)), decision).contribution(), 1e-9,
                "(b) ROE 10% → 0.0");
        assertEquals(-0.8, service.roeFactor(roeQuarters(bd(5), bd(1000)), decision).contribution(), 1e-9,
                "(c) ROE 2% → -0.8（deteriorating severe 線）");
        assertEquals(-1.0, service.roeFactor(roeQuarters(bd(0), bd(1000)), decision).contribution(), 1e-9,
                "(d) ROE 0% → -1.0");
        assertEquals(-0.5,
                service.roeFactor(roeQuarters(new BigDecimal("12.5"), bd(1000)), decision).contribution(), 1e-9,
                "(e) ROE 5% → -0.5（peLoss 組合門檻的新對應水位）");
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

    /**
     * 建構單一年度 4 季（Q1–Q4，遞減排序）、每季單季淨利皆為 {@code quarterlyNetIncome} 的
     * FinancialRow 列表——年度累計值取 quarter × quarterlyNetIncome，使 standalone() 逐季相減
     * 後每季還原為同一個值，讓 approximateRoePct() 的四季合＝4 × quarterlyNetIncome，方便
     * 用單一參數精確控制 roeFactor() 要餵入的 ROE 百分比。
     */
    private static List<FundamentalAnalysisService.FinancialRow> roeQuarters(
            BigDecimal quarterlyNetIncome, BigDecimal equity) {
        List<FundamentalAnalysisService.FinancialRow> rows = new ArrayList<>();
        for (int quarter = 4; quarter >= 1; quarter--) {
            rows.add(new FundamentalAnalysisService.FinancialRow(
                    2025, quarter, BigDecimal.ZERO, quarterlyNetIncome.multiply(BigDecimal.valueOf(quarter)),
                    equity, "EXCHANGE", List.of("https://example.test/financial"), Instant.EPOCH, Instant.EPOCH));
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
