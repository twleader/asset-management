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
 * Dedicated best-five Redis projection.  It intentionally never touches generic price keys,
 * indexes, ticks, high/low keys, or the {@code price-update} channel.
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

    /** Writes only a strictly newer canonical Fubon source timestamp and never refreshes stale TTL. */
    public WriteOutcome writeStrictNewer(TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {
        if (!validSnapshot(snapshot)) return WriteOutcome.FAILED;
        try {
            String sourceUpdatedEpochMicros = sourceUpdatedEpochMicros(snapshot.sourceTime());
            if (sourceUpdatedEpochMicros == null) return WriteOutcome.FAILED;
            String payload = mapper.writeValueAsString(new CacheEnvelope(sourceUpdatedEpochMicros, snapshot));
            String result = redis.execute(WRITE, List.of(key(snapshot.market(), snapshot.stockCode())), payload,
                    sourceUpdatedEpochMicros, Long.toString(TTL.toSeconds()), snapshot.market(), snapshot.stockCode());
            return switch (result == null ? "FAILED" : result) {
                case "WRITTEN" -> WriteOutcome.WRITTEN;
                case "STALE" -> WriteOutcome.REJECTED_STALE;
                default -> WriteOutcome.FAILED;
            };
        } catch (Exception failure) {
            log.warn("五檔 Redis 寫入失敗 market={} code={}", snapshot.market(), snapshot.stockCode());
            return WriteOutcome.FAILED;
        }
    }

    /** Pure read: malformed data is a miss and this method never repairs Redis or the database. */
    public Optional<TwQuoteDetailFetchClient.QuoteDetailResult> find(String code, String market) {
        if (code == null || market == null) return Optional.empty();
        try {
            String payload = redis.opsForValue().get(key(market, code));
            if (payload == null) return Optional.empty();
            CacheEnvelope envelope = mapper.readValue(payload, CacheEnvelope.class);
            if (envelope == null || envelope.snapshot() == null || envelope.sourceUpdatedEpochMicros() == null
                    || !envelope.sourceUpdatedEpochMicros().equals(sourceUpdatedEpochMicros(envelope.snapshot().sourceTime()))
                    || !code.equals(envelope.snapshot().stockCode()) || !market.equals(envelope.snapshot().market())
                    || !validSnapshot(envelope.snapshot())) {
                return Optional.empty();
            }
            return Optional.of(envelope.snapshot());
        } catch (Exception failure) {
            log.warn("五檔 Redis 讀取失敗 market={} code={}", market, code);
            return Optional.empty();
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

    private static boolean validSnapshot(TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {
        if (snapshot == null || !snapshot.supported() || !snapshot.available()
                || !"台股".equals(snapshot.market()) || !"FUBON_BOOKS".equals(snapshot.source())
                || snapshot.stockCode() == null || snapshot.stockCode().isBlank() || "0000".equals(snapshot.stockCode())
                || snapshot.stockName() == null || snapshot.stockName().isBlank()
                || snapshot.sourceTime() == null || sourceUpdatedEpochMicros(snapshot.sourceTime()) == null
                || snapshot.fetchedAt() == null || !"OPEN".equals(snapshot.marketStatus())
                || !positive(snapshot.price()) || !positive(snapshot.previousClose())
                || snapshot.levels() == null || snapshot.levels().size() != 5) {
            return false;
        }
        Set<BigDecimal> bids = new HashSet<>();
        Set<BigDecimal> asks = new HashSet<>();
        for (int index = 0; index < 5; index++) {
            TwQuoteDetailFetchClient.OrderBookLevel level = snapshot.levels().get(index);
            if (level == null || level.level() != index + 1
                    || !validSide(level.bidPrice(), level.bidVolumeLots(), bids)
                    || !validSide(level.askPrice(), level.askVolumeLots(), asks)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validSide(BigDecimal price, Long lots, Set<BigDecimal> seen) {
        if (price == null || lots == null) return price == null && lots == null;
        return positive(price) && lots >= 0 && seen.add(price.stripTrailingZeros());
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

    /** The public payload shape is intentionally limited to the ordering token and typed snapshot. */
    private record CacheEnvelope(String sourceUpdatedEpochMicros,
                                 TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {}
}
