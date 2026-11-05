package com.steven.assets.service;

import com.steven.assets.model.DailyInventory.Session;
import com.steven.assets.service.srpp.us.UsReportCalendar;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Optional;

@Component
public final class DailyInventoryCalendarAdapter implements DailyInventoryCalendarPort {
    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");
    private final MarketDataService marketData;
    private final UsReportCalendar usCalendar;
    public DailyInventoryCalendarAdapter(MarketDataService marketData, UsReportCalendar usCalendar) {
        this.marketData = marketData;
        this.usCalendar = usCalendar;
    }
    @Override public Optional<Session> dueSession(String market, Instant at) {
        Session session;
        if ("台股".equals(market)) {
            ZonedDateTime local = at.atZone(TPE);
            if (local.toLocalTime().isBefore(LocalTime.of(19, 30))) return Optional.empty();
            Optional<Boolean> open = marketData.isTradingDayKnown(market, local.toLocalDate());
            if (open == null || open.isEmpty()) throw new IllegalStateException("INVENTORY_CALENDAR_UNAVAILABLE");
            if (!open.get()) return Optional.empty();
            session = new Session(market, local.toLocalDate(), TPE,
                    local.toLocalDate().atTime(13, 30).atZone(TPE).toInstant());
        } else if ("美股".equals(market)) {
            ZonedDateTime local = at.atZone(UsReportCalendar.NY);
            if (local.toLocalTime().isBefore(LocalTime.of(19, 0))) return Optional.empty();
            if (!usCalendar.trading(local.toLocalDate())) return Optional.empty();
            var us = usCalendar.session(local.toLocalDate());
            session = new Session(market, us.date(), UsReportCalendar.NY, us.close());
        } else throw new IllegalArgumentException("INVENTORY_MARKET_UNSUPPORTED");
        return session.captureWindow(at) ? Optional.of(session) : Optional.empty();
    }
}
