package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Immutable market contract shared by core and IO adapters. */
public final class QuoteDetailData {
    private QuoteDetailData() {}
    public record OrderBookLevel(int level, BigDecimal bidPrice, Long bidVolumeLots,
                                 BigDecimal askPrice, Long askVolumeLots) {}
    public record QuoteDetailResult(String stockCode, String stockName, String market,
                                    boolean supported, boolean available, String source, String message,
                                    Instant sourceTime, Instant fetchedAt, String marketStatus,
                                    BigDecimal price, BigDecimal previousClose, BigDecimal openPrice,
                                    BigDecimal highPrice, BigDecimal lowPrice, BigDecimal averagePrice,
                                    BigDecimal change, BigDecimal changePercent, BigDecimal turnoverYi,
                                    Long volumeLots, Long previousVolumeLots, BigDecimal amplitudePercent,
                                    Long innerVolumeLots, Long outerVolumeLots, BigDecimal innerPercent,
                                    BigDecimal outerPercent, Long bidTotalLots, Long askTotalLots,
                                    List<OrderBookLevel> levels) {}
    /** Immutable result for one suffix request; only FOUND carries a snapshot. */
    public record YahooProbeOutcome(Kind kind, QuoteDetailResult snapshot) {
        public enum Kind { FOUND, STRUCTURAL_MISS, TRANSIENT_OR_INVALID }

        public static YahooProbeOutcome found(QuoteDetailResult snapshot) {
            return new YahooProbeOutcome(Kind.FOUND, snapshot);
        }

        public static YahooProbeOutcome structuralMiss() {
            return new YahooProbeOutcome(Kind.STRUCTURAL_MISS, null);
        }

        public static YahooProbeOutcome transientOrInvalid() {
            return new YahooProbeOutcome(Kind.TRANSIENT_OR_INVALID, null);
        }
    }
}
