package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingRadarRuleEngineTest {

    private final TradingRadarRuleEngine engine = new TradingRadarRuleEngine();

    @Test
    void confirmation_requiresPeriodPlusOneRows() {
        assertEquals(TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                engine.confirm(closesDescending(20, 100), 20));
        assertEquals(TradingRadarRuleEngine.Confirmation.ABOVE,
                engine.confirm(closesDescending(21, 121), 20));
    }

    @Test
    void confirmation_coversBelowAndMixed() {
        assertEquals(TradingRadarRuleEngine.Confirmation.BELOW,
                engine.confirm(closesAscending(21, 100), 20));

        List<BigDecimal> mixed = closesDescending(21, 121);
        mixed.set(0, new BigDecimal("90"));
        assertEquals(TradingRadarRuleEngine.Confirmation.MIXED, engine.confirm(mixed, 20));
    }

    @Test
    void marketScore_isClampedToBounds() {
        var high = engine.evaluateMarket(marketInput(
                new BigDecimal("200"), new BigDecimal("4"),
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("90"), new BigDecimal("10"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE));
        assertEquals(100, high.score());
        assertEquals(TradingRadarRuleEngine.MarketRegime.RISK_ON, high.regime());

        var low = engine.evaluateMarket(marketInput(
                new BigDecimal("10"), new BigDecimal("-5"),
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("90"),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW));
        assertEquals(0, low.score());
        assertEquals(TradingRadarRuleEngine.MarketRegime.RISK_OFF, low.regime());
    }

    @Test
    void qualifyingStock_becomesBuyOrAddByHeldFlag() {
        var notHeld = engine.evaluateStock(strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_ON));
        var held = engine.evaluateStock(strongStock(true, TradingRadarRuleEngine.MarketRegime.RISK_ON));
        assertEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, notHeld.action());
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, held.action());
    }

    // ═══ Task 446：買進閘門「單日漲幅」與「中檔乖離」否決門檻邊界回歸 ═══════════════════

    /**
     * completedChangePercent=5、ma60BiasPercent=12 為現行硬編門檻（{@code chasedDailyMove} 的
     * {@code BigDecimal.valueOf(5)} 與 {@code BIAS_HIGH=12.0}）的邊界值；其餘 buyGate 條件與
     * strongStock 相同（皆滿足），score 與 strongStock 相同（≥ 買進門檻 75，因為 completedChangePercent
     * 與 ma60BiasPercent 皆不參與分數計算，只參與 buyGate 否決判定）。無候選路徑（production 唯一
     * 路徑）在本任務改動前後，同一組輸入必須逐位元相同——本測試證明本任務完全沒有動到 production
     * 行為：兩個否決條件在邊界值上仍然各自成立，動作仍是 HOLD／WATCH。
     */
    @Test
    void chasedDailyMoveAndMidTierOverboughtBoundary_stillVetoesProductionBuyGate() {
        var notHeld = engine.evaluateStock(chasedAndMidTierOverboughtBoundaryStock(false));
        var held = engine.evaluateStock(chasedAndMidTierOverboughtBoundaryStock(true));

        assertTrue(notHeld.score() >= 75, "fixture 必須維持在買進門檻之上，否則本測試沒有驗證到否決邏輯");
        assertEquals(TradingRadarRuleEngine.Action.WATCH, notHeld.action());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, held.action());
    }

    @Test
    void riskOff_neverProducesBuyOrAdd() {
        var notHeld = engine.evaluateStock(strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_OFF));
        var held = engine.evaluateStock(strongStock(true, TradingRadarRuleEngine.MarketRegime.RISK_OFF));
        assertEquals(TradingRadarRuleEngine.Action.WATCH, notHeld.action());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, held.action());
    }

    @Test
    void incompleteData_vetoesMarketAndStock() {
        var market = engine.evaluateMarket(new TradingRadarRuleEngine.MarketInput(
                new BigDecimal("100"), BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(null, null, null, null, null),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE));
        assertNull(market.score());
        assertEquals(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE, market.regime());

        var stock = engine.evaluateStock(new TradingRadarRuleEngine.StockInput(
                true, new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(null, null, null, null, null),
                null,
                null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                false,
                null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null));
        assertNull(stock.score());
        assertEquals(TradingRadarRuleEngine.Action.NO_TRADE, stock.action());
        assertEquals(TradingRadarRuleEngine.CounterTrendState.NONE, stock.counterTrend().state());
    }

    @Test
    void oversoldWatch_keepsOriginal009804ScoreAndAction() {
        var stock = engine.evaluateStock(counterTrendStock(
                new BigDecimal("20.60"), new BigDecimal("-6.62"),
                new BigDecimal("18.5"), new BigDecimal("28.0"),
                new BigDecimal("24.0"), new BigDecimal("30.0"),
                TradingRadarRuleEngine.Confirmation.ABOVE));

        // V11 的「不殺低」只在 KD 深度超賣且季線乖離 <= -10% 的 EXTREME_OVERSOLD 生效。
        // 本案對季線乖離為 -7.9%，雖屬 OVERSOLD_WATCH，仍不可偽裝已觸發極端低檔保護。
        //
        // V16（Task 360）：K=18.5 < D=28.0（K<D、KD 均值 23.25 屬低檔）。
        // V15 時 extendedIndicators 為 null → KD/J 只有 direction −1.0 與 position +0.535
        // 兩分量 → −0.2325；改用標準 J = 3×18.5 − 2×28 = −0.5 → clampUnit((50+0.5)/50) 飽和為 +1.0，
        // 三分量平均 (−1.0 + 0.535 + 1.0)/3 = +0.178333。**分數上升** 33 → 37，
        // 正是 360.7g 對 K<D 標的的預期方向（深度超賣不再被當成超買扣分）。
        assertEquals(37, stock.score());
        assertEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, stock.action());
        assertEquals(TradingRadarRuleEngine.CounterTrendState.OVERSOLD_WATCH,
                stock.counterTrend().state());
        assertTrue(stock.counterTrend().risks().stream()
                .anyMatch(risk -> risk.contains("小額分批") && risk.contains("主規則建議仍優先")));
    }

    @Test
    void trialCandidate_requiresRealLowGoldenCrossAndStoppedFalling() {
        var stock = engine.evaluateStock(counterTrendStock(
                new BigDecimal("20.00"), new BigDecimal("0.50"),
                new BigDecimal("19.0"), new BigDecimal("18.0"),
                new BigDecimal("17.0"), new BigDecimal("18.0"),
                TradingRadarRuleEngine.Confirmation.ABOVE));

        assertEquals(TradingRadarRuleEngine.CounterTrendState.TRIAL_CANDIDATE,
                stock.counterTrend().state());
        // V9：本 fixture 的六項 TRIAL_BUY 條件此刻全數成立（年線乖離、年線兩日確認、K/D<20、
        // 真交叉、完成日 K 止跌，且 Task 264 已移除 RISK_OFF 封鎖），故主動作升級為分批試單。
        // V8 時因 RISK_OFF 封鎖而落到分數映射的 HOLD_CAUTION——那正是「急跌時買不進去」的病灶。
        assertEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, stock.action());
        assertTrue(stock.counterTrend().risks().stream()
                .anyMatch(risk -> risk.contains("小額試單")));
    }

    @Test
    void oversoldWatch_doesNotUpgradeWithoutNewCrossOrWhileStillFalling() {
        var noNewCross = engine.evaluateStock(counterTrendStock(
                new BigDecimal("20.00"), new BigDecimal("0.50"),
                new BigDecimal("19.0"), new BigDecimal("18.0"),
                new BigDecimal("19.0"), new BigDecimal("18.0"),
                TradingRadarRuleEngine.Confirmation.ABOVE));
        var stillFalling = engine.evaluateStock(counterTrendStock(
                new BigDecimal("20.00"), new BigDecimal("-0.50"),
                new BigDecimal("19.0"), new BigDecimal("18.0"),
                new BigDecimal("17.0"), new BigDecimal("18.0"),
                TradingRadarRuleEngine.Confirmation.ABOVE));

        assertEquals(TradingRadarRuleEngine.CounterTrendState.OVERSOLD_WATCH,
                noNewCross.counterTrend().state());
        assertEquals(TradingRadarRuleEngine.CounterTrendState.OVERSOLD_WATCH,
                stillFalling.counterTrend().state());
    }

    @Test
    void counterTrend_isNoneWhenAnnualStructureBreaks() {
        var stock = engine.evaluateStock(counterTrendStock(
                new BigDecimal("15.00"), new BigDecimal("0.50"),
                new BigDecimal("19.0"), new BigDecimal("18.0"),
                new BigDecimal("17.0"), new BigDecimal("18.0"),
                TradingRadarRuleEngine.Confirmation.BELOW));

        assertEquals(TradingRadarRuleEngine.CounterTrendState.NONE,
                stock.counterTrend().state());
    }

    @Test
    void bondDoesNotUseEquityRiskOffPenaltyOrBuyGate() {
        var equity = engine.evaluateStock(strongStock(
                true,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF));
        var bond = engine.evaluateStock(strongStock(
                true,
                TradingRadarRuleEngine.InstrumentType.BOND,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF));

        // V15：本 fixture 的 weekly／dailyCandle 皆為 null，五個新因子一律缺值、**不進 sumW**，
        // 故分數變動完全來自既有 18 個因子的權重改配（Task 356.7）。
        // 債券不套大盤因子，可用因子為 MA20 .04／MA60 .06／MA240 .06／KD_J .05／完成日漲跌 .01，
        // Σw=0.22；個股再加上 MARKET .05 × (−1.0)，Σw=0.27。兩者都遠離 50，
        // 可反證新因子沒有被以 0 冒充缺值（若被冒充，sumW 會變大而把分數往 50 拉）。
        //
        // V16（Task 360）：本 fixture 以相容建構式建立、extendedIndicators 為 null，
        // 故 V15 時 KD/J 只有 direction +1.0 與 position (50−50)/50 = 0 兩個分量 → 0.5。
        // 改用 standardJPosition(k, d) 後 J 位置分量恆可得：K=60／D=40 → 標準 J = 3×60 − 2×40 = 100
        // → clampUnit((50−100)/50) = −1.0，三分量平均 (1.0 + 0 − 1.0)/3 = 0。
        // 債券 Σ(w×c) 由 0.183 降為 0.158 → 0.71818 → 86；個股 0.133 → 0.108 → 0.4926→0.4 → 70。
        // 變動方向符合 360.7g 準則（K>D 的標的分數下降）；分量數 2→3 的可得性變化只發生在
        // 這類手寫語料，production 組裝路徑的 j9 與 k／d 本來就同時可得（360.6a）。
        assertEquals(70, equity.score());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, equity.action());
        assertEquals(86, bond.score());
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, bond.action());
        assertTrue(bond.reasons().stream().anyMatch(reason -> reason.contains("資產類別為債券")));
    }

    @Test
    void incompleteEquityMarketVetoesButBondCanStillBeEvaluated() {
        var equity = engine.evaluateStock(strongStock(
                true,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE));
        var bond = engine.evaluateStock(strongStock(
                true,
                TradingRadarRuleEngine.InstrumentType.BOND,
                TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE));

        assertNull(equity.score());
        assertEquals(TradingRadarRuleEngine.Action.NO_TRADE, equity.action());
        // V15：同 bondDoesNotUseEquityRiskOffPenaltyOrBuyGate 的權重改配，由 90 回到 92。
        // V16（Task 360）：同一 fixture 的 KD/J 由 0.5 變 0（K=60>D=40 → 標準 J 位置 −1.0），
        // 92 → 86，與該測試逐位一致。
        assertEquals(86, bond.score());
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, bond.action());
    }


    // ---- Task 217：大盤新鮮度閘門與逆勢止跌基準 ----

    @Test
    void marketStale_blocksBuyCandidateEvenWhenRiskOn() {
        var fresh = engine.evaluateStock(strongStock(
                false, TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, false));
        assertEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, fresh.action());

        var stale = engine.evaluateStock(strongStock(
                false, TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, true));
        assertNotEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, stale.action());
        assertNotEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, stale.action());
    }

    @Test
    void marketStale_dropsRiskOnBonusButKeepsRiskOffPenalty() {
        // 刻意用未觸及 clamp 上限的中性輸入：strongStock 原始分數 121，clamp 後看不出 8 分差。
        int freshScore = engine.evaluateStock(moderateStock(
                TradingRadarRuleEngine.MarketRegime.RISK_ON, false)).score();
        int staleScore = engine.evaluateStock(moderateStock(
                TradingRadarRuleEngine.MarketRegime.RISK_ON, true)).score();
        // V5 的 RISK_ON 貢獻由權重決定，不再是固定 8 分；此處斷言行為（stale 不得加分）而非寫死差值。
        assertTrue(staleScore < freshScore);

        // RISK_OFF 的扣分不因 stale 放寬。
        assertEquals(
                engine.evaluateStock(moderateStock(
                        TradingRadarRuleEngine.MarketRegime.RISK_OFF, false)).score(),
                engine.evaluateStock(moderateStock(
                        TradingRadarRuleEngine.MarketRegime.RISK_OFF, true)).score());
    }

    /** 長線佳＋短線深度超賣的標的。price 100 對 MA240 80 = +25% 乖離，KD 深度超賣且剛黃金交叉。 */
    private TradingRadarRuleEngine.StockInput trialBuyStock(
            String k, String d, String prevK, String prevD,
            String ma240, String completedChange,
            TradingRadarRuleEngine.Confirmation c240,
            TradingRadarRuleEngine.MarketRegime regime, boolean stale) {
        return new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("100"),
                new BigDecimal("0.5"),
                new BigDecimal(completedChange),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("105"), new BigDecimal(ma240),
                        new BigDecimal(k), new BigDecimal(d)),
                new BigDecimal(prevK),
                new BigDecimal(prevD),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                c240,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                regime,
                stale,
                null,
                // Task 264：季線乖離／年線乖離由同一組 price 與均線導出，避免 fixture 與被測邏輯各算一份
                biasOf("100", "105"),
                biasOf("100", ma240),
                null, null, null, null);
    }

    /** 乖離率（%），與 TradingRadarService.biasPercent 同式。 */
    private BigDecimal biasOf(String price, String ma) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal m = new BigDecimal(ma);
        return p.subtract(m).divide(m, 8, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    @Test
    void trialBuy_firesWhenLongTermStrongAndShortTermDeeplyOversoldAndTurning() {
        var input = trialBuyStock("15", "14", "12", "16", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false);
        var result = engine.evaluateStock(input);

        assertEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, result.action(),
                "長線佳＋KD 深度超賣＋剛黃金交叉＋已止跌，應產生分批試單");
        // 關鍵：月線與季線都是 BELOW，既有買進閘門必然關閉——證明這是平行路徑而非放寬閘門。
        assertEquals(TradingRadarRuleEngine.Confirmation.BELOW, input.ma20Confirmation());
        assertEquals(TradingRadarRuleEngine.Confirmation.BELOW, input.ma60Confirmation());
    }

    @Test
    void trialBuy_requiresRealGoldenCross_notMerelyStrongLowKd() {
        // 前一期已經 K>D（持續強勢）→ 不是交叉，不得試單。
        var notCrossing = engine.evaluateStock(trialBuyStock("15", "14", "18", "13", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, notCrossing.action());
    }

    @Test
    void trialBuy_requiresDeepOversold_bothKAndD() {
        // D 未低於 20 → 尚未完整進入低檔。
        var shallow = engine.evaluateStock(trialBuyStock("18", "22", "15", "25", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, shallow.action());
    }

    @Test
    void trialBuy_requiresStoppedFalling_usingCompletedBar() {
        var stillFalling = engine.evaluateStock(trialBuyStock("15", "14", "12", "16", "80", "-1.2",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, stillFalling.action(),
                "最近完成日仍在下跌時不得試單");
    }

    @Test
    void trialBuy_requiresLongTermPremium_notJustAboveAnnualMa() {
        // 貼著年線（+1.5%）：price>MA240 成立但乖離不足，長線結構不夠明確。
        // 這一項是關鍵——實測使用者投組中 price>MA240 幾乎 100% 成立，單靠它沒有篩選力。
        var hugging = engine.evaluateStock(trialBuyStock("15", "14", "12", "16", "98.5", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, hugging.action());
    }

    @Test
    void trialBuy_requiresAnnualConfirmation_rejectsFreshBreakout() {
        var unconfirmed = engine.evaluateStock(trialBuyStock("15", "14", "12", "16", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, unconfirmed.action());
    }

    /**
     * 需求「股市急跌時應建議買入」：Task 264 移除 {@code RISK_OFF} 封鎖，只保留 stale 封鎖。
     *
     * <p>推翻 Task 226 的理由：急跌時 regime 必為 {@code RISK_OFF}，原封鎖使該需求在結構上不可能滿足；
     * 且它與 {@code evaluateCounterTrend()} 既有的「大盤仍為 RISK_OFF，只限小額試單」文案自相矛盾。
     * 安全邊界由年線乖離 ≥ 5% 承擔：大盤急跌＋個股長線完好＝錯殺＝買點。</p>
     */
    @Test
    void trialBuy_isAllowedUnderRiskOffButStillBlockedWhenMarketDataIsStale() {
        var riskOff = engine.evaluateStock(trialBuyStock("15", "14", "12", "16", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF, false));
        assertEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, riskOff.action(),
                "大盤急跌但個股長線結構完好＝錯殺，須可產生分批試單");
        assertTrue(riskOff.risks().stream().anyMatch(r -> r.contains("小額分批")),
                "接刀性質須明確揭露");
        assertTrue(riskOff.risks().stream().anyMatch(r -> r.contains("RISK_OFF")),
                "大盤風險狀態須一併揭露");

        // stale 是「資料不新鮮」的技術狀態、不是市場判斷，看不見大盤真實狀況時仍不得放行。
        var stale = engine.evaluateStock(trialBuyStock("15", "14", "12", "16", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.RISK_ON, true));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, stale.action());
    }

    @Test
    void trialBuy_doesNotWidenTheOrdinaryBuyGate() {
        // 一檔跌破月線季線但 KD 不超賣的標的，不得因為新增了試單路徑就變成可買。
        var notOversold = engine.evaluateStock(trialBuyStock("55", "50", "48", "52", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, notOversold.action());
        assertNotEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, notOversold.action());
        assertNotEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, notOversold.action());
    }

    // ─── V5 新增行為（Requirement 43 修訂／Requirement 47）──────────────────────────

    /** 一檔各項技術面全綠的債券 ETF，只有 KD 過熱的差別。對應 00719B 的實測情境。 */
    private TradingRadarRuleEngine.StockInput bondWithKd(
            String k, String d, java.math.BigDecimal fxPercentile) {
        return new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("31.68"),
                new BigDecimal("0.25"),
                new BigDecimal("0.25"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("31.30"), new BigDecimal("31.01"), new BigDecimal("30.32"),
                        new BigDecimal(k), new BigDecimal(d)),
                new BigDecimal("50"),
                new BigDecimal("50"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.BOND,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                false,
                fxPercentile,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null);
    }

    @Test
    void kdOverheat_vetoesBuyEvenWhenAllTrendSignalsAreGreen() {
        // 00719B 的實測輸入：站上月/季/年線且三個兩日確認皆 ABOVE，但 K=90.76、D=86.01。
        var overheated = engine.evaluateStock(bondWithKd("90.76", "86.01", new BigDecimal("83.6")));
        assertNotEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, overheated.action(),
                "KD 均值 88.4 已達超買區，不得產生買進候選");
        assertTrue(overheated.risks().stream().anyMatch(r -> r.contains("超買區")));

        // 同樣的均線幾何，KD 不過熱時買進閘門應該打開——證明擋下來的是過熱而非別的條件。
        var normal = engine.evaluateStock(bondWithKd("51.85", "49.07", new BigDecimal("83.6")));
        assertEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, normal.action());
        assertTrue(normal.score() > overheated.score(), "過熱標的的分數應低於不過熱者");
    }

    // ── Task 232：過熱補 K 單獨門檻、文案修正與偏熱揭露 ──────────────────────────
    //
    // 共用 fixture 為 strongStockWithKd()：價 120 站上 MA20 110／MA60 100／MA240 90，
    // 三個兩日確認皆 ABOVE，大盤 RISK_ON 非 stale，當日漲跌 1%（不觸發 ±5%），fx 為 null。
    // V11 已重配技術面權重並為個股預留基本面／產業權重；
    // 此舊 fixture 以 NOT_APPLICABLE 輸入驗證技術面硬閘門，缺項由同一 Accumulator 正規化。

    @Test
    void kdHeat_userReportedCase_staysAddCandidateAndIsOnlyMarkedElevated() {
        // 使用者回報的 00882：K 82.3／D 75.2 → avg 78.75 未過 80、K 未過 85。
        // 本任務刻意不改變此案例的動作（門檻取 85 的直接後果），釘住以免日後被誤調為 80。
        // KD/J 位置 (50-78.75)/50=-0.575，併入 direction +1.0 後平均 0.2125。
        // V15（Task 356.7）：可用因子為 MA20 .04／MA60 .06／MA240 .06／KD_J .05／MARKET .05／
        // 完成日漲跌 .01，Σw=0.27、Σ(w×c)=0.193625 → 0.71713 → 86。
        // 五個新因子（日K 棒／週線趨勢／動能／乖離／週K 量能）在本 fixture 全部缺值而不進 sumW。
        //
        // V16（Task 360）：J 位置分量改由 standardJPosition(k, d) 現算且恆可得——
        // 標準 J = 3×82.3 − 2×75.2 = 96.5 → clampUnit((50−96.5)/50) = −0.93，
        // 三分量平均 (1.0 − 0.575 − 0.93)/3 = −0.168333。
        // Σ(w×c) = 0.183 + 0.05×(−0.168333) = 0.1745833 → /0.27 = 0.6466 → 82.33 → 82。
        // K>D 的標的分數下降，符合 360.7g 準則；動作維持 ADD_CANDIDATE 是本測試釘住的重點。
        var held = engine.evaluateStock(strongStockWithKd(true, "82.3", "75.2"));

        assertEquals(82, held.score(), "V16 J 極性修正後為 82 分");
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, held.action());
        assertEquals(TradingRadarRuleEngine.KdHeat.ELEVATED, held.kdHeat());
    }

    @Test
    void kdHeat_kAloneOverheated_closesBuyGateWithoutDeductingScore() {
        // K=86／D=70 → avg 78 未過 80，僅 K 過 85。KD/J 位置 (50-78)/50=-0.56，
        // 併入 direction +1.0 後平均 0.22；V15 權重下 Σ(w×c)/Σw=0.194/0.27=0.71852
        // → 86（Task 356.7）。
        //
        // V16（Task 360）：標準 J = 3×86 − 2×70 = 118 → clampUnit((50−118)/50) 飽和為 −1.0，
        // 三分量平均 (1.0 − 0.56 − 1.0)/3 = −0.186667；
        // Σ(w×c) = 0.183 + 0.05×(−0.186667) = 0.1736667 → /0.27 = 0.64321 → 82.16 → 82。
        // 分數仍 ≥ 75，證明降級仍來自閘門而非扣分（本測試的核心不變式未受影響）。
        var held = engine.evaluateStock(strongStockWithKd(true, "86", "70"));
        var notHeld = engine.evaluateStock(strongStockWithKd(false, "86", "70"));

        assertEquals(82, held.score(), "過熱硬閘門不在 KD/J 因子之外再重複扣分");
        assertEquals(TradingRadarRuleEngine.KdHeat.OVERHEATED, held.kdHeat());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, held.action());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, notHeld.action());
    }

    @Test
    void kdHeat_overheatRiskText_dropsPullbackProbabilityClaim() {
        // 十年回測顯示過熱組 20 日內跌逾 10% 的比例（11.1%）低於正常放行組（15.9%），
        // 故不得再宣稱「回檔機率升高」——系統不對使用者陳述自身資料不支持的因果。
        var overheated = engine.evaluateStock(strongStockWithKd(true, "86", "70"));

        assertTrue(overheated.risks().stream().noneMatch(r -> r.contains("回檔機率")),
                "過熱文案不得宣稱回檔機率升高");
        assertTrue(overheated.risks().stream().anyMatch(r -> r.contains("過熱區")));
    }

    @Test
    void kdHeat_avgAndKBothOverheated_emitSingleRiskLine() {
        var both = engine.evaluateStock(strongStockWithKd(true, "90", "86"));

        assertEquals(TradingRadarRuleEngine.KdHeat.OVERHEATED, both.kdHeat());
        assertEquals(1, both.risks().stream().filter(r -> r.contains("本日不列入買進／加碼候選")).count(),
                "avg 與 K 同時過熱時只能輸出一條過熱文案");
    }

    @Test
    void kdHeat_elevatedByAvgOnly_doesNotClaimKIsHigh() {
        // K=70／D=75 → avg 72.5 觸發偏熱，但 K 並未偏高：不得輸出「K 值 …」的假陳述。
        var elevated = engine.evaluateStock(strongStockWithKd(true, "70", "75"));

        assertEquals(TradingRadarRuleEngine.KdHeat.ELEVATED, elevated.kdHeat());
        assertTrue(elevated.risks().stream().anyMatch(r -> r.contains("KD 均值 73 偏高")));
        assertTrue(elevated.risks().stream().noneMatch(r -> r.contains("K 值")),
                "僅均值偏熱時不得述 K 值");
    }

    @Test
    void kdHeat_riskText_neverQuotesThresholdNumber() {
        // 條件為嚴格大於，而 K=85.02 顯示為 85.0；若把門檻寫進句子會變成
        // 「K 值 85.0 已高於 85」這種自我否定的陳述。
        var justOverheated = engine.evaluateStock(strongStockWithKd(true, "85.02", "60"));
        var justElevated = engine.evaluateStock(strongStockWithKd(true, "80.02", "60"));

        assertEquals(TradingRadarRuleEngine.KdHeat.OVERHEATED, justOverheated.kdHeat());
        assertEquals(TradingRadarRuleEngine.KdHeat.ELEVATED, justElevated.kdHeat());
        for (var r : justOverheated.risks()) {
            assertTrue(!r.contains("高於 85") && !r.contains("高於 80"), "文案不得引述門檻數字：" + r);
        }
        for (var r : justElevated.risks()) {
            assertTrue(!r.contains("高於 85") && !r.contains("高於 80"), "文案不得引述門檻數字：" + r);
        }
    }

    @Test
    void kdHeat_isNormalNotNull_whenIndicatorsAreIncomplete() {
        // K／D 為 null 會落入既有的資料不足分支；前端只讀三態字串，不得給 null。
        var incomplete = engine.evaluateStock(new TradingRadarRuleEngine.StockInput(
                true, new BigDecimal("120"), new BigDecimal("1"), new BigDecimal("1"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"), null, null),
                null, null,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false, null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null));

        assertEquals(TradingRadarRuleEngine.Action.NO_TRADE, incomplete.action());
        assertEquals(TradingRadarRuleEngine.KdHeat.NORMAL, incomplete.kdHeat());
    }

    /**
     * Task 360 升為 V16（360.7e）：KD／J（日線）與週線動能（週線）的 J 位置分量改由標準 J
     * （{@code 3K − 2D}）現算，不再餵入本專案為對齊券商畫面而採的顯示慣例 {@code j9 = 3D − 2K}。
     * 權重、門檻與因子組成完全未動，但個股 {@code score}／{@code action} 有實質變化。
     *
     * <p>升版判準沿用 Task 263（V8）與更早的 Task 228（V6）／Task 232（V7）——因子組成、權重、
     * 正規化未動，但使用者可觀察行為有實質變化即升版。</p>
     */
    @Test
    void ruleVersion_isV18() {
        assertEquals("TW_RULES_V20", TradingRadarRuleEngine.RULE_VERSION);
    }

    // ═══ Task 360：J 值因子極性修正（standardJPosition）═══
    //
    // 本專案的 j9 = 3D − 2K 是對齊券商畫面的顯示慣例，方向與坊間標準 J = 3K − 2D 相反。
    // Task 291（日線）與 Task 356.6b（週線）都把 j9 直接餵進語意為「低檔為正」的
    // clampUnit((50 − value)/50)，導致該分量在上漲（K>D）時加分、下跌時扣分，
    // 與其自述用途及 Requirement 43／59「不追高殺低」完全相反。
    //
    // kdJContribution()／weeklyMomentumContribution() 都是 private 實例方法且分量值不外露，
    // 故此處只測純函數 standardJPosition；「兩條路徑皆已改」由
    // TradingRadarThreeHorizonEngineTest 以分數方向驗證（360.7d）。

    /** 360.7a：缺值、動能為零、clamp 飽和三種基本性質。 */
    @Test
    void standardJPosition_basicProperties() {
        // 任一輸入缺值即回 null（可得性與既有 j9 路徑等價：j9 與 k／d 同出一個 KdPoint）。
        assertNull(TradingRadarRuleEngine.standardJPosition(null, new BigDecimal("50")));
        assertNull(TradingRadarRuleEngine.standardJPosition(new BigDecimal("50"), null));
        assertNull(TradingRadarRuleEngine.standardJPosition(null, null));

        // K == D（動能項 m = K − D 為零）時退化為純位置項 clampUnit((50 − K)/50)。
        assertEquals(0.0, TradingRadarRuleEngine.standardJPosition(
                new BigDecimal("50"), new BigDecimal("50")), 1e-9);
        assertEquals(0.6, TradingRadarRuleEngine.standardJPosition(
                new BigDecimal("20"), new BigDecimal("20")), 1e-9);
        assertEquals(-0.6, TradingRadarRuleEngine.standardJPosition(
                new BigDecimal("80"), new BigDecimal("80")), 1e-9);

        // clamp 飽和：K=100,D=0 → J=300 → (50−300)/50 = −5 → −1.0。
        assertEquals(-1.0, TradingRadarRuleEngine.standardJPosition(
                new BigDecimal("100"), new BigDecimal("0")), 1e-9);
        // K=0,D=100 → J=−200 → (50+200)/50 = +5 → +1.0。
        assertEquals(1.0, TradingRadarRuleEngine.standardJPosition(
                new BigDecimal("0"), new BigDecimal("100")), 1e-9);
    }

    /**
     * 360.7b：極性回歸測試（本任務核心防迴歸點）。
     *
     * <p>兩組皆為 2026-08-23 對執行中 stack 的實測值。誤寫回 {@code 3D − 2K}（或
     * {@code (j9 − 50)/50}）時本測試必紅。</p>
     */
    @Test
    void standardJPosition_polarity_deepOversoldPositive_strongMomentumNegative() {
        // NVDA 實測 K=23.7、D=45.5：KD 均值 34.6 屬明確超賣且 K<D（動能轉弱）→ 應為正（有利承接）。
        // 誤用 j9 = 3D − 2K = 89.1 時得 (50−89.1)/50 = −0.78，深度超賣反被當超買扣分。
        Double oversold = TradingRadarRuleEngine.standardJPosition(
                BigDecimal.valueOf(23.7), BigDecimal.valueOf(45.5));
        assertNotNull(oversold);
        assertTrue(oversold > 0,
                "深度超賣（K=23.7 < D=45.5）的 J 位置分量必須為正，實際為 " + oversold);

        // 00882 實測 K=62.0、D=44.5：KD 均值 53.3 中性偏高且 K>D（動能強勢）→ 應為負（不鼓勵追價）。
        // 誤用 j9 = 3D − 2K = 9.4 時得 (50−9.4)/50 = +0.81，強勢追漲反拿到承接滿分。
        Double strong = TradingRadarRuleEngine.standardJPosition(
                BigDecimal.valueOf(62.0), BigDecimal.valueOf(44.5));
        assertNotNull(strong);
        assertTrue(strong < 0,
                "動能強勢（K=62.0 > D=44.5）的 J 位置分量必須為負，實際為 " + strong);
    }

    /**
     * 360.7c：代數等價斷言。令 {@code avg = (K+D)/2}、{@code m = K − D}，
     * 則 {@code 3K − 2D = avg + 2.5m}，代入 {@code (50 − J)/50} 得
     * {@code (50 − avg)/50 − m/20}——位置項與 j9 版相同，只有動能項反號。
     */
    @Test
    void standardJPosition_matchesPositionMinusMomentumDecomposition() {
        double[][] cases = {{40, 45}, {55, 50}, {48, 52}};
        double[] expected = {0.4, -0.3, 0.2};
        for (int i = 0; i < cases.length; i++) {
            double k = cases[i][0];
            double d = cases[i][1];
            double decomposed = (50.0 - (k + d) / 2.0) / 50.0 - (k - d) / 20.0;
            Double actual = TradingRadarRuleEngine.standardJPosition(
                    BigDecimal.valueOf(k), BigDecimal.valueOf(d));
            assertNotNull(actual);
            assertEquals(expected[i], decomposed, 1e-9,
                    "分解式自身的預期值 K=" + k + " D=" + d);
            assertEquals(decomposed, actual, 1e-9, "K=" + k + " D=" + d);
        }
    }

    // ═══ Task 342：跨市場「不適用」與「資料不足」分家、量價文案只列舉實際採用的分量 ═══

    /** 跨市場那則提醒的專屬片段；不得用泛用詞「資料不足」——量價那句也含這四字。 */
    private static final String CROSS_MARKET_FRAGMENT = "美股科技共同交易日";

    /**
     * 342.10.2：{@code crossMarketApplicable=false}（美股大盤即 IXIC，跨市場因子會重複計分）
     * 必須<b>沉默</b>；但旗標為 {@code true} 而資料真的缺（台股確實會遇到，美股科技共同完成日
     * 有 5 個日曆日上限）時<b>仍要提醒</b>。這是台股方向的護欄，防止日後被順手兩邊都關掉。
     */
    @Test
    void 跨市場不適用時沉默但適用而資料缺時仍提醒() {
        var notApplicable = engine.evaluateMarket(
                crossMarketInput(false, false)).risks();
        var applicableButMissing = engine.evaluateMarket(
                crossMarketInput(true, false)).risks();

        assertTrue(notApplicable.stream().noneMatch(r -> r.contains(CROSS_MARKET_FRAGMENT)),
                "跨市場因子對美股是『不適用』，不得謊報成『資料不足』");
        assertTrue(applicableButMissing.stream().anyMatch(r -> r.contains(CROSS_MARKET_FRAGMENT)),
                "台股的跨市場資料真的缺時，這則正當提醒不得一起被關掉");
    }

    /**
     * 342.10.4：量價文案只能列舉本次 {@code marketActivity} 實際由哪幾個 ratio 構成。
     * 美股結構性沒有成交金額（{@code us_index_daily_history} 無成交值欄），台股也可能因
     * 樣本不足而 turnover 為 null——寫死「量能／成交金額」等於在講一個不存在的值。
     */
    @Test
    void 量價理由只列舉實際採用的分量() {
        var volumeOnly = engine.evaluateMarket(volumeActivityInput(
                new BigDecimal("1.5"), null)).reasons();
        assertTrue(volumeOnly.stream().anyMatch(r -> r.contains("大盤完成日上漲且量能同步放大")),
                "僅量比可用時應內插「量能」");
        assertTrue(volumeOnly.stream().noneMatch(r -> r.contains("成交金額")),
                "marketTurnoverRatio 為 null 時，理由文案不得出現「成交金額」字樣");

        var bothRatios = engine.evaluateMarket(volumeActivityInput(
                new BigDecimal("1.5"), new BigDecimal("1.5"))).reasons();
        assertTrue(bothRatios.stream().anyMatch(r -> r.contains("大盤完成日上漲且量能與成交金額同步放大")),
                "兩個 ratio 都可用時才列舉兩者");
    }

    /** 342.3：量價缺值那句不得再列舉美股結構性不存在的「成交金額」欄位。 */
    @Test
    void 量價缺值提醒不列舉不存在的欄位() {
        var risks = engine.evaluateMarket(volumeActivityInput(null, null)).risks();
        assertTrue(risks.stream().anyMatch(r -> r.contains("大盤完成日量價資料不足")));
        assertTrue(risks.stream().noneMatch(r -> r.contains("成交量或成交金額")),
                "列舉「成交金額」等於暗示美股本來該有卻沒有");
    }

    /** V10 前的 5 參數相容建構式必須把 crossMarketApplicable 填 true（漏改＝多一則正當提醒）。 */
    @Test
    void 相容建構式的跨市場旗標預設為適用() {
        var compat = new TradingRadarRuleEngine.MarketInput(
                new BigDecimal("100"), BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("50"), new BigDecimal("50")),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE);

        assertTrue(compat.crossMarketApplicable());
        assertTrue(engine.evaluateMarket(compat).risks().stream()
                        .anyMatch(r -> r.contains(CROSS_MARKET_FRAGMENT)),
                "漏改呼叫端的後果必須是多一則正當提醒，而不是靜默吞掉一則真提醒");
    }

    private TradingRadarRuleEngine.MarketInput crossMarketInput(
            boolean crossMarketApplicable, boolean usTechAvailable) {
        return fullMarketInput(null, null, null, null, usTechAvailable, crossMarketApplicable);
    }

    private TradingRadarRuleEngine.MarketInput volumeActivityInput(
            BigDecimal volumeRatio, BigDecimal turnoverRatio) {
        // completedChangePercent 為正 → 命中「上漲且…放大／不足」那兩個分支。
        return fullMarketInput(new BigDecimal("1.20"), volumeRatio, turnoverRatio,
                null, false, false);
    }

    private TradingRadarRuleEngine.MarketInput fullMarketInput(
            BigDecimal completedChangePercent,
            BigDecimal volumeRatio,
            BigDecimal turnoverRatio,
            BigDecimal usTechCompositePercent,
            boolean usTechAvailable,
            boolean crossMarketApplicable) {
        return new TradingRadarRuleEngine.MarketInput(
                new BigDecimal("100"), BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("50"), new BigDecimal("50")),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                completedChangePercent, volumeRatio, turnoverRatio,
                null, null, usTechCompositePercent, usTechAvailable, crossMarketApplicable);
    }

    // ═══ Task 264：使用者四條需求的驗收測試 ═══════════════════════════════════

    /**
     * 需求 6「高點下殺風險高時應建議賣出」。
     *
     * <p>V8 沒有任何在高檔主動建議賣出的路徑——KD 過熱只關買進閘門、不扣分，動作停在 HOLD。</p>
     */
    @Test
    void requirement_kdDeadCrossAloneMustNotProduceReduceAtOverbought() {
        // K=88／D=90：avg=89 > 80 故過熱；本期 k <= d、前期 K>D ⇒ 高檔死叉成立。
        var held = engine.evaluateStock(v9Stock(true, "88", "90", "92", "90", "25", "0.60"));
        var notHeld = engine.evaluateStock(v9Stock(false, "88", "90", "92", "90", "25", "0.60"));

        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT, held.timingState());
        assertNotEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, held.action(),
                "t273 已否定 KD 死叉單一賣出假設，V11 至少還需 MACD 或下跌爆量一項");
        assertNotEquals(TradingRadarRuleEngine.Action.AVOID, notHeld.action());
        assertTrue(!held.profitTakingConfirmed());
    }

    @Test
    void requirement_profitTakingNeedsTwoIndependentWeakeningSignals() {
        var heldInput = withExtended(
                v9Stock(true, "88", "90", "92", "90", "25", "0.60"),
                extended("0", "0", "0", "-1"), null);
        var freeInput = withExtended(
                v9Stock(false, "88", "90", "92", "90", "25", "0.60"),
                extended("0", "0", "0", "-1"), null);

        var held = engine.evaluateStock(heldInput);
        var notHeld = engine.evaluateStock(freeInput);
        assertTrue(held.profitTakingConfirmed());
        assertEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, held.action());
        assertEquals(TradingRadarRuleEngine.Action.AVOID, notHeld.action());
        assertTrue(held.risks().stream().anyMatch(r -> r.contains("不預測後續漲跌")));
    }

    /** 對照組：同樣極端超買但**未**轉弱（前期 K 已在 D 之下）→ 不得減碼，讓利潤奔跑。 */
    @Test
    void requirement_overboughtWithoutDeadCrossMustNotProduceReduce() {
        var held = engine.evaluateStock(v9Stock(true, "88", "90", "88", "90", "25", "0.60"));

        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT, held.timingState());
        assertNotEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, held.action(),
                "只要超買就出場會在主升段初期砍掉部位，與『獲利最大化』衝突");
        assertEquals(TradingRadarRuleEngine.Action.HOLD, held.action());
    }

    /**
     * 需求 3「不該殺低」。
     *
     * <p>V8 在此情境下分數落入出場區即直接輸出 EXIT_CANDIDATE，即在最低點建議賣出。</p>
     */
    @Test
    void requirement_extremeOversoldMustNotProduceExit() {
        var held = engine.evaluateStock(v9CrashStock(true, "0.20"));
        var notHeld = engine.evaluateStock(v9CrashStock(false, "0.20"));

        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD, held.timingState());
        assertTrue(held.score() < 40, "分數誠實反映弱勢（實算落在減碼／出場區），不因保護而灌水");
        assertEquals(TradingRadarRuleEngine.Action.HOLD_CAUTION, held.action(),
                "極端超賣時須阻擋出場，對稱於買方的『超買否決買進』");
        assertEquals(TradingRadarRuleEngine.Action.WAIT, notHeld.action());
        assertTrue(held.reasons().stream().anyMatch(r -> r.contains("不建議追殺出場")));
    }

    /** t273 顯示長期結構破壞組仍顯著反彈，V11 不再取消極端超賣保護。 */
    @Test
    void requirement_brokenLongTermStructureStillReceivesExtremeOversoldProtection() {
        var held = engine.evaluateStock(v9CrashStock(true, "0.05"));

        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD, held.timingState());
        assertEquals(TradingRadarRuleEngine.Action.HOLD_CAUTION, held.action());
        assertTrue(held.risks().stream().anyMatch(r -> r.contains("長期結構仍偏弱")));
    }

    /** 需求「不追高」：季線乖離因子讓過度拉伸的標的分數自然下降，V8 的二元 ±1 量不到「貴」。 */
    @Test
    void requirement_extensionFactorPenalisesStretchedPrice() {
        var base = v9Stock(true, "60", "58", "55", "54", "0", "0.60");
        var stretched = engine.evaluateStock(withExtended(base, extended("10", "20", "8", "1"), null));
        var nearMa = engine.evaluateStock(withExtended(base, extended("0", "0", "0", "1"), null));

        assertTrue(stretched.score() < nearMa.score(),
                "同樣站上均線，乖離 +25% 的分數必須低於貼著季線者（V8 兩者完全相同）");
    }

    /** 窄幅 KD 失效：債券 ETF 的 KD 在雜訊上飽和，不得因此誤發減碼或試單。 */
    @Test
    void narrowKdBandDisablesKdDrivenOverridesEntirely() {
        var narrow = engine.evaluateStock(v9StockWithBand(true, "88", "90", "92", "90", "25", "1.0"));

        assertEquals(TradingRadarRuleEngine.KdHeat.NORMAL, narrow.kdHeat(),
                "窄幅時不得標為過熱");
        assertNotEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT, narrow.timingState(),
                "窄幅時 TimingState 不得由 KD 判定");
        assertNotEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, narrow.action());
        assertTrue(narrow.risks().stream().anyMatch(r -> r.contains("高低帶過窄")));
    }

    /** 權重總和以斷言釘住，不靠人工加總（298.3 的 18 因子權重表機械檢查）。 */
    @Test
    void weightsSumToExactlyOne() {
        assertEquals(1.0, TradingRadarRuleEngine.SHORT_WEIGHT_SUM, 1e-9);
        assertEquals(1.0, TradingRadarRuleEngine.MEDIUM_WEIGHT_SUM, 1e-9);
    }

    // ═══ Task 298：BIAS／KD-J(W%R)／OSC 因子驗證 ═══════════════════════════════
    //
    // 以下測試用 factorIsolationStock 系列 fixture 中性化除受測因子外的所有計分項
    // （MA20/60/240、KD/J、MACD、RSI、BIAS、大盤、完成日漲跌皆貢獻恰為 0，仍佔權重分母，
    // 中期權重合計 Σw=0.52），使受測因子造成的分數差異可被精確手算並釘住，不受其他任務
    // 調整週邊因子時的分數漂移拖累。

    @Test
    void biasContribution_averagesOnlyBias10AndBias20_ignoringB10b20() {
        // bias10=+5 → clampUnit(-5/10)=-0.5；bias20=+5 → clampUnit(-5/20)=-0.25；均值 -0.375。
        // Σ(w×c)=MW_BIAS×(-0.375)=0.08×(-0.375)=-0.03，Σw=0.52 → sigma=-0.057692
        // → 50-2.8846=47.1154 → 47（Task 298）。
        var result = engine.evaluateStock(biasIsolationStock("5", "5", "1"));
        var differentB10b20 = engine.evaluateStock(biasIsolationStock("5", "5", "-40"));

        assertEquals(47, result.score(), "貢獻應等於只平均 bias10／bias20 兩分量");
        assertEquals(result.score(), differentB10b20.score(),
                "b10b20 為代數相依值，不得影響 BIAS 因子貢獻");
    }

    @Test
    void kdJContribution_wr9PullsPositionAndFallsBackToThreeComponentAverageWhenNull() {
        // k=60,d=40 → direction=+1.0、KD 均值 50 → position=(50-50)/50=0。
        var noWr = engine.evaluateStock(kdJIsolationStock("60", "40", null, null, "8.0"));
        var highWr = engine.evaluateStock(kdJIsolationStock("60", "40", null, "90", "8.0"));
        var lowWr = engine.evaluateStock(kdJIsolationStock("60", "40", null, "10", "8.0"));
        var withJ9NoWr = engine.evaluateStock(kdJIsolationStock("60", "40", "50", null, "8.0"));

        // V16（Task 360）：J 位置分量改由 standardJPosition(k, d) 現算且恆可得——
        // 標準 J = 3×60 − 2×40 = 100 → clampUnit((50−100)/50) 飽和為 −1.0（下列四臂皆同）。
        //
        // wr9=null → averageAvailable(1.0, 0.0, -1.0)=0.0 → Σ(w×c)=0 → sigma=0 → 50。
        // （V15 時 j9 缺值使該分量整個缺值，avg(1.0,0.0)=0.5 → 53。）
        assertEquals(50, noWr.score());
        // wr9=90（低檔）→ wrPosition=+0.8，併入平均後 avg(1.0,0.0,-1.0,0.8)=0.2，應把貢獻往正向拉。
        assertEquals(51, highWr.score(), "W%R 低檔（貢獻為正）應把 KD/J 貢獻往正向拉");
        assertTrue(highWr.score() > noWr.score());
        // wr9=10（高檔）→ wrPosition=-0.8，併入平均後 avg(1.0,0.0,-1.0,-0.8)=-0.2，應把貢獻往負向拉。
        assertEquals(49, lowWr.score(), "W%R 高檔（貢獻為負）應把 KD/J 貢獻往負向拉");
        assertTrue(lowWr.score() < noWr.score());
        // j9=50、wr9=null → j9 自 Task 360 起完全不進評分鏈，故與 noWr 逐位相同（V15 時為 52）。
        // 這一臂自此改為「j9 已退出評分鏈」的守護，不再是「wr9 缺值時退回三分量平均」。
        assertEquals(noWr.score(), withJ9NoWr.score(),
                "j9 不得再影響 KD/J 因子（Task 360：改讀 k／d 現算的標準 J）");
        assertEquals(50, withJ9NoWr.score());
    }

    @Test
    void kdJContribution_isNullEntirelyWhenKdBandIsNarrow_regardlessOfWr9() {
        // 窄幅時 kdJContribution 整體不計，不論 K/D/W%R 多極端，分數都應相同。
        var narrowHighWr = engine.evaluateStock(kdJIsolationStock("90", "10", null, "90", "1.0"));
        var narrowLowWr = engine.evaluateStock(kdJIsolationStock("10", "90", null, "10", "1.0"));

        assertEquals(50, narrowHighWr.score());
        assertEquals(narrowHighWr.score(), narrowLowWr.score(),
                "窄幅時 KD/J（含 W%R）整因子應為 null，不受任何 K/D/W%R 值影響");
    }

    @Test
    void oscContribution_normalizesByPriceAmplitudeInsteadOfHardSign() {
        // Task 298：oscPct = osc/price×100，貢獻 = clampUnit(oscPct / OSC_FULL_SCALE_PCT(0.5))。
        var noOsc = engine.evaluateStock(oscIsolationStock(null));            // MACD 因子缺值基準
        var tinyPositive = engine.evaluateStock(oscIsolationStock("0.01"));   // 0.01% → 貢獻 0.02
        var saturatedPositive = engine.evaluateStock(oscIsolationStock("1")); // 1% → 貢獻 +1（飽和）
        var evenLargerPositive = engine.evaluateStock(oscIsolationStock("5")); // 5% → 同樣飽和 +1
        var saturatedNegative = engine.evaluateStock(oscIsolationStock("-0.6")); // -0.6% → 貢獻 -1（飽和）

        // 貢獻 0.02 太小，不足以移動四捨五入後的整數分數——在此精度下與「無 OSC 訊號」無法區分，
        // 證明不是舊版 signum(osc) 的硬 +1（若是硬 +1，分數會等於 saturatedPositive）。
        assertEquals(noOsc.score(), tinyPositive.score(), "0.01% 的 OSC 貢獻應趨近 0");
        assertNotEquals(saturatedPositive.score(), tinyPositive.score(),
                "微幅 OSC 不得表現得像舊版硬翻轉的 +1 飽和值");

        assertEquals(55, saturatedPositive.score(), "OSC=1%（達 0.5% 全幅）應飽和為 +1");
        assertEquals(saturatedPositive.score(), evenLargerPositive.score(), "超過全幅後應維持飽和，不再放大");
        assertEquals(45, saturatedNegative.score(), "OSC=-0.6%（超過 0.5% 全幅）應飽和為 -1");
    }

    @Test
    void oscContribution_belowNarrativeThreshold_omitsDirectionalText() {
        // 0.05% < OSC_NARRATIVE_PCT(0.1%)：貢獻仍計入分數，但不得輸出動能文案（Task 298）。
        var result = engine.evaluateStock(oscIsolationStock("0.05"));

        assertTrue(result.reasons().stream().noneMatch(r -> r.contains("MACD 柱狀體 OSC 為正")));
        assertTrue(result.risks().stream().noneMatch(r -> r.contains("MACD 柱狀體 OSC 為負")));
    }

    // ── Task 298 測試用 fixture ────────────────────────────────────────────────
    //
    // 三者共用同一組中性化底盤：price=ma20=ma60=ma240=100（位置貢獻 0）、三項確認皆 MIXED
    // （確認貢獻 0）、regime=NEUTRAL（大盤貢獻 0）、completedChangePercent=0（完成日貢獻 0）、
    // RSI5／RSI10 皆 50（貢獻 0）、weeklyMa／fx／ETF／基本面缺值或 NOT_APPLICABLE（整項排除，
    // 不佔權重分母）。中期有效權重合計恆為 Σw=0.52（MA20+MA60+MA240+KD_J+MACD+RSI+BIAS+MARKET
    // +DAY_MOVE = 0.05+0.08+0.07+0.07+0.05+0.04+0.08+0.06+0.02），僅受測因子的分量會變動。

    /** 中性化 BIAS 以外所有計分因子：KD=50/50（J9／W%R 缺值）、OSC=0。 */
    private TradingRadarRuleEngine.StockInput biasIsolationStock(
            String bias10, String bias20, String b10b20) {
        return new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("50"), new BigDecimal("50")),
                new BigDecimal("50"), new BigDecimal("50"),
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                false,
                null,
                null, null, null, null,
                new BigDecimal("8.0"), null, null,
                null,
                new TradingRadarRuleEngine.ExtendedIndicators(
                        null, null, null,
                        null, null, null, null, BigDecimal.ZERO,
                        new BigDecimal("50"), new BigDecimal("50"),
                        new BigDecimal(bias10), new BigDecimal(bias20), new BigDecimal(b10b20),
                        null),
                null,
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    /** 中性化 KD/J（含 W%R）以外所有計分因子：BIAS 兩分量皆 0、OSC=0。band 為 9 日高低帶寬度（%）。 */
    private TradingRadarRuleEngine.StockInput kdJIsolationStock(
            String k, String d, String j9, String wr9, String band) {
        return new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal(k), new BigDecimal(d)),
                new BigDecimal("50"), new BigDecimal("50"),
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                false,
                null,
                null, null, null, null,
                new BigDecimal(band), null, null,
                null,
                new TradingRadarRuleEngine.ExtendedIndicators(
                        j9 == null ? null : new BigDecimal(j9), null, null,
                        null, null, null, null, BigDecimal.ZERO,
                        new BigDecimal("50"), new BigDecimal("50"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        wr9 == null ? null : new BigDecimal(wr9)),
                null,
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    /** 中性化 OSC 以外所有計分因子：KD=50/50（J9／W%R 缺值）、BIAS 兩分量皆 0，price 固定 100。 */
    private TradingRadarRuleEngine.StockInput oscIsolationStock(String osc) {
        return new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("50"), new BigDecimal("50")),
                new BigDecimal("50"), new BigDecimal("50"),
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                false,
                null,
                null, null, null, null,
                new BigDecimal("8.0"), null, null,
                null,
                new TradingRadarRuleEngine.ExtendedIndicators(
                        null, null, null,
                        null, null, null, null, osc == null ? null : new BigDecimal(osc),
                        new BigDecimal("50"), new BigDecimal("50"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        null),
                null,
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);
    }

    @Test
    void heldFlagDoesNotChangeEitherHorizonScore() {
        var held = engine.evaluateStock(strongStock(true, TradingRadarRuleEngine.MarketRegime.RISK_ON));
        var watched = engine.evaluateStock(strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_ON));
        assertEquals(held.score(), watched.score());
        assertEquals(held.shortScore(), watched.shortScore());
    }

    @Test
    void positiveFundamentalsLiftBothHorizonsAndMatterMoreToMediumTerm() {
        var base = strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_ON);
        var neutral = engine.evaluateStock(withFundamental(base,
                new TradingRadarRuleEngine.FundamentalInput(true, 0.0, 0.0, 0.0, 0.0, 0.0, false)));
        var positive = engine.evaluateStock(withFundamental(base,
                new TradingRadarRuleEngine.FundamentalInput(true, 1.0, 1.0, 1.0, 1.0, 1.0, false)));

        assertTrue(positive.shortScore() > neutral.shortScore());
        assertTrue(positive.score() > neutral.score());
        assertTrue(positive.score() - neutral.score() > positive.shortScore() - neutral.shortScore(),
                "中期基本面＋產業權重 36% 必須高於短期 20%");
    }

    @Test
    void multipleFundamentalDeteriorationBlocksBuyButDoesNotForceSell() {
        var deteriorating = new TradingRadarRuleEngine.FundamentalInput(
                true, -1.0, -0.9, 0.2, 0.1, 0.0, false);
        var watched = engine.evaluateStock(withFundamental(
                strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_ON), deteriorating));
        var held = engine.evaluateStock(withFundamental(
                strongStock(true, TradingRadarRuleEngine.MarketRegime.RISK_ON), deteriorating));

        assertNotEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, watched.action());
        assertNotEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, held.action());
        assertFalse(List.of(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE,
                TradingRadarRuleEngine.Action.EXIT_CANDIDATE).contains(held.action()),
                "多項惡化是買方閘門，不得直接製造賣出");
        assertTrue(held.risks().stream().anyMatch(v -> v.contains("基本面多項惡化")));
    }

    @Test
    void peLossPlusAnotherWeakFactorBlocksTrialBuy() {
        var base = trialBuyStock("15", "14", "12", "16", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF, false);
        var result = engine.evaluateStock(withFundamental(base,
                new TradingRadarRuleEngine.FundamentalInput(true, -0.6, 0.2, 0.1, -1.0, 0.0, true)));

        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, result.action());
        assertEquals(TradingRadarRuleEngine.CounterTrendState.OVERSOLD_WATCH, result.counterTrend().state());
        assertTrue(result.counterTrend().risks().stream().anyMatch(v -> v.contains("基本面多項惡化")));
    }

    /**
     * Task 365：非虧損分支下，估值 composite（PE／PB／殖利率算術平均）的 contribution 達 ±0.5 門檻時，
     * reasons／risks 文字須以「估值（PE／PB／殖利率）」開頭，不得再出現舊字面「PE 自身分位」——
     * 該 composite 本就是三者平均，不是 PE 自身分位。
     */
    @Test
    void valuationCompositeReasonUsesCompositeLabelNotPeLabel() {
        var base = strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_ON);
        var positive = engine.evaluateStock(withFundamental(base,
                new TradingRadarRuleEngine.FundamentalInput(true, 0.0, 0.0, 0.0, 1.0, 0.0, false)));

        assertTrue(positive.reasons().stream().anyMatch(r -> r.startsWith("估值（PE／PB／殖利率）")),
                "peContribution ≥ 0.5 且非可信虧損時，reasons 須含複合標籤文字");
        assertTrue(positive.reasons().stream().noneMatch(r -> r.contains("PE 自身分位")),
                "不得殘留舊字面「PE 自身分位」");
        assertTrue(positive.risks().stream().noneMatch(r -> r.contains("PE 自身分位")));
    }

    /**
     * Task 365：{@code peLoss()} 為真的可信虧損分支標籤「PE（可信來源顯示虧損）」維持不變、
     * 不得套用估值 composite 的複合標籤（理由：{@code contribution} 寫死 -1.0，不論 PB／殖利率
     * 是否可得都不參與平均，語意上就是 PE 自身的可信虧損判定）。
     */
    @Test
    void peLossRiskKeepsLossLabelUnchanged() {
        var base = strongStock(false, TradingRadarRuleEngine.MarketRegime.RISK_ON);
        var lossy = engine.evaluateStock(withFundamental(base,
                new TradingRadarRuleEngine.FundamentalInput(true, 0.0, 0.0, 0.0, -1.0, 0.0, true)));

        assertTrue(lossy.risks().stream().anyMatch(r -> r.startsWith("PE（可信來源顯示虧損）")),
                "peLoss() 為真且 contribution ≤ -0.5 時，risks 須維持原「PE（可信來源顯示虧損）」標籤");
        assertTrue(lossy.risks().stream().noneMatch(r -> r.contains("PE 自身分位")));
    }

    @Test
    void etfNotApplicableRedistributesAllFundamentalWeights() {
        var result = engine.evaluateStock(withFundamental(
                strongStock(false, TradingRadarRuleEngine.InstrumentType.BOND,
                        TradingRadarRuleEngine.MarketRegime.NEUTRAL),
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE));
        assertTrue(result.reasons().stream().noneMatch(v -> v.contains("基本面")));
        assertTrue(result.risks().stream().noneMatch(v -> v.contains("基本面")));
    }

    /** ETF 溢價 ≥ 3% 硬否決買進，且與自身歷史分位無關。 */
    @Test
    void etfPremiumAboveAbsoluteThresholdVetoesBuy() {
        var expensive = engine.evaluateStock(v9EtfStock(true, "3.5", "50"));
        var cheap = engine.evaluateStock(v9EtfStock(true, "0.2", "50"));

        assertNotEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, expensive.action(),
                "溢價 3% 就是為同一籃資產多付 3%，即使分位只是中位數也不得買進");
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, cheap.action());
        assertTrue(expensive.risks().stream().anyMatch(r -> r.contains("多付溢價")));
    }

    /** 52 週相對位置在創新高當日須經 clamp，否則貢獻超出 [-1,+1] 而破壞 score∈[0,100]。 */
    @Test
    void week52PositionAboveOneIsClampedAndScoreStaysInRange() {
        var newHigh = engine.evaluateStock(v9Stock(true, "60", "58", "55", "54", "25", "1.80"));

        assertTrue(newHigh.score() >= 0 && newHigh.score() <= 100,
                "score 必須恆落在 [0,100]，實得 " + newHigh.score());
    }

    // ── Task 264 測試用 fixture ────────────────────────────────────────────────

    /** 站上全部均線的多頭標的，可指定 KD、前期 KD、季線乖離（%）與 52 週位置。 */
    private TradingRadarRuleEngine.StockInput v9Stock(
            boolean held, String k, String d, String prevK, String prevD,
            String ma60Bias, String week52) {
        return v9StockWithBand(held, k, d, prevK, prevD, ma60Bias, week52, "8.0");
    }

    private TradingRadarRuleEngine.StockInput v9StockWithBand(
            boolean held, String k, String d, String prevK, String prevD,
            String ma60Bias, String band) {
        return v9StockWithBand(held, k, d, prevK, prevD, ma60Bias, "0.60", band);
    }

    private TradingRadarRuleEngine.StockInput v9StockWithBand(
            boolean held, String k, String d, String prevK, String prevD,
            String ma60Bias, String week52, String band) {
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("120"), new BigDecimal("1"), new BigDecimal("1"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"),
                        new BigDecimal(k), new BigDecimal(d)),
                new BigDecimal(prevK), new BigDecimal(prevD),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false,
                null,
                new BigDecimal(ma60Bias), new BigDecimal("33.3"),
                new BigDecimal(week52), new BigDecimal(band), null, null);
    }

    private TradingRadarRuleEngine.StockInput withExtended(
            TradingRadarRuleEngine.StockInput base,
            TradingRadarRuleEngine.ExtendedIndicators extended,
            BigDecimal volumeRatio) {
        return new TradingRadarRuleEngine.StockInput(
                base.held(), base.price(), base.changePercent(), base.completedChangePercent(),
                base.indicators(), base.previousK(), base.previousD(),
                base.ma20Confirmation(), base.ma60Confirmation(), base.ma240Confirmation(),
                base.instrumentType(), base.marketRegime(), base.marketStale(), base.fxPercentile(),
                base.ma60BiasPercent(), base.ma240BiasPercent(), base.week52Position(),
                base.kdBandWidthPercent(), base.etfPremiumPct(), base.etfPremiumPercentile(),
                new BigDecimal("115"), extended, volumeRatio);
    }

    private TradingRadarRuleEngine.StockInput withFundamental(
            TradingRadarRuleEngine.StockInput base,
            TradingRadarRuleEngine.FundamentalInput fundamental) {
        return new TradingRadarRuleEngine.StockInput(
                base.held(), base.price(), base.changePercent(), base.completedChangePercent(),
                base.indicators(), base.previousK(), base.previousD(),
                base.ma20Confirmation(), base.ma60Confirmation(), base.ma240Confirmation(),
                base.instrumentType(), base.marketRegime(), base.marketStale(), base.fxPercentile(),
                base.ma60BiasPercent(), base.ma60BiasPercentile(), base.ma240BiasPercent(), base.week52Position(),
                base.kdBandWidthPercent(), base.etfPremiumPct(), base.etfPremiumPercentile(),
                base.weeklyMa(), base.extendedIndicators(), base.volumeRatio(), fundamental);
    }

    private TradingRadarRuleEngine.ExtendedIndicators extended(
            String bias10, String bias20, String b10b20, String osc) {
        return new TradingRadarRuleEngine.ExtendedIndicators(
                new BigDecimal("50"), null, null,
                null, null, null, null, new BigDecimal(osc),
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal(bias10), new BigDecimal(bias20), new BigDecimal(b10b20),
                new BigDecimal("50"));
    }

    /** 跌破全部均線、KD 深度超賣、大盤急跌的崩跌情境；week52 決定長期結構是否已破壞。 */
    private TradingRadarRuleEngine.StockInput v9CrashStock(boolean held, String week52) {
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("60"), new BigDecimal("-6"), new BigDecimal("-6"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("75"), new BigDecimal("80"), new BigDecimal("90"),
                        new BigDecimal("8"), new BigDecimal("12")),
                new BigDecimal("10"), new BigDecimal("14"),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF,
                false,
                null,
                new BigDecimal("-25"), new BigDecimal("-33.3"),
                new BigDecimal(week52), new BigDecimal("8.0"), null, null);
    }

    /** 多頭 ETF，可指定折溢價（%）與其自身歷史分位。 */
    private TradingRadarRuleEngine.StockInput v9EtfStock(
            boolean held, String premiumPct, String percentile) {
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("120"), new BigDecimal("1"), new BigDecimal("1"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"),
                        new BigDecimal("55"), new BigDecimal("50")),
                new BigDecimal("50"), new BigDecimal("48"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false,
                null,
                new BigDecimal("8"), new BigDecimal("33.3"),
                new BigDecimal("0.60"), new BigDecimal("8.0"),
                new BigDecimal(premiumPct), new BigDecimal(percentile));
    }

    private TradingRadarRuleEngine.StockInput strongStockWithKd(boolean held, String k, String d) {
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("120"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"),
                        new BigDecimal(k), new BigDecimal(d)),
                new BigDecimal("55"),
                new BigDecimal("45"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false,
                null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null);
    }

    @Test
    void expensiveFx_vetoesBuyForForeignCurrencyAssets() {
        var cheap = engine.evaluateStock(bondWithKd("51.85", "49.07", new BigDecimal("20")));
        var expensive = engine.evaluateStock(bondWithKd("51.85", "49.07", new BigDecimal("95")));

        assertEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, cheap.action());
        assertNotEquals(TradingRadarRuleEngine.Action.BUY_CANDIDATE, expensive.action(),
                "換匯位於五年期第 95 百分位時不得產生買進候選");
        assertTrue(cheap.score() > expensive.score(), "換匯便宜時分數應高於昂貴時");
    }

    @Test
    void fxPercentile_isNullForTwdAssets_andWeightIsRedistributed() {
        // 台幣資產傳 null，該因子的權重由其餘因子吸收，不得以 0 分（中性）充當。
        var twdAsset = engine.evaluateStock(bondWithKd("51.85", "49.07", null));
        var fxNeutral = engine.evaluateStock(bondWithKd("51.85", "49.07", new BigDecimal("50")));
        // 「不適用」與「中性」在加權正規化下並不等價，這正是重分配與補 0 的差別：
        // 分位 50 的貢獻為 0 但權重仍進分母，會稀釋其餘正貢獻；null 則把權重讓給其他因子。
        // 故其他因子整體為正時，無曝險者的分數必然高於「被塞了一個 0」的版本。
        assertTrue(twdAsset.score() > fxNeutral.score(),
                "無匯率曝險應走權重重分配，不得等同於給 0 分的中性值");

        var fxExpensiveScore = engine.evaluateStock(
                bondWithKd("51.85", "49.07", new BigDecimal("95"))).score();
        assertTrue(twdAsset.score() > fxExpensiveScore,
                "無匯率曝險的標的不應被匯率拖累");
    }

    @Test
    void scoreNeverSaturates_evenOnExtremeInputs() {
        // V4 的缺陷：均線六項全滿即 108 分，超出 clamp 上限，使 KD 過熱的 −3 完全失效
        //（實測 00719B 原始分 110、00697B 113）。V5 正規化後任何輸入都落在 [0,100] 且不觸邊界。
        var allPositive = engine.evaluateStock(bondWithKd("99", "80", new BigDecimal("0")));
        var allNegative = engine.evaluateStock(new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("1"),
                new BigDecimal("-9"),
                new BigDecimal("-9"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("2"),
                        new BigDecimal("5"), new BigDecimal("60")),
                new BigDecimal("50"),
                new BigDecimal("50"),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF,
                false,
                new BigDecimal("99"),
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null));

        assertTrue(allPositive.score() >= 0 && allPositive.score() <= 100);
        assertTrue(allNegative.score() >= 0 && allNegative.score() <= 100);
        assertTrue(allNegative.score() < allPositive.score());
        // 關鍵：KD 在滿分區仍能改變分數（V4 下這裡兩者都會是 100）
        var sameGeometryLowerKd = engine.evaluateStock(bondWithKd("40", "38", new BigDecimal("0")));
        assertNotEquals(allPositive.score(), sameGeometryLowerKd.score(),
                "KD 必須能在均線全綠時仍影響分數，否則就是 V4 的飽和問題重演");
    }

    @Test
    void kdPosition_separatesOverboughtFromOversoldReversal() {
        // 同樣是 K>D（動能為正），但高檔與低檔的意義相反，分數必須有明顯差距。
        var highReversal = engine.evaluateStock(bondWithKd("90.76", "86.01", new BigDecimal("50")));
        var lowReversal = engine.evaluateStock(bondWithKd("26.10", "20.70", new BigDecimal("50")));
        assertTrue(lowReversal.score() > highReversal.score(),
                "低檔轉強（跌深反彈）的分數應高於高檔轉強（漲多了）");
    }

    /** 未觸頂的中性標的：50 −8(<MA20) +12 +15 −5(conf20 BELOW) +8 +10 +5(K>D) = 87。 */
    private TradingRadarRuleEngine.StockInput moderateStock(
            TradingRadarRuleEngine.MarketRegime regime, boolean marketStale) {
        return new TradingRadarRuleEngine.StockInput(
                true,
                new BigDecimal("100"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("105"), new BigDecimal("95"), new BigDecimal("90"),
                        new BigDecimal("60"), new BigDecimal("40")),
                new BigDecimal("55"),
                new BigDecimal("45"),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                regime,
                marketStale,
                null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null);
    }

    @Test
    void marketStale_doesNotAffectBond() {
        var fresh = engine.evaluateStock(strongStock(
                false, TradingRadarRuleEngine.InstrumentType.BOND,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF, false));
        var stale = engine.evaluateStock(strongStock(
                false, TradingRadarRuleEngine.InstrumentType.BOND,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF, true));
        assertEquals(fresh.action(), stale.action());
        assertEquals(fresh.score(), stale.score());
    }

    @Test
    void counterTrendStabilized_readsCompletedBarNotIntradayChange() {
        // 完成日 K 已止跌(+0.5)，盤中即時仍為 -1.2：應以完成日為準而升級為試單候選。
        var input = new TradingRadarRuleEngine.StockInput(
                true,
                new BigDecimal("20.60"),
                new BigDecimal("-1.2"),
                new BigDecimal("0.5"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("23.76"), new BigDecimal("22.37"), new BigDecimal("16.56"),
                        new BigDecimal("15"), new BigDecimal("14")),
                new BigDecimal("12"),
                new BigDecimal("13"),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF,
                false,
                null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null);
        assertEquals(TradingRadarRuleEngine.CounterTrendState.TRIAL_CANDIDATE,
                engine.evaluateStock(input).counterTrend().state());

        // 完成日 K 仍在跌：盤中翻紅也不得升級。
        var stillFalling = new TradingRadarRuleEngine.StockInput(
                true,
                new BigDecimal("20.60"),
                new BigDecimal("0.8"),
                new BigDecimal("-0.9"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("23.76"), new BigDecimal("22.37"), new BigDecimal("16.56"),
                        new BigDecimal("15"), new BigDecimal("14")),
                new BigDecimal("12"),
                new BigDecimal("13"),
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF,
                false,
                null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null);
        assertEquals(TradingRadarRuleEngine.CounterTrendState.OVERSOLD_WATCH,
                engine.evaluateStock(stillFalling).counterTrend().state());
    }

    private TradingRadarRuleEngine.MarketInput marketInput(
            BigDecimal price, BigDecimal change,
            BigDecimal ma20, BigDecimal ma60, BigDecimal ma240,
            BigDecimal k, BigDecimal d,
            TradingRadarRuleEngine.Confirmation c60,
            TradingRadarRuleEngine.Confirmation c240) {
        return new TradingRadarRuleEngine.MarketInput(price, change,
                new TradingRadarRuleEngine.Indicators(ma20, ma60, ma240, k, d), c60, c240);
    }

    private TradingRadarRuleEngine.StockInput strongStock(
            boolean held, TradingRadarRuleEngine.MarketRegime regime) {
        return strongStock(held, TradingRadarRuleEngine.InstrumentType.EQUITY, regime);
    }

    private TradingRadarRuleEngine.StockInput strongStock(
            boolean held,
            TradingRadarRuleEngine.InstrumentType instrumentType,
            TradingRadarRuleEngine.MarketRegime regime) {
        return strongStock(held, instrumentType, regime, false);
    }

    private TradingRadarRuleEngine.StockInput strongStock(
            boolean held,
            TradingRadarRuleEngine.InstrumentType instrumentType,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean marketStale) {
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("120"),
                new BigDecimal("1"),
                new BigDecimal("1"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"),
                        new BigDecimal("60"), new BigDecimal("40")),
                new BigDecimal("55"),
                new BigDecimal("45"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                instrumentType,
                regime,
                marketStale,
                null,
                // Task 264 新增：季線乖離／年線乖離／52 週位置／9 日帶寬／ETF 折溢價／折溢價分位
                null, null, null, null, null, null);
    }

    /**
     * Task 446 邊界回歸專用 fixture：以 {@code strongStock} 為底，
     * completedChangePercent=5（現行 chasedDailyMove 5% 門檻邊界）、
     * ma60BiasPercent=12（現行 BIAS_HIGH 邊界）、其餘欄位（含 extendedIndicators 缺值）不變。
     * completedChangePercent／ma60BiasPercent 皆不參與 score 計算（score 的 bias 因子讀
     * extendedIndicators，非 ma60BiasPercent；completedChangePercent 只用於 buyGate 判定），
     * 故 score 與 strongStock 相同、仍 ≥ 買進門檻 75，只有 buyGate 的兩個否決條件受影響。
     */
    private TradingRadarRuleEngine.StockInput chasedAndMidTierOverboughtBoundaryStock(boolean held) {
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("120"),
                new BigDecimal("1"),
                new BigDecimal("5"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"),
                        new BigDecimal("60"), new BigDecimal("40")),
                new BigDecimal("55"),
                new BigDecimal("45"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false,
                null,
                new BigDecimal("12"),
                null,
                null,
                null,
                null,
                null);
    }

    private TradingRadarRuleEngine.StockInput counterTrendStock(
            BigDecimal price,
            BigDecimal changePercent,
            BigDecimal k,
            BigDecimal d,
            BigDecimal previousK,
            BigDecimal previousD,
            TradingRadarRuleEngine.Confirmation annualConfirmation) {
        return new TradingRadarRuleEngine.StockInput(
                true,
                price,
                changePercent,
                changePercent,
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("23.76"),
                        new BigDecimal("22.37"),
                        new BigDecimal("16.56"),
                        k,
                        d),
                previousK,
                previousD,
                TradingRadarRuleEngine.Confirmation.BELOW,
                TradingRadarRuleEngine.Confirmation.BELOW,
                annualConfirmation,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF,
                false,
                null,
                // Task 264：乖離由同一組 price 與均線導出（季線 22.37、年線 16.56）
                biasOf(price.toPlainString(), "22.37"),
                biasOf(price.toPlainString(), "16.56"),
                null, null, null, null);
    }

    // ═══ Task 299：季線乖離自身分位路徑（極端時機二擇一）═══════════════════════

    @Test
    void extremeOverbought_firesViaPercentilePathWhenAbsoluteThresholdNotReached() {
        // bias=+8%（未達 20 絕對門檻），KD 過熱（k=88/d=90→avg=89>80），分位=99：
        // 應由分位路徑升級為 EXTREME_OVERBOUGHT，且風險文案須標明是以自身分布判定。
        var result = engine.evaluateStock(
                v9StockWithBiasPercentile(true, "88", "90", "92", "90", "8", "99"));

        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT, result.timingState());
        assertTrue(result.risks().stream().anyMatch(r -> r.contains("自身分布判定")));
    }

    @Test
    void extremeOversold_firesViaPercentilePathAndDowngradesLowScoreAction() {
        // bias=-8%（未達 -20 絕對門檻），KD 深度超賣（k=8/d=12→avg=10<20），分位=1：
        // 沿用 v9CrashStock 的弱勢幾何使分數落在減碼／出場區（<40），藉此驗證分位路徑觸發的
        // 極端超賣保護確實把動作由 REDUCE_CANDIDATE/EXIT_CANDIDATE 降級為 HOLD_CAUTION/WAIT，
        // 對稱於買方的「超買否決買進」。
        var held = engine.evaluateStock(crashStockWithBiasPercentile(true, "-8", "1"));
        var notHeld = engine.evaluateStock(crashStockWithBiasPercentile(false, "-8", "1"));

        assertTrue(held.score() < 40, "分數誠實反映弱勢（實算落在減碼／出場區），不因保護而灌水");
        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD, held.timingState());
        assertEquals(TradingRadarRuleEngine.Action.HOLD_CAUTION, held.action());
        assertEquals(TradingRadarRuleEngine.Action.WAIT, notHeld.action());
        assertTrue(held.reasons().stream().anyMatch(r -> r.contains("自身分布判定")));
    }

    @Test
    void extremeOverbought_doesNotUpgradeWhenPercentileMissing() {
        // 分位 null＋bias=+8%（未達 20 絕對門檻）：即使 KD 過熱，仍只能判 OVERBOUGHT，
        // 不得升級為 EXTREME——確保分位路徑缺值時不會意外放行（回歸）。
        var result = engine.evaluateStock(v9Stock(true, "88", "90", "92", "90", "8", "0.60"));

        assertEquals(TradingRadarRuleEngine.TimingState.OVERBOUGHT, result.timingState());
    }

    @Test
    void extremeOverbought_stillFiresViaAbsoluteThresholdWhenPercentileMissing() {
        // bias=+25%（達 20 絕對門檻）＋分位 null＋KD 過熱：絕對路徑須不受分位路徑新增影響，
        // 獨立成立（回歸，對照 requirement_kdDeadCrossAloneMustNotProduceReduceAtOverbought）。
        var result = engine.evaluateStock(v9Stock(true, "88", "90", "92", "90", "25", "0.60"));

        assertEquals(TradingRadarRuleEngine.TimingState.EXTREME_OVERBOUGHT, result.timingState());
    }

    // ── Task 299 測試用 fixture ────────────────────────────────────────────────

    /** v9Stock 為底，改用指定的季線乖離自身分位（可為 null）。 */
    private TradingRadarRuleEngine.StockInput v9StockWithBiasPercentile(
            boolean held, String k, String d, String prevK, String prevD,
            String ma60Bias, String percentile) {
        return withBiasAndPercentile(
                v9Stock(held, k, d, prevK, prevD, ma60Bias, "0.60"), ma60Bias, percentile);
    }

    /** v9CrashStock 為底（week52=0.20），改用未達絕對門檻的乖離與指定的自身分位。 */
    private TradingRadarRuleEngine.StockInput crashStockWithBiasPercentile(
            boolean held, String ma60Bias, String percentile) {
        return withBiasAndPercentile(v9CrashStock(held, "0.20"), ma60Bias, percentile);
    }

    /** 覆寫季線乖離與其自身分位，其餘欄位原封不動複製。 */
    private TradingRadarRuleEngine.StockInput withBiasAndPercentile(
            TradingRadarRuleEngine.StockInput base, String ma60Bias, String percentile) {
        return new TradingRadarRuleEngine.StockInput(
                base.held(), base.price(), base.changePercent(), base.completedChangePercent(),
                base.indicators(), base.previousK(), base.previousD(),
                base.ma20Confirmation(), base.ma60Confirmation(), base.ma240Confirmation(),
                base.instrumentType(), base.marketRegime(), base.marketStale(), base.fxPercentile(),
                new BigDecimal(ma60Bias), percentile == null ? null : new BigDecimal(percentile),
                base.ma240BiasPercent(), base.week52Position(),
                base.kdBandWidthPercent(), base.etfPremiumPct(), base.etfPremiumPercentile(),
                base.weeklyMa(), base.extendedIndicators(), base.volumeRatio(), base.fundamental());
    }

    // ═══ Task 305：因子貢獻一次計算，兩軌各自加權 ═══════════════════════════════

    /**
     * 守護測試：因子齊全（含基本面、外幣、ETF 欄位）情境下，
     * (a) 因子段文案兩軌必須逐位相同——證明兩軌讀的是同一份 {@code computeFactors} 結果，
     * 而非各自重算一遍；(b) {@code score}／{@code shortScore} 分別等於以 V12 權重
     * （t298 298.3）對同一組貢獻值手算的期望值，驗證「一次計算、兩軌加權」。
     *
     * <p>fixture 刻意讓 kdHeat=NORMAL、timing=NEUTRAL，且 TRIAL_BUY／profitTaking／
     * 基本面惡化閘門皆不成立、兩軌分數都落在 HOLD／WATCH 的純分數映射區（不觸發
     * {@code actionFor} 內任何會附加文案的分支）。此時 {@code describeHeat}、時機分位揭露、
     * {@code actionFor} 對兩軌都不再附加任何句子，故 reasons()／risks() 應與
     * shortReasons()／shortRisks() 完全相等，不必再自行切分「因子段」邊界。</p>
     */
    @Test
    void factorContributions_areSharedAcrossBothHorizons() {
        var input = factorCompleteStock();
        var result = engine.evaluateStock(input);

        // 前提：horizon 專屬分支（describeHeat／時機分位揭露／actionFor 文案）全數不觸發，
        // 因子段才會等於兩軌輸出的全部內容，不必另外切分子字串。
        assertEquals(TradingRadarRuleEngine.KdHeat.NORMAL, result.kdHeat());
        assertEquals(TradingRadarRuleEngine.TimingState.NEUTRAL, result.timingState());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.action());
        assertEquals(TradingRadarRuleEngine.Action.WATCH, result.shortAction());

        // (a) 因子段文案兩軌逐位相同。
        assertEquals(result.reasons(), result.shortReasons(),
                "因子段 reasons 兩軌必須逐位相同（同一份 computeFactors 結果）");
        assertEquals(result.risks(), result.shortRisks(),
                "因子段 risks 兩軌必須逐位相同（同一份 computeFactors 結果）");

        // (b) 18 因子貢獻值固定為下列已知值（由 fixture 的技術面／基本面／外幣／ETF 輸入導出）：
        //   ma5=+1.0 ma20=+1.0 ma60=+1.0 ma240=+1.0 kdJ=0.0 macd=+0.5 rsi=+0.5 bias=+0.5
        //   volume=-1.0 market=+0.5 dayMove=+0.5 fx=+0.4 etfPremium=+0.6
        //   eps=roe=revenue=pe=industry=+0.5
        // 本 fixture 的 weekly／dailyCandle 皆為 null，V15 新增的五個因子一律缺值不進 sumW，
        // 故有效權重總和為既有 18 因子之和：短期 Σw=0.84、中期 Σw=0.81。
        //
        // V16（Task 360）：kdJ 由 +0.25 變 0——K=60／D=40 → 標準 J = 100 →
        // clampUnit((50−100)/50) = −1.0，取代 V15 時由 j9=50 得到的 0；
        // 四分量平均 (1.0 + 0.0 − 1.0 + 0.0)/4 = 0（wr9=50 → wrPosition=0）。
        // K>D 的標的分數下降，符合 360.7g 準則。
        //   短期 Σ(w×c)＝0.329 + 0.12×0.0 = 0.329 → sigma=0.329/0.84=0.391667
        //     → score=round(50+50×0.391667)=round(69.583)=70（V15 為 0.359/0.84 → 71）
        //   中期 Σ(w×c)＝0.409 + 0.05×0.0 = 0.409 → sigma=0.409/0.81=0.504938
        //     → score=round(50+50×0.504938)=round(75.247)=75（V15 為 0.4215/0.81 → 76）
        assertEquals(70, result.shortScore(), "短期軌以 SW_ 權重加權同一組貢獻值");
        assertEquals(75, result.score(), "中期軌以 MW_ 權重加權同一組貢獻值");
    }

    /**
     * Task 305 守護測試用：18 因子全部有值的完整 fixture（含基本面、外幣、ETF）。
     *
     * <p>completedChangePercent=-2.5（下跌）刻意使 {@code actionFor} 的 {@code stillFalling}
     * 為真，讓兩軌買進閘門必然關閉、分數落在 HOLD／WATCH 的純分數映射區——這是讓
     * {@code actionFor} 對兩軌都不附加任何文案的前提，見
     * {@link #factorContributions_areSharedAcrossBothHorizons()} 的類上註解。</p>
     */
    private TradingRadarRuleEngine.StockInput factorCompleteStock() {
        return new TradingRadarRuleEngine.StockInput(
                false,
                new BigDecimal("120"),
                new BigDecimal("1"),
                new BigDecimal("-2.5"),
                new TradingRadarRuleEngine.Indicators(
                        new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("90"),
                        new BigDecimal("60"), new BigDecimal("40")),
                new BigDecimal("55"),
                new BigDecimal("45"),
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false,
                new BigDecimal("30"),
                new BigDecimal("5"), null,
                new BigDecimal("15"), new BigDecimal("0.5"),
                new BigDecimal("8.0"),
                new BigDecimal("1.0"), new BigDecimal("20"),
                new BigDecimal("115"),
                new TradingRadarRuleEngine.ExtendedIndicators(
                        new BigDecimal("50"), null, null,
                        null, null, null, null, new BigDecimal("0.3"),
                        new BigDecimal("20"), new BigDecimal("30"),
                        new BigDecimal("-6"), new BigDecimal("-8"), new BigDecimal("2"),
                        new BigDecimal("50")),
                new BigDecimal("1.5"),
                new TradingRadarRuleEngine.FundamentalInput(true, 0.5, 0.5, 0.5, 0.5, 0.5, false));
    }

    private List<BigDecimal> closesDescending(int size, int start) {
        List<BigDecimal> values = new ArrayList<>();
        for (int i = 0; i < size; i++) values.add(BigDecimal.valueOf(start - i));
        return values;
    }

    private List<BigDecimal> closesAscending(int size, int start) {
        List<BigDecimal> values = new ArrayList<>();
        for (int i = 0; i < size; i++) values.add(BigDecimal.valueOf(start + i));
        return values;
    }
}
