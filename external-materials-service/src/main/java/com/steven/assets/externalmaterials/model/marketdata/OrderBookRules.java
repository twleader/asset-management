package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Pure validation shared by orchestration and persistence adapters. */
public final class OrderBookRules {
    private OrderBookRules() {}
    private static final String TAIWAN = "台股";
    private static final String FUBON = "FUBON_BOOKS";
    private static final String YAHOO = "YAHOO_TW";
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[0-9]{4,6}[A-Z]?$");
    /** Validates every value before it can reach the canonical write transaction. */
    public static boolean isPersistable(QuoteDetailData.QuoteDetailResult snapshot) {
        if (snapshot == null || !snapshot.supported() || !snapshot.available()
                || !TAIWAN.equals(snapshot.market()) || !allowedSource(snapshot.source())
                || !"OPEN".equals(snapshot.marketStatus()) || snapshot.stockCode() == null
                || !CODE.matcher(snapshot.stockCode()).matches() || "0000".equals(snapshot.stockCode())
                || snapshot.stockName() == null || snapshot.stockName().isBlank()
                || snapshot.stockName().trim().equalsIgnoreCase(snapshot.stockCode())
                || snapshot.sourceTime() == null || snapshot.sourceTime().isBefore(Instant.EPOCH)
                || !hasMicrosecondPrecision(snapshot.sourceTime()) || snapshot.fetchedAt() == null
                || !validPositive(snapshot.price()) || !validPositive(snapshot.previousClose())
                || !validPositiveOrNull(snapshot.openPrice()) || !validPositiveOrNull(snapshot.highPrice())
                || !validPositiveOrNull(snapshot.lowPrice()) || !validPositiveOrNull(snapshot.averagePrice())
                || !validNonNegativeOrNull(snapshot.turnoverYi()) || !validNonNegativeOrNull(snapshot.volumeLots())
                || !validNonNegativeOrNull(snapshot.previousVolumeLots())
                || !validNonNegativeOrNull(snapshot.innerVolumeLots())
                || !validNonNegativeOrNull(snapshot.outerVolumeLots())
                || snapshot.levels() == null || snapshot.levels().size() != 5) {
            return false;
        }
        if (snapshot.highPrice() != null && snapshot.lowPrice() != null
                && snapshot.highPrice().compareTo(snapshot.lowPrice()) < 0) return false;
        Set<BigDecimal> bidPrices = new HashSet<>();
        Set<BigDecimal> askPrices = new HashSet<>();
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        for (int index = 0; index < 5; index++) {
            QuoteDetailData.OrderBookLevel level = snapshot.levels().get(index);
            if (level == null || level.level() != index + 1
                    || !validCompleteSide(level.bidPrice(), level.bidVolumeLots(), bidPrices)
                    || !validCompleteSide(level.askPrice(), level.askVolumeLots(), askPrices)
                    || (previousBid != null && previousBid.compareTo(level.bidPrice()) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(level.askPrice()) >= 0)) {
                return false;
            }
            previousBid = level.bidPrice();
            previousAsk = level.askPrice();
        }
        return true;
    }
    public static boolean isSupportedIdentity(String code, String market) {
        return TAIWAN.equals(market) && code != null && CODE.matcher(code).matches() && !"0000".equals(code);
    }
    public static boolean invalidMasterName(String name, String code) {
        return name == null || name.isBlank() || name.trim().equalsIgnoreCase(code);
    }
    public static boolean allowedSource(String source) {
        return FUBON.equals(source) || YAHOO.equals(source);
    }
    public static boolean hasMicrosecondPrecision(Instant value) {
        return value.getNano() % 1_000 == 0;
    }
    public static boolean validPositive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10;
    }
    public static boolean validPositiveOrNull(BigDecimal value) {
        return value == null || validPositive(value);
    }
    public static boolean validNonNegativeOrNull(BigDecimal value) {
        return value == null || (value.signum() >= 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10);
    }
    public static boolean validNonNegativeOrNull(Long value) {
        return value == null || value >= 0;
    }
    public static boolean validCompleteSide(BigDecimal price, Long lots, Set<BigDecimal> seen) {
        return validPositive(price) && lots != null && lots > 0 && seen.add(price.stripTrailingZeros());
    }
}
