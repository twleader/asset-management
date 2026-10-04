package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
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

class SrppTechnicalSeriesServiceTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    void returnsAdjustedDailyVolumesAndReproducibleObvWithExistingIndicatorCore() {
        var completed = mock(SrppCompletedTechnicalService.class);
        var technical = mock(TechnicalIndicatorService.class);
        List<StockPriceHistory> raw = rows(43, 100);
        List<StockPriceHistory> adjusted = rows(43, 200);
        LocalDate asOf = START.plusDays(42);
        var values = SrppCompletedTechnicalCalculator.calculate(adjusted, asOf).values();
        var facts = new SrppCompletedTechnicalService.SymbolFacts(
                "009826", "台股", "PARTIAL", asOf, START, 43,
                "ADJUSTED_RECORDED_EVENTS", List.of(START.plusDays(3)),
                "a".repeat(64), values, List.of("ONE_YEAR_HISTORY_OR_OHLC"));
        when(completed.loadOne("009826", "台股", asOf)).thenReturn(
                new SrppCompletedTechnicalService.LoadedSymbol(facts, raw, adjusted));
        var extended = new TechnicalIndicatorService.ExtendedIndicators(
                BigDecimal.valueOf(33), null, null, null, null, null, null, null,
                BigDecimal.valueOf(12), null, null, null, null, null);
        var full = new TechnicalIndicatorService.FullIndicators(
                BigDecimal.valueOf(20), null, null,
                BigDecimal.valueOf(25), null, null, null, null,
                extended, BigDecimal.valueOf(10));
        when(technical.computeFromSeries(any())).thenReturn(full);

        var response = new SrppTechnicalSeriesService(completed, technical)
                .read("台股", "009826", asOf, 21);

        assertThat(response.dailyBars()).hasSize(21);
        assertThat(response.dailyBars().get(0).tradingDate()).isEqualTo(START.plusDays(22));
        assertThat(response.dailyBars().get(20).tradingDate()).isEqualTo(asOf);
        assertThat(response.dailyBars().get(20).rawVolume()).isEqualTo(100);
        assertThat(response.dailyBars().get(20).volume()).isEqualTo(200);
        assertThat(response.dailyBars().get(20).closeSource()).isEqualTo("TEST");
        assertThat(response.dailyBars().get(20).obv20Change()).isEqualByComparingTo("4000");
        assertThat(response.summary().indicators().obv20Change()).isEqualByComparingTo("4000");
        assertThat(response.additionalIndicators().ma10()).isEqualByComparingTo("10");
        assertThat(response.additionalIndicators().ma20()).isEqualByComparingTo("20");
        assertThat(response.additionalIndicators().k9()).isEqualByComparingTo("25");
        assertThat(response.additionalIndicators().rsi5()).isEqualByComparingTo("12");
        assertThat(response.volumeUnit()).isEqualTo("SOURCE_UNIT_UNVERIFIED");
    }

    @Test
    void missingAsOfDoesNotReturnStaleBarsOrAdditionalIndicators() {
        var completed = mock(SrppCompletedTechnicalService.class);
        var technical = mock(TechnicalIndicatorService.class);
        LocalDate asOf = START.plusDays(43);
        var facts = new SrppCompletedTechnicalService.SymbolFacts(
                "009826", "台股", "UNAVAILABLE", asOf, START, 43,
                "UNAVAILABLE", List.of(), null, null, List.of("AS_OF_BAR_MISSING"));
        when(completed.loadOne("009826", "台股", asOf)).thenReturn(
                new SrppCompletedTechnicalService.LoadedSymbol(facts, rows(43, 100), List.of()));

        var response = new SrppTechnicalSeriesService(completed, technical)
                .read("台股", "009826", asOf, 60);

        assertThat(response.summary().status()).isEqualTo("UNAVAILABLE");
        assertThat(response.dailyBars()).isEmpty();
        assertThat(response.additionalIndicators()).isNull();
        verifyNoInteractions(technical);
    }

    private static List<StockPriceHistory> rows(int count, long volume) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            rows.add(StockPriceHistory.builder()
                    .stockCode("009826").market("台股").tradingDate(START.plusDays(i))
                    .openPrice(close).highPrice(close.add(BigDecimal.ONE))
                    .lowPrice(close.subtract(BigDecimal.ONE)).closePrice(close)
                    .volume(volume).closeSource("TEST").build());
        }
        return rows;
    }
}
