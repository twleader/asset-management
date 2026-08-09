package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易日 / 國定假日判定（external-materials-service 內的權威）。
 *
 * <p>用途：{@link MarketClock} 與 {@link ClosePersister} 的「假日整段休市」閘門。cron 以
 * {@code MON-FRI} 觸發只能濾週末，平日仍可能是國定假日（美股 Juneteenth 6/19 為週五），
 * 故抓價 / 收盤排程除「平日 + 時段」外，還要再判「當日非該市場假日」。
 *
 * <p>假日來源：
 * <ul>
 *   <li>台股：委派 {@link MarketDataFetchService#getTwHolidays(int)}（TWSE OpenAPI holidaySchedule，
 *       已是台股假日的唯一來源；抓不到時保守視為交易日，與 business-services 既有退化一致）。</li>
 *   <li>美股 / 英股：NYSE / LSE 法定規則純函式計算。</li>
 * </ul>
 *
 * <p><b>重要：美股 / 英股假日規則與 business-services 的
 * {@code com.steven.assets.service.MarketDataService#getUsHolidays / getUkHolidays} 為「同一套」
 * NYSE / LSE 法定規則。</b>因兩服務無共用 Maven module，且 external-materials-service 不可反向依賴
 * business-services（會造成循環依賴），故各自保留一份。<b>修改任一處（新增假日 / 調整 observed 規則）
 * 請務必同步另一處。</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketCalendar {

    private final MarketDataFetchService marketData;

    /** 美 / 英假日純函式計算結果 per-year 快取（ISO 日期字串集合）。 */
    private final Map<Integer, Set<String>> usHolidayCache = new ConcurrentHashMap<>();
    private final Map<Integer, Set<String>> ukHolidayCache = new ConcurrentHashMap<>();

    // ===== 交易日（非週末且非假日）=====

    public boolean isTwTradingDay(LocalDate date) {
        return !isWeekend(date) && !isTwHoliday(date);
    }

    public boolean isUsTradingDay(LocalDate date) {
        return !isWeekend(date) && !isUsHoliday(date);
    }

    public boolean isUkTradingDay(LocalDate date) {
        return !isWeekend(date) && !isUkHoliday(date);
    }

    // ===== 假日判定 =====

    public boolean isTwHoliday(LocalDate date) {
        try {
            Map<String, String> h = marketData.getTwHolidays(date.getYear());
            return h != null && h.containsKey(date.toString());
        } catch (Exception e) {
            // TWSE 假日表抓取失敗 → 保守視為交易日（不擋抓價），與 business-services 既有退化一致
            log.warn("查 TWSE 假日表失敗 {}: {}（保守視為交易日）", date, e.getMessage());
            return false;
        }
    }

    public boolean isUsHoliday(LocalDate date) {
        return usHolidays(date.getYear()).contains(date.toString());
    }

    public boolean isUkHoliday(LocalDate date) {
        return ukHolidays(date.getYear()).contains(date.toString());
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    // ===== 美股假日（NYSE）— 與 backend MarketDataService.getUsHolidays 同一套規則 =====

    private Set<String> usHolidays(int year) {
        return usHolidayCache.computeIfAbsent(year, MarketCalendar::computeUsHolidays);
    }

    private static Set<String> computeUsHolidays(int year) {
        Set<String> h = new LinkedHashSet<>();
        addNewYearObserved(h, year);                         // New Year's Day
        h.add(nthWeekday(year, 1, DayOfWeek.MONDAY, 3));     // MLK Day
        h.add(nthWeekday(year, 2, DayOfWeek.MONDAY, 3));     // Presidents' Day
        h.add(goodFriday(year));                             // Good Friday
        h.add(lastWeekday(year, 5, DayOfWeek.MONDAY));       // Memorial Day
        // Juneteenth became a NYSE full-day holiday in 2022.  The 2021 federal
        // holiday did not close the exchange, so do not add 2021 observed dates.
        if (year >= 2022) addObserved(h, year, 6, 19);       // Juneteenth
        addObserved(h, year, 7, 4);                          // Independence Day
        h.add(nthWeekday(year, 9, DayOfWeek.MONDAY, 1));     // Labor Day
        h.add(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4));  // Thanksgiving
        addObserved(h, year, 12, 25);                        // Christmas
        addUsExceptionalClosures(h, year);
        return h;
    }

    /**
     * Explicit full-day NYSE closures outside recurring holidays.  Never infer
     * one from a weekday; each entry is tied to a documented exchange closure.
     */
    private static void addUsExceptionalClosures(Set<String> holidays, int year) {
        Map<String, String> exceptional = Map.ofEntries(
                Map.entry("2001-09-11", "NYSE closure - September 11 attacks"),
                Map.entry("2001-09-12", "NYSE closure - September 11 attacks"),
                Map.entry("2001-09-13", "NYSE closure - September 11 attacks"),
                Map.entry("2001-09-14", "NYSE closure - September 11 attacks"),
                Map.entry("2004-06-11", "NYSE closure - President Reagan funeral"),
                Map.entry("2007-01-02", "NYSE closure - President Ford funeral"),
                Map.entry("2012-10-29", "NYSE closure - Hurricane Sandy"),
                Map.entry("2012-10-30", "NYSE closure - Hurricane Sandy"),
                Map.entry("2018-12-05", "NYSE closure - President George H.W. Bush funeral"),
                Map.entry("2025-01-09", "NYSE closure - President Carter funeral"));
        exceptional.forEach((date, reason) -> {
            if (date.startsWith(Integer.toString(year) + "-")) holidays.add(date);
        });
    }

    // ===== 英股假日（LSE）— 與 backend MarketDataService.getUkHolidays 同一套規則 =====

    private Set<String> ukHolidays(int year) {
        return ukHolidayCache.computeIfAbsent(year, MarketCalendar::computeUkHolidays);
    }

    private static Set<String> computeUkHolidays(int year) {
        Set<String> h = new LinkedHashSet<>();
        addUkObserved(h, LocalDate.of(year, 1, 1));          // New Year's Day
        LocalDate goodFri = LocalDate.parse(goodFriday(year));
        h.add(goodFri.toString());                           // Good Friday
        h.add(goodFri.plusDays(3).toString());              // Easter Monday
        h.add(nthWeekday(year, 5, DayOfWeek.MONDAY, 1));     // Early May Bank Holiday
        h.add(lastWeekday(year, 5, DayOfWeek.MONDAY));       // Spring Bank Holiday
        h.add(lastWeekday(year, 8, DayOfWeek.MONDAY));       // Summer Bank Holiday
        addUkObserved(h, LocalDate.of(year, 12, 25));        // Christmas Day
        addUkObserved(h, LocalDate.of(year, 12, 26));        // Boxing Day
        return h;
    }

    // ===== 純函式 helpers（與 backend MarketDataService 同步）=====

    /** 美股 observed：落在週六 → 前移週五，週日 → 後移週一。 */
    private static void addObserved(Set<String> h, int year, int month, int day) {
        LocalDate date = LocalDate.of(year, month, day);
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) date = date.minusDays(1);
        else if (dow == DayOfWeek.SUNDAY) date = date.plusDays(1);
        h.add(date.toString());
    }

    /** NYSE New Year's rule: Sunday is observed Monday; Saturday is not observed Friday. */
    private static void addNewYearObserved(Set<String> h, int year) {
        LocalDate date = LocalDate.of(year, 1, 1);
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) date = date.plusDays(1);
        if (date.getDayOfWeek() != DayOfWeek.SATURDAY) h.add(date.toString());
    }

    /** UK 銀行假日「下個工作日」順移：週末或已被佔用 → 往後推到非週末且未佔用日（Christmas + Boxing Day 連假相互避撞）。 */
    private static void addUkObserved(Set<String> h, LocalDate date) {
        LocalDate observed = date;
        while (observed.getDayOfWeek() == DayOfWeek.SATURDAY
                || observed.getDayOfWeek() == DayOfWeek.SUNDAY
                || h.contains(observed.toString())) {
            observed = observed.plusDays(1);
        }
        h.add(observed.toString());
    }

    private static String nthWeekday(int year, int month, DayOfWeek dow, int n) {
        LocalDate d = LocalDate.of(year, month, 1);
        int count = 0;
        while (true) {
            if (d.getDayOfWeek() == dow && ++count == n) return d.toString();
            d = d.plusDays(1);
        }
    }

    private static String lastWeekday(int year, int month, DayOfWeek dow) {
        LocalDate d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (d.getDayOfWeek() != dow) d = d.minusDays(1);
        return d.toString();
    }

    private static String goodFriday(int year) {
        int a = year % 19, b = year / 100, c = year % 100;
        int d = b / 4, e = b % 4, f = (b + 8) / 25;
        int g = (b - f + 1) / 3, hh = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - hh - k) % 7;
        int m = (a + 11 * hh + 22 * l) / 451;
        int month = (hh + l - 7 * m + 114) / 31;
        int day = ((hh + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day).minusDays(2).toString();
    }
}
