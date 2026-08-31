package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Task408: a validated LOCAL snapshot must bypass both formula calculations. */
class RadarInputAssemblerLocalSnapshotReuseTest {

    @Test
    void preparedBasisWithFullLocalSnapshotDoesNotRecomputeDailyOrWeeklyIndicators() {
        TechnicalIndicatorService indicators = mock(TechnicalIndicatorService.class);
        RadarInputAssembler assembler = new RadarInputAssembler(indicators, new DistributionAdjustedPriceService(),
                new TradingRadarRuleEngine());
        List<StockPriceHistory> rows = List.of(
                row(LocalDate.of(2026, 8, 21), "110"),
                row(LocalDate.of(2026, 8, 20), "109"),
                row(LocalDate.of(2026, 8, 19), "108"));
        RadarInputAssembler.Prepared prepared = assembler.prepare(rows, List.<StockDividendHistory>of(), false,
                rows.size(), RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS, new BigDecimal("110"));
        TradingRadarRuleEngine.WeeklyInput weekly = new TradingRadarRuleEngine.WeeklyInput(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 8, 14), 1);

        RadarInputAssembler.Assembled result = assembler.calculate(prepared,
                TechnicalIndicatorService.FullIndicators.EMPTY, weekly,
                TechnicalIndicatorService.FullIndicators.EMPTY);

        assertThat(result.indicators()).isEqualTo(TechnicalIndicatorService.FullIndicators.EMPTY);
        assertThat(result.weekly()).isSameAs(weekly);
        verify(indicators, never()).computeFromSeries(org.mockito.ArgumentMatchers.anyList());
    }

    private static StockPriceHistory row(LocalDate date, String close) {
        BigDecimal value = new BigDecimal(close);
        return StockPriceHistory.builder().tradingDate(date).openPrice(value).highPrice(value)
                .lowPrice(value).closePrice(value).volume(1L).build();
    }
}
