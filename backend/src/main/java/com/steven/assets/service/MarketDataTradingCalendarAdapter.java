package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/** Production adapter from MarketDataService's tri-state Optional to the radar calendar port. */
@Component
public final class MarketDataTradingCalendarAdapter implements TradingRadarSessionCalendarPort {

    static final String PROVIDER = "MARKET_DATA_SERVICE";

    private final MarketDataService marketDataService;

    public MarketDataTradingCalendarAdapter(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @Override
    public DayResolution resolve(String market, LocalDate date) {
        try {
            Optional<Boolean> known = marketDataService.isTradingDayKnown(market, date);
            if (known == null || known.isEmpty()) return unknown(date);
            return new DayResolution(date,
                    known.get() ? Status.OPEN : Status.CLOSED,
                    PROVIDER, null);
        } catch (RuntimeException e) {
            return unknown(date);
        }
    }

    private static DayResolution unknown(LocalDate date) {
        return new DayResolution(
                date, Status.UNKNOWN, PROVIDER, MARKET_CALENDAR_UNAVAILABLE);
    }
}
