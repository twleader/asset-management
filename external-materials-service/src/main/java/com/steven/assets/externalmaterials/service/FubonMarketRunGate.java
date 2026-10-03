package com.steven.assets.externalmaterials.service;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalTime;

@Component
public class FubonMarketRunGate {
    private final FubonMarketAccess access;
    private final MarketCalendar calendar;
    private final MarketClock clock;
    public FubonMarketRunGate(FubonMarketAccess access, MarketCalendar calendar, MarketClock clock) {
        this.access = access; this.calendar = calendar; this.clock = clock;
    }
    public String reason(String feature, String disabledReason, boolean afterClose) {
        String reason = FubonMarketData.featureReason(feature, disabledReason);
        if (reason != null) return reason;
        reason = access.unavailableReason();
        if (reason != null) return reason;
        var now = clock.instant().atZone(MarketClock.TW_ZONE);
        try {
            var known = calendar.isTwTradingDayKnown(now.toLocalDate());
            if (known.isEmpty()) return "CALENDAR_UNKNOWN";
            if (!known.get()) return "MARKET_CLOSED";
        } catch (RuntimeException failure) { return "CALENDAR_UNKNOWN"; }
        if (afterClose && now.toLocalTime().isBefore(LocalTime.of(13, 40))) return "BEFORE_CLOSE";
        return null;
    }
    /** Historical reads are independent of today's session; readiness and explicit flag still fail closed. */
    public String reasonForHistoricalRead(String feature, String disabledReason) {
        String reason = FubonMarketData.featureReason(feature, disabledReason);
        if (reason != null) return reason;
        return access.unavailableReason();
    }
    public LocalDate today() { return clock.instant().atZone(MarketClock.TW_ZONE).toLocalDate(); }
    public LocalDate latestCompletedTwDay() {
        LocalDate candidate = today().minusDays(1);
        for (int i = 0; i < 14; i++, candidate = candidate.minusDays(1)) {
            var tradingDay = calendar.isTwTradingDayKnown(candidate);
            if (tradingDay.isEmpty()) throw new FubonMarketData.Unavailable("CALENDAR_UNKNOWN");
            if (tradingDay.get()) return candidate;
        }
        throw new FubonMarketData.Unavailable("CALENDAR_UNKNOWN");
    }
    public boolean sameDay(LocalDate date) { return today().equals(date); }
}
