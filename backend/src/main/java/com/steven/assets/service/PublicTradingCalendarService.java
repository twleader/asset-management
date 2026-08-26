package com.steven.assets.service;

import com.steven.assets.dto.PublicTradingCalendarDto;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarAuthorityAvailability;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarAuthorityStatus;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarHoliday;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarHolidaySet;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarMarketDefinition;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarMarketStatus;
import com.steven.assets.dto.PublicTradingCalendarDto.CalendarTradingDayCount;
import com.steven.assets.dto.PublicTradingCalendarDto.MarketSessionStatus;
import com.steven.assets.dto.PublicTradingCalendarDto.TradingCalendarDay;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure selected-year calendar projection. Each authority is read once into a defensive copy before days are built;
 * current market status deliberately remains the existing independent session read.
 */
@Service
@RequiredArgsConstructor
public class PublicTradingCalendarService {

    private static final Pattern YEAR = Pattern.compile("^[0-9]{4}$");
    private static final String TAIPEI = "Asia/Taipei";

    private final MarketDataService marketData;
    private final StockPriceService stockPrices;

    public PublicTradingCalendarDto.PublicTradingCalendarResponse current(List<String> rawYears) {
        int year = parseYear(rawYears);
        int currentYear = ZonedDateTime.now(MarketZones.TW_ZONE).getYear();
        AuthoritySnapshot tw = snapshot(() -> marketData.getTwHolidays(year), "TWSE/DGPA", true);
        AuthoritySnapshot us = snapshot(() -> marketData.getUsHolidays(year), "NYSE", false);
        AuthoritySnapshot uk = snapshot(() -> marketData.getUkHolidays(year), "LSE", false);
        List<TradingCalendarDay> days = buildDays(year, tw, us, uk);
        return new PublicTradingCalendarDto.PublicTradingCalendarResponse(
                year, ZonedDateTime.now(MarketZones.TW_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), TAIPEI,
                List.of(currentYear - 1, currentYear, currentYear + 1), currentYear - 1, currentYear + 1,
                marketDefinitions(), availability(tw, us, uk), counts(days), holidays(tw, us, uk), days,
                marketStatus(stockPrices.getMarketStatus()));
    }

    private int parseYear(List<String> rawYears) {
        if (rawYears == null || rawYears.size() != 1 || rawYears.getFirst() == null) {
            throw new InvalidPublicTradingCalendarRequestException();
        }
        String rawYear = rawYears.getFirst();
        if (!YEAR.matcher(rawYear).matches()) {
            throw new InvalidPublicTradingCalendarRequestException();
        }
        try {
            int year = Integer.parseInt(rawYear);
            if (year < 1900 || year > 9999) {
                throw new InvalidPublicTradingCalendarRequestException();
            }
            return year;
        } catch (NumberFormatException invalid) {
            throw new InvalidPublicTradingCalendarRequestException();
        }
    }

    private AuthoritySnapshot snapshot(HolidayReader reader, String source, boolean emptyUnavailable) {
        try {
            Map<String, String> raw = reader.read();
            // Map.copyOf rejects a nullable authority-supplied holiday name; a defensive immutable
            // LinkedHashMap preserves that valid data without retaining the mutable source map.
            Map<String, String> copied = raw == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
            if (emptyUnavailable && copied.isEmpty()) {
                return new AuthoritySnapshot(false, source, "年度假日日曆暫時不可用", copied);
            }
            return new AuthoritySnapshot(true, source, null, copied);
        } catch (RuntimeException unavailable) {
            return new AuthoritySnapshot(false, source, "年度假日日曆暫時不可用", Map.of());
        }
    }

    private List<TradingCalendarDay> buildDays(
            int year, AuthoritySnapshot tw, AuthoritySnapshot us, AuthoritySnapshot uk) {
        List<TradingCalendarDay> days = new ArrayList<>();
        for (LocalDate date = LocalDate.of(year, 1, 1); date.getYear() == year; date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            MarketFlags twFlags = flags(date, weekend, tw);
            MarketFlags usFlags = flags(date, weekend, us);
            MarketFlags ukFlags = flags(date, weekend, uk);
            days.add(new TradingCalendarDay(date, weekday(date.getDayOfWeek()), weekend,
                    twFlags.trading(), usFlags.trading(), ukFlags.trading(),
                    twFlags.holiday(), usFlags.holiday(), ukFlags.holiday()));
        }
        return List.copyOf(days);
    }

    private MarketFlags flags(LocalDate date, boolean weekend, AuthoritySnapshot authority) {
        if (!authority.available()) {
            return new MarketFlags(null, null);
        }
        boolean holiday = authority.holidays().containsKey(date.toString());
        return new MarketFlags(!weekend && !holiday, holiday);
    }

    private String weekday(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private List<CalendarMarketDefinition> marketDefinitions() {
        return List.of(
                new CalendarMarketDefinition("TW", "台股", "TWSE", TAIPEI, "09:00-13:30", false),
                new CalendarMarketDefinition("US", "美股", "NYSE", "America/New_York", "09:30-16:00", true),
                new CalendarMarketDefinition("UK", "英股", "LSE", "Europe/London", "08:00-16:30", true));
    }

    private PublicTradingCalendarDto.CalendarAvailability availability(
            AuthoritySnapshot tw, AuthoritySnapshot us, AuthoritySnapshot uk) {
        return new PublicTradingCalendarDto.CalendarAvailability(toAvailability(tw), toAvailability(us), toAvailability(uk));
    }

    private CalendarAuthorityAvailability toAvailability(AuthoritySnapshot snapshot) {
        return new CalendarAuthorityAvailability(snapshot.available() ? CalendarAuthorityStatus.AVAILABLE
                : CalendarAuthorityStatus.UNAVAILABLE, snapshot.source(), snapshot.message());
    }

    private CalendarTradingDayCount counts(List<TradingCalendarDay> days) {
        return new CalendarTradingDayCount(count(days, MarketCode.TW), count(days, MarketCode.US), count(days, MarketCode.UK));
    }

    private Integer count(List<TradingCalendarDay> days, MarketCode market) {
        List<Boolean> values = days.stream().map(day -> switch (market) {
            case TW -> day.twTrading();
            case US -> day.usTrading();
            case UK -> day.ukTrading();
        }).toList();
        if (values.stream().anyMatch(Objects::isNull)) {
            return null;
        }
        return (int) values.stream().filter(Boolean.TRUE::equals).count();
    }

    private CalendarHolidaySet holidays(AuthoritySnapshot tw, AuthoritySnapshot us, AuthoritySnapshot uk) {
        return new CalendarHolidaySet(toHolidays(tw), toHolidays(us), toHolidays(uk));
    }

    private List<CalendarHoliday> toHolidays(AuthoritySnapshot authority) {
        return authority.holidays().entrySet().stream()
                .map(entry -> new CalendarHoliday(parseHolidayDate(entry.getKey()), entry.getValue()))
                .filter(holiday -> holiday.date() != null)
                .sorted(Comparator.comparing(CalendarHoliday::date))
                .toList();
    }

    private LocalDate parseHolidayDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private CalendarMarketStatus marketStatus(Map<String, Object> raw) {
        Map<String, Object> values = raw == null ? Map.of() : raw;
        return new CalendarMarketStatus(
                session(values, "tw", TAIPEI), session(values, "us", "America/New_York"),
                session(values, "uk", "Europe/London"));
    }

    private MarketSessionStatus session(Map<String, Object> values, String prefix, String timezone) {
        return new MarketSessionStatus(Boolean.TRUE.equals(values.get(prefix + "MarketOpen")),
                stringValue(values.get(prefix + "Time")), stringValue(values.get(prefix + "TradingDate")), timezone);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @FunctionalInterface
    private interface HolidayReader {
        Map<String, String> read();
    }

    private record AuthoritySnapshot(boolean available, String source, String message, Map<String, String> holidays) {}
    private record MarketFlags(Boolean trading, Boolean holiday) {}
    private enum MarketCode { TW, US, UK }
}
