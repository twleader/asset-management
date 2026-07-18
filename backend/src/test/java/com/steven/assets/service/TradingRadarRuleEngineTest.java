package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                true, new BigDecimal("100"), BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(null, null, null, null, null),
                null,
                null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL));
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

        assertEquals(17, stock.score());
        assertEquals(TradingRadarRuleEngine.Action.EXIT_CANDIDATE, stock.action());
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
        assertEquals(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE, stock.action());
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

        assertEquals(98, equity.score());
        assertEquals(TradingRadarRuleEngine.Action.HOLD, equity.action());
        assertEquals(100, bond.score());
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
        assertEquals(100, bond.score());
        assertEquals(TradingRadarRuleEngine.Action.ADD_CANDIDATE, bond.action());
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
        return new TradingRadarRuleEngine.StockInput(
                held,
                new BigDecimal("120"),
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
                regime);
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
                TradingRadarRuleEngine.MarketRegime.RISK_OFF);
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
