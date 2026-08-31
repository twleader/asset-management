package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** Pair MGET/dual-key Lua implementation; no vendor request and no database mutation. */
@Repository
public class FubonTechnicalV2CacheRepository {
    private static final DefaultRedisScript<String> WRITE = new DefaultRedisScript<>();
    static { WRITE.setLocation(new ClassPathResource("redis/fubon-technical-v2-pair-write.lua")); WRITE.setResultType(String.class); }
    private static final String ABSENT = "__ABSENT__";
    private final StringRedisTemplate redis;
    public FubonTechnicalV2CacheRepository(StringRedisTemplate redis) { this.redis = redis; }

    public record Read(String outcome, FubonTechnicalV2Cache.Pair pair, String dailyGeneration, String weeklyGeneration) {}
    public record Write(String outcome) {}

    public Read read(String code, Instant now, String fingerprint, boolean requireBound) {
        try {
            List<String> raw = redis.opsForValue().multiGet(List.of(FubonTechnicalV2Cache.key(code, "D"), FubonTechnicalV2Cache.key(code, "W")));
            if (raw == null || raw.size() != 2 || raw.get(0) == null || raw.get(1) == null)
                return new Read("MISS", null, generation(raw, 0), generation(raw, 1));
            var daily = FubonTechnicalV2Cache.decode(raw.get(0), code, "D", now, fingerprint, requireBound);
            var weekly = FubonTechnicalV2Cache.decode(raw.get(1), code, "W", now, fingerprint, requireBound);
            if (!daily.bundleGeneration().equals(weekly.bundleGeneration()) || !daily.captureId().equals(weekly.captureId())
                    || !daily.origin().equals(weekly.origin()) || !daily.binding().equals(weekly.binding())
                    || !Objects.equals(daily.contextFingerprint(), weekly.contextFingerprint())
                    || !daily.oldestObservedAt().equals(weekly.oldestObservedAt())
                    || !daily.freshUntil().equals(weekly.freshUntil()))
                return new Read("CORRUPT_CACHE", null, generation(raw, 0), generation(raw, 1));
            return new Read("AVAILABLE", new FubonTechnicalV2Cache.Pair(daily, weekly), daily.bundleGeneration(), weekly.bundleGeneration());
        } catch (RuntimeException failure) { return new Read("CORRUPT_CACHE", null, null, null); }
    }

    public Write write(String code, FubonTechnicalV2Cache.Pair pair, Read expected, boolean forceFubon) {
        if (pair == null || expected == null) return new Write("FAILED");
        // Do not consult the Java wall clock for expiry.  Redis TIME is the
        // authoritative fence inside the Lua script; a local pre-check could
        // falsely reject a still-valid DB re-projection on clock skew.
        if (!pair.daily().freshUntil().equals(pair.weekly().freshUntil())
                || !pair.daily().bundleGeneration().equals(pair.weekly().bundleGeneration())
                || !pair.daily().captureId().equals(pair.weekly().captureId())
                || !Objects.equals(pair.daily().contextFingerprint(), pair.weekly().contextFingerprint()))
            return new Write("FAILED");
        if (forceFubon && (!"FUBON_SDK".equals(pair.daily().origin())
                || !"UNBOUND_FUBON_SOURCE".equals(pair.daily().binding()))) return new Write("FAILED");
        try {
            String response = redis.execute(WRITE, List.of(FubonTechnicalV2Cache.key(code, "D"), FubonTechnicalV2Cache.key(code, "W")),
                    expected.dailyGeneration() == null ? ABSENT : expected.dailyGeneration(),
                    expected.weeklyGeneration() == null ? ABSENT : expected.weeklyGeneration(),
                    FubonTechnicalV2Cache.encode(pair.daily()), FubonTechnicalV2Cache.encode(pair.weekly()),
                    Long.toString(pair.daily().freshUntil().toEpochMilli()), forceFubon ? "1" : "0");
            return new Write(response == null ? "FAILED" : response);
        } catch (RuntimeException failure) { return new Write("FAILED"); }
    }

    private static String generation(List<String> raw, int index) {
        if (raw == null || raw.size() <= index || raw.get(index) == null) return null;
        try {
            JsonNode node = FubonMarketJson.parse(raw.get(index));
            JsonNode value = node.get("bundleGeneration");
            return value == null || !value.isTextual() ? "__CORRUPT__" : value.textValue();
        } catch (RuntimeException invalid) { return "__CORRUPT__"; }
    }
}
