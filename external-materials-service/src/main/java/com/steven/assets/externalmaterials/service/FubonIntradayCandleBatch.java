package com.steven.assets.externalmaterials.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Container-only persisted facts; never vendor payloads or a price authority. */
public record FubonIntradayCandleBatch(int schemaVersion, LocalDate tradingDate, Instant asOf,
                                     List<Stock> stocks) {
    public FubonIntradayCandleBatch { stocks = List.copyOf(stocks); }
    public record Candle(Instant candleAt, String open, String high, String low, String close, long volume) {}
    public record Stock(String stockCode, String market, String provider, String status, String reason,
                        LocalDate sourceDate, Instant requestStartedAt, Instant capturedAt,
                        Instant lastCompletedAt, List<Candle> candles) {
        public Stock { candles = List.copyOf(candles); }
        public static Stock unavailable(String code, LocalDate day, String reason) {
            return new Stock(code, "台股", "FUBON_SDK", "UNAVAILABLE", reason, day,
                    null, null, null, List.of());
        }
    }
}
