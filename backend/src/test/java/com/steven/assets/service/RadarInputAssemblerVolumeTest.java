package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/** Task 291 個股相對量守門：完成日、分母排除與最小樣本不可漂移。 */
class RadarInputAssemblerVolumeTest {

    private final RadarInputAssembler assembler = new RadarInputAssembler(
            mock(TechnicalIndicatorService.class), mock(DistributionAdjustedPriceService.class),
            mock(TradingRadarRuleEngine.class));

    @Test
    void latestCompletedDayIsComparedWithPriorMedianAndLiveRowIsExcluded() {
        List<StockPriceHistory> rows = new ArrayList<>();
        rows.add(row(0, 9_999L));       // 盤中列，不是本次完成日
        rows.add(row(1, 200L));         // latestCompletedIndex = 1
        for (int i = 2; i < 12; i++) rows.add(row(i, 100L));

        assertEquals("2.0000", assembler.volumeRatio(rows, 1).toPlainString());
    }

    @Test
    void fewerThanTenPositivePriorSamplesIsUnavailable() {
        List<StockPriceHistory> rows = new ArrayList<>();
        rows.add(row(0, 200L));
        for (int i = 1; i < 10; i++) rows.add(row(i, i == 9 ? 0L : 100L));

        assertNull(assembler.volumeRatio(rows, 0));
    }

    private static StockPriceHistory row(int daysAgo, Long volume) {
        return StockPriceHistory.builder()
                .stockCode("2330").market("台股")
                .tradingDate(LocalDate.of(2026, 8, 8).minusDays(daysAgo))
                .volume(volume).build();
    }
}
