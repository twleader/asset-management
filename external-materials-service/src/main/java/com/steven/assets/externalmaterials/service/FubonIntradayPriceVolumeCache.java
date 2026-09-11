package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketJson;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatterBuilder;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Dedicated Task425 price-volume cache.  It is never a generic price/tick cache or DB writer. */
@Repository
public class FubonIntradayPriceVolumeCache {
    private static final Duration TTL = Duration.ofHours(18);
    private static final DefaultRedisScript<String> WRITE = new DefaultRedisScript<>();
    static { WRITE.setLocation(new ClassPathResource("redis/fubon-intraday-price-volume-write.lua")); WRITE.setResultType(String.class); }
    private final StringRedisTemplate redis;
    private static final java.time.format.DateTimeFormatter UTC_MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    public FubonIntradayPriceVolumeCache(StringRedisTemplate redis) { this.redis = redis; }

    public enum Write { WRITTEN, REJECTED_STALE, FAILED }
    public record Read(String outcome, IntradayVolumesRead snapshot) {}

    public static String key(String symbol) {
        if (!validSymbol(symbol)) throw new IllegalArgumentException("INVALID_SYMBOL");
        return "fubon:intraday-price-volume:台股:" + symbol;
    }

    /** A typed error can never reach this method; only validated OK/NO_DATA snapshots are eligible. */
    public Write write(IntradayVolumesRead snapshot) {
        if (!valid(snapshot)) return Write.FAILED;
        try {
            String payload = encode(snapshot);
            String outcome = redis.execute(WRITE, List.of(key(snapshot.symbol())), payload,
                    Long.toString(TTL.toSeconds()), snapshot.sourceDate().toString());
            return "WRITTEN".equals(outcome) ? Write.WRITTEN : "REJECTED_STALE".equals(outcome) ? Write.REJECTED_STALE : Write.FAILED;
        } catch (RuntimeException failure) { return Write.FAILED; }
    }

    /** Pure cache read: stale/malformed/missing data is simply unavailable and never triggers a provider call. */
    public Read read(String symbol, Instant now) {
        if (!validSymbol(symbol) || now == null) return new Read("MISS", null);
        try {
            String raw = redis.opsForValue().get(key(symbol));
            if (raw == null || raw.length() > 2 * 1024 * 1024) return new Read("MISS", null);
            LocalDate today = now.atZone(MarketClock.TW_ZONE).toLocalDate();
            IntradayVolumesRead snapshot = FubonMarketJson.intradayVolumes(FubonMarketJson.parse(raw), symbol, today, now.plusSeconds(30));
            if (!"OK".equals(snapshot.status()) || snapshot.observedAt().isBefore(now.minusSeconds(120))
                    || snapshot.observedAt().isAfter(now.plusSeconds(30))) return new Read("STALE", null);
            return new Read("AVAILABLE", snapshot);
        } catch (RuntimeException invalid) { return new Read("CORRUPT", null); }
    }

    private static boolean valid(IntradayVolumesRead value) {
        if (value == null || !validSymbol(value.symbol()) || value.sourceDate() == null || value.observedAt() == null
                || !Set425.exchange(value.exchange()) || !value.usableSnapshot()
                || value.sourceMarket() != null && (!value.sourceMarket().matches("[\\x20-\\x7e]{1,20}"))) return false;
        if (!value.sourceDate().equals(value.observedAt().atZone(MarketClock.TW_ZONE).toLocalDate())) return false;
        if ("OK".equals(value.status())) {
            BigDecimal previous = null;
            for (IntradayVolumeLevel level : value.levels()) {
                if (level == null || !positive(level.price()) || level.volume() < 0
                        || level.bidVolume() != null && level.bidVolume() < 0 || level.askVolume() != null && level.askVolume() < 0
                        || previous != null && level.price().compareTo(previous) <= 0) return false;
                previous = level.price();
            }
        }
        return true
                && ("OK".equals(value.status()) ? value.reason() == null && !value.levels().isEmpty()
                : "NO_DATA".equals(value.status()) && "NO_DATA".equals(value.reason()) && value.levels().isEmpty());
    }
    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 20 && Math.max(value.scale(), 0) <= 10;
    }

    private static String encode(IntradayVolumesRead value) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 1); root.put("symbol", value.symbol()); root.put("market", MARKET); root.put("provider", PROVIDER);
            root.put("sourceDate", value.sourceDate().toString());
            root.put("observedAt", UTC_MICROS.format(value.observedAt().truncatedTo(ChronoUnit.MICROS)));
            root.put("instrumentType", "EQUITY"); root.put("exchange", value.exchange()); root.put("sourceMarket", value.sourceMarket());
            root.put("status", value.status()); root.put("reason", value.reason());
            root.put("levels", value.levels().stream().map(level -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("price", canonical(level.price())); row.put("volume", Long.toString(level.volume()));
                row.put("bidVolume", level.bidVolume() == null ? null : Long.toString(level.bidVolume()));
                row.put("askVolume", level.askVolume() == null ? null : Long.toString(level.askVolume()));
                return row;
            }).toList());
            return FubonMarketJson.MAPPER.writeValueAsString(root);
        } catch (Exception impossible) { throw new IllegalArgumentException("INVALID_RESPONSE", impossible); }
    }

    /** Small local boundary helper avoids exporting a mutable exchange allowlist. */
    private static final class Set425 {
        static boolean exchange(String value) { return "TWSE".equals(value) || "TPEx".equals(value) || "ESB".equals(value); }
    }
}
