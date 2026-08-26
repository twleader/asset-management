package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Dedicated DB-canonical Redis mirror for full normalized Fubon LIVE rows. */
@Slf4j
@Component
public class FubonLiveResponseCache {

    private static final Duration TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<String> WRITE = writeScript();

    public enum WriteOutcome { WRITTEN, REJECTED_STALE, FAILED }

    public record CachedResponse(
            Instant receivedAt,
            String batchId,
            String countersJson,
            String responseRowJson) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public FubonLiveResponseCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public static String key(String market, String code) {
        return "price:fubon-live-response:" + market + ":" + code;
    }

    /** Uses only a DB-returned canonical row and never refreshes a stale key's TTL. */
    public WriteOutcome writeStrictNewer(FubonLiveResponseStore.CanonicalResponse canonical) {
        if (!validCanonical(canonical)) return WriteOutcome.FAILED;
        try {
            String receiptMicros = receiptMicros(canonical.receivedAt());
            ObjectNode payload = mapper.createObjectNode();
            payload.put("receivedAt", canonical.receivedAt().toString());
            payload.put("receivedEpochMicros", receiptMicros);
            payload.put("batchId", canonical.batchId());
            payload.set("counters", mapper.readTree(canonical.countersJson()));
            payload.set("responseRow", mapper.readTree(canonical.responseRowJson()));
            String result = redis.execute(WRITE, List.of(key(canonical.market(), canonical.stockCode())),
                    mapper.writeValueAsString(payload), receiptMicros, Long.toString(TTL.toSeconds()),
                    canonical.market(), canonical.stockCode());
            return switch (result == null ? "FAILED" : result) {
                case "WRITTEN" -> WriteOutcome.WRITTEN;
                case "STALE" -> WriteOutcome.REJECTED_STALE;
                default -> WriteOutcome.FAILED;
            };
        } catch (Exception failure) {
            log.warn("富邦 LIVE 完整回應 Redis 寫入失敗 market={} code={}", canonical.market(), canonical.stockCode());
            return WriteOutcome.FAILED;
        }
    }

    /** Pure candidate read.  Readers must still validate this exact receipt time against PostgreSQL. */
    public Optional<CachedResponse> find(String code, String market) {
        if (!FubonLiveResponseStore.supportedIdentity(code, market)) return Optional.empty();
        try {
            String value = redis.opsForValue().get(key(market, code));
            if (value == null) return Optional.empty();
            JsonNode root = mapper.readTree(value);
            if (root == null || !root.isObject() || root.size() != 5
                    || !root.hasNonNull("receivedAt") || !root.hasNonNull("receivedEpochMicros")
                    || !root.hasNonNull("batchId") || !root.has("counters") || !root.has("responseRow")) {
                return Optional.empty();
            }
            Instant receivedAt = Instant.parse(root.get("receivedAt").asText());
            if (!receiptMicros(receivedAt).equals(root.get("receivedEpochMicros").asText())
                    || root.get("batchId").asText().isBlank() || !root.get("counters").isObject()
                    || !root.get("responseRow").isObject()
                    || !code.equals(root.get("responseRow").path("stockCode").asText())) {
                return Optional.empty();
            }
            return Optional.of(new CachedResponse(receivedAt, root.get("batchId").asText(),
                    mapper.writeValueAsString(root.get("counters")), mapper.writeValueAsString(root.get("responseRow"))));
        } catch (Exception failure) {
            log.warn("富邦 LIVE 完整回應 Redis 讀取失敗 market={} code={}", market, code);
            return Optional.empty();
        }
    }

    static String receiptMicros(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) return null;
        try {
            long micros = Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1_000L);
            return micros >= 0 ? Long.toString(micros) : null;
        } catch (ArithmeticException overflow) { return null; }
    }

    private static boolean validCanonical(FubonLiveResponseStore.CanonicalResponse row) {
        return row != null && FubonLiveResponseStore.supportedIdentity(row.stockCode(), row.market())
                && row.receivedAt() != null && row.receivedAt().equals(row.receivedAt().truncatedTo(ChronoUnit.MICROS))
                && receiptMicros(row.receivedAt()) != null && row.batchId() != null && !row.batchId().isBlank()
                && row.countersJson() != null && row.responseRowJson() != null;
    }

    private static DefaultRedisScript<String> writeScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/fubon-live-response-cache-write.lua"));
        script.setResultType(String.class);
        return script;
    }
}
