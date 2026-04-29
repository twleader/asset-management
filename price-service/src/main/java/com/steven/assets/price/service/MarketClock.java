package com.steven.assets.price.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 市場開收盤時段判斷。
 * - 台股：週一～五 09:00–13:30 Asia/Taipei
 * - 美股：週一～五 09:30–16:00 America/New_York（JVM 自動處理 EST/EDT 夏令）
 */
@Component
public class MarketClock {

    public static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    public static final ZoneId US_ZONE = ZoneId.of("America/New_York");

    public boolean isTwMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(TW_ZONE);
        if (isWeekend(now.getDayOfWeek())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(13, 30));
    }

    public boolean isUsMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(US_ZONE);
        if (isWeekend(now.getDayOfWeek())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(16, 0));
    }

    public boolean isTwMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(TW_ZONE);
        if (isWeekend(now.getDayOfWeek())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(13, 30)) && t.isBefore(LocalTime.of(13, 50));
    }

    public boolean isUsMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(US_ZONE);
        if (isWeekend(now.getDayOfWeek())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(16, 20));
    }

    private static boolean isWeekend(DayOfWeek dow) {
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
