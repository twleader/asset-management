package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Strict, latest-point-only Fubon 1m/5m technical bundle. */
public final class FubonIntradayTechnical {
    private FubonIntradayTechnical() {}

    public record Bundle(int schemaVersion, String symbol, String market, String provider,
                         Instant observedAt, Frame oneMinute, Frame fiveMinute) {}

    public record Frame(String timeframe, LocalDate sourceDate, Instant sourceTimestamp,
                        Instant observedAt, Kdj kdj, Macd macd, Bollinger bollinger) {}

    public record Kdj(BigDecimal k, BigDecimal d, BigDecimal j) {}
    public record Macd(BigDecimal macdLine, BigDecimal signalLine) {}
    public record Bollinger(BigDecimal upper, BigDecimal middle, BigDecimal lower) {}
}
