package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.TradingRadarRuleEngine.HorizonStructure;
import com.steven.assets.service.TradingRadarRuleEngine.StructureFacts;
import com.steven.assets.service.TradingRadarRuleEngine.StructureStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 577／Requirement 202：{@link RadarCompletedStructure} 的純函數判定。
 *
 * <p>三軌尺度：SHORT 日K SMA5／L=5（需 7 根）、SWING 日K SMA10／L=20（需 22 根）、
 * MEDIUM 週K SMA4／L=10（需 12 根）。</p>
 */
class RadarCompletedStructureTest {

    private static final LocalDate NEWEST_DAY = LocalDate.of(2026, 11, 13);       // 週五
    private static final LocalDate NEWEST_WEEK_END = LocalDate.of(2026, 11, 6);   // 週五

    private static final int SHORT_REQUIRED = 7;
    private static final int SWING_REQUIRED = 22;
    private static final int MEDIUM_REQUIRED = 12;

    // ───────────────────────── 樣本工具 ─────────────────────────

    /** 平盤基準：每根 h=101、l=99、c=100。index 0 為最新。 */
    private static double[][] flat(int n) {
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) out[i] = new double[] {101, 99, 100};
        return out;
    }

    /** 高檔轉弱：收盤 97→95、高點 101→96、連兩根低於確認均線。 */
    private static double[][] weakened(int n) {
        double[][] b = flat(n);
        b[1] = new double[] {101, 96, 97};
        b[0] = new double[] {96, 94, 95};
        return b;
    }

    /** 跌深止穩：低點 99.5→99.5→100 墊高、收盤 103 突破前三根且站上均線。 */
    private static double[][] stabilized(int n) {
        double[][] b = flat(n);
        b[2] = new double[] {101, 99.5, 100};
        b[1] = new double[] {101, 99.5, 100};
        b[0] = new double[] {104, 100, 103};
        return b;
    }

    /**
     * 支撐反覆測試後跌破：index 3（recent）與 index L+1（older）低點觸及 100，其間收盤 reboundClose，
     * 最新兩根收盤 98.5→98 皆低於 99。其餘基準 h=103、l=102、c=reboundClose（≤103）。
     */
    private static double[][] supportBroken(int n, int lookback, double reboundClose) {
        double[][] b = new double[n][];
        for (int i = 0; i < n; i++) b[i] = new double[] {103, 102, reboundClose};
        b[lookback + 1] = new double[] {102, 100, 101};
        b[3] = new double[] {102, 100.5, 101};
        b[1] = new double[] {99, 98, 98.5};
        b[0] = new double[] {98.8, 97.5, 98};
        return b;
    }

    private static List<StockPriceHistory> daily(double[][] hlc) {
        List<StockPriceHistory> out = new ArrayList<>();
        for (int i = 0; i < hlc.length; i++) out.add(day(NEWEST_DAY.minusDays(i), hlc[i]));
        return out;
    }

    private static StockPriceHistory day(LocalDate date, double[] hlc) {
        return StockPriceHistory.builder()
                .stockCode("TEST").market("台股").tradingDate(date)
                .openPrice(bd(hlc[2])).highPrice(bd(hlc[0])).lowPrice(bd(hlc[1])).closePrice(bd(hlc[2]))
                .volume(1000L).build();
    }

    private static List<WeeklyBarAggregator.WeeklyBar> weekly(double[][] hlc) {
        List<WeeklyBarAggregator.WeeklyBar> out = new ArrayList<>();
        for (int i = 0; i < hlc.length; i++) {
            out.add(new WeeklyBarAggregator.WeeklyBar(NEWEST_WEEK_END.minusWeeks(i),
                    bd(hlc[i][2]), bd(hlc[i][0]), bd(hlc[i][1]), bd(hlc[i][2]), 5000L));
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static StructureFacts dailyOnly(double[][] hlc) {
        return RadarCompletedStructure.evaluate(daily(hlc), List.of(), Set.of(), false);
    }

    private static StructureFacts weeklyOnly(double[][] hlc) {
        return RadarCompletedStructure.evaluate(List.of(), weekly(hlc), Set.of(), false);
    }

    private static void assertUnavailable(HorizonStructure hs, String reason) {
        assertThat(hs.status()).isEqualTo(StructureStatus.UNAVAILABLE);
        assertThat(hs.unavailableReason()).isEqualTo(reason);
        assertThat(hs.weakened()).isFalse();
        assertThat(hs.stabilized()).isFalse();
        assertThat(hs.supportBroken()).isFalse();
    }

    // ───────────────────────── 三軌正反例 ─────────────────────────

    @Test
    void short_weakened_strictHighLowerRequired() {
        HorizonStructure yes = dailyOnly(weakened(SHORT_REQUIRED)).shortTerm();
        assertThat(yes.status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(yes.weakened()).isTrue();
        assertThat(yes.stabilized()).isFalse();
        assertThat(yes.confirmationPeriod()).isEqualTo(5);
        assertThat(yes.asOf()).isEqualTo(NEWEST_DAY);
        assertThat(yes.confirmationSma()).isEqualByComparingTo("98.4");

        double[][] equalHigh = weakened(SHORT_REQUIRED);
        equalHigh[0] = new double[] {101, 94, 95};   // high0 == high1 → 非嚴格降低
        assertThat(dailyOnly(equalHigh).shortTerm().weakened()).isFalse();

        double[][] equalClose = weakened(SHORT_REQUIRED);
        equalClose[0] = new double[] {97, 94, 97};   // close0 == close1 → 非嚴格下跌
        assertThat(dailyOnly(equalClose).shortTerm().weakened()).isFalse();
    }

    @Test
    void short_stabilized_strictLowRisingRequired() {
        HorizonStructure yes = dailyOnly(stabilized(SHORT_REQUIRED)).shortTerm();
        assertThat(yes.status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(yes.stabilized()).isTrue();
        assertThat(yes.weakened()).isFalse();

        double[][] equalLow = stabilized(SHORT_REQUIRED);
        equalLow[0] = new double[] {104, 99.5, 103};   // low0 == low1 → 非嚴格墊高
        assertThat(dailyOnly(equalLow).shortTerm().stabilized()).isFalse();

        double[][] notBreakout = stabilized(SHORT_REQUIRED);
        notBreakout[3] = new double[] {104, 99, 103};  // close0 == 前三根最高收盤 → 未突破
        assertThat(dailyOnly(notBreakout).shortTerm().stabilized()).isFalse();
    }

    @Test
    void short_supportBroken_reboundBoundaryIsInclusive() {
        HorizonStructure yes = dailyOnly(supportBroken(SHORT_REQUIRED, 5, 103)).shortTerm();
        assertThat(yes.status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(yes.supportBroken()).isTrue();
        assertThat(yes.support()).isEqualByComparingTo("100");

        HorizonStructure no = dailyOnly(supportBroken(SHORT_REQUIRED, 5, 102.99)).shortTerm();
        assertThat(no.status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(no.supportBroken()).isFalse();

        double[][] notBelow = supportBroken(SHORT_REQUIRED, 5, 103);
        notBelow[1] = new double[] {99.5, 98.9, 99};   // close1 == support×0.99 → 非嚴格跌破
        notBelow[0] = new double[] {99, 97.5, 98};
        assertThat(dailyOnly(notBelow).shortTerm().supportBroken()).isFalse();
    }

    @Test
    void swing_weakenedStabilizedSupportBroken() {
        HorizonStructure weak = dailyOnly(weakened(SWING_REQUIRED)).swing();
        assertThat(weak.status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(weak.confirmationPeriod()).isEqualTo(10);
        assertThat(weak.weakened()).isTrue();

        double[][] equalHigh = weakened(SWING_REQUIRED);
        equalHigh[0] = new double[] {101, 94, 95};
        assertThat(dailyOnly(equalHigh).swing().weakened()).isFalse();

        assertThat(dailyOnly(stabilized(SWING_REQUIRED)).swing().stabilized()).isTrue();
        double[][] equalLow = stabilized(SWING_REQUIRED);
        equalLow[0] = new double[] {104, 99.5, 103};
        assertThat(dailyOnly(equalLow).swing().stabilized()).isFalse();

        assertThat(dailyOnly(supportBroken(SWING_REQUIRED, 20, 103)).swing().supportBroken()).isTrue();
        assertThat(dailyOnly(supportBroken(SWING_REQUIRED, 20, 102.99)).swing().supportBroken()).isFalse();
    }

    @Test
    void medium_weeklyWeakenedStabilizedSupportBroken() {
        HorizonStructure weak = weeklyOnly(weakened(MEDIUM_REQUIRED)).medium();
        assertThat(weak.status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(weak.confirmationPeriod()).isEqualTo(4);
        assertThat(weak.asOf()).isEqualTo(NEWEST_WEEK_END);
        assertThat(weak.weakened()).isTrue();

        double[][] equalHigh = weakened(MEDIUM_REQUIRED);
        equalHigh[0] = new double[] {101, 94, 95};
        assertThat(weeklyOnly(equalHigh).medium().weakened()).isFalse();

        assertThat(weeklyOnly(stabilized(MEDIUM_REQUIRED)).medium().stabilized()).isTrue();
        double[][] equalLow = stabilized(MEDIUM_REQUIRED);
        equalLow[0] = new double[] {104, 99.5, 103};
        assertThat(weeklyOnly(equalLow).medium().stabilized()).isFalse();

        assertThat(weeklyOnly(supportBroken(MEDIUM_REQUIRED, 10, 103)).medium().supportBroken()).isTrue();
        assertThat(weeklyOnly(supportBroken(MEDIUM_REQUIRED, 10, 102.99)).medium().supportBroken()).isFalse();
    }

    // ───────────────────────── UNAVAILABLE ─────────────────────────

    @Test
    void insufficientBars_eachHorizon() {
        StructureFacts shortOnly = dailyOnly(flat(SHORT_REQUIRED - 1));
        assertUnavailable(shortOnly.shortTerm(), RadarCompletedStructure.INSUFFICIENT_BARS);
        StructureFacts swingShort = dailyOnly(flat(SWING_REQUIRED - 1));
        assertThat(swingShort.shortTerm().status()).isEqualTo(StructureStatus.AVAILABLE);
        assertUnavailable(swingShort.swing(), RadarCompletedStructure.INSUFFICIENT_BARS);
        assertUnavailable(weeklyOnly(flat(MEDIUM_REQUIRED - 1)).medium(), RadarCompletedStructure.INSUFFICIENT_BARS);
        assertThat(weeklyOnly(flat(MEDIUM_REQUIRED)).medium().status()).isEqualTo(StructureStatus.AVAILABLE);
    }

    @Test
    void duplicateOrUnorderedDates_areUnavailable() {
        List<StockPriceHistory> duplicate = new ArrayList<>(daily(flat(SHORT_REQUIRED)));
        duplicate.set(1, day(NEWEST_DAY, new double[] {101, 99, 100}));
        assertUnavailable(RadarCompletedStructure.evaluate(duplicate, List.of(), Set.of(), false).shortTerm(),
                RadarCompletedStructure.NON_DESC_DATES);

        List<StockPriceHistory> unordered = new ArrayList<>(daily(flat(SHORT_REQUIRED)));
        StockPriceHistory a = unordered.get(2);
        unordered.set(2, unordered.get(3));
        unordered.set(3, a);
        assertUnavailable(RadarCompletedStructure.evaluate(unordered, List.of(), Set.of(), false).shortTerm(),
                RadarCompletedStructure.NON_DESC_DATES);
    }

    @Test
    void nonPositiveOrInconsistentHlc_areUnavailable() {
        double[][] zeroLow = flat(SHORT_REQUIRED);
        zeroLow[4] = new double[] {101, 0, 100};
        assertUnavailable(dailyOnly(zeroLow).shortTerm(), RadarCompletedStructure.INVALID_HLC);

        double[][] lowAboveClose = flat(SHORT_REQUIRED);
        lowAboveClose[2] = new double[] {101, 100.5, 100};
        assertUnavailable(dailyOnly(lowAboveClose).shortTerm(), RadarCompletedStructure.INVALID_HLC);

        double[][] highBelowClose = flat(SHORT_REQUIRED);
        highBelowClose[0] = new double[] {99.5, 99, 100};
        assertUnavailable(dailyOnly(highBelowClose).shortTerm(), RadarCompletedStructure.INVALID_HLC);

        List<StockPriceHistory> nullClose = new ArrayList<>(daily(flat(SHORT_REQUIRED)));
        nullClose.get(5).setClosePrice(null);
        assertUnavailable(RadarCompletedStructure.evaluate(nullClose, List.of(), Set.of(), false).shortTerm(),
                RadarCompletedStructure.INVALID_HLC);
    }

    @Test
    void weeklyBarMissingHighOrLow_unfilteredInput_isUnavailable() {
        List<WeeklyBarAggregator.WeeklyBar> bars = new ArrayList<>(weekly(flat(MEDIUM_REQUIRED)));
        WeeklyBarAggregator.WeeklyBar b = bars.get(5);
        bars.set(5, new WeeklyBarAggregator.WeeklyBar(b.weekEndDate(), b.open(), null, b.low(), b.close(), b.volume()));
        assertUnavailable(RadarCompletedStructure.evaluate(List.of(), bars, Set.of(), false).medium(),
                RadarCompletedStructure.INVALID_HLC);

        WeeklyBarAggregator.WeeklyBar c = bars.get(5);
        bars.set(5, new WeeklyBarAggregator.WeeklyBar(c.weekEndDate(), c.open(), bd(101), null, c.close(), c.volume()));
        assertUnavailable(RadarCompletedStructure.evaluate(List.of(), bars, Set.of(), false).medium(),
                RadarCompletedStructure.INVALID_HLC);
    }

    @Test
    void openNullOrNonPositive_doesNotAffectResult() {
        List<StockPriceHistory> base = daily(weakened(SWING_REQUIRED));
        StructureFacts expected = RadarCompletedStructure.evaluate(base, List.of(), Set.of(), false);

        List<StockPriceHistory> nullOpen = daily(weakened(SWING_REQUIRED));
        nullOpen.forEach(r -> r.setOpenPrice(null));
        List<StockPriceHistory> negativeOpen = daily(weakened(SWING_REQUIRED));
        negativeOpen.forEach(r -> r.setOpenPrice(new BigDecimal("-1")));

        assertThat(RadarCompletedStructure.evaluate(nullOpen, List.of(), Set.of(), false)).isEqualTo(expected);
        assertThat(RadarCompletedStructure.evaluate(negativeOpen, List.of(), Set.of(), false)).isEqualTo(expected);
        assertThat(expected.shortTerm().status()).isEqualTo(StructureStatus.AVAILABLE);
    }

    @Test
    void droppedDateInsideMediumWeek_singleDay_isUpstreamDropped() {
        List<WeeklyBarAggregator.WeeklyBar> bars = weekly(flat(MEDIUM_REQUIRED));
        // 第 4 根完成週的週三被上游剔除；其餘日列聚合的週 HLC 合法。
        LocalDate wednesday = bars.get(4).weekEndDate().with(DayOfWeek.WEDNESDAY);
        assertUnavailable(RadarCompletedStructure.evaluate(List.of(), bars, Set.of(wednesday), false).medium(),
                RadarCompletedStructure.UPSTREAM_DROPPED_ROW);
        // 對照：剔除日落在所用 12 週之外 → 仍可用。
        LocalDate outside = bars.get(MEDIUM_REQUIRED - 1).weekEndDate().with(DayOfWeek.MONDAY).minusDays(1);
        assertThat(RadarCompletedStructure.evaluate(List.of(), bars, Set.of(outside), false).medium().status())
                .isEqualTo(StructureStatus.AVAILABLE);
    }

    @Test
    void wholeWeekDropped_adjacentWeeksExactly14DaysApart_isUpstreamDropped() {
        List<WeeklyBarAggregator.WeeklyBar> full = weekly(flat(MEDIUM_REQUIRED + 1));
        List<WeeklyBarAggregator.WeeklyBar> missingWeek = new ArrayList<>(full);
        WeeklyBarAggregator.WeeklyBar removed = missingWeek.remove(6);
        Set<LocalDate> wholeWeek = Set.of(
                removed.weekEndDate().with(DayOfWeek.MONDAY), removed.weekEndDate().with(DayOfWeek.TUESDAY),
                removed.weekEndDate().with(DayOfWeek.WEDNESDAY), removed.weekEndDate().with(DayOfWeek.THURSDAY),
                removed.weekEndDate());
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(
                missingWeek.get(6).weekEndDate(), missingWeek.get(5).weekEndDate())).isEqualTo(14);

        assertUnavailable(RadarCompletedStructure.evaluate(List.of(), missingWeek, wholeWeek, false).medium(),
                RadarCompletedStructure.UPSTREAM_DROPPED_ROW);
        // 無剔除列的 14 天自然缺週（長假）→ 仍 AVAILABLE。
        assertThat(RadarCompletedStructure.evaluate(List.of(), missingWeek, Set.of(), false).medium().status())
                .isEqualTo(StructureStatus.AVAILABLE);
    }

    @Test
    void calendarGaps_dailyOver10_weeklyOver14() {
        List<StockPriceHistory> gap11 = new ArrayList<>();
        List<StockPriceHistory> gap10 = new ArrayList<>();
        double[][] hlc = flat(SHORT_REQUIRED);
        for (int i = 0; i < SHORT_REQUIRED; i++) {
            gap11.add(day(i < 3 ? NEWEST_DAY.minusDays(i) : NEWEST_DAY.minusDays(i + 10), hlc[i]));
            gap10.add(day(i < 3 ? NEWEST_DAY.minusDays(i) : NEWEST_DAY.minusDays(i + 9), hlc[i]));
        }
        assertUnavailable(RadarCompletedStructure.evaluate(gap11, List.of(), Set.of(), false).shortTerm(),
                RadarCompletedStructure.CALENDAR_GAP);
        assertThat(RadarCompletedStructure.evaluate(gap10, List.of(), Set.of(), false).shortTerm().status())
                .isEqualTo(StructureStatus.AVAILABLE);

        List<WeeklyBarAggregator.WeeklyBar> weeks = new ArrayList<>(weekly(flat(MEDIUM_REQUIRED + 2)));
        weeks.remove(4);
        weeks.remove(4);   // 相鄰兩根相隔 21 天
        assertUnavailable(RadarCompletedStructure.evaluate(List.of(), weeks, Set.of(), false).medium(),
                RadarCompletedStructure.CALENDAR_GAP);
    }

    @Test
    void droppedDailyDateInsideWindowOrUndatedFlag_isUpstreamDropped() {
        List<StockPriceHistory> rows = daily(flat(SWING_REQUIRED));
        StructureFacts inShort = RadarCompletedStructure.evaluate(rows, List.of(), Set.of(NEWEST_DAY.minusDays(3)), false);
        assertUnavailable(inShort.shortTerm(), RadarCompletedStructure.UPSTREAM_DROPPED_ROW);
        assertUnavailable(inShort.swing(), RadarCompletedStructure.UPSTREAM_DROPPED_ROW);

        // 只落在 SWING 視窗、不在 SHORT 視窗。
        StructureFacts swingOnly = RadarCompletedStructure.evaluate(rows, List.of(), Set.of(NEWEST_DAY.minusDays(15)), false);
        assertThat(swingOnly.shortTerm().status()).isEqualTo(StructureStatus.AVAILABLE);
        assertUnavailable(swingOnly.swing(), RadarCompletedStructure.UPSTREAM_DROPPED_ROW);

        StructureFacts undated = RadarCompletedStructure.evaluate(rows, weekly(flat(MEDIUM_REQUIRED)), Set.of(), true);
        assertUnavailable(undated.shortTerm(), RadarCompletedStructure.UPSTREAM_DROPPED_ROW);
        assertUnavailable(undated.swing(), RadarCompletedStructure.UPSTREAM_DROPPED_ROW);
        assertUnavailable(undated.medium(), RadarCompletedStructure.UPSTREAM_DROPPED_ROW);
    }

    @Test
    void neverReadsBeyondRequiredBars() {
        List<StockPriceHistory> exact = daily(weakened(SHORT_REQUIRED));
        List<StockPriceHistory> longer = new ArrayList<>(exact);
        longer.add(null);                                                      // 第 required 根以後的壞列
        longer.add(day(NEWEST_DAY.plusDays(30), new double[] {1, 5, -3}));    // 亂序且 HLC 不合法
        HorizonStructure a = RadarCompletedStructure.evaluate(exact, List.of(), Set.of(), false).shortTerm();
        HorizonStructure b = RadarCompletedStructure.evaluate(longer, List.of(), Set.of(), false).shortTerm();
        assertThat(b).isEqualTo(a);
        assertThat(a.status()).isEqualTo(StructureStatus.AVAILABLE);

        List<WeeklyBarAggregator.WeeklyBar> weeks = new ArrayList<>(weekly(weakened(MEDIUM_REQUIRED)));
        HorizonStructure w1 = RadarCompletedStructure.evaluate(List.of(), weeks, Set.of(), false).medium();
        weeks.add(null);
        HorizonStructure w2 = RadarCompletedStructure.evaluate(List.of(), weeks, Set.of(), false).medium();
        assertThat(w2).isEqualTo(w1);
    }

    /** get(index ≥ limit) 即拋例外的輸入：證明判定從不讀取所需根數以後的列（含轉換階段）。 */
    private static <T> List<T> guarded(List<T> backing, int limit) {
        return new java.util.AbstractList<>() {
            @Override
            public T get(int index) {
                if (index >= limit) throw new AssertionError("讀到第 " + index + " 根（上限 " + limit + "）");
                return backing.get(index);
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    @Test
    void neverReadsBeyondRequiredBars_guardedInputThrowsOnOverRead() {
        List<StockPriceHistory> rows = daily(weakened(SWING_REQUIRED + 30));
        List<WeeklyBarAggregator.WeeklyBar> weeks = weekly(weakened(MEDIUM_REQUIRED + 30));
        StructureFacts expected = RadarCompletedStructure.evaluate(
                rows.subList(0, SWING_REQUIRED), weeks.subList(0, MEDIUM_REQUIRED), Set.of(), false);

        StructureFacts guardedResult = RadarCompletedStructure.evaluate(
                guarded(rows, SWING_REQUIRED), guarded(weeks, MEDIUM_REQUIRED), Set.of(), false);

        assertThat(guardedResult).isEqualTo(expected);
        assertThat(guardedResult.shortTerm().status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(guardedResult.swing().status()).isEqualTo(StructureStatus.AVAILABLE);
        assertThat(guardedResult.medium().status()).isEqualTo(StructureStatus.AVAILABLE);
    }

    @Test
    void nullInputs_areUnavailableNotException() {
        StructureFacts facts = RadarCompletedStructure.evaluate(null, null, null, false);
        assertUnavailable(facts.shortTerm(), RadarCompletedStructure.INSUFFICIENT_BARS);
        assertUnavailable(facts.swing(), RadarCompletedStructure.INSUFFICIENT_BARS);
        assertUnavailable(facts.medium(), RadarCompletedStructure.INSUFFICIENT_BARS);
    }
}
