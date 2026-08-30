package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.time.Instant;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Pure validation also used by the final narrow writer entry, not only the JSON parser. */
public final class FubonStockPushContract {
    private FubonStockPushContract() {}
    public static boolean valid(StockEvent event, Instant receivedAt) {
        if (event == null || receivedAt == null || !validSymbol(event.symbol()) || event.sourceDate() == null
                || event.tradeTime() == null || event.tradeTimeMicros() <= 0 || event.tradeSize() <= 0
                || !positive(event.price()) || !optionalPositive(event.previousClose())
                || !optionalPositive(event.openPrice()) || !optionalPositive(event.highPrice())
                || !optionalPositive(event.lowPrice())) return false;
        Instant exact = Instant.ofEpochSecond(event.tradeTimeMicros() / 1_000_000, event.tradeTimeMicros() % 1_000_000 * 1000);
        if (!exact.equals(event.tradeTime()) || exact.isAfter(receivedAt)
                || !exact.atZone(MarketClock.TW_ZONE).toLocalDate().equals(event.sourceDate())
                || !receivedAt.atZone(MarketClock.TW_ZONE).toLocalDate().equals(event.sourceDate())
                || !inSession(exact) || !inSession(receivedAt)) return false;
        if (event.highPrice() != null && (event.highPrice().compareTo(event.price()) < 0
                || event.openPrice() != null && event.highPrice().compareTo(event.openPrice()) < 0)) return false;
        if (event.lowPrice() != null && (event.lowPrice().compareTo(event.price()) > 0
                || event.openPrice() != null && event.lowPrice().compareTo(event.openPrice()) > 0)) return false;
        if (event.highPrice() != null && event.lowPrice() != null && event.highPrice().compareTo(event.lowPrice()) < 0) return false;
        String name = event.name();
        return name == null || !name.isBlank() && name.equals(name.trim()) && name.length() <= 100
                && name.chars().noneMatch(Character::isISOControl);
    }
    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && (long)value.precision() + Math.max(-(long)value.scale(), 0) <= 20
                && Math.max(value.scale(), 0) <= 10;
    }
    private static boolean optionalPositive(BigDecimal value) { return value == null || positive(value); }
}
