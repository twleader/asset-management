package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(33, stock.score());
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

        assertEquals(74, equity.score());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, equity.action());
        assertEquals(92, bond.score());
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
        assertEquals(92, bond.score());
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
        // 動能 +0.71×0.08、位置 −0.575×0.13 → Σ(w×c)=0.63205 → 83.27 → 83。
        var held = engine.evaluateStock(strongStockWithKd(true, "82.3", "75.2"));

        assertEquals(87, held.score(), "V11 中期技術面重配後為 87 分");
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, held.action());
        assertEquals(TradingRadarRuleEngine.KdHeat.ELEVATED, held.kdHeat());
    }

    @Test
    void kdHeat_kAloneOverheated_closesBuyGateWithoutDeductingScore() {
        // K=86／D=70 → avg 78 未過 80，僅 K 過 85。動能 clamp 為 +1.0、位置 −0.56
        // → Σ(w×c)=0.6572 → 84.59 → 85。分數仍 ≥ 75，證明降級來自閘門而非扣分。
        var held = engine.evaluateStock(strongStockWithKd(true, "86", "70"));
        var notHeld = engine.evaluateStock(strongStockWithKd(false, "86", "70"));

        assertEquals(87, held.score(), "過熱硬閘門不在 KD/J 因子之外再重複扣分");
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
     * Task 263 升為 V8：大盤即時點位的 high／low 改由 Yahoo 5 分 K 的 high／low 陣列提供
     * （原本是「5 分格收盤價」的本地聚合），K／D 與 regime／score 因此與修正前不同。
     * 升版判準沿用 Task 228（V6）與 Task 232（V7）——因子組成、權重、正規化完全未動，
     * 但使用者可觀察行為有實質變化即升版。
     */
    @Test
    void ruleVersion_isV11() {
        assertEquals("TW_RULES_V11", TradingRadarRuleEngine.RULE_VERSION);
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

    /** 權重總和以斷言釘住，不靠人工加總。 */
    @Test
    void weightsSumToExactlyOne() {
        assertEquals(1.0, TradingRadarRuleEngine.SHORT_WEIGHT_SUM, 1e-9);
        assertEquals(1.0, TradingRadarRuleEngine.MEDIUM_WEIGHT_SUM, 1e-9);
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
                base.ma60BiasPercent(), base.ma240BiasPercent(), base.week52Position(),
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
