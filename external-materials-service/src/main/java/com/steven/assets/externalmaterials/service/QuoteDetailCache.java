package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dedicated Redis candidate cache for database-canonical best-five snapshots.
 *
 * <p>Redis never chooses a source or source time. It accepts only a PostgreSQL-assigned revision,
 * and a reader must compare that revision with PostgreSQL before returning a cached payload.</p>
 */
@Slf4j
@Component
public class QuoteDetailCache {

    public enum WriteOutcome { WRITTEN, REJECTED_STALE, FAILED }

    private static final Duration TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<String> WRITE = writeScript();

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public QuoteDetailCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public static String key(String market, String code) {
        return "price:quote-detail:" + market + ":" + code;
    }

    /** Write only a snapshot paired with the exact revision returned by the DB transaction. */
    public WriteOutcome writeStrictNewer(IntradayOrderBookSnapshotStore.CanonicalSnapshot canonical) {
        if (canonical == null || canonical.canonicalRevision() <= 0 || !validSnapshot(canonical.snapshot())) {
            return WriteOutcome.FAILED;
        }
        try {
            String revision = revisionText(canonical.canonicalRevision());
            String sourceUpdatedEpochMicros = sourceUpdatedEpochMicros(canonical.snapshot().sourceTime());
            if (revision == null || sourceUpdatedEpochMicros == null) return WriteOutcome.FAILED;
            String payload = mapper.writeValueAsString(new CacheEnvelope(
                    revision, sourceUpdatedEpochMicros, canonical.snapshot()));
            String result = redis.execute(WRITE, List.of(key(canonical.snapshot().market(), canonical.snapshot().stockCode())),
                    payload, revision, Long.toString(TTL.toSeconds()), canonical.snapshot().market(),
                    canonical.snapshot().stockCode());
            return switch (result == null ? "FAILED" : result) {
                case "WRITTEN" -> WriteOutcome.WRITTEN;
                case "STALE" -> WriteOutcome.REJECTED_STALE;
                default -> WriteOutcome.FAILED;
            };
        } catch (Exception failure) {
            log.warn("五檔 Redis 寫入失敗 market={} code={}", canonical.snapshot().market(), canonical.snapshot().stockCode());
            return WriteOutcome.FAILED;
        }
    }

    /**
     * Pure candidate read. Legacy payloads without an exact positive revision are cache misses;
     * this method neither repairs Redis nor reads/writes PostgreSQL.
     */
    public Optional<CachedSnapshot> find(String code, String market) {
        if (code == null || market == null) return Optional.empty();
        try {
            String payload = redis.opsForValue().get(key(market, code));
            if (payload == null) return Optional.empty();
            CacheEnvelope envelope = mapper.readValue(payload, CacheEnvelope.class);
            if (envelope == null || envelope.snapshot() == null || !validRevision(envelope.canonicalRevision())
                    || envelope.sourceUpdatedEpochMicros() == null
                    || !envelope.sourceUpdatedEpochMicros().equals(sourceUpdatedEpochMicros(envelope.snapshot().sourceTime()))
                    || !code.equals(envelope.snapshot().stockCode()) || !market.equals(envelope.snapshot().market())
                    || !validSnapshot(envelope.snapshot())) {
                return Optional.empty();
            }
            return Optional.of(new CachedSnapshot(envelope.canonicalRevision(), envelope.snapshot()));
        } catch (Exception failure) {
            log.warn("五檔 Redis 讀取失敗 market={} code={}", market, code);
            return Optional.empty();
        }
    }

    /** Exact decimal-string representation used in payload and Lua comparison. */
    static String revisionText(long revision) {
        return revision > 0 ? Long.toString(revision) : null;
    }

    static boolean validRevision(String value) {
        if (value == null || !value.matches("^[1-9][0-9]*$")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    static String sourceUpdatedEpochMicros(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) return null;
        try {
            long micros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
                    value.getNano() / 1_000L);
            return micros < 0 ? null : Long.toString(micros);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    static boolean validSnapshot(TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {
        if (snapshot == null || !snapshot.supported() || !snapshot.available()
                || !"台股".equals(snapshot.market()) || !allowedSource(snapshot.source())
                || snapshot.stockCode() == null || !snapshot.stockCode().matches("^[0-9]{4,6}[A-Z]?$")
                || "0000".equals(snapshot.stockCode()) || snapshot.stockName() == null || snapshot.stockName().isBlank()
                || snapshot.sourceTime() == null || sourceUpdatedEpochMicros(snapshot.sourceTime()) == null
                || snapshot.fetchedAt() == null || !"OPEN".equals(snapshot.marketStatus())
                || !positive(snapshot.price()) || !positive(snapshot.previousClose())
                || snapshot.levels() == null || snapshot.levels().size() != 5) {
            return false;
        }
        Set<BigDecimal> bids = new HashSet<>();
        Set<BigDecimal> asks = new HashSet<>();
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        for (int index = 0; index < 5; index++) {
            TwQuoteDetailFetchClient.OrderBookLevel level = snapshot.levels().get(index);
            if (level == null || level.level() != index + 1
                    || !validCompleteSide(level.bidPrice(), level.bidVolumeLots(), bids)
                    || !validCompleteSide(level.askPrice(), level.askVolumeLots(), asks)
                    || (previousBid != null && previousBid.compareTo(level.bidPrice()) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(level.askPrice()) >= 0)) {
                return false;
            }
            previousBid = level.bidPrice();
            previousAsk = level.askPrice();
        }
        return true;
    }

    private static boolean allowedSource(String source) {
        return "FUBON_BOOKS".equals(source) || "YAHOO_TW".equals(source);
    }

    private static boolean validCompleteSide(BigDecimal price, Long lots, Set<BigDecimal> seen) {
        return positive(price) && lots != null && lots > 0 && seen.add(price.stripTrailingZeros());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10;
    }

    private static DefaultRedisScript<String> writeScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/quote-detail-cache-write.lua"));
        script.setResultType(String.class);
        return script;
    }

    public record CachedSnapshot(String canonicalRevision, TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {}

    /** Internal JSON shape; the revision intentionally remains a decimal string. */
    private record CacheEnvelope(String canonicalRevision, String sourceUpdatedEpochMicros,
                                 TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {}
}
