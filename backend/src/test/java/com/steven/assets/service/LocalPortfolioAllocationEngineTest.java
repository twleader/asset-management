package com.steven.assets.service;

import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.dto.PortfolioAdviceResult;
import com.steven.assets.dto.RetirementProjectionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 339（Requirement 80）本機配置模板引擎的<b>純函式</b>測試——
 * 不啟 Spring context、不連 DB、不建 client，全部以 {@code new LocalPortfolioAllocationEngine()} 直接測。
 *
 * <p>涵蓋 339.14 的 (a) 配置模板為純函式且固定輸入產出確定比例、(b) 三類名稱、
 * (g) {@code rebalancePlan} 只到類別層級，以及風險／年數對照表的邊界與後備行為。</p>
 */
class LocalPortfolioAllocationEngineTest {

    private final LocalPortfolioAllocationEngine engine = new LocalPortfolioAllocationEngine();

    // ===== (a) 配置模板：固定 riskTolerance ＋ yearsToRetirement → 確定比例 =====

    @Test
    void template_isDeterministicForFixedRiskAndHorizon() {
        LocalPortfolioAllocationEngine.Template t1 =
                engine.templateOf(LocalPortfolioAllocationEngine.RISK_BALANCED, 25);
        LocalPortfolioAllocationEngine.Template t2 =
                engine.templateOf(LocalPortfolioAllocationEngine.RISK_BALANCED, 25);

        assertEquals(t1, t2, "同輸入必須得到完全相同的輸出（純函式）");
        assertEquals(0, new BigDecimal("15").compareTo(t1.cashPct()));
        assertEquals(0, new BigDecimal("30").compareTo(t1.fundPct()));
        assertEquals(0, new BigDecimal("55").compareTo(t1.stockPct()));
        assertFalse(t1.rationale().isBlank(), "每一格都要有一句中文理由");
    }

    @Test
    void everyTemplateCellSumsTo100AndHasRationale() {
        for (String risk : List.of(LocalPortfolioAllocationEngine.RISK_CONSERVATIVE,
                LocalPortfolioAllocationEngine.RISK_BALANCED,
                LocalPortfolioAllocationEngine.RISK_AGGRESSIVE)) {
            for (Integer years : List.of(30, 15, 5, 0)) {
                LocalPortfolioAllocationEngine.Template t = engine.templateOf(risk, years);
                BigDecimal sum = t.cashPct().add(t.fundPct()).add(t.stockPct());
                assertEquals(0, new BigDecimal("100").compareTo(sum),
                        "比例加總須為 100：" + risk + "/" + years);
                assertFalse(t.rationale().isBlank(), risk + "/" + years + " 缺理由");
            }
        }
    }

    @Test
    void stockWeightDecreasesWithLowerRiskAndShorterHorizon() {
        BigDecimal aggressiveLong = engine.templateOf(
                LocalPortfolioAllocationEngine.RISK_AGGRESSIVE, 30).stockPct();
        BigDecimal balancedLong = engine.templateOf(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 30).stockPct();
        BigDecimal conservativeLong = engine.templateOf(
                LocalPortfolioAllocationEngine.RISK_CONSERVATIVE, 30).stockPct();
        assertTrue(aggressiveLong.compareTo(balancedLong) > 0);
        assertTrue(balancedLong.compareTo(conservativeLong) > 0);

        BigDecimal balancedImminent = engine.templateOf(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 0).stockPct();
        assertTrue(balancedLong.compareTo(balancedImminent) > 0, "距退休越近，股票比重應越低");
    }

    /**
     * riskTolerance 的合法值只有 CONSERVATIVE／BALANCED／AGGRESSIVE
     * （<b>不是</b> LOW／MEDIUM／HIGH）；猜錯會讓對照表永遠 miss、靜默落到最保守檔。
     * 本測試同時釘住「合法值有效」與「非法值落最保守檔」兩側。
     */
    @Test
    void riskCodes_areTheProfileCodesNotLowMediumHigh() {
        LocalPortfolioAllocationEngine.Template mostConservative =
                engine.templateOf(LocalPortfolioAllocationEngine.RISK_CONSERVATIVE, 30);

        assertEquals(mostConservative, engine.templateOf("HIGH", 30), "HIGH 不是合法代碼 → 落最保守檔");
        assertEquals(mostConservative, engine.templateOf("MEDIUM", 30));
        assertEquals(mostConservative, engine.templateOf("LOW", 30));
        assertEquals(mostConservative, engine.templateOf(null, 30));

        assertNotEquals(mostConservative, engine.templateOf(LocalPortfolioAllocationEngine.RISK_AGGRESSIVE, 30),
                "合法的 AGGRESSIVE 必須真的命中不同的一格，否則對照表等於永遠 miss");
        assertNotEquals(mostConservative, engine.templateOf(LocalPortfolioAllocationEngine.RISK_BALANCED, 30));
    }

    @Test
    void nullHorizonFallsBackToMostConservativeCell() {
        assertEquals(engine.templateOf(LocalPortfolioAllocationEngine.RISK_CONSERVATIVE, 0),
                engine.templateOf(null, null));
    }

    @Test
    void horizonBuckets_boundaries() {
        assertEquals(LocalPortfolioAllocationEngine.Horizon.LONG,
                LocalPortfolioAllocationEngine.horizonOf(20));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.MEDIUM,
                LocalPortfolioAllocationEngine.horizonOf(19));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.MEDIUM,
                LocalPortfolioAllocationEngine.horizonOf(10));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.SHORT,
                LocalPortfolioAllocationEngine.horizonOf(9));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.SHORT,
                LocalPortfolioAllocationEngine.horizonOf(3));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.IMMINENT,
                LocalPortfolioAllocationEngine.horizonOf(2));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.IMMINENT,
                LocalPortfolioAllocationEngine.horizonOf(0));
        assertEquals(LocalPortfolioAllocationEngine.Horizon.IMMINENT,
                LocalPortfolioAllocationEngine.horizonOf(null));
    }

    // ===== 距退休年數推算（純函式：「今天」由呼叫端傳入）=====

    @Test
    void yearsToRetirement_prefersRetirementDate() {
        LocalDate today = LocalDate.of(2026, 8, 16);
        assertEquals(13, LocalPortfolioAllocationEngine.yearsToRetirement(
                today, LocalDate.of(2040, 6, 15), LocalDate.of(1980, 1, 1)),
                "整月數 / 12 無條件捨去（2026-08 → 2040-06 為 13 年 10 個月）");
    }

    @Test
    void yearsToRetirement_fallsBackToBirthDateWithAssumedAge() {
        LocalDate today = LocalDate.of(2026, 8, 16);
        assertEquals(LocalPortfolioAllocationEngine.ASSUMED_RETIREMENT_AGE - 46,
                LocalPortfolioAllocationEngine.yearsToRetirement(today, null, LocalDate.of(1980, 1, 1)));
    }

    @Test
    void yearsToRetirement_isNullWhenBothDatesMissing() {
        assertNull(LocalPortfolioAllocationEngine.yearsToRetirement(LocalDate.of(2026, 8, 16), null, null));
    }

    @Test
    void yearsToRetirement_isZeroWhenAlreadyRetired() {
        LocalDate today = LocalDate.of(2026, 8, 16);
        assertEquals(0, LocalPortfolioAllocationEngine.yearsToRetirement(
                today, LocalDate.of(2020, 1, 1), LocalDate.of(1950, 1, 1)));
    }

    // ===== (b) 三類名稱必須與 getCurrentAllocation() 逐字一致 =====

    @Test
    void assetClassNames_areExactlyTheThreeExistingOnes() {
        // 逐字釘住（另有一支服務層測試把這三個常數與 getCurrentAllocation() 的實際輸出對照）
        assertEquals("存款（現金）", LocalPortfolioAllocationEngine.CLASS_CASH);
        assertEquals("信託基金", LocalPortfolioAllocationEngine.CLASS_FUND);
        assertEquals("股票", LocalPortfolioAllocationEngine.CLASS_STOCK);

        PortfolioAdviceResult r = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 20, currentAllocation(), availableProjection());
        assertEquals(List.of("存款（現金）", "信託基金", "股票"),
                r.targetAllocation().stream().map(PortfolioAdviceResult.TargetAllocation::assetClass).toList());
    }

    // ===== evaluate：金額欄位刻意留白（由既有 enrich 回填）、references 空、warnings 首條為聲明 =====

    @Test
    void evaluate_leavesAmountsToEnrichAndReturnsNoReferences() {
        PortfolioAdviceResult r = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_AGGRESSIVE, 30, currentAllocation(), availableProjection());

        for (PortfolioAdviceResult.TargetAllocation t : r.targetAllocation()) {
            assertNull(t.targetAmount(), "targetAmount 必須留給既有 enrich() 回填，本引擎不得自算");
            assertNull(t.deltaAmount(), "deltaAmount 必須留給既有 enrich() 回填，本引擎不得自算");
            assertFalse(t.rationale().isBlank());
        }
        // currentValue 直接來自傳入的 getCurrentAllocation() 結果，不重查快照
        assertEquals(0, new BigDecimal("4000000").compareTo(r.targetAllocation().get(0).currentValue()));
        assertEquals(0, new BigDecimal("1000000").compareTo(r.targetAllocation().get(1).currentValue()));
        assertEquals(0, new BigDecimal("5000000").compareTo(r.targetAllocation().get(2).currentValue()));

        assertTrue(r.references().isEmpty(), "本機路徑不搜尋網路 → references 固定空陣列");
        assertEquals(LocalPortfolioAllocationEngine.TEMPLATE_DISCLAIMER, r.warnings().get(0),
                "warnings 首條固定為「經驗法則、未經回測、非個人化投資建議」聲明");
        assertTrue(r.warnings().contains(LocalPortfolioAllocationEngine.NO_HOLDING_LEVEL_WARNING),
                "不產生個股層級建議的限制須在 warnings 明示");
        assertTrue(r.rebalancePlan().isEmpty(), "rebalancePlan 待 enrich 之後才由 withRebalancePlan 產生");
        assertFalse(r.actions().isEmpty());
    }

    @Test
    void evaluate_warnsWhenRiskOrHorizonOrSnapshotMissing() {
        PortfolioAdviceResult r = engine.evaluate(null, null,
                CurrentAllocationDto.empty(), RetirementProjectionDto.unavailable("請先填生日。", null, null));

        assertEquals(LocalPortfolioAllocationEngine.TEMPLATE_DISCLAIMER, r.warnings().get(0));
        assertTrue(r.warnings().contains(LocalPortfolioAllocationEngine.RISK_UNKNOWN_WARNING));
        assertTrue(r.warnings().contains(LocalPortfolioAllocationEngine.HORIZON_UNKNOWN_WARNING));
        assertTrue(r.warnings().contains(LocalPortfolioAllocationEngine.NO_SNAPSHOT_WARNING));
        for (PortfolioAdviceResult.TargetAllocation t : r.targetAllocation()) {
            assertNull(t.currentValue(), "無快照時三類現況金額皆為 null");
        }
    }

    // ===== (h) riskAssessment 援引傳入的既有退休試算，不自行重算 =====

    @Test
    void riskAssessment_quotesTheProvidedProjection() {
        PortfolioAdviceResult depleting = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 12, currentAllocation(), depletingProjection());
        assertTrue(depleting.riskAssessment().contains("83"), depleting.riskAssessment());
        assertTrue(depleting.riskAssessment().contains("2069"), depleting.riskAssessment());

        PortfolioAdviceResult lasting = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 12, currentAllocation(), availableProjection());
        assertTrue(lasting.riskAssessment().contains("100"), lasting.riskAssessment());

        PortfolioAdviceResult none = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 12, currentAllocation(),
                RetirementProjectionDto.unavailable("請先填「生日」。", null, null));
        assertTrue(none.riskAssessment().contains("請先填「生日」。"), none.riskAssessment());
    }

    // ===== (g) rebalancePlan 只到類別層級 =====

    @Test
    void withRebalancePlan_isClassLevelOnlyAndDerivedFromDelta() {
        // enrich 後的樣子：股票需減碼 100 萬、存款需增碼 100 萬、基金剛好
        PortfolioAdviceResult enriched = new PortfolioAdviceResult(
                "s", "r",
                List.of(
                        alloc(LocalPortfolioAllocationEngine.CLASS_CASH, "20", "3000000", "4000000", "1000000"),
                        alloc(LocalPortfolioAllocationEngine.CLASS_FUND, "30", "3000000", "3000000", "0"),
                        alloc(LocalPortfolioAllocationEngine.CLASS_STOCK, "50", "6000000", "5000000", "-1000000")),
                List.of(), List.of(), List.of("w"), List.of());

        PortfolioAdviceResult out = engine.withRebalancePlan(enriched);

        assertEquals(3, out.rebalancePlan().size());
        for (PortfolioAdviceResult.Rebalance r : out.rebalancePlan()) {
            assertEquals(LocalPortfolioAllocationEngine.HOLDING_OVERALL, r.holding(),
                    "本機檔位不得產生個股層級建議");
        }
        assertEquals(LocalPortfolioAllocationEngine.ACTION_BUY, out.rebalancePlan().get(0).action());
        assertEquals(0, new BigDecimal("1000000").compareTo(out.rebalancePlan().get(0).estimatedAmount()));
        assertEquals(LocalPortfolioAllocationEngine.ACTION_HOLD, out.rebalancePlan().get(1).action());
        assertEquals(0, BigDecimal.ZERO.compareTo(out.rebalancePlan().get(1).estimatedAmount()));
        assertEquals(LocalPortfolioAllocationEngine.ACTION_SELL, out.rebalancePlan().get(2).action());
        assertEquals(0, new BigDecimal("1000000").compareTo(out.rebalancePlan().get(2).estimatedAmount()),
                "estimatedAmount 為差額絕對值（正數）");

        // 其餘欄位原封不動（withRebalancePlan 只補 plan）
        assertSame(enriched.targetAllocation(), out.targetAllocation());
        assertEquals(enriched.warnings(), out.warnings());
    }

    @Test
    void withRebalancePlan_skipsItemsWithoutDelta() {
        PortfolioAdviceResult noAmounts = new PortfolioAdviceResult(
                "s", "r",
                List.of(alloc(LocalPortfolioAllocationEngine.CLASS_CASH, "20", null, null, null)),
                List.of(), List.of(), List.of(), List.of());

        assertTrue(engine.withRebalancePlan(noAmounts).rebalancePlan().isEmpty(),
                "無金額（無快照）時不產生調整動作");
    }

    // ===== Requirement 82 / Task 341：子分配對照表（SUB_TEMPLATES）=====

    @Test
    void subTemplate_everyOfTwelveCellsSumsTo100ForBothStockAndFund() {
        for (String risk : List.of(LocalPortfolioAllocationEngine.RISK_CONSERVATIVE,
                LocalPortfolioAllocationEngine.RISK_BALANCED,
                LocalPortfolioAllocationEngine.RISK_AGGRESSIVE)) {
            for (Integer years : List.of(30, 15, 5, 0)) {
                LocalPortfolioAllocationEngine.SubTemplate st = engine.subTemplateOf(risk, years);
                assertNotNull(st, risk + "/" + years + " 缺子分配格");
                BigDecimal stockSum = st.stockGrowthPct().add(st.stockIncomePct())
                        .add(st.stockBondShortPct()).add(st.stockBondMidPct()).add(st.stockBondLongPct());
                BigDecimal fundSum = st.fundGrowthPct().add(st.fundIncomePct())
                        .add(st.fundBondShortPct()).add(st.fundBondMidPct()).add(st.fundBondLongPct());
                assertEquals(0, new BigDecimal("100").compareTo(stockSum),
                        "股票子分配加總須為 100：" + risk + "/" + years);
                assertEquals(0, new BigDecimal("100").compareTo(fundSum),
                        "基金子分配加總須為 100：" + risk + "/" + years);
            }
        }
    }

    @Test
    void subTemplate_stockBondPctsAreAlwaysZero() {
        // 設計原則：債券曝險一律經由信託基金達成，股票桶目標次分配的三個債券期別固定為 0
        for (String risk : List.of(LocalPortfolioAllocationEngine.RISK_CONSERVATIVE,
                LocalPortfolioAllocationEngine.RISK_BALANCED,
                LocalPortfolioAllocationEngine.RISK_AGGRESSIVE)) {
            for (Integer years : List.of(30, 15, 5, 0)) {
                LocalPortfolioAllocationEngine.SubTemplate st = engine.subTemplateOf(risk, years);
                assertEquals(0, BigDecimal.ZERO.compareTo(st.stockBondShortPct()));
                assertEquals(0, BigDecimal.ZERO.compareTo(st.stockBondMidPct()));
                assertEquals(0, BigDecimal.ZERO.compareTo(st.stockBondLongPct()));
            }
        }
    }

    // ===== Requirement 82 / Task 341：withSubAllocationAmounts =====

    @Test
    void withSubAllocationAmounts_producesFiveSubItemsPerBucketWithCorrectAmounts() {
        PortfolioAdviceResult enriched = new PortfolioAdviceResult(
                "s", "r",
                List.of(
                        alloc(LocalPortfolioAllocationEngine.CLASS_CASH, "20", "2000000", "2000000", "0"),
                        alloc(LocalPortfolioAllocationEngine.CLASS_FUND, "30", "3000000", "3000000", "0"),
                        alloc(LocalPortfolioAllocationEngine.CLASS_STOCK, "50", "5000000", "5000000", "0")),
                List.of(), List.of(), List.of("w"), List.of());

        // RISK_BALANCED / 距退休 15 年（MEDIUM）：股票 65/35/0/0/0、基金 35/25/15/15/10
        PortfolioAdviceResult out = engine.withSubAllocationAmounts(
                enriched, currentAllocationWithSubItems(), LocalPortfolioAllocationEngine.RISK_BALANCED, 15);

        PortfolioAdviceResult.TargetAllocation cash = findByClass(out, LocalPortfolioAllocationEngine.CLASS_CASH);
        assertTrue(cash.subAllocations().isEmpty(), "存款（現金）不細分子類別");

        PortfolioAdviceResult.TargetAllocation stock = findByClass(out, LocalPortfolioAllocationEngine.CLASS_STOCK);
        assertEquals(5, stock.subAllocations().size(), "目標比例為 0 的子類別仍須輸出一筆");
        assertEquals(List.of(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH,
                        LocalPortfolioAllocationEngine.SUBCLASS_INCOME,
                        LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT,
                        LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID,
                        LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG),
                stock.subAllocations().stream().map(PortfolioAdviceResult.SubAllocation::subClass).toList());
        BigDecimal stockSubSum = stock.subAllocations().stream()
                .map(PortfolioAdviceResult.SubAllocation::targetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(stockSubSum.subtract(stock.targetAmount()).abs().compareTo(BigDecimal.ONE) <= 0,
                "股票子分配 targetAmount 之和應約等於頂層 targetAmount（尾差 ≤ 1 元）：" + stockSubSum);

        PortfolioAdviceResult.SubAllocation stockGrowth = findBySubClass(stock, LocalPortfolioAllocationEngine.SUBCLASS_GROWTH);
        assertEquals(0, new BigDecimal("3250000").compareTo(stockGrowth.targetAmount()));
        assertEquals(0, new BigDecimal("2000000").compareTo(stockGrowth.currentValue()));
        assertEquals(0, new BigDecimal("1250000").compareTo(stockGrowth.deltaAmount()),
                "deltaAmount = targetAmount − currentValue");

        PortfolioAdviceResult.SubAllocation stockBondShort = findBySubClass(stock, LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT);
        assertEquals(0, BigDecimal.ZERO.compareTo(stockBondShort.targetPct()));
        assertEquals(0, BigDecimal.ZERO.compareTo(stockBondShort.targetAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(stockBondShort.currentValue()), "現況查無該子類別時視為 0");

        PortfolioAdviceResult.TargetAllocation fund = findByClass(out, LocalPortfolioAllocationEngine.CLASS_FUND);
        assertEquals(5, fund.subAllocations().size());
        BigDecimal fundSubSum = fund.subAllocations().stream()
                .map(PortfolioAdviceResult.SubAllocation::targetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(fundSubSum.subtract(fund.targetAmount()).abs().compareTo(BigDecimal.ONE) <= 0,
                "信託基金子分配 targetAmount 之和應約等於頂層 targetAmount（尾差 ≤ 1 元）：" + fundSubSum);

        PortfolioAdviceResult.SubAllocation fundBondShort = findBySubClass(fund, LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT);
        assertEquals(0, new BigDecimal("450000").compareTo(fundBondShort.targetAmount()));
        assertEquals(0, new BigDecimal("300000").compareTo(fundBondShort.currentValue()));
        assertEquals(0, new BigDecimal("150000").compareTo(fundBondShort.deltaAmount()));
    }

    // ===== Requirement 82 / Task 341：STOCK_BOND_HOLDING_WARNING =====

    @Test
    void evaluate_triggersStockBondHoldingWarningWhenStockBucketHoldsBondSubclasses() {
        PortfolioAdviceResult r = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 20,
                currentAllocationWithStockBondHolding(new BigDecimal("100000"), new BigDecimal("50000"), new BigDecimal("50000")),
                availableProjection());

        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("200,000")),
                "股票桶三個債券期別金額加總（100,000+50,000+50,000=200,000）須出現在提醒文案：" + r.warnings());
    }

    @Test
    void evaluate_doesNotTriggerStockBondHoldingWarningWhenNoBondSubclassInStockBucket() {
        PortfolioAdviceResult r = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 20, currentAllocation(), availableProjection());

        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("被歸類為債券型標的")),
                "股票桶無債券子類別時不得觸發提醒：" + r.warnings());
    }

    @Test
    void evaluate_fundBucketBondHoldingsDoNotTriggerTheStockOnlyWarning() {
        // 信託基金桶本就允許債券部位（非例外情況），僅股票桶才觸發此則提醒
        List<CurrentAllocationDto.Item> items = new ArrayList<>();
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_CASH,
                new BigDecimal("4000000"), new BigDecimal("40.0"), List.of()));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_FUND,
                new BigDecimal("1000000"), new BigDecimal("10.0"), List.of(
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID,
                                new BigDecimal("500000"), new BigDecimal("50.0")))));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_STOCK,
                new BigDecimal("5000000"), new BigDecimal("50.0"), List.of()));
        CurrentAllocationDto current = new CurrentAllocationDto(1L, LocalDate.of(2026, 8, 15), new BigDecimal("10000000"), items);

        PortfolioAdviceResult r = engine.evaluate(
                LocalPortfolioAllocationEngine.RISK_BALANCED, 20, current, availableProjection());

        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("被歸類為債券型標的")),
                "信託基金桶持有債券不觸發股票專屬的提醒：" + r.warnings());
    }

    // ===== 測試資料工具 =====

    private static PortfolioAdviceResult.TargetAllocation alloc(String assetClass, String pct,
                                                                String current, String target, String delta) {
        return new PortfolioAdviceResult.TargetAllocation(
                assetClass, new BigDecimal(pct),
                current == null ? null : new BigDecimal(current),
                target == null ? null : new BigDecimal(target),
                delta == null ? null : new BigDecimal(delta),
                "r", List.of());
    }

    private static CurrentAllocationDto currentAllocation() {
        List<CurrentAllocationDto.Item> items = new ArrayList<>();
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_CASH,
                new BigDecimal("4000000"), new BigDecimal("40.0"), List.of()));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_FUND,
                new BigDecimal("1000000"), new BigDecimal("10.0"), List.of()));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_STOCK,
                new BigDecimal("5000000"), new BigDecimal("50.0"), List.of()));
        return new CurrentAllocationDto(1L, LocalDate.of(2026, 8, 15), new BigDecimal("10000000"), items);
    }

    /** 股票桶含成長/收益兩類、信託基金桶含五類皆非 0 的現況（供 withSubAllocationAmounts 測試）。 */
    private static CurrentAllocationDto currentAllocationWithSubItems() {
        List<CurrentAllocationDto.Item> items = new ArrayList<>();
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_CASH,
                new BigDecimal("2000000"), new BigDecimal("20.0"), List.of()));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_FUND,
                new BigDecimal("1600000"), new BigDecimal("16.0"), List.of(
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, new BigDecimal("500000"), new BigDecimal("31.3")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, new BigDecimal("500000"), new BigDecimal("31.3")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, new BigDecimal("300000"), new BigDecimal("18.8")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, new BigDecimal("200000"), new BigDecimal("12.5")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, new BigDecimal("100000"), new BigDecimal("6.3")))));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_STOCK,
                new BigDecimal("3000000"), new BigDecimal("30.0"), List.of(
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, new BigDecimal("2000000"), new BigDecimal("66.7")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, new BigDecimal("1000000"), new BigDecimal("33.3")))));
        return new CurrentAllocationDto(1L, LocalDate.of(2026, 8, 15), new BigDecimal("10000000"), items);
    }

    /** 股票桶含指定短/中/長期債金額（供 STOCK_BOND_HOLDING_WARNING 觸發測試）。 */
    private static CurrentAllocationDto currentAllocationWithStockBondHolding(
            BigDecimal shortAmt, BigDecimal midAmt, BigDecimal longAmt) {
        List<CurrentAllocationDto.Item> items = new ArrayList<>();
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_CASH,
                new BigDecimal("4000000"), new BigDecimal("40.0"), List.of()));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_FUND,
                new BigDecimal("1000000"), new BigDecimal("10.0"), List.of()));
        items.add(new CurrentAllocationDto.Item(LocalPortfolioAllocationEngine.CLASS_STOCK,
                new BigDecimal("5000000"), new BigDecimal("50.0"), List.of(
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, shortAmt, new BigDecimal("2.0")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, midAmt, new BigDecimal("1.0")),
                        new CurrentAllocationDto.SubItem(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, longAmt, new BigDecimal("1.0")))));
        return new CurrentAllocationDto(1L, LocalDate.of(2026, 8, 15), new BigDecimal("10000000"), items);
    }

    private static PortfolioAdviceResult.TargetAllocation findByClass(PortfolioAdviceResult r, String assetClass) {
        return r.targetAllocation().stream().filter(t -> assetClass.equals(t.assetClass())).findFirst()
                .orElseThrow(() -> new AssertionError("找不到類別：" + assetClass));
    }

    private static PortfolioAdviceResult.SubAllocation findBySubClass(
            PortfolioAdviceResult.TargetAllocation t, String subClass) {
        return t.subAllocations().stream().filter(s -> subClass.equals(s.subClass())).findFirst()
                .orElseThrow(() -> new AssertionError("找不到子類別：" + subClass));
    }

    private static RetirementProjectionDto availableProjection() {
        return new RetirementProjectionDto(true, null, 46, 65, 100, new BigDecimal("10000000"),
                null, List.of(), new BigDecimal("20000000"), null, null, true, new BigDecimal("5000000"));
    }

    private static RetirementProjectionDto depletingProjection() {
        return new RetirementProjectionDto(true, null, 46, 65, 100, new BigDecimal("10000000"),
                null, List.of(), new BigDecimal("20000000"), 83, 2069, false, null);
    }
}
