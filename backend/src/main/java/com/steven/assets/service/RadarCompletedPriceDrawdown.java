package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Completed calendar-window price facts only; no IO, sizing, return forecast or cash-dividend adjustment. */
public final class RadarCompletedPriceDrawdown {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Set<String> TW_CODES = Set.of("0050", "00881");
    private static final Set<String> US_CODES = Set.of("GOOGL", "GOOG", "MSFT", "AVGO", "NVDA", "AAPL", "AMZN");
    public enum Status { AVAILABLE, UNAVAILABLE, NOT_EVALUATED }
    public enum PriceBasis { TW_SPLIT_STOCK_PRICE, STORED_PRICE_ONLY }

    public record Context(Status status, String stockCode, String market, LocalDate windowStart,
                          LocalDate completedAsOfDate, LocalDate highDate, BigDecimal high,
                          BigDecimal referencePrice, PriceBasis priceBasis, String unavailableReason) {
        public static final Context NOT_EVALUATED = new Context(Status.NOT_EVALUATED, null, null,
                null, null, null, null, null, null, "CONTEXT_NOT_SUPPLIED");
        public static Context unavailable(String code, String market, String reason) {
            return new Context(Status.UNAVAILABLE, code, market, null, null, null, null, null, null, reason);
        }
        /** Exact multiplication decides eligibility. Rounded presentation never authorizes an action. */
        public boolean eligible(BigDecimal price) {
            BigDecimal threshold = threshold(stockCode, market);
            return status == Status.AVAILABLE && threshold != null && high != null && high.signum() > 0
                    && referencePrice != null && referencePrice.signum() > 0 && price != null
                    && referencePrice.compareTo(price) == 0
                    && ("台股".equals(market) && priceBasis == PriceBasis.TW_SPLIT_STOCK_PRICE
                        || "美股".equals(market) && priceBasis == PriceBasis.STORED_PRICE_ONLY)
                    && windowStart != null && completedAsOfDate != null && highDate != null
                    && windowStart.equals(completedAsOfDate.minusWeeks(52))
                    && !highDate.isBefore(windowStart) && !highDate.isAfter(completedAsOfDate)
                    && referencePrice.multiply(HUNDRED).compareTo(high.multiply(HUNDRED.subtract(threshold))) <= 0;
        }
        public boolean validPriceFact(String code, String expectedMarket, BigDecimal price) {
            return status == Status.AVAILABLE && code != null && code.equals(stockCode)
                    && expectedMarket != null && expectedMarket.equals(market)
                    && high != null && high.signum() > 0 && referencePrice != null && referencePrice.signum() > 0
                    && price != null && price.signum() > 0 && price.compareTo(referencePrice) == 0
                    && ("台股".equals(market) && priceBasis == PriceBasis.TW_SPLIT_STOCK_PRICE
                        || "美股".equals(market) && priceBasis == PriceBasis.STORED_PRICE_ONLY)
                    && windowStart != null && completedAsOfDate != null && highDate != null
                    && windowStart.equals(completedAsOfDate.minusWeeks(52))
                    && !highDate.isBefore(windowStart) && !highDate.isAfter(completedAsOfDate);
        }
        public BigDecimal drawdownPercent() {
            return high == null || high.signum() <= 0 || referencePrice == null ? null
                    : high.subtract(referencePrice).multiply(HUNDRED).divide(high, MathContext.DECIMAL128);
        }
    }

    private RadarCompletedPriceDrawdown() {}
    public static BigDecimal threshold(String code, String market) {
        if (code == null || market == null) return null;
        if ("台股".equals(market) && TW_CODES.contains(code)) return new BigDecimal("20");
        if ("美股".equals(market) && US_CODES.contains(code)) return new BigDecimal("30");
        return null;
    }
    public static boolean selected(String code, String market) { return threshold(code, market) != null; }

    /**
     * Price-normalized input is still one canonical read. Only the completed rows enter the peak.
     * A boundary row proves span, not per-session completeness; natural long holidays are not missing-data proof.
     */
    public static Context evaluate(String code, String market, List<StockPriceHistory> priceRowsDesc,
                                   int firstCompleted, BigDecimal price, PriceBasis basis,
                                   Set<LocalDate> droppedDates, boolean droppedUndated) {
        if (code == null || !("台股".equals(market) || "美股".equals(market))) return Context.NOT_EVALUATED;
        if (priceRowsDesc == null || firstCompleted < 0 || firstCompleted >= priceRowsDesc.size()
                || price == null || price.signum() <= 0 || basis == null)
            return Context.unavailable(code, market, "PRICE_WINDOW_UNAVAILABLE");
        StockPriceHistory newest = priceRowsDesc.get(firstCompleted);
        if (newest == null || newest.getTradingDate() == null)
            return Context.unavailable(code, market, "MISSING_COMPLETED_DATE");
        LocalDate asOf = newest.getTradingDate(), from = asOf.minusWeeks(52);
        if (droppedUndated || droppedDates != null && droppedDates.stream().anyMatch(date -> date != null
                && !date.isBefore(from) && !date.isAfter(asOf)))
            return Context.unavailable(code, market, "UPSTREAM_DROPPED_ROW");
        LocalDate previous = null, highDate = null;
        BigDecimal high = null;
        boolean spanCovered = false;
        for (int i = firstCompleted; i < priceRowsDesc.size(); i++) {
            StockPriceHistory row = priceRowsDesc.get(i);
            if (row == null || row.getTradingDate() == null)
                return Context.unavailable(code, market, "MISSING_WINDOW_DATE");
            LocalDate date = row.getTradingDate();
            if (previous != null && !date.isBefore(previous))
                return Context.unavailable(code, market, "NON_DESC_DATES");
            previous = date;
            // The boundary row proves coverage and must belong to the same instrument even outside the peak window.
            if (!code.equals(row.getStockCode()) || !market.equals(row.getMarket()))
                return Context.unavailable(code, market, "ROW_IDENTITY_MISMATCH");
            if (!date.isAfter(from)) spanCovered = true;
            if (date.isBefore(from)) break;
            BigDecimal h = row.getHighPrice(), close = row.getClosePrice();
            if (h == null || close == null || h.signum() <= 0 || close.signum() <= 0 || h.compareTo(close) < 0)
                return Context.unavailable(code, market, "INVALID_OR_MISSING_HIGH");
            if (high == null || h.compareTo(high) > 0) { high = h; highDate = date; }
            if (date.equals(from)) break;
        }
        if (!spanCovered || high == null) return Context.unavailable(code, market, "INSUFFICIENT_52_WEEK_SPAN");
        return new Context(Status.AVAILABLE, code, market, from, asOf, highDate, high, price, basis, null);
    }
}
