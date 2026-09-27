package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.FubonIntradayTechnicalJson;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** Atomic Redis-only latest-point cache for both intraday timeframes. */
@Repository
public class FubonIntradayTechnicalCache {
    public static final Duration TTL = Duration.ofMinutes(12);
    static final String CURSOR_KEY = "fubon:technical:intraday:tw:cursor:v1";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final java.time.format.DateTimeFormatter UTC_MICROS =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static final DefaultRedisScript<String> WRITE = new DefaultRedisScript<>();
    static {
        WRITE.setLocation(new ClassPathResource("redis/fubon-intraday-technical-write.lua"));
        WRITE.setResultType(String.class);
    }

    private final StringRedisTemplate redis;
    private final MarketClock clock;
    public FubonIntradayTechnicalCache(StringRedisTemplate redis, MarketClock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public enum Write { WRITTEN, REJECTED_STALE, FAILED }

    public String readCursor() {
        return redis.opsForValue().get(CURSOR_KEY);
    }

    public void advanceCursor(String symbol) {
        if (!FubonMarketData.validSymbol(symbol)) throw new IllegalArgumentException("INVALID_SYMBOL");
        redis.opsForValue().set(CURSOR_KEY, symbol);
    }

    public static String key(String symbol) {
        if (!FubonMarketData.validSymbol(symbol)) throw new IllegalArgumentException("INVALID_SYMBOL");
        return "fubon:technical:intraday:tw:" + symbol + ":v1";
    }

    public Write write(FubonIntradayTechnical.Bundle bundle) {
        if (bundle == null || bundle.schemaVersion() != 1 || !FubonMarketData.validSymbol(bundle.symbol())
                || !FubonMarketData.MARKET.equals(bundle.market()) || !FubonMarketData.PROVIDER.equals(bundle.provider())
                || bundle.observedAt() == null || bundle.oneMinute() == null || bundle.fiveMinute() == null) return Write.FAILED;
        try {
            String payload = encode(bundle);
            Instant now = clock.instant();
            LocalDate today = now.atZone(MarketClock.TW_ZONE).toLocalDate();
            FubonIntradayTechnicalJson.parse(FubonMarketJson.parse(payload), bundle.symbol(), today, now);
            String outcome = redis.execute(WRITE, List.of(key(bundle.symbol())), payload, Long.toString(TTL.toSeconds()));
            if ("WRITTEN".equals(outcome)) return Write.WRITTEN;
            if ("REJECTED_STALE".equals(outcome)) return Write.REJECTED_STALE;
            return Write.FAILED;
        } catch (RuntimeException failure) { return Write.FAILED; }
    }

    private static String encode(FubonIntradayTechnical.Bundle value) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 1);
            root.put("symbol", value.symbol());
            root.put("market", value.market());
            root.put("provider", value.provider());
            root.put("observedAt", instant(value.observedAt()));
            root.put("oneMinute", frame(value.oneMinute()));
            root.put("fiveMinute", frame(value.fiveMinute()));
            return JSON.writeValueAsString(root);
        } catch (Exception invalid) { throw new IllegalArgumentException("INVALID_RESPONSE", invalid); }
    }

    private static Map<String, Object> frame(FubonIntradayTechnical.Frame value) {
        if (value == null || value.sourceDate() == null || value.observedAt() == null
                || value.kdj() == null || value.macd() == null || value.bollinger() == null) throw new IllegalArgumentException("INVALID_RESPONSE");
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("timeframe", value.timeframe());
        frame.put("sourceDate", value.sourceDate().toString());
        frame.put("sourceTimestamp", value.sourceTimestamp() == null ? null : instant(value.sourceTimestamp()));
        frame.put("observedAt", instant(value.observedAt()));
        Map<String, String> kdj = new LinkedHashMap<>();
        kdj.put("k", decimal(value.kdj().k())); kdj.put("d", decimal(value.kdj().d())); kdj.put("j", decimal(value.kdj().j()));
        Map<String, String> macd = new LinkedHashMap<>();
        macd.put("macdLine", decimal(value.macd().macdLine())); macd.put("signalLine", decimal(value.macd().signalLine()));
        Map<String, String> bollinger = new LinkedHashMap<>();
        bollinger.put("upper", decimal(value.bollinger().upper()));
        bollinger.put("middle", decimal(value.bollinger().middle()));
        bollinger.put("lower", decimal(value.bollinger().lower()));
        frame.put("kdj", kdj); frame.put("macd", macd); frame.put("bollinger", bollinger);
        return frame;
    }

    private static String instant(Instant value) {
        return UTC_MICROS.format(value.truncatedTo(ChronoUnit.MICROS));
    }

    private static String decimal(java.math.BigDecimal value) {
        if (value == null || value.precision() > 38 || Math.max(value.scale(), 0) > 18)
            throw new IllegalArgumentException("INVALID_DECIMAL");
        return FubonMarketData.canonical(value);
    }
}
