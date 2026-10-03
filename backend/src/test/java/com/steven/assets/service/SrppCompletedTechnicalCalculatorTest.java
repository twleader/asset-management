package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SrppCompletedTechnicalCalculatorTest {
    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    @Test
    void flatSeriesHasNeutralIndicatorsAndNoInventedTrend() {
        List<StockPriceHistory> rows = rows(400, false);
        var result = SrppCompletedTechnicalCalculator.calculate(rows, rows.get(399).getTradingDate());
        assertThat(result.missing()).isEmpty();
        var v = result.values();
        assertThat(v.ma5()).isEqualByComparingTo("100");
        assertThat(v.rsi14()).isEqualByComparingTo("50");
        assertThat(v.macdLine()).isEqualByComparingTo("0");
        assertThat(v.macdSignal()).isEqualByComparingTo("0");
        assertThat(v.bollingerMiddle()).isEqualByComparingTo("100");
        assertThat(v.bollingerUpper()).isEqualByComparingTo("100");
        assertThat(v.adx14()).isEqualByComparingTo("0");
        assertThat(v.plusDi14()).isEqualByComparingTo("0");
        assertThat(v.minusDi14()).isEqualByComparingTo("0");
        assertThat(v.obv20Change()).isEqualByComparingTo("0");
        assertThat(v.volumeRatio20()).isEqualByComparingTo("1");
        assertThat(v.oneYearPositionPct()).isEqualByComparingTo("50");
    }

    @Test
    void shortHistoryStillProvidesIndicatorsWithEnoughBars() {
        List<StockPriceHistory> rows = rows(43, true);
        var result = SrppCompletedTechnicalCalculator.calculate(rows, rows.get(42).getTradingDate());
        assertThat(result.missing()).containsExactly("ONE_YEAR_HISTORY_OR_OHLC");
        var v = result.values();
        assertThat(v.rsi14()).isEqualByComparingTo("100");
        assertThat(v.adx14()).isEqualByComparingTo("100");
        assertThat(v.minusDi14()).isEqualByComparingTo("0");
        assertThat(v.macdLine()).isNotNull();
        assertThat(v.obv20Change()).isEqualByComparingTo("20000");
        assertThat(v.volumeRatio20()).isEqualByComparingTo("1");
        assertThat(v.oneYearPositionPct()).isNull();
    }

    @Test
    void missingLatestVolumeDoesNotErasePriceIndicators() {
        List<StockPriceHistory> rows = rows(43, true);
        rows.get(42).setVolume(null);
        var result = SrppCompletedTechnicalCalculator.calculate(rows, rows.get(42).getTradingDate());
        assertThat(result.values().rsi14()).isNotNull();
        assertThat(result.values().adx14()).isNotNull();
        assertThat(result.values().obv20Change()).isNull();
        assertThat(result.values().volumeRatio20()).isNull();
        assertThat(result.missing()).contains("OBV20_HISTORY_OR_VOLUME", "VOLUME_RATIO20_HISTORY_OR_VOLUME");
    }

    @Test
    void missingOldRatioBaselineDoesNotEraseObvChange() {
        List<StockPriceHistory> rows = rows(43, true);
        rows.get(22).setVolume(null); // previous 20-day average includes this bar; OBV20 does not.
        var result = SrppCompletedTechnicalCalculator.calculate(rows, rows.get(42).getTradingDate());
        assertThat(result.values().obv20Change()).isEqualByComparingTo("20000");
        assertThat(result.values().volumeRatio20()).isNull();
        assertThat(result.missing()).contains("VOLUME_RATIO20_HISTORY_OR_VOLUME");
        assertThat(result.missing()).doesNotContain("OBV20_HISTORY_OR_VOLUME");
    }

    @Test
    void requestedDateMissingNeverBorrowsPreviousBar() {
        var prices = mock(StockPriceHistoryRepository.class);
        var dividends = mock(StockDividendHistoryRepository.class);
        var adjustments = new DistributionAdjustedPriceService();
        LocalDate asOf = START.plusDays(50);
        when(prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                any(), any(), any(), any())).thenReturn(rows(50, false));
        var response = new SrppCompletedTechnicalService(prices, dividends, adjustments)
                .read("台股", asOf, List.of("0050"));
        assertThat(response.symbols().get(0).status()).isEqualTo("UNAVAILABLE");
        assertThat(response.symbols().get(0).missing()).containsExactly("AS_OF_BAR_MISSING");
        assertThat(response.symbols().get(0).indicators()).isNull();
        verifyNoInteractions(dividends);
    }

    private static List<StockPriceHistory> rows(int count, boolean up) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(up ? 100 + i : 100);
            rows.add(StockPriceHistory.builder()
                    .stockCode("0050").market("台股").tradingDate(START.plusDays(i))
                    .openPrice(close).highPrice(close.add(BigDecimal.ONE))
                    .lowPrice(close.subtract(BigDecimal.ONE)).closePrice(close)
                    .volume(1000L).closeSource("TEST").build());
        }
        return rows;
    }
}
