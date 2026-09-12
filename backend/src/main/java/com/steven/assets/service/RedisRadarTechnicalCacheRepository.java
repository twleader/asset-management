package com.steven.assets.service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** Redis/Lua implementation of {@link RadarTechnicalCachePort}. */
@Repository
public class RedisRadarTechnicalCacheRepository implements RadarTechnicalCachePort {
    private static final String ABSENT = "__ABSENT__";
    private static final DefaultRedisScript<String> PAIR_WRITE = new DefaultRedisScript<>();
    private static final DefaultRedisScript<String> MARKET_LOCAL_WRITE = new DefaultRedisScript<>();

    static {
        PAIR_WRITE.setLocation(new ClassPathResource("redis/fubon-technical-v2-pair-write.lua"));
        PAIR_WRITE.setResultType(String.class);
        MARKET_LOCAL_WRITE.setLocation(new ClassPathResource("redis/radar-technical-market-local-write.lua"));
        MARKET_LOCAL_WRITE.setResultType(String.class);
    }

    private final StringRedisTemplate redis;

    public RedisRadarTechnicalCacheRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<String, Pair> readPairs(List<String> codes) {
        if (codes == null || codes.isEmpty()) return Map.of();
        try {
            List<String> keys = new ArrayList<>(codes.size() * 2);
            for (String code : codes) {
                keys.add(key(code, "D"));
                keys.add(key(code, "W"));
            }
            List<String> values = redis.opsForValue().multiGet(keys);
            if (values == null || values.size() != keys.size()) return Map.of();
            Map<String, Pair> result = new LinkedHashMap<>();
            for (int index = 0; index < codes.size(); index++) {
                result.put(codes.get(index), new Pair(values.get(index * 2), values.get(index * 2 + 1)));
            }
            return Map.copyOf(result);
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    @Override
    public String writePair(PairWrite request) {
        if (request == null || request.code() == null || request.expected() == null
                || request.dailyDocument() == null || request.weeklyDocument() == null
                || request.freshUntil() == null) return null;
        try {
            return redis.execute(PAIR_WRITE, List.of(key(request.code(), "D"), key(request.code(), "W")),
                    request.expected().daily() == null ? ABSENT : request.expected().daily(),
                    request.expected().weekly() == null ? ABSENT : request.expected().weekly(),
                    request.dailyDocument(), request.weeklyDocument(),
                    Long.toString(request.freshUntil().toEpochMilli()),
                    request.allowFubonReplaceLocal() ? "1" : "0");
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Override
    public String readMarketLocal(String market, String code) {
        if (market == null || market.isBlank() || code == null || code.isBlank()) return null;
        try {
            return redis.opsForValue().get(marketLocalKey(market, code));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Override
    public Map<MarketLocalKey, String> readMarketLocals(List<MarketLocalKey> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) return Map.of();
        List<MarketLocalKey> keys = rawKeys.stream()
                .filter(key -> key != null && key.market() != null && !key.market().isBlank()
                        && key.code() != null && !key.code().isBlank())
                .distinct()
                .sorted(java.util.Comparator.comparing(MarketLocalKey::market)
                        .thenComparing(MarketLocalKey::code))
                .toList();
        if (keys.isEmpty()) return Map.of();
        try {
            List<String> redisKeys = keys.stream()
                    .map(key -> marketLocalKey(key.market(), key.code())).toList();
            List<String> values = redis.opsForValue().multiGet(redisKeys);
            if (values == null || values.size() != keys.size()) return Map.of();
            Map<MarketLocalKey, String> result = new LinkedHashMap<>();
            for (int index = 0; index < keys.size(); index++) {
                String value = values.get(index);
                if (value != null) result.put(keys.get(index), value);
            }
            return Map.copyOf(result);
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    @Override
    public String writeMarketLocal(MarketLocalWrite request) {
        if (request == null || request.market() == null || request.market().isBlank()
                || request.code() == null || request.code().isBlank()
                || request.document() == null || request.freshUntil() == null) return null;
        try {
            return redis.execute(MARKET_LOCAL_WRITE, List.of(marketLocalKey(request.market(), request.code())),
                    request.expectedDocument() == null ? ABSENT : request.expectedDocument(), request.document(),
                    Long.toString(request.freshUntil().toEpochMilli()));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static String key(String code, String timeframe) {
        return "fubon:technical:tw:{" + code + "}:" + timeframe + ":v2";
    }

    /** Stable UTF-8 identity prevents `code` reuse in another market from colliding. */
    static String marketLocalKey(String market, String code) {
        if (market == null || market.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("market/code required");
        }
        String identity = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((market + '\u0000' + code).getBytes(StandardCharsets.UTF_8));
        return "radar:technical:local:{" + identity + "}:v1";
    }
}
