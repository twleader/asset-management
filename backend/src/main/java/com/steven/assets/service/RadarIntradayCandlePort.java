package com.steven.assets.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Pure read of external-owned, immutable completed Fubon minute facts and their capture receipt. */
public interface RadarIntradayCandlePort {
    record Candle(Instant candleAt, BigDecimal open, BigDecimal high, BigDecimal low,
                  BigDecimal close, long volume) {}
    record Capture(String stockCode, String market, String provider, String status, String reason,
                   LocalDate sourceDate, Instant requestStartedAt, Instant capturedAt,
                   Instant lastCompletedAt, List<Candle> candles) {
        public Capture { candles = candles == null ? List.of() : List.copyOf(candles); }
    }
    /** Bounded chunks of at most 30 Taiwan codes; never refreshes a vendor, cache or database. */
    Map<String, Capture> read(List<String> stockCodes, LocalDate tradingDate, Instant asOf);
}
