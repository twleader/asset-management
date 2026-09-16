package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RadarInputAssemblerBollingerTest {
    private final TechnicalIndicatorService indicators = new TechnicalIndicatorService(
            mock(StockPriceHistoryRepository.class), mock(PriceQueryService.class),
            mock(TwseIndexDailyHistoryRepository.class), mock(UsIndexDailyHistoryRepository.class));
    private final RadarInputAssembler assembler = new RadarInputAssembler(indicators,
            new DistributionAdjustedPriceService(), new TradingRadarRuleEngine());

    @Test
    void knownPopulationWindowUsesSameTwentyClosesForAverageAndVariance() {
        var value = RadarInputAssembler.completedBollinger(linearRows(20, 120), 0);
        // Values 101..120: mean 110.5, population variance 33.25 (not sample 35).
        double sigma = Math.sqrt(33.25);
        assertThat(value.middleBand()).isEqualByComparingTo("110.5");
        assertThat(value.upperBand().doubleValue()).isCloseTo(110.5 + 2 * sigma, within());
        assertThat(value.lowerBand().doubleValue()).isCloseTo(110.5 - 2 * sigma, within());
        assertThat(value.bandWidthPercent().doubleValue()).isCloseTo(4 * sigma / 110.5 * 100, within());
        assertThat(value.percentB().doubleValue()).isCloseTo((120 - (110.5 - 2 * sigma)) / (4 * sigma), within());
        assertThat(value.asOfDate()).isEqualTo(linearRows(20, 120).getFirst().getTradingDate());
        assertThat(value.period()).isEqualTo(20);
        assertThat(value.standardDeviationMultiplier()).isEqualTo(2);
        assertThat(value.percentB().scale()).isEqualTo(8);
    }

    @Test
    void constantWindowPreservesPricesAndZeroWidthWithoutFalsePosition() {
        List<StockPriceHistory> rows = linearRows(20, 120);
        rows.forEach(row -> row.setClosePrice(new BigDecimal("100")));
        var value = RadarInputAssembler.completedBollinger(rows, 0);
        assertThat(value.middleBand()).isEqualByComparingTo("100");
        assertThat(value.upperBand()).isEqualByComparingTo("100");
        assertThat(value.lowerBand()).isEqualByComparingTo("100");
        assertThat(value.bandWidthPercent()).isZero();
        assertThat(value.percentB()).isNull();
    }

    @Test
    void incompleteAndDirtyWindowsAreRejectedRatherThanSkippingRows() {
        assertThat(RadarInputAssembler.completedBollinger(linearRows(19, 120), 0)).isNull();
        assertThat(RadarInputAssembler.completedBollinger(null, 0)).isNull();
        assertThat(RadarInputAssembler.completedBollinger(linearRows(30, 120), -1)).isNull();
        for (BigDecimal invalid : new BigDecimal[]{null, BigDecimal.ZERO, BigDecimal.ONE.negate(), new BigDecimal("1E400")}) {
            var rows = linearRows(30, 120);
            rows.get(10).setClosePrice(invalid);
            assertThat(RadarInputAssembler.completedBollinger(rows, 0)).isNull();
        }
        var rows = linearRows(30, 120);
        rows.set(10, null);
        assertThat(RadarInputAssembler.completedBollinger(rows, 0)).isNull();
        rows = linearRows(30, 120);
        rows.get(10).setTradingDate(rows.get(9).getTradingDate());
        assertThat(RadarInputAssembler.completedBollinger(rows, 0)).isNull();
        rows.get(10).setTradingDate(null);
        assertThat(RadarInputAssembler.completedBollinger(rows, 0)).isNull();
    }

    @Test
    void truePrepareCalculateHonorsCompletedAuthorityAndFreshLocalOverridesCannotReplaceBollinger() {
        var rows = linearRows(30, 130);
        var prepared = assembler.prepare(rows, List.of(), false, 19, 30, rows.getFirst().getClosePrice());
        assertThat(assembler.calculate(prepared).bollinger()).isNull();
        prepared = assembler.prepare(rows, List.of(), false, 30, 30, rows.getFirst().getClosePrice());
        var local = assembler.calculate(prepared);
        var cache = assembler.calculate(prepared, TechnicalIndicatorService.FullIndicators.EMPTY,
                local.weekly(), TechnicalIndicatorService.FullIndicators.EMPTY);
        assertThat(cache.bollinger()).isEqualTo(local.bollinger());
        assertThat(cache.bollinger().asOfDate()).isEqualTo(local.volatility60().asOfDate());
        assertThat(cache.bollinger().middleBand()).isEqualByComparingTo("120.5");
    }

    @Test
    void liveRowIsNotAWindowMemberAndExtremeSplitRetainsCompletedCommonBasisAndRatios() {
        var complete = linearRows(260, 360);
        var ordinary = withLive(complete, "361");
        var extreme = withLive(complete, "180"); // Existing 2:1 inferred split scales all completed bars.
        var before = assembler.assemble(ordinary, List.of(), true, 260, 241, new BigDecimal("361"));
        var after = assembler.assemble(extreme, List.of(), true, 260, 241, new BigDecimal("180"));
        assertThat(before.bollinger().asOfDate()).isEqualTo(complete.getFirst().getTradingDate());
        assertThat(before.bollinger().middleBand()).isEqualByComparingTo("350.5");
        assertThat(after.bollinger().percentB().doubleValue()).isCloseTo(before.bollinger().percentB().doubleValue(), within());
        assertThat(after.bollinger().bandWidthPercent().doubleValue()).isCloseTo(before.bollinger().bandWidthPercent().doubleValue(), within());
        assertThat(after.bollinger().middleBand().doubleValue() / before.bollinger().middleBand().doubleValue())
                .isCloseTo(after.dailyCandle().close().doubleValue() / before.dailyCandle().close().doubleValue(), within());
        assertThat(after.bollinger().middleBand()).isNotEqualByComparingTo(before.bollinger().middleBand());
        assertThat(TradingRadarRuleEngine.bollingerExtensionPenalty(after.bollinger()))
                .isCloseTo(TradingRadarRuleEngine.bollingerExtensionPenalty(before.bollinger()), within());
    }

    @Test
    void cashDistributionUsesExactSameAdjustedCompletedCandleAndWindowWithoutAnotherAdjustment() {
        var rows = linearRows(260, 360);
        var event = StockDividendHistory.builder().stockCode("2330").market("台股").year(2026)
                .exDividendDate(rows.get(8).getTradingDate()).cashDividend(new BigDecimal("5"))
                .stockDividend(BigDecimal.ZERO).build();
        var observed = assembler.assemble(rows, List.of(event), false, 260, 241, rows.getFirst().getClosePrice());
        var expected = RadarInputAssembler.completedBollinger(observed.adjustedRowsDesc(), 0);
        assertThat(observed.bollinger()).isEqualTo(expected);
        assertThat(observed.bollinger().asOfDate()).isEqualTo(observed.volatility60().asOfDate());
        assertThat(observed.dailyCandle().close()).isEqualByComparingTo(observed.adjustedRowsDesc().getFirst().getClosePrice());
        assertThat(observed.bollinger()).isNotEqualTo(RadarInputAssembler.completedBollinger(rows, 0));
    }

    @Test
    void helperIgnoresRowsOutsideExactWindowAndOfflineAsOfSliceIgnoresFutureRows() {
        var complete = linearRows(260, 360);
        var truncated = assembler.assemble(complete, List.of(), false, 260, 241, complete.getFirst().getClosePrice());
        var future = withLive(complete, "900");
        // Offline callers cut history at decision date before shared assembler; future cannot enter the slice.
        var atDate = future.stream().filter(row -> !row.getTradingDate().isAfter(complete.getFirst().getTradingDate())).toList();
        var repeated = assembler.assemble(atDate, List.of(), false, atDate.size(), 241, complete.getFirst().getClosePrice());
        assertThat(repeated.bollinger()).isEqualTo(truncated.bollinger());
        complete.get(25).setClosePrice(null);
        assertThat(RadarInputAssembler.completedBollinger(complete, 0)).isEqualTo(truncated.bollinger());
    }

    static List<StockPriceHistory> linearRows(int count, int newest) {
        List<StockPriceHistory> rows = new ArrayList<>();
        LocalDate latest = LocalDate.of(2026, 9, 15);
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(newest - i);
            rows.add(StockPriceHistory.builder().stockCode("2330").market("台股")
                    .tradingDate(latest.minusDays(i)).closePrice(close).openPrice(close)
                    .highPrice(close.add(BigDecimal.ONE)).lowPrice(close.subtract(BigDecimal.ONE)).volume(10000L).build());
        }
        return rows;
    }
    private static List<StockPriceHistory> withLive(List<StockPriceHistory> complete, String price) {
        var rows = new ArrayList<>(complete);
        BigDecimal value = new BigDecimal(price);
        rows.addFirst(StockPriceHistory.builder().stockCode("2330").market("台股")
                .tradingDate(complete.getFirst().getTradingDate().plusDays(1)).closePrice(value)
                .openPrice(value).highPrice(value.add(BigDecimal.ONE)).lowPrice(value.subtract(BigDecimal.ONE)).volume(500L).build());
        return rows;
    }
    private static org.assertj.core.data.Offset<Double> within() { return org.assertj.core.data.Offset.offset(1E-8); }
}
