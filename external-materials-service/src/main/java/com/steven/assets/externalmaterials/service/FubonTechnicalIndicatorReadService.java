package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Pure cache/radar/calendar view. A cold calendar stays unknown and never triggers a load. */
@Service
public class FubonTechnicalIndicatorReadService {
    private final FubonTechnicalCachePort cache;
    private final FubonRadarScope radar;
    private final MarketCalendar calendar;
    private final MarketClock clock;
    public FubonTechnicalIndicatorReadService(FubonTechnicalCachePort cache,
            FubonRadarScope radar, MarketCalendar calendar, MarketClock clock) {
        this.cache = cache; this.radar = radar; this.calendar = calendar; this.clock = clock;
    }
    public Result read(String symbol) {
        if (!validSymbol(symbol)) return empty("INVALID_REQUEST", "INVALID_SYMBOL", symbol);
        try {
            if (!radar.current(Integer.MAX_VALUE).contains(symbol)) return empty("NOT_RADAR", "NOT_RADAR", symbol);
        } catch (Unavailable failure) { return empty("UNAVAILABLE", failure.reason(), symbol); }
        FubonTechnicalCache.Read stored = cache.read(symbol);
        if (stored.document() == null) return empty(stored.outcome(), stored.outcome(), symbol);
        var now = clock.instant();
        LocalDate expected = expectedDate();
        Map<String, GroupView> groups = new TreeMap<>();
        for (String name : GROUPS) {
            var group = stored.document().groups().get(name);
            boolean valid = group.payload() != null && group.expiryMillis() > now.toEpochMilli();
            String status = !valid ? "UNAVAILABLE" : expected == null
                    || group.sourceDate().isBefore(expected) ? "HISTORICAL" : "AVAILABLE";
            groups.put(name, new GroupView(status, group.parameters(), group.sourceDate(), group.sourceTimestamp(),
                    valid ? group.payload() : null, group.observedAt(), group.expiresAt(), group.lastAttempt()));
        }
        String outcome = groups.values().stream().allMatch(g -> "UNAVAILABLE".equals(g.status())) ? "UNAVAILABLE"
                : groups.values().stream().anyMatch(g -> "AVAILABLE".equals(g.status())) ? "AVAILABLE" : "HISTORICAL";
        return new Result(outcome, expected == null ? "CALENDAR_UNKNOWN" : null, symbol, PROVIDER,
                FubonTechnicalCache.manifest(), groups, expected != null, expected);
    }
    private LocalDate expectedDate() {
        var now = clock.instant().atZone(MarketClock.TW_ZONE);
        LocalDate date = now.toLocalTime().isBefore(LocalTime.of(13, 40)) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        for (int days = 0; days < 14; days++, date = date.minusDays(1)) {
            Optional<Boolean> known = calendar.peekTwTradingDayKnown(date);
            if (known.isEmpty()) return null;
            if (known.get()) return date;
        }
        return null;
    }
    private static Result empty(String outcome, String reason, String symbol) {
        return new Result(outcome, reason, symbol, PROVIDER, FubonTechnicalCache.manifest(), Map.of(), false, null);
    }
    public record GroupView(String status, Map<String, Object> parameters,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate sourceDate,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant sourceTimestamp,
                            Map<String, String> payload,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt,
                            FubonTechnicalCache.Attempt lastAttempt) {}
    public record Result(String outcome, String reason, String symbol, String provider,
                         Map<String, Map<String, Object>> parameters, Map<String, GroupView> groups,
                         boolean calendarKnown, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate expectedTradingDate) {
        public Result { parameters = Map.copyOf(parameters); groups = Map.copyOf(groups); }
    }
}
