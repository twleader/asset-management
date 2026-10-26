package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Pure validation shared by orchestration and persistence adapters. */
public final class OrderBookCacheRules {
    private OrderBookCacheRules() {}
    /** Exact decimal-string representation used in payload and Lua comparison. */
    public static String revisionText(long revision) {
        return revision > 0 ? Long.toString(revision) : null;
    }
    public static boolean validRevision(String value) {
        if (value == null || !value.matches("^[1-9][0-9]*$")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }
    public static String sourceUpdatedEpochMicros(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) return null;
        try {
            long micros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
                    value.getNano() / 1_000L);
            return micros < 0 ? null : Long.toString(micros);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }
    public static boolean validSnapshot(QuoteDetailData.QuoteDetailResult snapshot) {
        if (snapshot == null || !snapshot.supported() || !snapshot.available()
                || !"台股".equals(snapshot.market()) || !allowedSource(snapshot.source())
                || snapshot.stockCode() == null || !snapshot.stockCode().matches("^[0-9]{4,6}[A-Z]?$")
                || "0000".equals(snapshot.stockCode()) || snapshot.stockName() == null || snapshot.stockName().isBlank()
                || snapshot.sourceTime() == null || sourceUpdatedEpochMicros(snapshot.sourceTime()) == null
                || snapshot.fetchedAt() == null || !"OPEN".equals(snapshot.marketStatus())
                || !positive(snapshot.price()) || !positive(snapshot.previousClose())
                || snapshot.levels() == null || snapshot.levels().size() != 5) {
            return false;
        }
        Set<BigDecimal> bids = new HashSet<>();
        Set<BigDecimal> asks = new HashSet<>();
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        for (int index = 0; index < 5; index++) {
            QuoteDetailData.OrderBookLevel level = snapshot.levels().get(index);
            if (level == null || level.level() != index + 1
                    || !validCompleteSide(level.bidPrice(), level.bidVolumeLots(), bids)
                    || !validCompleteSide(level.askPrice(), level.askVolumeLots(), asks)
                    || (previousBid != null && previousBid.compareTo(level.bidPrice()) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(level.askPrice()) >= 0)) {
                return false;
            }
            previousBid = level.bidPrice();
            previousAsk = level.askPrice();
        }
        return true;
    }
    public static boolean allowedSource(String source) {
        return "FUBON_BOOKS".equals(source) || "YAHOO_TW".equals(source);
    }
    public static boolean validCompleteSide(BigDecimal price, Long lots, Set<BigDecimal> seen) {
        return positive(price) && lots != null && lots > 0 && seen.add(price.stripTrailingZeros());
    }
    public static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10;
    }
}
