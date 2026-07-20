package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                null));
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

        assertEquals(37, stock.score());
        // V5：KD 位置子因子讓超賣得正分，分數由 V4 的 17 升至 37，動作隨之由出場候選降級為減碼候選。
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
        // V5：長線佳（價 > 年線）且 KD 位於低檔，KD 位置子因子給正貢獻，
        // 分數已不再落在減碼區間——這正是「長線好、短線超賣不該被叫賣」的修正。
        assertEquals(TradingRadarRuleEngine.Action.HOLD_CAUTION, stock.action());
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

        assertEquals(82, equity.score());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, equity.action());
        assertEquals(90, bond.score());
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
        assertEquals(90, bond.score());
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
                null);
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

    @Test
    void trialBuy_blockedByRiskOffAndStaleMarketForEquities() {
        var riskOff = engine.evaluateStock(trialBuyStock("15", "14", "12", "16", "80", "0.3",
                TradingRadarRuleEngine.Confirmation.ABOVE,
                TradingRadarRuleEngine.MarketRegime.RISK_OFF, false));
        assertNotEquals(TradingRadarRuleEngine.Action.TRIAL_BUY, riskOff.action());

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
                fxPercentile);
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
                new BigDecimal("99")));

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
                null);
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
                null);
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
                null);
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
                null);
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
