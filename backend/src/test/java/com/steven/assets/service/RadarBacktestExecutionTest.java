package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RadarBacktestExecutionTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    @Test
    void primaryExecutionUsesNextOpenThroughNextOpenPlusHorizonIncludingH1() {
        var costKey = new RadarBacktestExecution.CostKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK);
        var attempt = RadarBacktestExecution.execute(List.of(
                        bar(0, "10", "20"),
                        bar(1, "11", "21"),
                        bar(2, "12", "22"),
                        bar(3, "13", "23")),
                0, 1, costKey, RadarBacktestExecution.resolveCost(costKey, Map.of()), true);

        assertThat(attempt.primary()).isPresent();
        var sample = attempt.primary().orElseThrow();
        assertThat(sample.mode()).isEqualTo(RadarBacktestExecution.ExecutionMode.NEXT_OPEN_PRIMARY);
        assertThat(sample.signalDate()).isEqualTo(START);
        assertThat(sample.entryDate()).isEqualTo(START.plusDays(1));
        assertThat(sample.exitDate()).isEqualTo(START.plusDays(2));
        assertThat(sample.entryPrice()).isEqualByComparingTo("11");
        assertThat(sample.exitPrice()).isEqualByComparingTo("12");
        assertThat(sample.grossReturnRatio()).isEqualByComparingTo("0.090909090909");
        assertThat(attempt.excludedInsufficientForward()).isFalse();
    }

    @Test
    void missingOpensExcludePrimarySeparatelyButNeverConsumeCloseSensitivity() {
        var key = new RadarBacktestExecution.CostKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.EQUITY_ETF);
        var attempt = RadarBacktestExecution.execute(List.of(
                        bar(0, "10", "10"),
                        bar(1, null, "11"),
                        bar(2, "0", "13")),
                0, 1, key, RadarBacktestExecution.resolveCost(key, null), true);

        assertThat(attempt.primary()).isEmpty();
        assertThat(attempt.excludedMissingEntryOpen()).isTrue();
        assertThat(attempt.excludedMissingExitOpen()).isTrue();
        assertThat(attempt.closeSensitivity()).isPresent();
        assertThat(attempt.closeSensitivity().orElseThrow().mode())
                .isEqualTo(RadarBacktestExecution.ExecutionMode.CLOSE_FALLBACK_SENSITIVITY);
        assertThat(attempt.closeSensitivity().orElseThrow().entryPrice()).isEqualByComparingTo("11");
        assertThat(attempt.closeSensitivity().orElseThrow().exitPrice()).isEqualByComparingTo("13");
    }

    @Test
    void forwardBoundaryIsDisclosedInsteadOfUsingLastAvailablePrice() {
        var key = new RadarBacktestExecution.CostKey(
                RadarBacktestExecution.US_MARKET, RadarBacktestExecution.InstrumentKind.STOCK);
        var attempt = RadarBacktestExecution.execute(
                List.of(bar(0, "10", "10"), bar(1, "11", "11")),
                0, 1, key, RadarBacktestExecution.resolveCost(key, null), true);

        assertThat(attempt.primary()).isEmpty();
        assertThat(attempt.closeSensitivity()).isEmpty();
        assertThat(attempt.excludedInsufficientForward()).isTrue();
        assertThat(attempt.entryDate()).isEqualTo(START.plusDays(1));
        assertThat(attempt.exitDate()).isNull();
    }

    @Test
    void conservativeDefaultsCoverBothMarketsAndAllThreeInstrumentKinds() {
        Map<RadarBacktestExecution.CostKey, RadarBacktestExecution.CostAssumption> defaults =
                RadarBacktestExecution.defaultCosts();

        assertThat(defaults).hasSize(6);
        for (var kind : RadarBacktestExecution.InstrumentKind.values()) {
            assertThat(defaults).containsKey(new RadarBacktestExecution.CostKey(
                    RadarBacktestExecution.TW_MARKET, kind));
            var us = defaults.get(new RadarBacktestExecution.CostKey(
                    RadarBacktestExecution.US_MARKET, kind));
            assertThat(us.sellTaxPct()).isEqualByComparingTo("0");
            assertThat(us.sourceLabel()).isEqualTo(RadarBacktestExecution.DEFAULT_COST_SOURCE);
        }
        assertThat(defaults.get(new RadarBacktestExecution.CostKey(
                        RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK))
                .sellTaxPct()).isEqualByComparingTo("0.3000");
        assertThat(defaults.get(new RadarBacktestExecution.CostKey(
                        RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.EQUITY_ETF))
                .sellTaxPct()).isEqualByComparingTo("0.1000");
        assertThat(defaults.get(new RadarBacktestExecution.CostKey(
                        RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.BOND_ETF))
                .sellTaxPct()).isEqualByComparingTo("0.1000");
    }

    @Test
    void oneAndTwoSideCostsFollowTheFixedFormula() {
        var cost = RadarBacktestExecution.defaultCosts().get(new RadarBacktestExecution.CostKey(
                RadarBacktestExecution.TW_MARKET, RadarBacktestExecution.InstrumentKind.STOCK));

        assertThat(cost.entryCashRequired(new BigDecimal("100"))).isEqualByComparingTo("100.1925");
        assertThat(cost.exitCashReceived(new BigDecimal("110"))).isEqualByComparingTo("109.45825");
        BigDecimal expected = new BigDecimal("109.45825")
                .divide(new BigDecimal("100.1925"), 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                .setScale(8, RoundingMode.HALF_UP);
        assertThat(cost.netReturnPct(new BigDecimal("100"), new BigDecimal("110")))
                .isEqualByComparingTo(expected);
        assertThat(cost.returnPracticalDeltaPct()).isGreaterThanOrEqualTo(new BigDecimal("0.10"));
    }

    @Test
    void adjustedBarsApplySplitFactorToEveryOhlcField() {
        LocalDate split = START.plusDays(1);
        List<StockPriceHistory> rawDesc = List.of(
                row(split, "24", "26", "23", "25"),
                row(START, "98", "104", "96", "100"));

        List<RadarBacktestExecution.AdjustedBar> adjusted = RadarBacktestExecution.adjustedBars(
                rawDesc, List.of(), new DistributionAdjustedPriceService());

        assertThat(adjusted).hasSize(2);
        var before = adjusted.get(0);
        assertThat(before.date()).isEqualTo(START);
        assertThat(before.open()).isEqualByComparingTo("24.5");
        assertThat(before.high()).isEqualByComparingTo("26");
        assertThat(before.low()).isEqualByComparingTo("24");
        assertThat(before.close()).isEqualByComparingTo("25");
        assertThat(adjusted.get(1).close()).isEqualByComparingTo("25");
    }

    @Test
    void invalidMarketAndCostRatesFailClosed() {
        assertThatThrownBy(() -> new RadarBacktestExecution.CostKey(
                "港股", RadarBacktestExecution.InstrumentKind.STOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RadarBacktestExecution.CostAssumption(
                new BigDecimal("5.01"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "x", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RadarBacktestExecution.AdjustedBar bar(int offset, String open, String close) {
        return new RadarBacktestExecution.AdjustedBar("TEST", RadarBacktestExecution.TW_MARKET,
                START.plusDays(offset), decimal(open), decimal(open), decimal(open), decimal(close));
    }

    private static StockPriceHistory row(
            LocalDate date, String open, String high, String low, String close) {
        return StockPriceHistory.builder()
                .stockCode("TEST")
                .market(RadarBacktestExecution.TW_MARKET)
                .tradingDate(date)
                .openPrice(decimal(open))
                .highPrice(decimal(high))
                .lowPrice(decimal(low))
                .closePrice(decimal(close))
                .build();
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
