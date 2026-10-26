package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable market contract shared by core and IO adapters. */
public final class PriceData {
    private PriceData() {}
    public record PriceResult(
            String stockCode,
            String market,
            BigDecimal price,
            BigDecimal change,
            BigDecimal changePct,
            String source,
            String stockName,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal previousClose,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume,
            LocalDate tradingDate,
            Instant freshnessInstant
    ) {
        /** Source-compatible constructor for non-price call sites while they migrate to timed observations. */
        public PriceResult(
                String stockCode, String market, BigDecimal price, BigDecimal change,
                BigDecimal changePct, String source, String stockName, BigDecimal buyPrice,
                BigDecimal sellPrice, BigDecimal openPrice, BigDecimal previousClose,
                BigDecimal highPrice, BigDecimal lowPrice, Long volume) {
            this(stockCode, market, price, change, changePct, source, stockName, buyPrice,
                    sellPrice, openPrice, previousClose, highPrice, lowPrice, volume, null, null);
        }

        public PriceResult withTiming(LocalDate date, Instant instant) {
            return new PriceResult(stockCode, market, price, change, changePct, source, stockName,
                    buyPrice, sellPrice, openPrice, previousClose, highPrice, lowPrice, volume,
                    date, instant);
        }

        public PriceResult withSource(String stableSource) {
            return new PriceResult(stockCode, market, price, change, changePct, stableSource, stockName,
                    buyPrice, sellPrice, openPrice, previousClose, highPrice, lowPrice, volume,
                    tradingDate, freshnessInstant);
        }
    }
    /**
     * A qualified same-session reference from the TWSE MIS {@code d/y} pair.
     *
     * <p>Fubon's generic {@code previousClose} and Yahoo metadata do not carry this exact-date
     * semantic proof, so they intentionally cannot construct this type.</p>
     */
    public record TwSessionReference(String stockCode, LocalDate tradingDate,
                                     BigDecimal price, String source) {
        public TwSessionReference {
            if (stockCode == null || stockCode.isBlank() || tradingDate == null
                    || price == null || price.signum() <= 0
                    || !"TWSE_MIS_Y".equals(source)) {
                throw new IllegalArgumentException("invalid TWSE session reference");
            }
        }
    }
    public enum TwQuoteClassification { RESOLVED, NO_TRADE, MISSING, INVALID }
    /** Immutable evidence for one complete two-wave Taiwan MIS fetch. */
    public record TwQuoteBatchSummary(
            Map<String, PriceResult> resolved,
            Set<String> noTradeCodes,
            Set<String> missingCodes,
            Set<String> invalidCodes,
            Set<String> foreignCodes,
            Set<String> schemaAnomalies,
            Set<String> sourceTimeAnomalyCodes,
            Set<String> capacityRejectedCodes,
            int httpRequests,
            int requestFailures
    ) {
        public TwQuoteBatchSummary {
            resolved = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
            noTradeCodes = Collections.unmodifiableSet(new LinkedHashSet<>(noTradeCodes));
            missingCodes = Collections.unmodifiableSet(new LinkedHashSet<>(missingCodes));
            invalidCodes = Collections.unmodifiableSet(new LinkedHashSet<>(invalidCodes));
            foreignCodes = Collections.unmodifiableSet(new LinkedHashSet<>(foreignCodes));
            schemaAnomalies = Collections.unmodifiableSet(new LinkedHashSet<>(schemaAnomalies));
            sourceTimeAnomalyCodes = Collections.unmodifiableSet(new LinkedHashSet<>(sourceTimeAnomalyCodes));
            capacityRejectedCodes = Collections.unmodifiableSet(new LinkedHashSet<>(capacityRejectedCodes));
        }

        public int requestedCount() {
            return resolved.size() + noTradeCodes.size() + missingCodes.size() + invalidCodes.size();
        }
    }
}
