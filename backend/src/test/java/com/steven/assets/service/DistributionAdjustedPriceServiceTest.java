package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionAdjustedPriceServiceTest {

    private final DistributionAdjustedPriceService service =
            new DistributionAdjustedPriceService();

    @Test
    void cashDistributionDoesNotCreateFalseMediumTermBreakdown() {
        LocalDate first = LocalDate.of(2025, 7, 1);
        LocalDate exDate = first.plusDays(221);
        List<StockPriceHistory> raw = new ArrayList<>();
        for (int i = 0; i < 241; i++) {
            BigDecimal close = i < 221
                    ? new BigDecimal("100.00")
                    : new BigDecimal("90.00").add(BigDecimal.valueOf(i - 221)
                            .divide(BigDecimal.valueOf(19), 8, RoundingMode.HALF_UP));
            raw.add(row(first.plusDays(i), close));
        }
        raw = raw.reversed();

        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(1L)
                .stockCode("00751B")
                .market("台股")
                .exDividendDate(exDate)
                .cashDividend(new BigDecimal("10.00"))
                .build();

        var adjusted = service.adjust(raw, List.of(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("91.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertTrue(average(raw, 60).compareTo(new BigDecimal("91.00")) > 0,
                "原始季線會被除息前價格墊高");
        assertTrue(average(adjusted.rowsDesc(), 60).compareTo(new BigDecimal("91.00")) < 0,
                "還原權息後現價應站回季線之上");
    }

    @Test
    void noEffectiveEventIsExactNoOp() {
        List<StockPriceHistory> rows = List.of(
                row(LocalDate.of(2026, 7, 17), new BigDecimal("31.44")),
                row(LocalDate.of(2026, 7, 16), new BigDecimal("31.38")));

        var adjusted = service.adjust(rows, List.of());

        assertFalse(adjusted.adjusted());
        assertEquals(rows, adjusted.rowsDesc());
    }

    @Test
    void stockDistributionUsesParValueFactorAndKeepsLatestPrice() {
        LocalDate exDate = LocalDate.of(2026, 1, 2);
        List<StockPriceHistory> rows = List.of(
                row(exDate, new BigDecimal("100.00")),
                row(exDate.minusDays(1), new BigDecimal("110.00")));
        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(2L)
                .stockCode("TEST")
                .market("台股")
                .exDividendDate(exDate)
                .stockDividend(BigDecimal.ONE)
                .build();

        var adjusted = service.adjust(rows, List.of(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("100.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("100.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()));
        assertTrue(changePercent(rows.get(0).getClosePrice(), rows.get(1).getClosePrice())
                .compareTo(new BigDecimal("-5")) < 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(changePercent(
                adjusted.rowsDesc().get(0).getClosePrice(),
                adjusted.rowsDesc().get(1).getClosePrice())));
    }

    private StockPriceHistory row(LocalDate date, BigDecimal close) {
        return StockPriceHistory.builder()
                .stockCode("00751B")
                .market("台股")
                .tradingDate(date)
                .openPrice(close)
                .highPrice(close)
                .lowPrice(close)
                .closePrice(close)
                .build();
    }

    private BigDecimal average(List<StockPriceHistory> rowsDesc, int days) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) {
            sum = sum.add(rowsDesc.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(days), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        return current.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
