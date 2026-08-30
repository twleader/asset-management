package com.steven.assets.externalmaterials.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** Pure GET and validation only; no repair, TTL refresh or vendor access. */
@Repository
public class FubonTechnicalIndicatorCacheRepository {
    private final StringRedisTemplate redis;
    public FubonTechnicalIndicatorCacheRepository(StringRedisTemplate redis) { this.redis = redis; }
    String raw(String symbol) { return redis.opsForValue().get(FubonTechnicalCache.key(symbol)); }
    public FubonTechnicalCache.Read read(String symbol) {
        try {
            String raw = raw(symbol);
            if (raw == null) return new FubonTechnicalCache.Read("MISS", null);
            try { return new FubonTechnicalCache.Read("AVAILABLE", FubonTechnicalCache.decode(raw, symbol)); }
            catch (RuntimeException corrupt) { return new FubonTechnicalCache.Read("CORRUPT_CACHE", null); }
        } catch (RuntimeException unavailable) { return new FubonTechnicalCache.Read("UNAVAILABLE", null); }
    }
}
