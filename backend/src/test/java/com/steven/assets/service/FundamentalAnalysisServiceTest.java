package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.steven.assets.repository.FundamentalAnalysisBatchRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

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
    void epsTrendKeepsTurnaroundAndLossStatesInsteadOfReturningNull() {
        assertEquals("TURNAROUND", FundamentalAnalysisService.epsTrend(
                List.of(bd(1), bd(1), bd(1), bd(1), bd(-1), bd(-1), bd(-1), bd(-1))).type());
        assertEquals("TURNED_LOSS", FundamentalAnalysisService.epsTrend(
                List.of(bd(-1), bd(-1), bd(-1), bd(-1), bd(1), bd(1), bd(1), bd(1))).type());
        assertEquals("PERSISTENT_LOSS", FundamentalAnalysisService.epsTrend(
                List.of(bd(-1), bd(-1), bd(-1), bd(-1), bd(-2), bd(-2), bd(-2), bd(-2))).type());
        assertEquals(1.0, FundamentalAnalysisService.epsTrend(
                List.of(bd(1), bd(1), bd(1), bd(1), bd(-1), bd(-1), bd(-1), bd(-1))).contribution());
    }

    @Test
    void roeUsesAverageBeginningAndEndingEquityAndSupportsExplicitFallback() {
        assertEquals(new BigDecimal("66.6667"), FundamentalAnalysisService.approximateRoePct(
                List.of(bd(25), bd(25), bd(25), bd(25)), bd(100), bd(200)));

        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        List<FundamentalAnalysisService.FinancialRow> recentWithoutBeginning = new ArrayList<>(List.of(
                financialRow(2026, 2, 25, 200, "EXCHANGE"),
                financialRow(2026, 1, 25, 200, "EXCHANGE"),
                financialRow(2025, 4, 25, 200, "EXCHANGE"),
                financialRow(2025, 3, 25, 200, "EXCHANGE"),
                financialRow(2025, 2, 25, 0, "EXCHANGE")));
        recentWithoutBeginning.set(4, new FundamentalAnalysisService.FinancialRow(
                2025, 2, bd(0), bd(50), null, "EXCHANGE", List.of("u"), Instant.EPOCH, Instant.EPOCH));
        FundamentalAnalysisService.Factor fallback = service.roeFactor(
                recentWithoutBeginning, LocalDate.of(2026, 8, 15));
        assertTrue(fallback.fallback());
        assertEquals(50.0000, fallback.value().doubleValue(), 0.00001);

        List<FundamentalAnalysisService.FinancialRow> crossProvider = new ArrayList<>(recentWithoutBeginning);
        crossProvider.set(4, financialRow(2025, 2, 25, 100, "FINMIND"));
        assertNull(service.roeFactor(crossProvider, LocalDate.of(2026, 8, 15)),
                "期初權益不得跨 provider 拼接");
        assertNull(FundamentalAnalysisService.approximateRoePct(
                List.of(bd(25), bd(25), bd(25), bd(25)), bd(0), bd(200)));
    }

    @Test
    void valuationCompositeUsesSameProviderAndAllPercentagePointComponents() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate latest = LocalDate.of(2026, 8, 8);
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    latest.minusDays(i),
                    BigDecimal.valueOf(10 + i),
                    BigDecimal.valueOf(2 + i / 10.0),
                    BigDecimal.valueOf(4 + i / 100.0),
                    false, "EXCHANGE", List.of("u"), Instant.EPOCH, Instant.EPOCH));
        }
        var composite = service.valuationComposite(rows, latest, false);
        assertEquals("EXCHANGE", composite.provider());
        assertEquals(3, composite.coverage());
        assertEquals(new BigDecimal("4.0"), composite.dividendYieldPct());
        assertTrue(composite.contribution() <= 1.0 && composite.contribution() >= -1.0);

        FundamentalAnalysisService.ValuationRow loss = new FundamentalAnalysisService.ValuationRow(
                latest, null, null, new BigDecimal("4.00"), true, "EXCHANGE", List.of(),
                Instant.EPOCH, Instant.EPOCH);
        var lossComposite = service.valuationComposite(List.of(loss), latest, false);
        assertEquals(-1.0, lossComposite.contribution());
        assertTrue(lossComposite.peComponent().loss());
        assertEquals("EXCHANGE", lossComposite.peComponent().provider());
        assertEquals(latest, lossComposite.peComponent().asOf());
    }

    // ── Task 334.5：SEC_DERIVED 進 provider 白名單（最末順位），但虧損旗標仍只認一手觀測 ──────

    @Test
    void derivedProviderWithEnoughHistoryProducesPercentileAndKeepsItsOwnProvenance() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 8);
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i),
                    BigDecimal.valueOf(10 + i),
                    BigDecimal.valueOf(2 + i / 10.0),
                    BigDecimal.valueOf(4 + i / 100.0),
                    false, "SEC_DERIVED", List.of("derived-url"), Instant.EPOCH, Instant.EPOCH));
        }

        var composite = service.valuationComposite(rows, decision, false);

        assertNotNull(composite, "白名單漏了 SEC_DERIVED 時整組會被靜默丟棄，連日誌都沒有");
        assertEquals("SEC_DERIVED", composite.provider());
        assertEquals("SEC_DERIVED", composite.peComponent().provider());
        assertEquals(3, composite.coverage());
        assertNotNull(composite.pePercentile());
        assertNotNull(composite.pbPercentile());
        assertFalse(composite.peComponent().loss());
        assertTrue(composite.contribution() <= 1.0 && composite.contribution() >= -1.0);

        // 推導值同樣受 250 筆門檻約束，不得因為是自家推導就放行。
        assertNull(service.valuationComposite(rows.subList(0, 249), decision, false));
    }

    @Test
    void derivedLossFlagMustNotBypassTheSampleThresholdAndForceLossValuation() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 8);
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        // Yahoo 當期快照沒有旗標（美股列的實際常見狀態），loss 決策因此會往後面的 provider 掉。
        rows.add(new FundamentalAnalysisService.ValuationRow(
                decision, new BigDecimal("30.00"), null, "YAHOO", List.of("yahoo-url"),
                Instant.EPOCH, Instant.EPOCH));
        rows.add(new FundamentalAnalysisService.ValuationRow(
                decision, null, true, "SEC_DERIVED", List.of("derived-url"), Instant.EPOCH, Instant.EPOCH));
        for (int i = 1; i <= 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i), BigDecimal.valueOf(10 + i), false, "SEC_DERIVED",
                    List.of("derived-url"), Instant.EPOCH, Instant.EPOCH));
        }

        var composite = service.valuationComposite(rows, decision, false);

        // latestLoss 沒有筆數門檻：不排除 SEC_DERIVED 的話，推導出的虧損旗標會直接把整組 VALUATION
        // 換成 contribution 寫死 -1.0 的 lossComponent。
        assertNotNull(composite);
        assertFalse(composite.peComponent().loss());
        assertEquals("SEC_DERIVED", composite.peComponent().provider());
        assertEquals(1.0, composite.contribution());

        // 控制組：同一組資料只把虧損旗標換成一手觀測 provider，既有的 -1.0 行為必須原封不動。
        List<FundamentalAnalysisService.ValuationRow> observedLoss = new ArrayList<>(rows);
        observedLoss.set(1, new FundamentalAnalysisService.ValuationRow(
                decision, null, true, "FINMIND", List.of("finmind-url"), Instant.EPOCH, Instant.EPOCH));
        var lossComposite = service.valuationComposite(observedLoss, decision, false);
        assertEquals(-1.0, lossComposite.contribution());
        assertTrue(lossComposite.peComponent().loss());
    }

    @Test
    void dividendYieldBoundaryConvertsRatioExactlyOnce() {
        assertEquals(new BigDecimal("4.0000"), FundamentalAnalysisService
                .normalizeDividendYieldPct(new BigDecimal("0.04"), true));
        assertEquals(new BigDecimal("4.00"), FundamentalAnalysisService
                .normalizeDividendYieldPct(new BigDecimal("4.00"), false));
        assertEquals(new BigDecimal("4.01"), FundamentalAnalysisService
                .normalizeDividendYieldPct(new BigDecimal("4.01"), false));
        // A DB value already expressed in percentage points must not be sent through the
        // ratio adapter a second time.
        assertEquals(new BigDecimal("4.00"), FundamentalAnalysisService
                .normalizeDividendYieldPct(new BigDecimal("4.00"), false));
    }

    @Test
    void valuationComponentsKeepIndependentProviderDateAndSevereYieldObservation() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 8);
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i), BigDecimal.valueOf(10 + i), null, null, false,
                    "EXCHANGE", List.of("pe-url"), Instant.parse("2026-08-08T01:00:00Z"), Instant.EPOCH));
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i + 1), null, BigDecimal.valueOf(2 + i / 10.0), null, false,
                    "YAHOO", List.of("pb-url"), Instant.parse("2026-08-07T01:00:00Z"), Instant.EPOCH));
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i + 2), null, null, BigDecimal.valueOf(4 + i / 100.0), false,
                    "FINMIND", List.of("yield-url"), Instant.parse("2026-08-06T01:00:00Z"), Instant.EPOCH));
        }

        var composite = service.valuationComposite(rows, decision, true);

        assertEquals("MULTI", composite.provider());
        assertEquals("EXCHANGE", composite.peComponent().provider());
        assertEquals("YAHOO", composite.pbComponent().provider());
        assertEquals("FINMIND", composite.dividendYieldComponent().provider());
        assertEquals(LocalDate.of(2026, 8, 8), composite.peComponent().asOf());
        assertEquals(LocalDate.of(2026, 8, 7), composite.pbComponent().asOf());
        assertEquals(LocalDate.of(2026, 8, 6), composite.dividendYieldComponent().asOf());
        assertNotNull(composite.dividendYieldPct(), "severe path still exposes raw yield");
        assertEquals(2, composite.coverage(), "severe path excludes yield from score coverage");
    }

    @Test
    void explicitHigherPriorityNonLossDoesNotFallThroughToLowerProviderLoss() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 8);
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        rows.add(new FundamentalAnalysisService.ValuationRow(
                decision, null, null, null, false, "EXCHANGE", List.of("official"),
                Instant.EPOCH, Instant.EPOCH));
        rows.add(new FundamentalAnalysisService.ValuationRow(
                decision, null, null, null, true, "YAHOO", List.of("fallback"),
                Instant.EPOCH, Instant.EPOCH));

        assertNull(service.valuationComposite(rows, decision, false));
        assertNull(service.peFactor(rows, decision));
    }

    @Test
    void peLossKeepsItsOwnProvenanceWhenYieldComesFromAnotherProvider() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 8);
        Instant peAt = Instant.parse("2026-08-08T01:00:00Z");
        Instant yieldAt = Instant.parse("2026-08-07T01:00:00Z");
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        rows.add(new FundamentalAnalysisService.ValuationRow(
                decision, null, null, new BigDecimal("5.00"), true, "EXCHANGE",
                List.of("pe-loss-url"), peAt, peAt));
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i + 1), BigDecimal.valueOf(10 + i / 10.0), null, null,
                    false, "EXCHANGE", List.of("old-pe-url"), yieldAt, yieldAt));
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i + 1), null, null, BigDecimal.valueOf(4 + i / 100.0),
                    false, "FINMIND", List.of("yield-url"), yieldAt, yieldAt));
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i + 1), null, BigDecimal.valueOf(2 + i / 100.0), null,
                    false, "YAHOO", List.of("pb-url"), yieldAt, yieldAt));
        }

        var composite = service.valuationComposite(rows, decision, false);

        assertEquals(-1.0, composite.contribution());
        assertNull(composite.peValue(), "explicit PE loss replaces historical positive PE disclosure");
        assertEquals(3, composite.coverage());
        assertEquals("MULTI", composite.provider());
        assertTrue(composite.peComponent().loss());
        assertEquals("EXCHANGE", composite.peComponent().provider());
        assertEquals(List.of("pe-loss-url"), composite.peComponent().urls());
        assertEquals("FINMIND", composite.dividendYieldComponent().provider());
        assertEquals("YAHOO", composite.pbComponent().provider());
        assertNotNull(composite.pbValue());
        assertEquals("pe-loss-url", composite.urls().getFirst());
        assertTrue(composite.urls().contains("yield-url"));
        assertEquals(decision, LocalDate.parse(composite.asOf()));
    }

    @Test
    void severeFinancialYieldOnlyRemainsDisclosureWithoutSyntheticLossScore() {
        FundamentalAnalysisService service = new FundamentalAnalysisService(null, null, null);
        LocalDate decision = LocalDate.of(2026, 8, 8);
        List<FundamentalAnalysisService.ValuationRow> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(new FundamentalAnalysisService.ValuationRow(
                    decision.minusDays(i), null, null, BigDecimal.valueOf(4 + i / 100.0),
                    false, "FINMIND", List.of("yield-url"), Instant.EPOCH, Instant.EPOCH));
        }
        var composite = service.valuationComposite(rows, decision, true);
        assertNotNull(composite);
        assertNull(composite.contribution());
        assertEquals(0, composite.coverage());
        assertNotNull(composite.dividendYieldPct());
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
    void usMarketCoverageCapsAtThreeAndNeverFabricatesRevenueOrIndustry() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        // 14:00Z is after the US market-local date has advanced to 2026-08-08;
        // a 02:00Z decision is still 2026-08-07 in New York and must not see the
        // 2026-08-08 valuation row.
        Instant decisionInstant = Instant.parse("2026-08-08T14:00:00Z");

        List<FundamentalAnalysisService.FinancialRow> financials = financialRows("SEC_EDGAR", 2026, 2);
        List<FundamentalAnalysisService.ValuationRow> valuations = valuationRows("YAHOO", LocalDate.of(2026, 8, 8));

        when(repository.findSnapshot(any(), eq(decisionInstant))).thenReturn(snapshot(financials, valuations));
        var service = new FundamentalAnalysisService(repository, new ObjectMapper(), mock(PublicInfoEvidenceResolver.class));

        var resolved = service.resolve("AAPL", "Apple", "美股", decisionInstant);

        assertTrue(resolved.input().applicable());
        assertNull(resolved.input().revenueContribution());
        assertNull(resolved.input().industryContribution());
        assertEquals(3, resolved.snapshot().coverage());
        assertNull(resolved.snapshot().revenueYoy3mPct());
        assertNull(resolved.snapshot().industryRevenueYoyPct());
        assertEquals("SEC_EDGAR", resolved.snapshot().epsProvider());
    }

    @Test
    void listBatchReadsFourObservationFamiliesOnceForAllExactPairs() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        Instant decision = Instant.parse("2026-09-12T06:00:00Z");
        when(publicInfo.loadForEarliestDecision(decision)).thenReturn(List.of());
        when(repository.findSnapshots(any(), eq(decision))).thenReturn(Map.of());
        FundamentalAnalysisService service = new FundamentalAnalysisService(repository, new ObjectMapper(), publicInfo);
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                null, "2330", "台股", "台積電", null, AssetClassifier.defaultDividendThreshold());

        var results = service.resolveBatch(List.of(
                new FundamentalAnalysisService.BatchQuery("2330", "台積電", "台股", profile),
                new FundamentalAnalysisService.BatchQuery("2330", "同碼美股", "美股", profile)), decision);

        assertEquals(2, results.size());
        verify(publicInfo, times(1)).loadForEarliestDecision(decision);
        verify(repository, times(1)).findSnapshots(any(), eq(decision));
    }

    @Test
    void batchOnlyReadsApplicablePairsAndKeepsSingleStockFullSnapshotParity() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        Instant decision = Instant.parse("2026-08-08T14:00:00Z");
        var tw = batchQuery("2330", "台股", "台積電");
        var us = batchQuery("2330", "美股", "US ordinary stock");
        var etf = batchQuery("0050", "台股", "元大台灣50");
        var bond = batchQuery("00679B", "台股", "元大美債20年");
        var unknown = new FundamentalAnalysisService.BatchQuery(
                "UNKNOWN", "unknown", "台股", TradingRadarAssetProfileResolver.AssetProfile.unknown("missing"));
        var twKey = new FundamentalAnalysisBatchRepository.Key(tw.stockCode(), tw.market());
        var usKey = new FundamentalAnalysisBatchRepository.Key(us.stockCode(), us.market());
        var twRows = snapshot(financialRows("EXCHANGE", 2026, 2), valuationRows("EXCHANGE", LocalDate.of(2026, 8, 8)));
        var usRows = snapshot(financialRows("SEC_EDGAR", 2026, 2), valuationRows("YAHOO", LocalDate.of(2026, 8, 8)));
        when(repository.findSnapshot(twKey, decision)).thenReturn(twRows);
        when(repository.findSnapshot(usKey, decision)).thenReturn(usRows);
        when(repository.findSnapshots(List.of(twKey, usKey), decision)).thenReturn(Map.of(twKey, twRows, usKey, usRows));
        when(publicInfo.loadForEarliestDecision(decision)).thenReturn(List.of());
        var service = new FundamentalAnalysisService(repository, new ObjectMapper(), publicInfo);

        var results = service.resolveBatch(List.of(us, etf, tw, bond, unknown, tw), decision);

        assertEquals(5, results.size());
        assertEquals(service.resolve(tw.stockCode(), tw.stockName(), tw.market(), decision, tw.profile()), results.get(tw));
        assertEquals(service.resolve(us.stockCode(), us.stockName(), us.market(), decision, us.profile()), results.get(us));
        assertEquals(3, results.get(us).snapshot().coverage());
        for (var query : List.of(etf, bond, unknown)) {
            assertEquals(FundamentalAnalysisService.Resolved.unavailable(false), results.get(query));
        }
        verify(repository).findSnapshots(List.of(twKey, usKey), decision);
    }

    @Test
    void nonApplicableBatchNeedsNeitherObservationsNorPublicInformation() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        var service = new FundamentalAnalysisService(repository, new ObjectMapper(), publicInfo);
        var queries = List.of(batchQuery("0050", "台股", "元大台灣50"),
                batchQuery("00679B", "台股", "元大美債20年"),
                new FundamentalAnalysisService.BatchQuery("UNKNOWN", "unknown", "美股", null));

        var results = service.resolveBatch(queries, Instant.parse("2026-08-08T14:00:00Z"));

        assertEquals(queries.size(), results.size());
        queries.forEach(query -> assertEquals(FundamentalAnalysisService.Resolved.unavailable(false), results.get(query)));
        verifyNoInteractions(repository, publicInfo);
    }

    @Test
    void failedApplicableBatchPreservesEveryTargetAndInapplicableFlags() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        var service = new FundamentalAnalysisService(repository, new ObjectMapper(), publicInfo);
        Instant decision = Instant.parse("2026-08-08T14:00:00Z");
        var stock = batchQuery("2330", "台股", "台積電");
        var etf = batchQuery("0050", "台股", "元大台灣50");
        var keys = List.of(new FundamentalAnalysisBatchRepository.Key(stock.stockCode(), stock.market()));
        when(repository.findSnapshots(keys, decision)).thenThrow(new IllegalStateException("unavailable"));
        when(publicInfo.loadForEarliestDecision(decision)).thenReturn(List.of());

        var results = service.resolveBatch(List.of(stock, etf), decision);

        assertEquals(2, results.size());
        assertTrue(results.get(stock).snapshot().applicable());
        assertEquals(0, results.get(stock).snapshot().coverage());
        assertEquals(FundamentalAnalysisService.Resolved.unavailable(false), results.get(etf));
        verify(repository).findSnapshots(keys, decision);
    }

    @Test
    void sourceUrlParsingIsSharedOnlyWithinOneBatchIncludingMalformedAndImmutableResults() throws Exception {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        ObjectMapper mapper = spy(new ObjectMapper());
        Instant decision = Instant.parse("2026-08-08T14:00:00Z");
        String good = "[\"https://example.test/evidence\"]";
        String malformed = "not-json";
        var valuations = new ArrayList<FundamentalAnalysisBatchRepository.ValuationObservation>();
        for (int i = 0; i < 250; i++) {
            valuations.add(new FundamentalAnalysisBatchRepository.ValuationObservation(
                    LocalDate.of(2026, 8, 8).minusDays(i), bd(10 + i), bd(2), bd(4), false,
                    "EXCHANGE", good, Instant.EPOCH, Instant.EPOCH));
        }
        // Older revisions still reach the unchanged as-of selector. Invalid URL JSON remains empty.
        for (int i = 0; i < 50; i++) {
            valuations.add(new FundamentalAnalysisBatchRepository.ValuationObservation(
                    LocalDate.of(2024, 1, 1).minusDays(i), bd(12), bd(2), bd(4), false,
                    "YAHOO", malformed, Instant.EPOCH, Instant.EPOCH));
        }
        var observations = new FundamentalAnalysisBatchRepository.Snapshot(
                List.of(new FundamentalAnalysisBatchRepository.FinancialObservation(
                        2026, 2, bd(1), bd(100), bd(1000), "EXCHANGE", good, Instant.EPOCH, Instant.EPOCH)),
                List.of(new FundamentalAnalysisBatchRepository.RevenueObservation(
                        2026, 7, "industry", bd(10), "EXCHANGE", good, Instant.EPOCH, Instant.EPOCH)),
                valuations,
                List.of(new FundamentalAnalysisBatchRepository.IndustryObservation(
                        "industry", 2026, 7, bd(10), 10, "EXCHANGE", malformed, Instant.EPOCH, Instant.EPOCH)));
        var first = batchQuery("2330", "台股", "台積電");
        var second = batchQuery("2317", "台股", "鴻海");
        when(repository.findSnapshots(any(), eq(decision))).thenReturn(Map.of(
                new FundamentalAnalysisBatchRepository.Key(first.stockCode(), first.market()), observations,
                new FundamentalAnalysisBatchRepository.Key(second.stockCode(), second.market()), observations));
        when(publicInfo.loadForEarliestDecision(decision)).thenReturn(List.of());
        var service = new FundamentalAnalysisService(repository, mapper, publicInfo);

        var firstResult = service.resolveBatch(List.of(first, second), decision);

        assertEquals(List.of("https://example.test/evidence"), firstResult.get(first).snapshot().peEvidence().sourceUrls());
        assertThrows(UnsupportedOperationException.class,
                () -> firstResult.get(first).snapshot().peEvidence().sourceUrls().add("mutate"));
        verify(mapper, times(2)).readValue(anyString(), org.mockito.ArgumentMatchers.<TypeReference<List<String>>>any());
        assertEquals(firstResult, service.resolveBatch(List.of(first, second), decision));
        verify(mapper, times(4)).readValue(anyString(), org.mockito.ArgumentMatchers.<TypeReference<List<String>>>any());
    }

    private static FundamentalAnalysisService.BatchQuery batchQuery(String code, String market, String name) {
        return new FundamentalAnalysisService.BatchQuery(code, name, market,
                TradingRadarAssetProfileResolver.resolve(null, code, market, name, null,
                        AssetClassifier.defaultDividendThreshold()));
    }

    @Test
    void rawSingleDecisionSelectionKeepsFirstSeenOrderTieLeftAndEveryEvidenceFamily() {
        Instant now = Instant.parse("2026-09-23T08:00:00Z");
        Instant old = now.minusSeconds(60);
        var fOld = new FundamentalAnalysisBatchRepository.FinancialObservation(
                2026, 2, bd(1), bd(2), bd(3), "EXCHANGE", "[]", old, old);
        var fNew = new FundamentalAnalysisBatchRepository.FinancialObservation(
                2026, 2, bd(4), bd(5), bd(6), "EXCHANGE", null, old, now);
        var fTie = new FundamentalAnalysisBatchRepository.FinancialObservation(
                2026, 2, bd(7), bd(8), bd(9), "EXCHANGE", "[]", old, now);
        var rOld = new FundamentalAnalysisBatchRepository.RevenueObservation(
                2026, 8, "old", bd(1), "EXCHANGE", "[]", old, old);
        var rNew = new FundamentalAnalysisBatchRepository.RevenueObservation(
                2026, 8, "new", bd(2), "EXCHANGE", "[]", old, now);
        var iOld = new FundamentalAnalysisBatchRepository.IndustryObservation(
                "industry", 2026, 8, bd(1), 3, "EXCHANGE", "[]", old, old);
        var iNew = new FundamentalAnalysisBatchRepository.IndustryObservation(
                "industry", 2026, 8, bd(2), 4, "EXCHANGE", "[]", old, now);
        LocalDate date = LocalDate.of(2026, 9, 23);
        var vOld = valuationObservation(date, 1, "[]", old, old);
        var anotherKey = valuationObservation(date.minusDays(1), 2, "[]", old, old);
        var vNew = valuationObservation(date, 3, "not-json", old, now);
        var vTie = valuationObservation(date, 4, "[]", old, now);
        var futureObserved = valuationObservation(date, 5, "[]", old, now.plusNanos(1));
        var futureAvailable = valuationObservation(date, 6, "[]", now.plusNanos(1), old);
        var missingAvailable = valuationObservation(date, 7, "[]", null, old);
        var missingObserved = valuationObservation(date, 8, "[]", old, null);
        var raw = new FundamentalAnalysisBatchRepository.Snapshot(List.of(fOld, fNew, fTie),
                List.of(rOld, rNew), List.of(vOld, anotherKey, vNew, vTie, futureObserved,
                futureAvailable, missingAvailable, missingObserved), List.of(iOld, iNew));

        var selected = FundamentalAnalysisService.selectSnapshotAsOf(raw, now);

        assertEquals(List.of(fNew), selected.financials());
        assertEquals(List.of(rNew), selected.revenues());
        assertEquals(List.of(vNew, anotherKey), selected.valuations());
        assertEquals(List.of(iNew), selected.industries());
        assertEquals(8, raw.valuations().size(), "repository snapshot must retain all revisions");
        assertEquals(List.of(vOld, anotherKey), FundamentalAnalysisService.selectSnapshotAsOf(raw, old).valuations());
        assertEquals(FundamentalAnalysisBatchRepository.Snapshot.empty(),
                FundamentalAnalysisService.selectSnapshotAsOf(raw, null));
    }

    @Test
    void earlySelectionKeepsFullSnapshotParityAndNeverParsesDiscardedRevisions() throws Exception {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        ObjectMapper mapper = spy(new ObjectMapper());
        Instant now = Instant.parse("2026-08-08T14:00:00Z");
        var query = batchQuery("2330", "台股", "台積電");
        var key = new FundamentalAnalysisBatchRepository.Key(query.stockCode(), query.market());
        var observations = new ArrayList<FundamentalAnalysisBatchRepository.ValuationObservation>();
        for (int day = 0; day < 250; day++) {
            LocalDate date = LocalDate.of(2026, 8, 8).minusDays(day);
            for (int revision = 0; revision < 4; revision++) {
                observations.add(valuationObservation(date, 900 + revision,
                        "[\"https://example.test/discarded/" + revision + "\"]", Instant.EPOCH,
                        now.minusSeconds(20 - revision)));
            }
            observations.add(valuationObservation(date, 10 + day,
                    "[\"https://example.test/selected\"]", Instant.EPOCH, now.minusSeconds(1)));
            // Equal timestamp ties keep the first row, even if the later row looks more complete.
            observations.add(valuationObservation(date, 500, "[]", Instant.EPOCH, now.minusSeconds(1)));
        }
        var raw = new FundamentalAnalysisBatchRepository.Snapshot(List.of(), List.of(), observations, List.of());
        when(repository.findSnapshot(key, now)).thenReturn(raw);
        when(repository.findSnapshots(List.of(key), now)).thenReturn(Map.of(key, raw));
        when(publicInfo.loadForEarliestDecision(now)).thenReturn(List.of());
        var service = new FundamentalAnalysisService(repository, mapper, publicInfo);
        var baseline = service.resolve(query.stockCode(), query.stockName(), query.market(), now, query.profile());
        org.mockito.Mockito.clearInvocations(mapper);

        var batch = service.resolveBatch(List.of(query), now).get(query);

        assertEquals(baseline, batch);
        assertEquals(new BigDecimal("10"), batch.snapshot().peValue());
        assertEquals(1500, raw.valuations().size());
        verify(mapper, times(1)).readValue(anyString(), org.mockito.ArgumentMatchers.<TypeReference<List<String>>>any());
        assertEquals(baseline, service.resolveBatch(List.of(query), now).get(query));
        verify(mapper, times(2)).readValue(anyString(), org.mockito.ArgumentMatchers.<TypeReference<List<String>>>any());
    }

    @Test
    void selectedMalformedUrlsAndNullObservationsKeepExistingFailSoftSnapshot() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        PublicInfoEvidenceResolver publicInfo = mock(PublicInfoEvidenceResolver.class);
        Instant now = Instant.parse("2026-08-08T14:00:00Z");
        var query = batchQuery("2330", "台股", "台積電");
        var key = new FundamentalAnalysisBatchRepository.Key(query.stockCode(), query.market());
        var observations = new ArrayList<FundamentalAnalysisBatchRepository.ValuationObservation>();
        for (int i = 0; i < 250; i++) {
            observations.add(valuationObservation(LocalDate.of(2026, 8, 8).minusDays(i), 10 + i,
                    i == 0 ? "[null]" : null, Instant.EPOCH, Instant.EPOCH));
        }
        observations.add(valuationObservation(LocalDate.of(2026, 8, 8), 99, "not-json", null, now));
        var raw = new FundamentalAnalysisBatchRepository.Snapshot(List.of(), List.of(), observations, List.of());
        when(repository.findSnapshot(key, now)).thenReturn(raw);
        when(repository.findSnapshots(List.of(key), now)).thenReturn(Map.of(key, raw));
        when(publicInfo.loadForEarliestDecision(now)).thenReturn(List.of());
        var service = new FundamentalAnalysisService(repository, new ObjectMapper(), publicInfo);

        var baseline = service.resolve(query.stockCode(), query.stockName(), query.market(), now, query.profile());
        var actual = service.resolveBatch(List.of(query), now).get(query);

        assertEquals(baseline, actual);
        assertEquals(List.of(), actual.snapshot().peEvidence().sourceUrls());
        assertEquals(new BigDecimal("10"), actual.snapshot().peValue());
    }

    private static FundamentalAnalysisBatchRepository.ValuationObservation valuationObservation(
            LocalDate date, long pe, String urls, Instant available, Instant observed) {
        return new FundamentalAnalysisBatchRepository.ValuationObservation(
                date, bd(pe), bd(2), bd(4), false, "EXCHANGE", urls, available, observed);
    }

    /** 測試 (k)：resolveInputsForBacktest() 對 market="美股" 也能組出非 unavailable 的結果（不再卡在獨立閘門）。 */
    @Test
    void resolveInputsForBacktestAllowsUsMarketThroughIndependentGate() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        FundamentalAnalysisService service =
                new FundamentalAnalysisService(repository, new ObjectMapper(), mock(PublicInfoEvidenceResolver.class));
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
    void resolveInputsForBacktestKeepsNullCodeAndEtfGuardsForBothMarkets() {
        FundamentalAnalysisBatchRepository repository = mock(FundamentalAnalysisBatchRepository.class);
        when(repository.hasEtfNav("VOO", "美股")).thenReturn(true);
        FundamentalAnalysisService service =
                new FundamentalAnalysisService(repository, new ObjectMapper(), mock(PublicInfoEvidenceResolver.class));
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

    private static FundamentalAnalysisBatchRepository.Snapshot snapshot(
            List<FundamentalAnalysisService.FinancialRow> financials,
            List<FundamentalAnalysisService.ValuationRow> valuations) {
        return new FundamentalAnalysisBatchRepository.Snapshot(
                financials.stream().map(row -> new FundamentalAnalysisBatchRepository.FinancialObservation(
                        row.year(), row.quarter(), row.eps(), row.income(), row.equity(), row.provider(), "[]",
                        row.availableAt(), row.observedAt())).toList(),
                List.of(),
                valuations.stream().map(row -> new FundamentalAnalysisBatchRepository.ValuationObservation(
                        row.date(), row.pe(), row.pb(), row.dividendYieldPct(), row.loss(), row.provider(), "[]",
                        row.availableAt(), row.observedAt())).toList(),
                List.of());
    }

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

    private static FundamentalAnalysisService.FinancialRow financialRow(
            int year, int quarter, int income, int equity, String provider) {
        return new FundamentalAnalysisService.FinancialRow(
                year, quarter, BigDecimal.valueOf(income), BigDecimal.valueOf(income * quarter),
                BigDecimal.valueOf(equity), provider, List.of("u"), Instant.EPOCH, Instant.EPOCH);
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
