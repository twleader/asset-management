package com.steven.assets.externalmaterials.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

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
public class MarketClock {

    public static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    public static final ZoneId US_ZONE = ZoneId.of("America/New_York");
    public static final ZoneId LON_ZONE = ZoneId.of("Europe/London");

    private final MarketCalendar calendar;
    private final Clock clock;

    @Autowired
    public MarketClock(MarketCalendar calendar) {
        this(calendar, Clock.systemUTC());
    }

    MarketClock(MarketCalendar calendar, Clock clock) {
        this.calendar = calendar;
        this.clock = clock;
    }

    /** Shared controllable receipt clock for LIVE producer state that must be ordered by arrival. */
    public Instant instant() {
        return clock.instant();
    }

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
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TW_ZONE));
        if (!calendar.isTwTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(13, 30));
    }

    /**
     * 台股 LIVE 外呼唯一可用的 fail-closed 授權。
     *
     * <p>盤外時間不需要日曆即可確定為 closed；盤中則只接受 shared known calendar 明確回傳
     * {@code true}。年度 authority 依序採用 TWSE primary、完整 DGPA provisional，並於讀取時
     * union operator closure；兩個年度來源都不可用或查詢丟例外時回 {@link Optional#empty()}，
     * 絕不借用上方 legacy fail-open boolean。</p>
     */
    public Optional<Boolean> isTwMarketOpenKnown() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TW_ZONE));
        LocalTime time = now.toLocalTime();
        if (time.isBefore(LocalTime.of(9, 0)) || !time.isBefore(LocalTime.of(13, 30))) {
            return Optional.of(false);
        }
        try {
            return calendar.isTwTradingDayKnown(now.toLocalDate());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public boolean isUsMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(US_ZONE));
        if (!calendar.isUsTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(16, 0));
    }

    public boolean isUkMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(LON_ZONE));
        if (!calendar.isUkTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(8, 0)) && !t.isAfter(LocalTime.of(16, 30));
    }

    public boolean isTwMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TW_ZONE));
        if (!calendar.isTwTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(13, 30)) && t.isBefore(LocalTime.of(13, 50));
    }

    public boolean isUsMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(US_ZONE));
        if (!calendar.isUsTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(16, 20));
    }

    public boolean isUkMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(LON_ZONE));
        if (!calendar.isUkTradingDay(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return t.isAfter(LocalTime.of(16, 30)) && t.isBefore(LocalTime.of(16, 50));
    }
}
