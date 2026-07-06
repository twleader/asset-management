package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 市場開收盤時段判斷。
 * - 台股：交易日 09:00–13:30 Asia/Taipei
 * - 美股：交易日 09:30–16:00 America/New_York（JVM 自動處理 EST/EDT 夏令）
 * - 英股：交易日 08:00–16:30 Europe/London（JVM 自動處理 BST/GMT）
 *
 * 「交易日」= 平日且非該市場國定假日（委派 {@link MarketCalendar}）。原本只判週末，導致
 * 平日的國定假日（如美股 Juneteenth 6/19 為週五）被誤判為開盤，照抓價污染 Redis（2026/06 修正）。
 */
@Component
@RequiredArgsConstructor
public class MarketClock {

    public static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    public static final ZoneId US_ZONE = ZoneId.of("America/New_York");
    public static final ZoneId LON_ZONE = ZoneId.of("Europe/London");

    private final MarketCalendar calendar;

    /** 市場別 → 該市場時區。 */
    public static ZoneId zoneOf(String market) {
        return "美股".equals(market) ? US_ZONE
                : "英股".equals(market) ? LON_ZONE
                : TW_ZONE;
    }

    /** 指定市場、指定日期是否為交易日（非週末且非該市場國定假日）。 */
    public boolean isTradingDay(String market, LocalDate date) {
        return switch (market) {
            case "美股" -> calendar.isUsTradingDay(date);
            case "英股" -> calendar.isUkTradingDay(date);
            default -> calendar.isTwTradingDay(date);
        };
    }

    public boolean isTwMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(TW_ZONE);
        if (!calendar.isTwTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(13, 30));
    }

    public boolean isUsMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(US_ZONE);
        if (!calendar.isUsTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(16, 0));
    }

    public boolean isUkMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(LON_ZONE);
        if (!calendar.isUkTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(8, 0)) && !t.isAfter(LocalTime.of(16, 30));
    }

    public boolean isTwMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(TW_ZONE);
        if (!calendar.isTwTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(13, 30)) && t.isBefore(LocalTime.of(13, 50));
    }

    public boolean isUsMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(US_ZONE);
        if (!calendar.isUsTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(16, 20));
    }

    public boolean isUkMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(LON_ZONE);
        if (!calendar.isUkTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(16, 30)) && t.isBefore(LocalTime.of(16, 50));
    }
}
