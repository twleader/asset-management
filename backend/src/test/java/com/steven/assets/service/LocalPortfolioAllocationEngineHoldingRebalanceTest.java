package com.steven.assets.service;

import com.steven.assets.dto.PortfolioAdviceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 344（Requirement 84）標的層級再平衡的<b>純函式</b>測試——不啟 Spring context、不連 DB，
 * 全部以 {@code new LocalPortfolioAllocationEngine()} 直接測。
 *
 * <p>涵蓋任務檔「驗證」段的 (a) 守恆不變式、(b) tie-break 全序、(c) {@code T_g} 先捨入、
 * (d) 在途款排除、(e)(f) 存款 waterfall、(g) clamp 與 shortfall、(h) {@code P_g = ∅} 兩分支、
 * (i) 最小金額門檻、(j) 同標的跨券商合併。</p>
 *
 * <p>金額取自任務檔記載的實測快照（snapshot 15），故每一條斷言都對應一個真實情境，
 * 不是憑空造的數字。</p>
 */
class LocalPortfolioAllocationEngineHoldingRebalanceTest {

    private final LocalPortfolioAllocationEngine engine = new LocalPortfolioAllocationEngine();

    // ===== (a) 守恆不變式：Σ estimatedAmount == |T_g|，七個實測群組全部成立 =====

    @Test
    void conservation_holdsForAllSevenRealGroups() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "5263797");
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "363839");
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, "480128");
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, "107250");
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, "252363");
        // 全部群組中唯一會觸發 344.7 捨入者（來源值 345929.51）——漏掉這組等於捨入規則沒有測試覆蓋
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_FUND,
                LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, "345930");
        // 存款組走 344.9 的 waterfall（演算法不同），守恆同樣須成立
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_CASH, null, "2338033");

        // 類別層級三筆一律保留（344.19）
        assertEquals(3, out.rebalancePlan().stream()
                .filter(r -> LocalPortfolioAllocationEngine.HOLDING_OVERALL.equals(r.holding())).count());
    }

    @Test
    void everyHoldingLevelRowDeviatesAtMostOneDollarFromTheTheoreticalShare() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        BigDecimal groupValue = new BigDecimal("8010622");
        BigDecimal total = new BigDecimal("5263797");
        for (PortfolioAdviceResult.Rebalance r : detailRows(out)) {
            if (!LocalPortfolioAllocationEngine.CLASS_STOCK.equals(r.assetClass())
                    || !LocalPortfolioAllocationEngine.SUBCLASS_GROWTH.equals(r.subClass())) {
                continue;
            }
            BigDecimal value = growthValueOf(r.holding());
            BigDecimal theoretical = value.multiply(total).divide(groupValue, 6, RoundingMode.HALF_UP);
            assertTrue(theoretical.subtract(r.estimatedAmount()).abs().compareTo(BigDecimal.ONE) <= 0,
                    r.holding() + " 與理論比例值的偏差須 ≤ 1 元：" + theoretical + " vs " + r.estimatedAmount());
        }
    }

    // ===== (b) tie-break 為全序：打亂輸入順序，輸出逐筆完全相同 =====

    @Test
    void output_isIdenticalRegardlessOfInputOrder() {
        PortfolioAdviceResult first = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        List<LocalPortfolioAllocationEngine.Holding> shuffled =
                new ArrayList<>(realHoldings().holdings());
        Collections.shuffle(shuffled, new Random(42));
        PortfolioAdviceResult second = engine.withHoldingLevelRebalance(realFixture(),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(shuffled));

        List<String> a = first.rebalancePlan().stream()
                .map(r -> r.holding() + "@" + r.estimatedAmount().toPlainString()).toList();
        List<String> b = second.rebalancePlan().stream()
                .map(r -> r.holding() + "@" + r.estimatedAmount().toPlainString()).toList();
        assertEquals(a, b, "tie-break 必須是全序，不得依賴輸入或 Map 迭代順序");
    }

    // ===== (c) T_g 先捨入到整數元：帶小數的 deltaAmount 不得產生小數 estimatedAmount =====

    @Test
    void groupTotal_isRoundedBeforeDistribution() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        for (PortfolioAdviceResult.Rebalance r : detailRows(out)) {
            assertEquals(0, r.estimatedAmount().stripTrailingZeros().scale() <= 0 ? 0 : 1,
                    r.holding() + " 的 estimatedAmount 不得帶小數：" + r.estimatedAmount());
        }
        // 信託基金－長期債來源值為 345929.51，捨入後 345,930 全額落在唯一一檔基金上
        PortfolioAdviceResult.Rebalance fundRow = detailRows(out).stream()
                .filter(r -> r.holding().contains("施羅德")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("345930").compareTo(fundRow.estimatedAmount()));
    }

    // ===== (d) 在途款以 currency 排除：payable=false 的「正值」在途款同樣不得進入分攤 =====

    @Test
    void transitDeposits_areExcludedByCurrencyEvenWhenPositive() {
        List<LocalPortfolioAllocationEngine.Holding> holdings = new ArrayList<>(deposits());
        holdings.add(deposit("信用卡待付款", "信用卡待付款", "-102124", 50, null, "TRANSIT_TWD", 901));
        holdings.add(deposit("賣股待收款", "證券戶", "500000", 50, null, "TRANSIT_TWD", 902));

        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(cashOnlyFixture("-2338033"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(holdings));

        for (PortfolioAdviceResult.Rebalance r : detailRows(out)) {
            assertFalse(r.holding().contains("待付款") || r.holding().contains("待收款"),
                    "在途／轉帳中的部位不是可自由處分的部位，不得出現在清單：" + r.holding());
        }
        // 正值在途款若只靠「v ≤ 0」是攔不住的，必須靠 currency 這一條
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_CASH, null, "2338033");
        assertTrue(out.warnings().stream().anyMatch(w -> w.contains("在途／轉帳中")),
                "被排除的在途款須以 warning 明示（分子含、分母不含，兩者刻意不同源）");
    }

    // ===== (e)(f) 存款減碼走 waterfall；只動到一筆定存；存款增碼仍走等比例 =====

    @Test
    void depositSell_followsWithdrawalOrderWaterfallAndTouchesExactlyOneTimeDeposit() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(cashOnlyFixture("-2338033"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(deposits()));

        List<PortfolioAdviceResult.Rebalance> rows = detailRows(out);
        assertEquals(7, rows.size(), "只有被抽到的存款列才出現（未抽到者不產生 HOLD，是完全不出現）");
        assertEquals(List.of(
                        "富邦銀行 活存", "國泰世華 活存", "華南銀行 活存",
                        "國泰世華 美元活存", "富邦銀行 美元活存",
                        "Line Bank 優利活存 1.5%", "富邦銀行 定存"),
                rows.stream().map(PortfolioAdviceResult.Rebalance::holding).toList(),
                "順序即提領優先序：活存 → 美元活存 → 優利活存 → 定存");
        assertEquals(0, new BigDecimal("1988010").compareTo(rows.get(6).estimatedAmount()),
                "最後一筆為部分抽取");
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_CASH, null, "2338033");

        // 定存只被動到一筆；另兩筆定存與兩筆美元定存完全不動
        long timeDepositRows = rows.stream().filter(r -> r.holding().contains("定存")).count();
        assertEquals(1, timeDepositRows, "等比例會讓三筆定存同時解約，waterfall 只動到一筆");
        assertTrue(out.warnings().stream().anyMatch(w -> w.contains("定存中途解約")),
                "真的抽到定存時才附加解約損失 warning");
    }

    @Test
    void depositBuy_usesProportionalNotWaterfall() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(cashOnlyFixture("1000000"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(deposits()));

        List<PortfolioAdviceResult.Rebalance> rows = detailRows(out);
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_CASH, null, "1000000");
        // waterfall 會把 150,082 的富邦活存整筆填滿；等比例只給它約 11.7%
        BigDecimal fubonSavings = rows.stream().filter(r -> "富邦銀行 活存".equals(r.holding()))
                .map(PortfolioAdviceResult.Rebalance::estimatedAmount).findFirst().orElseThrow();
        assertTrue(fubonSavings.compareTo(new BigDecimal("20000")) < 0,
                "存款增碼不套 waterfall（waterfall 會整筆填滿）：" + fubonSavings);
        assertTrue(fubonSavings.signum() > 0);
        assertTrue(out.warnings().stream().noneMatch(w -> w.contains("定存中途解約")),
                "增碼不觸發解約損失 warning");
        for (PortfolioAdviceResult.Rebalance r : rows) {
            assertEquals(LocalPortfolioAllocationEngine.ACTION_BUY, r.action());
        }
    }

    // ===== (g) clamp 觸頂後的重分配與 shortfall 記錄 =====

    @Test
    void sellOverflow_isClampedAndRecordedAsShortfallWithoutLeakingToOtherGroups() {
        List<LocalPortfolioAllocationEngine.Holding> holdings = List.of(
                stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "元大台灣50", "0050", "300000"),
                stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "Vanguard S&P 500 ETF", "VOO", "200000"));

        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(
                stockOnlyFixture("-800000"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(holdings));

        List<PortfolioAdviceResult.Rebalance> rows = detailRows(out);
        // Σ estimatedAmount == V（可賣出上限），而不是 |T_g| ——塞一個做不到的金額進去會讓使用者下不掉單
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "500000");
        assertEquals(0, new BigDecimal("300000").compareTo(rows.get(0).estimatedAmount()));
        assertEquals(0, new BigDecimal("200000").compareTo(rows.get(1).estimatedAmount()));
        assertTrue(rows.get(rows.size() - 1).rationale().contains("仍差 300,000 元"),
                "shortfall 只寫進最後一列的 rationale：" + rows.get(rows.size() - 1).rationale());
        // 不得轉嫁給其他群組
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "0");
    }

    // ===== (h) P_g = ∅：T_g > 0 走 344.16、T_g < 0 走 344.17 防禦分支 =====

    @Test
    void emptyGroup_emitsExactlyOneUnspecifiedRowInsteadOfBeingSkipped() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(
                stockOnlyFixture("1220811"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(List.of()));

        List<PortfolioAdviceResult.Rebalance> rows = detailRows(out);
        assertEquals(1, rows.size());
        PortfolioAdviceResult.Rebalance r = rows.get(0);
        assertEquals(LocalPortfolioAllocationEngine.ACTION_UNSPECIFIED, r.action(),
                "不得沿用 BUY——綠色「增碼」tag 是可執行動作的視覺語意");
        assertEquals(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH + "：尚無持有標的", r.holding());
        assertEquals(0, new BigDecimal("1220811").compareTo(r.estimatedAmount()));
        assertTrue(r.rationale().contains("完整 AI 分析"), r.rationale());
        assertFalse(LocalPortfolioAllocationEngine.HOLDING_OVERALL.equals(r.holding()),
                "用「整體」會 render 得與本功能之前一模一樣，使用者分不出「做不到」與「沒做」");
    }

    @Test
    void emptyGroupWithNegativeDelta_fallsBackToTheSnapshotDriftBranch() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(
                stockOnlyFixture("-1000"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(List.of()));

        PortfolioAdviceResult.Rebalance r = detailRows(out).get(0);
        assertEquals(LocalPortfolioAllocationEngine.ACTION_UNSPECIFIED, r.action());
        assertTrue(r.rationale().contains("重新儲存快照"),
                "不得靜默吞掉、也不得拋例外（把使用者可自行修復的資料問題變成 500）：" + r.rationale());
    }

    @Test
    void nullDelta_skipsTheWholeThingInsteadOfThrowing() {
        // 無資產快照：rebalancePlan 本來就是空的，deltaAmount 全為 null
        PortfolioAdviceResult noSnapshot = new PortfolioAdviceResult("s", "r",
                List.of(new PortfolioAdviceResult.TargetAllocation(
                        LocalPortfolioAllocationEngine.CLASS_STOCK, new BigDecimal("50"), null, null, null,
                        "r", List.of())),
                List.of(), List.of(), List.of("w"), List.of());

        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(noSnapshot,
                new LocalPortfolioAllocationEngine.HoldingBreakdown(List.of()));

        assertNotNull(out);
        assertTrue(out.rebalancePlan().isEmpty(), "沒有金額就沒有可執行動作（比照 withRebalancePlan 既有慣例）");
    }

    // ===== (i) 最小金額門檻：N == 1 不合併但附加提示；N ≥ 2 合併為一列 =====

    @Test
    void smallAmounts_keepTheirNameWhenAloneAndCarryAContextAppropriateHint() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        PortfolioAdviceResult.Rebalance googl = detailRows(out).stream()
                .filter(r -> r.holding().contains("GOOGL")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("7296").compareTo(googl.estimatedAmount()));
        assertTrue(googl.rationale().contains("交易成本"),
                "股票的門檻錨點是券商最低手續費：" + googl.rationale());
        assertFalse(googl.holding().startsWith("其餘"), "N == 1 時保留原標的名稱、不合併");

        PortfolioAdviceResult.Rebalance huanan = detailRows(out).stream()
                .filter(r -> r.holding().contains("華南")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("5210").compareTo(huanan.estimatedAmount()));
        assertFalse(huanan.rationale().contains("交易成本"),
                "存款提領沒有手續費，不得用「交易成本」措辭：" + huanan.rationale());
        assertTrue(huanan.rationale().contains("短少"),
                "waterfall 的全額抽取被略過會直接破壞守恆，須明示短少多少：" + huanan.rationale());
    }

    @Test
    void smallAmounts_areMergedIntoOneRowWhenThereAreTwoOrMore() {
        List<LocalPortfolioAllocationEngine.Holding> holdings = List.of(
                stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "元大台灣50", "0050", "9980000"),
                stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "Alphabet Inc. Class A", "GOOGL", "10000"),
                stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "Microsoft Corp", "MSFT", "10000"));

        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(
                stockOnlyFixture("1000000"),
                new LocalPortfolioAllocationEngine.HoldingBreakdown(holdings));

        List<PortfolioAdviceResult.Rebalance> rows = detailRows(out);
        assertEquals(2, rows.size(), "兩筆不足門檻者合併為單一列");
        PortfolioAdviceResult.Rebalance merged = rows.get(rows.size() - 1);
        assertEquals("其餘 2 檔（每檔不足 10,000 元）", merged.holding());
        assertEquals(0, new BigDecimal("2000").compareTo(merged.estimatedAmount()));
        // 合併列同時保住守恆與可解釋性（不得把小額併進同群組最大一筆）
        assertGroupSum(out, LocalPortfolioAllocationEngine.CLASS_STOCK,
                LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "1000000");
    }

    // ===== (j) 同一標的跨券商多列先合併再分攤 =====

    @Test
    void sameTargetAcrossBrokers_isMergedIntoOneRow() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        List<PortfolioAdviceResult.Rebalance> rows00919 = detailRows(out).stream()
                .filter(r -> r.holding().contains("00919")).toList();
        assertEquals(1, rows00919.size(), "00919 在富邦有三列，不合併會出現三行建議");
        assertTrue(rows00919.get(0).rationale().contains("3 個帳戶／券商的合計"),
                rows00919.get(0).rationale());
        assertFalse(rows00919.get(0).rationale().contains("富邦"),
                "不逐一列出各券商金額（該需求已於 344.13 否決）");
    }

    @Test
    void classLevelRowsCarryNoSubClassButDetailRowsDo() {
        PortfolioAdviceResult out = engine.withHoldingLevelRebalance(realFixture(), realHoldings());

        for (PortfolioAdviceResult.Rebalance r : out.rebalancePlan()) {
            if (LocalPortfolioAllocationEngine.HOLDING_OVERALL.equals(r.holding())) {
                assertNull(r.subClass(), "類別層級橫跨整桶，不屬於任何子類別");
            } else if (!LocalPortfolioAllocationEngine.CLASS_CASH.equals(r.assetClass())) {
                assertNotNull(r.subClass(), "股票／基金的標的層級明細須帶子類別供前端分段：" + r.holding());
            }
        }
    }

    // ===== fixtures =====

    /** 實測快照（snapshot 15）的七個群組。 */
    private PortfolioAdviceResult realFixture() {
        List<PortfolioAdviceResult.TargetAllocation> targets = List.of(
                target(LocalPortfolioAllocationEngine.CLASS_CASH, "-2338033", List.of()),
                target(LocalPortfolioAllocationEngine.CLASS_FUND, "8077733", List.of(
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "1220811"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "2441622"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, "2848559"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, "1220811"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, "345929.51"))),
                target(LocalPortfolioAllocationEngine.CLASS_STOCK, "-5739699", List.of(
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "5263797"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "363839"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, "-480128"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, "-107250"),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, "-252363"))));
        return engine.withRebalancePlan(new PortfolioAdviceResult(
                "s", "r", targets, List.of(), List.of(), List.of("既有 warning"), List.of()));
    }

    private LocalPortfolioAllocationEngine.HoldingBreakdown realHoldings() {
        List<LocalPortfolioAllocationEngine.Holding> h = new ArrayList<>();
        // 股票－成長型（V = 8,010,622）
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "Alphabet Inc. Class A", "GOOGL", "11104"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "元大台灣50", "0050", "3000000"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "Vanguard S&P 500 ETF", "VOO", "2500000"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, "Vanguard Total World Stock ETF", "VT", "2499518"));
        // 股票－收益型（V = 2,993,391；00919 三列同券商，須合併）
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "國泰台灣ESG永續高股息", "00882", "14910"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "群益台灣精選高息", "00919", "1000000"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "群益台灣精選高息", "00919", "500000"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "群益台灣精選高息", "00919", "478481"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "國泰永續高股息", "00878", "1000000"));
        // 股票－各期別債（目標 0 → 全額減碼）
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, "國泰投資級公司債", "00725B", "300000"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, "國泰投資級公司債", "00725B", "180128"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, "元大投資級公司債", "00720B", "107250"));
        h.add(stock(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, "元大美債20年", "00679B", "252363"));
        // 信託基金－長期債（唯一一列，實測 61,007.49）
        h.add(fund(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, "施羅德環球收益債券非常避險 (月配)", "61007.49"));
        h.addAll(deposits());
        return new LocalPortfolioAllocationEngine.HoldingBreakdown(h);
    }

    /** 實測 11 列存款（合計 8,544,212；不含在途款）。 */
    private static List<LocalPortfolioAllocationEngine.Holding> deposits() {
        List<LocalPortfolioAllocationEngine.Holding> h = new ArrayList<>();
        h.add(deposit("富邦銀行", "活存", "150082", 10, null, "TWD", 1));
        h.add(deposit("國泰世華", "活存", "47562", 10, null, "TWD", 2));
        h.add(deposit("華南銀行", "活存", "5210", 10, null, "TWD", 3));
        h.add(deposit("國泰世華", "美元活存", "54047", 20, null, "USD", 4));
        h.add(deposit("富邦銀行", "美元活存", "10750", 20, null, "USD", 5));
        h.add(deposit("Line Bank", "優利活存 1.5%", "82372", 30, "1.5000", "TWD", 6));
        h.add(deposit("富邦銀行", "定存", "2815000", 90, "1.7150", "TWD", 7));
        h.add(deposit("國泰世華", "定存", "2700000", 90, "1.7150", "TWD", 8));
        h.add(deposit("台新銀行", "定存", "2483458", 90, "1.7150", "TWD", 9));
        h.add(deposit("國泰世華", "美元定存", "131557", 91, null, "USD", 10));
        h.add(deposit("富邦銀行", "美元定存", "64174", 91, null, "USD", 11));
        return h;
    }

    private PortfolioAdviceResult cashOnlyFixture(String delta) {
        return engine.withRebalancePlan(new PortfolioAdviceResult("s", "r",
                List.of(target(LocalPortfolioAllocationEngine.CLASS_CASH, delta, List.of())),
                List.of(), List.of(), List.of("既有 warning"), List.of()));
    }

    private PortfolioAdviceResult stockOnlyFixture(String growthDelta) {
        return engine.withRebalancePlan(new PortfolioAdviceResult("s", "r",
                List.of(target(LocalPortfolioAllocationEngine.CLASS_STOCK, growthDelta, List.of(
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, growthDelta),
                        sub(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, "0")))),
                List.of(), List.of(), List.of("既有 warning"), List.of()));
    }

    private static PortfolioAdviceResult.TargetAllocation target(
            String assetClass, String delta, List<PortfolioAdviceResult.SubAllocation> subs) {
        return new PortfolioAdviceResult.TargetAllocation(assetClass, new BigDecimal("30"),
                new BigDecimal("1000000"), new BigDecimal("1000000"), new BigDecimal(delta), "r", subs);
    }

    private static PortfolioAdviceResult.SubAllocation sub(String subClass, String delta) {
        return new PortfolioAdviceResult.SubAllocation(subClass, new BigDecimal("20"),
                BigDecimal.ZERO, new BigDecimal(delta), new BigDecimal(delta), "r");
    }

    private static LocalPortfolioAllocationEngine.Holding stock(
            String subClass, String name, String code, String value) {
        return new LocalPortfolioAllocationEngine.Holding(
                LocalPortfolioAllocationEngine.CLASS_STOCK, subClass,
                PortfolioAdviceService.stockDisplayName(name, code), new BigDecimal(value),
                null, null, null, "TW|" + code);
    }

    private static LocalPortfolioAllocationEngine.Holding fund(String subClass, String name, String value) {
        return new LocalPortfolioAllocationEngine.Holding(
                LocalPortfolioAllocationEngine.CLASS_FUND, subClass, name, new BigDecimal(value),
                null, null, null, name);
    }

    private static LocalPortfolioAllocationEngine.Holding deposit(
            String bank, String type, String value, int withdrawalOrder, String rate, String currency, long id) {
        return new LocalPortfolioAllocationEngine.Holding(
                LocalPortfolioAllocationEngine.CLASS_CASH, null, bank + " " + type, new BigDecimal(value),
                withdrawalOrder, rate == null ? null : new BigDecimal(rate), currency,
                id + "|" + type + "|" + currency + "|" + id);
    }

    // ===== assertions =====

    private static List<PortfolioAdviceResult.Rebalance> detailRows(PortfolioAdviceResult r) {
        return r.rebalancePlan().stream()
                .filter(x -> !LocalPortfolioAllocationEngine.HOLDING_OVERALL.equals(x.holding())).toList();
    }

    private static void assertGroupSum(PortfolioAdviceResult r, String assetClass, String subClass, String expected) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PortfolioAdviceResult.Rebalance row : detailRows(r)) {
            if (assetClass.equals(row.assetClass()) && Objects.equals(subClass, row.subClass())) {
                sum = sum.add(row.estimatedAmount());
            }
        }
        assertEquals(0, new BigDecimal(expected).compareTo(sum),
                "群組 " + assetClass + "/" + subClass + " 的 Σ estimatedAmount 應為 " + expected + "，實得 " + sum);
    }

    private static BigDecimal growthValueOf(String holding) {
        if (holding.contains("GOOGL")) return new BigDecimal("11104");
        if (holding.contains("0050")) return new BigDecimal("3000000");
        if (holding.contains("VOO")) return new BigDecimal("2500000");
        return new BigDecimal("2499518");
    }
}
