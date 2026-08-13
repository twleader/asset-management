package com.steven.assets.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Redis/raw JSON 只留在外圍 adapter，轉成 immutable cache model 再交給 service。 */
@Component
public class RedisUsdTwdLiveCacheAdapter implements UsdTwdLiveRateCachePort {

    static final String SESSION_KEY = "exchange-rate:session:USD:TWD";
    static final String SPOT_KEY = "exchange-rate:spot:USD:TWD";

    private static final Set<String> TIMESTAMPED_SOURCES = Set.of("MEGA_BANK", "YAHOO");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisUsdTwdLiveCacheAdapter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Snapshot readSnapshot() {
        String heartbeatJson = redis.opsForValue().get(SESSION_KEY);
        String spotJson = redis.opsForValue().get(SPOT_KEY);
        return new Snapshot(
                heartbeatJson == null ? Optional.empty() : Optional.of(parseHeartbeat(heartbeatJson)),
                spotJson == null ? Optional.empty() : Optional.of(parseSpot(spotJson)));
    }

    private Heartbeat parseHeartbeat(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) malformed("heartbeat 非 object");
            Instant heartbeatAt = Instant.parse(requiredText(root, "heartbeatAt"));
            JsonNode sources = root.get("eligibleSources");
            if (sources == null || !sources.isArray() || sources.isEmpty()) {
                malformed("heartbeat eligibleSources 缺漏");
            }
            Set<String> eligibleSources = new HashSet<>();
            for (JsonNode source : sources) {
                if (!source.isTextual() || !eligibleSources.add(source.asText())) {
                    malformed("heartbeat eligibleSources 非法");
                }
            }
            return new Heartbeat(heartbeatAt, eligibleSources);
        } catch (MalformedUsdTwdRateException ex) {
            throw ex;
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new MalformedUsdTwdRateException("USD/TWD heartbeat malformed", ex);
        }
    }

    private Spot parseSpot(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) malformed("spot 非 object");
            JsonNode sourceTimeNode = root.get("sourceUpdatedAt");
            Instant sourceUpdatedAt = sourceTimeNode == null || sourceTimeNode.isNull()
                    ? null : Instant.parse(sourceTimeNode.asText());
            return new Spot(
                    LocalDate.parse(requiredText(root, "rateDate")),
                    requiredDecimal(root, "buyRate"),
                    requiredDecimal(root, "sellRate"),
                    requiredText(root, "source"),
                    Instant.parse(requiredText(root, "polledAt")),
                    sourceUpdatedAt,
                    parseWatermarks(root));
        } catch (MalformedUsdTwdRateException ex) {
            throw ex;
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new MalformedUsdTwdRateException("USD/TWD spot malformed", ex);
        }
    }

    private Map<String, Instant> parseWatermarks(JsonNode root) {
        JsonNode watermarks = root.get("sourceUpdatedAtHighWatermarks");
        if (watermarks == null || !watermarks.isObject()) malformed("spot high-watermark map 缺漏");
        Map<String, Instant> parsed = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = watermarks.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!TIMESTAMPED_SOURCES.contains(field.getKey()) || !field.getValue().isTextual()) {
                malformed("spot high-watermark entry 非法");
            }
            parsed.put(field.getKey(), Instant.parse(field.getValue().asText()));
        }
        return parsed;
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) malformed(name + " 缺漏");
        return node.asText();
    }

    private static BigDecimal requiredDecimal(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isNumber()) malformed(name + " 缺漏");
        return node.decimalValue();
    }

    private static void malformed(String message) {
        throw new MalformedUsdTwdRateException(message);
    }
}
