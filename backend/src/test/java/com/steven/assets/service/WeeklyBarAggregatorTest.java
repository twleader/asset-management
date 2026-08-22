package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 356.2：ISO 週 K 聚合。
 *
 * <p>本檔的週界案例（一般週、短週、ISO 跨年、單日成週、中段壞週）刻意與
 * {@code bff} 的 {@code ChartSeriesAlignerTest} 對應——兩者的<b>分桶規則必須逐字一致</b>，
 * 但<b>壞資料週的處理刻意不同</b>（BFF 整週丟棄、business 逐欄 null）。
 * 後者在 {@link #middleBadWeekKeepsBarWithNullColumns_whereBffDropsWholeWeek} 明確斷言，
 * 不留白，否則這個分歧不會被任何測試抓到。</p>
 */
class WeeklyBarAggregatorTest {

    // ───────────────────────── 一般週：open/high/low/close/volume ─────────────────────────

    @Test
    void openTakesFirstDayHighLowTakeExtremesCloseTakesLastDayAndVolumeSums() {
        List<StockPriceHistory> rows = new ArrayList<>();
        // ISO 2026-W02：週一到週五
        rows.add(row("2026-01-05", 10, 13, 9, 12, 100L));
        rows.add(row("2026-01-06", 12, 15, 11, 14, 200L));
        rows.add(row("2026-01-07", 14, 16, 8, 15, 300L));
        // ISO 2026-W03
        rows.add(row("2026-01-12", 15, 18, 14, 17, 400L));
        rows.add(row("2026-01-13", 17, 19, 16, 18, 500L));
        // ISO 2026-W04（最晚一週 → 進行中週）
        rows.add(row("2026-01-19", 18, 20, 17, 19, 600L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).hasSize(2);
        WeeklyBarAggregator.WeeklyBar latest = aggregation.completedDesc().get(0);
        assertThat(latest.weekEndDate()).isEqualTo(LocalDate.parse("2026-01-13"));
        assertThat(latest.open()).isEqualByComparingTo("15");
        assertThat(latest.high()).isEqualByComparingTo("19");
        assertThat(latest.low()).isEqualByComparingTo("14");
        assertThat(latest.close()).isEqualByComparingTo("18");
        assertThat(latest.volume()).isEqualTo(900L);

        WeeklyBarAggregator.WeeklyBar older = aggregation.completedDesc().get(1);
        assertThat(older.weekEndDate()).isEqualTo(LocalDate.parse("2026-01-07"));
        assertThat(older.open()).isEqualByComparingTo("10");
        assertThat(older.high()).isEqualByComparingTo("16");
        assertThat(older.low()).isEqualByComparingTo("8");
        assertThat(older.close()).isEqualByComparingTo("15");
        assertThat(older.volume()).isEqualTo(600L);
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<StockPriceHistory> ascending = List.of(
                row("2026-01-05", 10, 13, 9, 12, 100L),
                row("2026-01-06", 12, 15, 11, 14, 200L),
                row("2026-01-12", 15, 18, 14, 17, 400L));
        List<StockPriceHistory> descending = new ArrayList<>(ascending).reversed();

        assertThat(WeeklyBarAggregator.aggregate(descending).completedDesc())
                .isEqualTo(WeeklyBarAggregator.aggregate(ascending).completedDesc());
    }

    // ───────────────────────── 進行中週一律排除 ─────────────────────────

    @Test
    void latestIsoWeekIsAlwaysThePartialEvenWhenItLooksLikeAFullWeek() {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (String date : List.of("2026-01-05", "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09")) {
            rows.add(row(date, 10, 11, 9, 10, 100L));
        }
        // 完整的週一～週五，但它是序列中最晚的 ISO 週 → 仍然是進行中週。
        for (String date : List.of("2026-01-12", "2026-01-13", "2026-01-14", "2026-01-15", "2026-01-16")) {
            rows.add(row(date, 20, 21, 19, 20, 100L));
        }

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).hasSize(1);
        assertThat(aggregation.completedDesc().get(0).weekEndDate())
                .isEqualTo(LocalDate.parse("2026-01-09"));
        assertThat(aggregation.currentPartial()).isNotNull();
        assertThat(aggregation.currentPartial().weekEndDate())
                .isEqualTo(LocalDate.parse("2026-01-16"));
        assertThat(aggregation.completedDesc())
                .as("進行中週不得混進完成週")
                .noneMatch(bar -> bar.weekEndDate().equals(LocalDate.parse("2026-01-16")));
    }

    // ───────────────────────── ISO 跨年週 ─────────────────────────

    @Test
    void isoCrossYearWeekIsOneBarNotTwo() {
        // 2025-12-29（一）～2026-01-04（日）同屬 ISO 2026-W01；用 getYear() 分桶會被切成兩根。
        List<StockPriceHistory> rows = List.of(
                row("2025-12-29", 10, 12, 9, 11, 100L),
                row("2025-12-30", 11, 13, 10, 12, 100L),
                row("2026-01-02", 12, 15, 11, 14, 100L),
                // 下一個 ISO 週（2026-W02）＝進行中週
                row("2026-01-05", 14, 16, 13, 15, 100L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).hasSize(1);
        WeeklyBarAggregator.WeeklyBar bar = aggregation.completedDesc().get(0);
        assertThat(bar.weekEndDate()).isEqualTo(LocalDate.parse("2026-01-02"));
        assertThat(bar.open()).isEqualByComparingTo("10");
        assertThat(bar.high()).isEqualByComparingTo("15");
        assertThat(bar.low()).isEqualByComparingTo("9");
        assertThat(bar.close()).isEqualByComparingTo("14");
        assertThat(bar.volume()).isEqualTo(300L);
    }

    @Test
    void december28OfALeadingYearBelongsToNextIsoWeekBasedYear() {
        // 2026-12-28（一）屬 ISO 2027-W01：與 2027-01-01（五）同一根。
        List<StockPriceHistory> rows = List.of(
                row("2026-12-28", 10, 12, 9, 11, 100L),
                row("2027-01-01", 11, 14, 10, 13, 100L),
                row("2027-01-04", 13, 15, 12, 14, 100L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).hasSize(1);
        assertThat(aggregation.completedDesc().get(0).weekEndDate())
                .isEqualTo(LocalDate.parse("2027-01-01"));
        assertThat(aggregation.completedDesc().get(0).high()).isEqualByComparingTo("14");
    }

    // ───────────────────────── 單日成週／短週 ─────────────────────────

    @Test
    void singleTradingDayFormsItsOwnWeek() {
        List<StockPriceHistory> rows = List.of(
                row("2026-02-04", 10, 12, 9, 11, 100L),   // ISO 2026-W06，該週只有一天
                row("2026-02-09", 11, 13, 10, 12, 100L)); // ISO 2026-W07 → 進行中週

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).hasSize(1);
        WeeklyBarAggregator.WeeklyBar bar = aggregation.completedDesc().get(0);
        assertThat(bar.weekEndDate()).isEqualTo(LocalDate.parse("2026-02-04"));
        assertThat(bar.open()).isEqualByComparingTo("10");
        assertThat(bar.high()).isEqualByComparingTo("12");
        assertThat(bar.low()).isEqualByComparingTo("9");
        assertThat(bar.close()).isEqualByComparingTo("11");
    }

    // ───────────────────────── 缺值規則 ─────────────────────────

    @Test
    void wholeWeekVolumeIsNullWhenAnyDayMissesVolume() {
        List<StockPriceHistory> rows = List.of(
                row("2026-01-05", 10, 13, 9, 12, 100L),
                row("2026-01-06", 12, 15, 11, 14, null),
                row("2026-01-12", 15, 18, 14, 17, 400L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).hasSize(1);
        assertThat(aggregation.completedDesc().get(0).volume())
                .as("任一日缺量即整根缺值，不得以 0 補")
                .isNull();
        // 價格欄不受成交量缺值影響。
        assertThat(aggregation.completedDesc().get(0).close()).isEqualByComparingTo("14");
    }

    @Test
    void openIsNullWhenAnyDayMissesOpenAndIsNeverSubstitutedByClose() {
        List<StockPriceHistory> rows = List.of(
                row("2026-01-05", 10, 13, 9, 12, 100L),
                row("2026-01-06", null, 15, 11, 14, 100L),
                row("2026-01-12", 15, 18, 14, 17, 100L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc().get(0).open()).isNull();
        assertThat(aggregation.completedDesc().get(0).high()).isEqualByComparingTo("15");
        assertThat(aggregation.completedDesc().get(0).low()).isEqualByComparingTo("9");
    }

    @Test
    void weekIsDiscardedWhenLastDayHasNoClose() {
        List<StockPriceHistory> rows = List.of(
                row("2026-01-05", 10, 13, 9, 12, 100L),
                row("2026-01-06", 12, 15, 11, null, 100L),
                row("2026-01-12", 15, 18, 14, 17, 100L),
                row("2026-01-19", 18, 20, 17, 19, 100L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc())
                .as("2026-W02 的最晚交易日沒有收盤 → 整根丟棄，只剩 2026-W03")
                .hasSize(1);
        assertThat(aggregation.completedDesc().get(0).weekEndDate())
                .isEqualTo(LocalDate.parse("2026-01-12"));
    }

    /**
     * 與 BFF {@code ChartSeriesAligner.weekly} 的<b>刻意分歧</b>：同一組資料，週界完全相同，
     * 但壞資料週的處理不同。
     *
     * <p>BFF（{@code ChartSeriesAlignerTest} 的 {@code weekly支援一到四日短週與ISO跨年並在壞日整週failClosed}
     * 與 {@code daily截在最後完整OHLC且weekly以ISO週聚合}）只要該週任一日 candle 不合法就丟掉<b>整週</b>；
     * 本側改採<b>逐欄</b> null——{@code high}／{@code low} 缺值只讓那兩欄為 null，整根仍存在，
     * 再由 {@code RadarInputAssembler} 把它排除在<b>指標序列</b>之外（Task 356.3c-2）。</p>
     */
    @Test
    void middleBadWeekKeepsBarWithNullColumns_whereBffDropsWholeWeek() {
        List<StockPriceHistory> rows = List.of(
                // W02 正常
                row("2026-01-05", 10, 13, 9, 12, 100L),
                row("2026-01-06", 12, 15, 11, 14, 100L),
                // W03 中段壞週：2026-01-13 只有收盤（BFF 會丟掉整個 W03）
                row("2026-01-12", 14, 16, 13, 15, 100L),
                closeOnly("2026-01-13", 16),
                // W04 正常（進行中週）
                row("2026-01-19", 16, 18, 15, 17, 100L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc())
                .as("壞週仍成立一根（與 BFF 的整週丟棄刻意不同）")
                .hasSize(2);
        WeeklyBarAggregator.WeeklyBar bad = aggregation.completedDesc().get(0);
        assertThat(bad.weekEndDate()).isEqualTo(LocalDate.parse("2026-01-13"));
        assertThat(bad.open()).as("該週任一日缺 open → open 為 null").isNull();
        assertThat(bad.high()).as("該週任一日缺 high → high 為 null，不得以 close 冒充").isNull();
        assertThat(bad.low()).isNull();
        assertThat(bad.close())
                .as("close 取該週最晚交易日的收盤")
                .isEqualByComparingTo("16");
        // 週界與 BFF 完全相同：哪幾天算同一週、哪一天是週收盤都一致。
        assertThat(aggregation.completedDesc().get(1).weekEndDate())
                .isEqualTo(LocalDate.parse("2026-01-06"));
    }

    // ───────────────────────── 邊界 ─────────────────────────

    @Test
    void emptyInputsReturnEmptyAggregation() {
        assertThat(WeeklyBarAggregator.aggregate(null)).isEqualTo(WeeklyBarAggregator.Aggregation.EMPTY);
        assertThat(WeeklyBarAggregator.aggregate(List.of())).isEqualTo(WeeklyBarAggregator.Aggregation.EMPTY);
    }

    @Test
    void onlyOneIsoWeekMeansNoCompletedWeek() {
        List<StockPriceHistory> rows = List.of(
                row("2026-01-05", 10, 13, 9, 12, 100L),
                row("2026-01-06", 12, 15, 11, 14, 100L));

        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rows);

        assertThat(aggregation.completedDesc()).isEmpty();
        assertThat(aggregation.currentPartial()).isNotNull();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static StockPriceHistory row(
            String date, Integer open, Integer high, Integer low, Integer close, Long volume) {
        return StockPriceHistory.builder()
                .stockCode("2330").market("台股")
                .tradingDate(LocalDate.parse(date))
                .openPrice(open == null ? null : BigDecimal.valueOf(open))
                .highPrice(high == null ? null : BigDecimal.valueOf(high))
                .lowPrice(low == null ? null : BigDecimal.valueOf(low))
                .closePrice(close == null ? null : BigDecimal.valueOf(close))
                .volume(volume)
                .build();
    }

    /** 只有收盤的列（BFF 判定為 invalid candle 的形狀）。 */
    private static StockPriceHistory closeOnly(String date, int close) {
        return row(date, null, null, null, close, 100L);
    }
}
