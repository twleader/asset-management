package com.steven.assets.dto;

import java.time.LocalDate;
import java.util.List;

/** Typed global market-calendar response for the no-tenant 9090 endpoint. */
public final class PublicTradingCalendarDto {
    private PublicTradingCalendarDto() {}

    public enum CalendarAuthorityStatus { AVAILABLE, UNAVAILABLE }

    public record PublicTradingCalendarResponse(
            int year,
            String generatedAt,
            String timezone,
            List<Integer> availableYears,
            int minYear,
            int maxYear,
            List<CalendarMarketDefinition> markets,
            CalendarAvailability availability,
            CalendarTradingDayCount tradingDayCount,
            CalendarHolidaySet holidays,
            List<TradingCalendarDay> days,
            CalendarMarketStatus marketStatus) {
        public PublicTradingCalendarResponse {
            availableYears = availableYears == null ? List.of() : List.copyOf(availableYears);
            markets = markets == null ? List.of() : List.copyOf(markets);
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    public record CalendarMarketDefinition(
            String code,
            String displayName,
            String exchange,
            String timezone,
            String regularTradingHours,
            boolean daylightSavingSupported) {}

    public record CalendarAvailability(
            CalendarAuthorityAvailability tw,
            CalendarAuthorityAvailability us,
            CalendarAuthorityAvailability uk) {}

    public record CalendarAuthorityAvailability(
            CalendarAuthorityStatus status,
            String source,
            String message) {}

    public record CalendarTradingDayCount(Integer tw, Integer us, Integer uk) {}

    public record CalendarHolidaySet(
            List<CalendarHoliday> tw,
            List<CalendarHoliday> us,
            List<CalendarHoliday> uk) {
        public CalendarHolidaySet {
            tw = tw == null ? List.of() : List.copyOf(tw);
            us = us == null ? List.of() : List.copyOf(us);
            uk = uk == null ? List.of() : List.copyOf(uk);
        }
    }

    public record CalendarHoliday(LocalDate date, String name) {}

    public record TradingCalendarDay(
            LocalDate date,
            String weekday,
            boolean isWeekend,
            Boolean twTrading,
            Boolean usTrading,
            Boolean ukTrading,
            Boolean twHoliday,
            Boolean usHoliday,
            Boolean ukHoliday) {}

    public record CalendarMarketStatus(
            MarketSessionStatus tw,
            MarketSessionStatus us,
            MarketSessionStatus uk) {}

    public record MarketSessionStatus(
            boolean marketOpen,
            String localTime,
            String displayTradingDate,
            String timezone) {}
}
