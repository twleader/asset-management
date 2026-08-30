package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketJson;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Validated compare-and-merge in Lua, with a CAS check binding validation to the exact existing bytes. */
@Component
public class FubonTechnicalIndicatorCacheWriter implements FubonTechnicalCachePort {
    private static final DefaultRedisScript<String> WRITE = new DefaultRedisScript<>();
    static { WRITE.setLocation(new ClassPathResource("redis/fubon-technical-indicator-cache-write.lua")); WRITE.setResultType(String.class); }
    private final StringRedisTemplate redis;
    private final FubonTechnicalIndicatorCacheRepository repository;
    public FubonTechnicalIndicatorCacheWriter(StringRedisTemplate redis, FubonTechnicalIndicatorCacheRepository repository) {
        this.redis = redis; this.repository = repository;
    }
    @Override public FubonTechnicalCache.Read read(String symbol) { return repository.read(symbol); }
    @Override public FubonTechnicalCache.Write write(TechnicalRead read) {
        try {
            FubonTechnicalCache.Document candidate = FubonTechnicalCache.candidate(read);
            String encoded = FubonTechnicalCache.encode(candidate);
            for (int attempt = 0; attempt < 5; attempt++) {
                String raw = repository.raw(read.symbol());
                FubonTechnicalCache.Document current = null;
                if (raw != null) {
                    try { current = FubonTechnicalCache.decode(raw, read.symbol()); }
                    catch (RuntimeException corrupt) { return all("CORRUPT_CACHE"); }
                }
                Map<String, Object> metadata = new TreeMap<>();
                for (String group : GROUPS) {
                    FubonTechnicalCache.Group incoming = candidate.groups().get(group);
                    FubonTechnicalCache.Group existing = current == null ? null : current.groups().get(group);
                    metadata.put(group, Map.of("incomingExpiry", incoming.expiryMillis(),
                            "existingExpiry", existing == null ? 0 : existing.expiryMillis(),
                            "incomingAttempt", incoming.attemptMillis(),
                            "existingAttempt", existing == null ? 0 : existing.attemptMillis()));
                }
                String response = redis.execute(WRITE, List.of(FubonTechnicalCache.key(read.symbol())),
                        raw == null ? "0" : "1", raw == null ? "" : raw, encoded,
                        FubonMarketJson.MAPPER.writeValueAsString(metadata));
                if (response == null) return all("FAILED");
                var result = FubonMarketJson.parse(response);
                if (result.path("retry").asBoolean(false)) continue;
                FubonMarketJson.fields(result, Set.copyOf(GROUPS));
                Map<String, String> outcomes = new TreeMap<>();
                for (String group : GROUPS) outcomes.put(group, FubonMarketJson.text(result.get(group)));
                return new FubonTechnicalCache.Write(outcomes);
            }
            return all("CONCURRENT_WRITE");
        } catch (RuntimeException failure) { return all("FAILED"); }
        catch (Exception failure) { return all("FAILED"); }
    }
    private static FubonTechnicalCache.Write all(String outcome) {
        Map<String, String> results = new TreeMap<>();
        GROUPS.forEach(group -> results.put(group, outcome));
        return new FubonTechnicalCache.Write(results);
    }
}
