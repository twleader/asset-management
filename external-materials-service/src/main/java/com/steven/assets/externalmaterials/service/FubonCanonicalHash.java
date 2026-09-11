package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Canonical source-fact hashes.  No vendor/wire-supplied hash is trusted. */
public final class FubonCanonicalHash {
    private FubonCanonicalHash() {}

    public static String technical(TechnicalProfile profile, LocalDate sourceDate, Map<String, String> payload) {
        return digest(technicalInputBytes(profile, sourceDate, payload));
    }
    /** Exact UTF-8 bytes used by the immutable PostgreSQL/Redis source-fact writer. */
    public static byte[] technicalInputBytes(TechnicalProfile profile, LocalDate sourceDate, Map<String, String> payload) {
        if (profile == null || sourceDate == null || payload == null) throw new IllegalArgumentException("technical hash inputs");
        return ("FUBON_TECHNICAL_FACT_V1\n" + profile.profileId() + "\n" + sourceDate + "\n"
                + canonical(profile.parameters()) + "\n" + canonical(payload)).getBytes(StandardCharsets.UTF_8);
    }
    public static String basic(Map<String, Object> rootWithoutObservedAt) {
        return digest("FUBON_BASIC_V1\n" + canonical(rootWithoutObservedAt));
    }
    public static String candle(Map<String, Object> sourceAndCandle) {
        return digest("FUBON_INTRADAY_CANDLE_V1\n" + canonical(sourceAndCandle));
    }
    /** Task425 immutable daily fact hash; observedAt/createdAt are deliberately not source content. */
    public static String dailyCandle(Map<String, Object> canonicalDailyCandle) {
        return digest(dailyCandleInputBytes(canonicalDailyCandle));
    }
    public static byte[] dailyCandleInputBytes(Map<String, Object> canonicalDailyCandle) {
        if (canonicalDailyCandle == null) throw new IllegalArgumentException("daily candle hash inputs");
        return ("FUBON_HISTORICAL_DAILY_CANDLE_FACT_V1\n" + canonical(canonicalDailyCandle)).getBytes(StandardCharsets.UTF_8);
    }
    public static String canonical(Map<String, ?> value) {
        try { return FubonMarketJson.MAPPER.writeValueAsString(new TreeMap<>(value)); }
        catch (Exception impossible) { throw new IllegalArgumentException("SCHEMA_INVALID", impossible); }
    }
    public static String decimal(BigDecimal value) {
        return FubonMarketData.canonical(value);
    }
    private static String digest(String input) { return digest(input.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input)); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
