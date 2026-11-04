package com.steven.assets.integration.holdingcost;

import com.steven.assets.service.HoldingCostCachePort;
import com.steven.assets.service.HoldingCostSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** A content-addressed private mirror, separate from global price and immutable radar keys. */
@Component
public class RedisHoldingCostCacheAdapter implements HoldingCostCachePort {
    public static final Duration RETENTION = Duration.ofHours(36);
    private static final Logger log = LoggerFactory.getLogger(RedisHoldingCostCacheAdapter.class);
    private final StringRedisTemplate redis;

    public RedisHoldingCostCacheAdapter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public boolean publish(HoldingCostSnapshot committed) {
        // Orchestration must call only after the transactional writer proxy has committed.
        if (committed == null || TransactionSynchronizationManager.isActualTransactionActive()) return false;
        try {
            redis.opsForValue().set(committed.cacheKey(), committed.canonicalJson(), RETENTION);
            return true;
        } catch (RuntimeException unavailable) {
            log.warn("Private holding cost cache write unavailable owner={} snapshot={}", committed.ownerId(), committed.snapshotId());
            return false;
        }
    }

    @Override
    public Map<Long, BigDecimal> readMatching(HoldingCostSnapshot database) {
        if (database == null || database.entries().isEmpty()) return Map.of();
        try {
            String candidate = redis.opsForValue().get(database.cacheKey());
            // Exact canonical bytes validate every field, complete scope, numeric representation and schema.
            if (!database.canonicalJson().equals(candidate)) return Map.of();
            return database.entries().stream().collect(Collectors.toUnmodifiableMap(
                    HoldingCostSnapshot.Entry::holdingId, HoldingCostSnapshot.Entry::investmentCost));
        } catch (RuntimeException unavailable) {
            log.warn("Private holding cost cache read unavailable owner={} snapshot={}", database.ownerId(), database.snapshotId());
            return Map.of();
        }
    }
}
