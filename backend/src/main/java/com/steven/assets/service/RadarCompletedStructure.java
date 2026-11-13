package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 完成 K 價格結構判定（Task 577／Requirement 202，{@code TW_RULES_V25}）。
 *
 * <p><b>純函數</b>：不讀時鐘、不做 IO、不依賴 Spring 或資料庫；同一輸入恆得同一輸出。
 * production（經 {@link RadarInputAssembler}）與回測（{@link BacktestService} 經同一 assembler）
 * 共用本類別唯一入口 {@link #evaluate}，不得各自另寫一份。</p>
 *
 * <p>本判定只作為減碼與試單的<b>額外</b>確認（只加嚴、不放寬），以及支撐破位的風險揭露；
 * 不進分數、不改門檻，也不會因支撐破位而在低檔給賣出。結構只用 high／low／close，open 不參與、不驗。</p>
 *
 * <p>三軌尺度、間距容忍與支撐比例<b>皆為判斷性初值，未經回測</b>，不得宣稱可提高獲利。</p>
 */
public final class RadarCompletedStructure {

    private static final MathContext MC = MathContext.DECIMAL128;

    /** 一周軌確認均線期數（日K）。判斷性初值，未經回測。 */
    public static final int SHORT_CONFIRM_PERIOD = 5;
    /** 一周軌支撐回看根數 L（日K）。判斷性初值，未經回測。 */
    public static final int SHORT_SUPPORT_LOOKBACK = 5;
    /** 1周~1月軌確認均線期數（日K）。判斷性初值，未經回測。 */
    public static final int SWING_CONFIRM_PERIOD = 10;
    /** 1周~1月軌支撐回看根數 L（日K）。判斷性初值，未經回測。 */
    public static final int SWING_SUPPORT_LOOKBACK = 20;
    /** 1月~6月軌確認均線期數（週K）。判斷性初值，未經回測。 */
    public static final int MEDIUM_CONFIRM_PERIOD = 4;
    /** 1月~6月軌支撐回看根數 L（週K）。判斷性初值，未經回測。 */
    public static final int MEDIUM_SUPPORT_LOOKBACK = 10;

    /** 相鄰兩根日K 容許的最大日曆日差（可涵蓋農曆年長假）。判斷性初值，未經回測。 */
    public static final int DAILY_MAX_GAP_DAYS = 10;
    /** 相鄰兩根週K 容許的最大日曆日差（自然缺一週）。判斷性初值，未經回測。 */
    public static final int WEEKLY_MAX_GAP_DAYS = 14;

    /** 支撐測試帶：low ≤ support × 1.01。判斷性初值，未經回測。 */
    private static final BigDecimal SUPPORT_TOUCH = new BigDecimal("1.01");
    /** 兩次測試之間的反彈：close ≥ support × 1.03。判斷性初值，未經回測。 */
    private static final BigDecimal SUPPORT_REBOUND = new BigDecimal("1.03");
    /** 確認跌破：close < support × 0.99。判斷性初值，未經回測。 */
    private static final BigDecimal SUPPORT_BREAK = new BigDecimal("0.99");

    public static final String INSUFFICIENT_BARS = "INSUFFICIENT_BARS";
    public static final String MISSING_ROW = "MISSING_ROW";
    public static final String MISSING_DATE = "MISSING_DATE";
    public static final String NON_DESC_DATES = "NON_DESC_DATES";
    public static final String INVALID_HLC = "INVALID_HLC";
    public static final String UPSTREAM_DROPPED_ROW = "UPSTREAM_DROPPED_ROW";
    public static final String CALENDAR_GAP = "CALENDAR_GAP";
    /** 沒有任何可用技術序列（{@code Assembled.EMPTY}）。 */
    public static final String NO_PRICE_SERIES = "NO_PRICE_SERIES";

    private RadarCompletedStructure() {}

    /** 結構計算所需的中性 K 棒（只含 high／low／close 與日期）。 */
    private record Bar(LocalDate date, BigDecimal high, BigDecimal low, BigDecimal close) {}

    /**
     * 唯一入口：三軌完成 K 結構判定。
     *
     * @param completedDailyDesc            只含完成日（不含盤中列）的日K，依交易日嚴格降序。
     * @param completedWeeklyDescUnfiltered 只含完成週的週K（降序），<b>未經</b>缺 high／low 過濾。
     * @param droppedDailyDates             上游剔除列（非正收盤等）的交易日；無剔除時傳空集合。
     * @param droppedUndatedDailyRow        上游是否剔除過日期為 null 的列；無時傳 false。
     */
    public static TradingRadarRuleEngine.StructureFacts evaluate(
            List<StockPriceHistory> completedDailyDesc,
            List<WeeklyBarAggregator.WeeklyBar> completedWeeklyDescUnfiltered,
            Set<LocalDate> droppedDailyDates,
            boolean droppedUndatedDailyRow) {
        Set<LocalDate> dropped = droppedDailyDates == null ? Set.of() : droppedDailyDates;
        // 只轉換各軌實際會用到的最多根數：日K 取 SHORT／SWING 所需較大者、週K 取 MEDIUM 所需；
        // 不得讀取（get）第 required 根以後的列。
        List<Bar> daily = dailyBars(completedDailyDesc, Math.max(
                required(SHORT_CONFIRM_PERIOD, SHORT_SUPPORT_LOOKBACK),
                required(SWING_CONFIRM_PERIOD, SWING_SUPPORT_LOOKBACK)));
        List<Bar> weekly = weeklyBars(completedWeeklyDescUnfiltered,
                required(MEDIUM_CONFIRM_PERIOD, MEDIUM_SUPPORT_LOOKBACK));
        return new TradingRadarRuleEngine.StructureFacts(
                horizon(daily, false, SHORT_CONFIRM_PERIOD, SHORT_SUPPORT_LOOKBACK, dropped, droppedUndatedDailyRow),
                horizon(daily, false, SWING_CONFIRM_PERIOD, SWING_SUPPORT_LOOKBACK, dropped, droppedUndatedDailyRow),
                horizon(weekly, true, MEDIUM_CONFIRM_PERIOD, MEDIUM_SUPPORT_LOOKBACK, dropped, droppedUndatedDailyRow));
    }

    /** 三軌皆 UNAVAILABLE（例如沒有任何序列）。 */
    public static TradingRadarRuleEngine.StructureFacts unavailableAll(String reason) {
        return new TradingRadarRuleEngine.StructureFacts(
                TradingRadarRuleEngine.HorizonStructure.unavailable(reason, SHORT_CONFIRM_PERIOD),
                TradingRadarRuleEngine.HorizonStructure.unavailable(reason, SWING_CONFIRM_PERIOD),
                TradingRadarRuleEngine.HorizonStructure.unavailable(reason, MEDIUM_CONFIRM_PERIOD));
    }

    /** 某軌所需根數：{@code max(confirm + 1, 4, L + 2)}。 */
    static int required(int confirm, int lookback) {
        return Math.max(Math.max(confirm + 1, 4), lookback + 2);
    }

    private static List<Bar> dailyBars(List<StockPriceHistory> rows, int limit) {
        if (rows == null) return List.of();
        int n = Math.min(rows.size(), limit);
        List<Bar> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StockPriceHistory row = rows.get(i);
            out.add(row == null ? null
                    : new Bar(row.getTradingDate(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice()));
        }
        return out;
    }

    private static List<Bar> weeklyBars(List<WeeklyBarAggregator.WeeklyBar> bars, int limit) {
        if (bars == null) return List.of();
        int n = Math.min(bars.size(), limit);
        List<Bar> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            WeeklyBarAggregator.WeeklyBar bar = bars.get(i);
            out.add(bar == null ? null : new Bar(bar.weekEndDate(), bar.high(), bar.low(), bar.close()));
        }
        return out;
    }

    private static TradingRadarRuleEngine.HorizonStructure horizon(
            List<Bar> bars, boolean weekly, int confirm, int lookback,
            Set<LocalDate> dropped, boolean droppedUndated) {
        int required = required(confirm, lookback);
        if (bars.size() < required) {
            return TradingRadarRuleEngine.HorizonStructure.unavailable(INSUFFICIENT_BARS, confirm);
        }
        // 只看所需視窗（索引 0..required-1）；不得跳過壞列補齊、不得讀第 required 根以後。
        for (int i = 0; i < required; i++) {
            Bar bar = bars.get(i);
            if (bar == null) return TradingRadarRuleEngine.HorizonStructure.unavailable(MISSING_ROW, confirm);
            if (bar.date() == null) {
                return TradingRadarRuleEngine.HorizonStructure.unavailable(MISSING_DATE, confirm);
            }
            if (i > 0 && !bar.date().isBefore(bars.get(i - 1).date())) {
                return TradingRadarRuleEngine.HorizonStructure.unavailable(NON_DESC_DATES, confirm);
            }
            if (!validHlc(bar)) return TradingRadarRuleEngine.HorizonStructure.unavailable(INVALID_HLC, confirm);
        }
        LocalDate newest = bars.get(0).date();
        LocalDate oldest = bars.get(required - 1).date();
        LocalDate rangeFrom = weekly ? oldest.with(DayOfWeek.MONDAY) : oldest;
        LocalDate rangeTo = weekly ? newest.with(DayOfWeek.SUNDAY) : newest;
        if (droppedUndated || dropped.stream().anyMatch(date -> date != null
                && !date.isBefore(rangeFrom) && !date.isAfter(rangeTo))) {
            return TradingRadarRuleEngine.HorizonStructure.unavailable(UPSTREAM_DROPPED_ROW, confirm);
        }
        int maxGap = weekly ? WEEKLY_MAX_GAP_DAYS : DAILY_MAX_GAP_DAYS;
        for (int i = 1; i < required; i++) {
            if (ChronoUnit.DAYS.between(bars.get(i).date(), bars.get(i - 1).date()) > maxGap) {
                return TradingRadarRuleEngine.HorizonStructure.unavailable(CALENDAR_GAP, confirm);
            }
        }

        BigDecimal close0 = bars.get(0).close();
        BigDecimal close1 = bars.get(1).close();
        BigDecimal sma0 = sma(bars, 0, confirm);
        BigDecimal sma1 = sma(bars, 1, confirm);
        boolean weakened = close0.compareTo(close1) < 0
                && close0.compareTo(sma0) < 0
                && close1.compareTo(sma1) < 0
                && bars.get(0).high().compareTo(bars.get(1).high()) < 0;
        BigDecimal maxPriorClose = bars.get(1).close().max(bars.get(2).close()).max(bars.get(3).close());
        boolean stabilized = bars.get(0).low().compareTo(bars.get(1).low()) > 0
                && bars.get(1).low().compareTo(bars.get(2).low()) >= 0
                && close0.compareTo(maxPriorClose) > 0
                && close0.compareTo(sma0) > 0;

        BigDecimal support = bars.get(2).low();
        for (int i = 3; i <= lookback + 1; i++) support = support.min(bars.get(i).low());
        boolean supportBroken = supportBroken(bars, lookback, support, close0, close1);

        return new TradingRadarRuleEngine.HorizonStructure(
                TradingRadarRuleEngine.StructureStatus.AVAILABLE,
                weakened, stabilized, supportBroken, support, newest, confirm, sma0, null);
    }

    private static boolean supportBroken(List<Bar> bars, int lookback, BigDecimal support,
                                         BigDecimal close0, BigDecimal close1) {
        BigDecimal touch = support.multiply(SUPPORT_TOUCH, MC);
        BigDecimal rebound = support.multiply(SUPPORT_REBOUND, MC);
        BigDecimal breakLevel = support.multiply(SUPPORT_BREAK, MC);
        if (!(close0.compareTo(breakLevel) < 0 && close1.compareTo(breakLevel) < 0
                && close0.compareTo(close1) < 0)) {
            return false;
        }
        int last = lookback + 1;
        for (int recent = 2; recent <= last; recent++) {
            if (bars.get(recent).low().compareTo(touch) > 0) continue;
            for (int older = recent + 3; older <= last; older++) {
                if (bars.get(older).low().compareTo(touch) > 0) continue;
                for (int between = recent + 1; between < older; between++) {
                    if (bars.get(between).close().compareTo(rebound) >= 0) return true;
                }
            }
        }
        return false;
    }

    /** SMA(i, m)：第 i 根起（含）往舊 m 根收盤平均。 */
    private static BigDecimal sma(List<Bar> bars, int start, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i < start + period; i++) sum = sum.add(bars.get(i).close(), MC);
        return sum.divide(BigDecimal.valueOf(period), MC);
    }

    private static boolean validHlc(Bar bar) {
        BigDecimal high = bar.high();
        BigDecimal low = bar.low();
        BigDecimal close = bar.close();
        if (high == null || low == null || close == null) return false;
        if (high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) return false;
        return low.compareTo(close) <= 0 && high.compareTo(close) >= 0;
    }
}
