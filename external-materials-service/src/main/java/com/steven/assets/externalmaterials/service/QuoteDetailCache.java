package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookCacheData.CachedSnapshot;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookCacheData.WriteOutcome;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookData;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;
import com.steven.assets.externalmaterials.service.port.OrderBookCachePort;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import static com.steven.assets.externalmaterials.model.marketdata.OrderBookCacheRules.*;

/**
 * Dedicated Redis candidate cache for database-canonical best-five snapshots.
 *
 * <p>Redis never chooses a source or source time. It accepts only a PostgreSQL-assigned revision,
 * and a reader must compare that revision with PostgreSQL before returning a cached payload.</p>
 */
@Slf4j
@Component
public class QuoteDetailCache implements OrderBookCachePort {

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
    public WriteOutcome writeStrictNewer(OrderBookData.CanonicalSnapshot canonical) {
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

    private static DefaultRedisScript<String> writeScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/quote-detail-cache-write.lua"));
        script.setResultType(String.class);
        return script;
    }

    /** Internal JSON shape; the revision intentionally remains a decimal string. */
    private record CacheEnvelope(String canonicalRevision, String sourceUpdatedEpochMicros,
                                 QuoteDetailData.QuoteDetailResult snapshot) {}
}
