package com.steven.assets.bff.tradingcalendar;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/** TradingCalendarView 的頁面年度邊界；不可下沉到通用 market-data API。 */
@Component
public final class TradingCalendarYearWindow {
    public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final Clock clock;

    public TradingCalendarYearWindow() {
        this(Clock.system(TAIPEI));
    }

    public TradingCalendarYearWindow(Clock clock) {
        this.clock = clock.withZone(TAIPEI);
    }

    public Window snapshot() {
        int currentYear = LocalDate.now(clock).getYear();
        return new Window(currentYear, currentYear - 1, currentYear + 1,
                List.of(currentYear - 1, currentYear, currentYear + 1));
    }

    public int currentYear() { return snapshot().currentYear(); }
    public int minYear() { return snapshot().minYear(); }
    public int maxYear() { return snapshot().maxYear(); }
    public List<Integer> availableYears() { return snapshot().availableYears(); }
    public boolean accepts(int year) { return snapshot().accepts(year); }

    public record Window(int currentYear, int minYear, int maxYear, List<Integer> availableYears) {
        public Window {
            availableYears = List.copyOf(availableYears);
        }

        public boolean accepts(int year) {
            return year >= minYear && year <= maxYear;
        }
    }
}
