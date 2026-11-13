package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class RadarCompletedPriceDrawdownTest {
    static final LocalDate DAY = LocalDate.of(2026, 11, 13);

    static List<StockPriceHistory> rows(String code, String market) {
        List<StockPriceHistory> out = new ArrayList<>();
        for (LocalDate d = DAY; out.size() < 500; d = d.minusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            out.add(StockPriceHistory.builder().stockCode(code).market(market).tradingDate(d)
                    .openPrice(new BigDecimal("99")).highPrice(new BigDecimal("100"))
                    .lowPrice(new BigDecimal("98")).closePrice(new BigDecimal("99")).volume(100L).build());
        }
        return out;
    }
    static RadarCompletedPriceDrawdown.Context evaluate(List<StockPriceHistory> rows, int offset,
                                                        String code, String market, String price) {
        return RadarCompletedPriceDrawdown.evaluate(code, market, rows, offset, new BigDecimal(price),
                "台股".equals(market) ? RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE
                        : RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY, Set.of(), false);
    }

    @Test void calendarWindowIncludesBoundaryAndExcludesOlderAndLivePeaks() {
        var rows = rows("0050", "台股");
        var cutoff = DAY.minusWeeks(52);
        rows.stream().filter(r -> r.getTradingDate().equals(cutoff)).findFirst().orElseThrow()
                .setHighPrice(new BigDecimal("200"));
        rows.stream().filter(r -> r.getTradingDate().isBefore(cutoff)).forEach(r -> r.setHighPrice(new BigDecimal("9999")));
        rows.add(0, StockPriceHistory.builder().stockCode("0050").market("台股").tradingDate(DAY.plusDays(1))
                .closePrice(new BigDecimal("10000")).highPrice(new BigDecimal("10000")).build());
        var fact = evaluate(rows, 1, "0050", "台股", "160");
        assertThat(fact.status()).isEqualTo(RadarCompletedPriceDrawdown.Status.AVAILABLE);
        assertThat(fact.high()).isEqualByComparingTo("200");
        assertThat(fact.highDate()).isEqualTo(cutoff);
        assertThat(fact.eligible(new BigDecimal("160"))).isTrue();
    }

    @Test void fixed240RowsDoNotProve52CalendarWeekSpan() {
        var rows = rows("00881", "台股");
        assertThat(evaluate(rows.subList(0, 240), 0, "00881", "台股", "80").unavailableReason())
                .isEqualTo("INSUFFICIENT_52_WEEK_SPAN");
        rows.get(245).setHighPrice(new BigDecimal("150"));
        assertThat(evaluate(rows, 0, "00881", "台股", "80").high()).isEqualByComparingTo("150");
    }

    @Test void naturalTwelveDayHolidayIsAvailableButKnownDroppedRowIsUnavailable() {
        for (String code : List.of("0050", "00881")) {
            var rows = rows(code, "台股");
            rows.removeIf(r -> r.getTradingDate().isAfter(LocalDate.of(2026, 2, 11))
                    && r.getTradingDate().isBefore(LocalDate.of(2026, 2, 23)));
            assertThat(evaluate(rows, 0, code, "台股", "80").status()).isEqualTo(RadarCompletedPriceDrawdown.Status.AVAILABLE);
            var dropped = RadarCompletedPriceDrawdown.evaluate(code, "台股", rows, 0, new BigDecimal("80"),
                    RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE, Set.of(LocalDate.of(2026, 2, 24)), false);
            assertThat(dropped.unavailableReason()).isEqualTo("UPSTREAM_DROPPED_ROW");
        }
    }

    @Test void missingHighInvalidHighIdentityOrUndatedDataNeverUsesCloseAsPeak() {
        var rows = rows("NVDA", "美股");
        rows.get(20).setHighPrice(null);
        assertThat(evaluate(rows, 0, "NVDA", "美股", "70").unavailableReason()).isEqualTo("INVALID_OR_MISSING_HIGH");
        rows.get(20).setHighPrice(BigDecimal.ONE);
        assertThat(evaluate(rows, 0, "NVDA", "美股", "70").unavailableReason()).isEqualTo("INVALID_OR_MISSING_HIGH");
        rows.get(20).setHighPrice(new BigDecimal("100"));
        rows.get(20).setStockCode("GOOGL");
        assertThat(evaluate(rows, 0, "NVDA", "美股", "70").unavailableReason()).isEqualTo("ROW_IDENTITY_MISMATCH");
        assertThat(RadarCompletedPriceDrawdown.evaluate("NVDA", "美股", rows, 0, new BigDecimal("70"),
                RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY, Set.of(), true).unavailableReason())
                .isEqualTo("UPSTREAM_DROPPED_ROW");
    }

    @Test void preciseThresholdScopeAndPriceIdentityAreClosedForAllNineSymbols() {
        for (String market : List.of("台股", "美股")) {
            for (String code : "台股".equals(market) ? List.of("0050", "00881")
                    : List.of("GOOGL", "GOOG", "MSFT", "AVGO", "NVDA", "AAPL", "AMZN")) {
                BigDecimal boundary = new BigDecimal("台股".equals(market) ? "80" : "70");
                for (BigDecimal delta : List.of(new BigDecimal("-0.000000000000000001"), BigDecimal.ZERO,
                        new BigDecimal("0.000000000000000001"))) {
                    BigDecimal price = boundary.add(delta);
                    var fact = evaluate(rows(code, market), 0, code, market, price.toPlainString());
                    assertThat(fact.eligible(price)).isEqualTo(delta.signum() <= 0);
                    assertThat(fact.eligible(price.add(BigDecimal.ONE))).isFalse();
                }
            }
        }
        assertThat(RadarCompletedPriceDrawdown.selected("006208", "台股")).isFalse();
        assertThat(RadarCompletedPriceDrawdown.selected("NVDA", "台股")).isFalse();
        assertThat(RadarCompletedPriceDrawdown.selected(null, "台股")).isFalse();
        assertThat(RadarCompletedPriceDrawdown.Context.NOT_EVALUATED.eligible(BigDecimal.ONE)).isFalse();
    }

    @Test void twPriceReturnKeepsCashPeakAndNormalizesSplitWithoutChangingOriginalAdjust() {
        var rows = rows("0050", "台股");
        LocalDate ex = rows.get(20).getTradingDate();
        var dividend = StockDividendHistory.builder().id(1L).stockCode("0050").market("台股")
                .canonicalDate(ex).exDividendDate(ex).cashDividend(new BigDecimal("10"))
                .stockDividend(BigDecimal.ZERO).resolutionStatus("RESOLVED").sourceSnapshotId(1L).build();
        var service = new DistributionAdjustedPriceService();
        var price = service.priceReturnBasis(rows, List.of(dividend), "台股");
        assertThat(price.rowsDesc().get(100).getHighPrice()).isEqualByComparingTo("100");
        assertThat(service.adjust(rows, List.of(dividend)).rowsDesc().get(100).getHighPrice()).isLessThan(new BigDecimal("100"));
        rows.subList(100, rows.size()).forEach(r -> { r.setHighPrice(new BigDecimal("400"));
            r.setClosePrice(new BigDecimal("396")); r.setLowPrice(new BigDecimal("392")); r.setOpenPrice(new BigDecimal("396")); });
        var normalized = service.priceReturnBasis(rows, List.of(), "台股");
        var fact = evaluate(normalized.rowsDesc(), 0, "0050", "台股", "99");
        assertThat(fact.high()).isEqualByComparingTo("100");
        assertThat(fact.eligible(new BigDecimal("99"))).isFalse();
    }

    @Test void usPriceOnlyAcceptsComparableRowsAndRejectsKnownSplitOrRecentDividend() {
        var rows = rows("NVDA", "美股");
        var service = new DistributionAdjustedPriceService();
        assertThat(evaluate(service.priceReturnBasis(rows, List.of(), "美股").rowsDesc(), 0, "NVDA", "美股", "70")
                .eligible(new BigDecimal("70"))).isTrue();
        rows.get(20).setClosePrice(new BigDecimal("990"));
        assertThatThrownBy(() -> service.priceReturnBasis(rows, List.of(), "美股"))
                .hasMessageContaining("US_PRICE_ONLY_BASIS_BREAK");
        rows.get(20).setClosePrice(new BigDecimal("99"));
        var recent = StockDividendHistory.builder().stockCode("NVDA").market("美股")
                .canonicalDate(DAY).cashDividend(BigDecimal.ONE).build();
        assertThatThrownBy(() -> service.priceReturnBasis(rows, List.of(recent), "美股"))
                .hasMessageContaining("US_PRICE_ONLY_RECENT_EX_DIVIDEND");
    }
    @Test void foreignBoundaryCannotProveSpanAndCrossMarketBasisCannotAuthorizeBuy() {
        var rows = rows("0050", "台股");
        var from = DAY.minusWeeks(52);
        rows.removeIf(row -> row.getTradingDate().equals(from));
        var boundary = rows.stream().filter(row -> row.getTradingDate().isBefore(from)).findFirst().orElseThrow();
        boundary.setStockCode("00881");
        assertThat(evaluate(rows, 0, "0050", "台股", "80").unavailableReason()).isEqualTo("ROW_IDENTITY_MISMATCH");
        for (String market : List.of("台股", "美股")) {
            var context = new RadarCompletedPriceDrawdown.Context(RadarCompletedPriceDrawdown.Status.AVAILABLE,
                    "台股".equals(market) ? "0050" : "NVDA", market, from, DAY, DAY, new BigDecimal("100"),
                    new BigDecimal("70"), "台股".equals(market) ? RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY
                            : RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE, null);
            assertThat(context.eligible(new BigDecimal("70"))).isFalse();
        }
    }

}
