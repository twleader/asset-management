package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** USD/TWD 的兩個固定 Redis key writer；不寫 DB、不 publish。 */
@Slf4j
@Component
public class ExchangeRateSpotCacheWriter {

    public static final String SESSION_KEY = "exchange-rate:session:USD:TWD";
    public static final String SPOT_KEY = "exchange-rate:spot:USD:TWD";
    static final Duration SESSION_TTL = Duration.ofSeconds(10);
    static final Duration SPOT_TTL = Duration.ofHours(24);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Set<UsdTwdSource> TIMESTAMPED_SOURCES =
            Set.of(UsdTwdSource.MEGA_BANK, UsdTwdSource.YAHOO);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ExchangeRateSpotCacheWriter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public boolean writeHeartbeat(Instant heartbeatAt, Set<UsdTwdSource> eligibleSources) {
        if (heartbeatAt == null || eligibleSources == null || eligibleSources.isEmpty()
                || eligibleSources.contains(UsdTwdSource.YAHOO)) {
            return false;
        }
        try {
            List<String> sources = new ArrayList<>();
            if (eligibleSources.contains(UsdTwdSource.BANK_OF_TAIWAN)) {
                sources.add(UsdTwdSource.BANK_OF_TAIWAN.name());
            }
            if (eligibleSources.contains(UsdTwdSource.MEGA_BANK)) {
                sources.add(UsdTwdSource.MEGA_BANK.name());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("heartbeatAt", heartbeatAt.toString());
            payload.put("eligibleSources", sources);
            redis.opsForValue().set(SESSION_KEY, mapper.writeValueAsString(payload), SESSION_TTL);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("寫入 USD/TWD session heartbeat 失敗: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 驗證 quote 與舊 payload，再保留 per-source high-watermark 後覆寫同一 key。
     * 任一舊資料／Redis／序列化錯誤都 fail closed，不得以空 map 重建覆寫。
     */
    public boolean writeSpot(UsdTwdSpotQuote quote) {
        if (!isValidQuote(quote)) return false;
        try {
            String existing = redis.opsForValue().get(SPOT_KEY);
            EnumMap<UsdTwdSource, Instant> highWatermarks = existing == null
                    ? new EnumMap<>(UsdTwdSource.class)
                    : parseExisting(existing);

            if (TIMESTAMPED_SOURCES.contains(quote.source())) {
                Instant previous = highWatermarks.get(quote.source());
                if (previous != null && quote.sourceUpdatedAt().isBefore(previous)) {
                    log.warn("拒絕倒退的 USD/TWD {} provider timestamp: {} < {}",
                            quote.source(), quote.sourceUpdatedAt(), previous);
                    return false;
                }
                highWatermarks.put(quote.source(), quote.sourceUpdatedAt());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rateDate", quote.rateDate().toString());
            payload.put("buyRate", quote.buyRate());
            payload.put("sellRate", quote.sellRate());
            payload.put("source", quote.source().name());
            payload.put("polledAt", quote.polledAt().toString());
            payload.put("sourceUpdatedAt", quote.sourceUpdatedAt() == null
                    ? null : quote.sourceUpdatedAt().toString());
            Map<String, String> serializedWatermarks = new LinkedHashMap<>();
            for (UsdTwdSource source : List.of(UsdTwdSource.MEGA_BANK, UsdTwdSource.YAHOO)) {
                Instant value = highWatermarks.get(source);
                if (value != null) serializedWatermarks.put(source.name(), value.toString());
            }
            payload.put("sourceUpdatedAtHighWatermarks", serializedWatermarks);
            redis.opsForValue().set(SPOT_KEY, mapper.writeValueAsString(payload), SPOT_TTL);
            return true;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("寫入 USD/TWD spot cache 失敗，保留上一筆: {}", ex.getMessage());
            return false;
        }
    }

    private EnumMap<UsdTwdSource, Instant> parseExisting(String json)
            throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("舊 spot payload 非 object");
        validateExistingPayload(root);
        JsonNode mapNode = root.get("sourceUpdatedAtHighWatermarks");
        if (mapNode == null || !mapNode.isObject()) {
            throw new IllegalArgumentException("舊 spot high-watermark map 缺漏");
        }
        EnumMap<UsdTwdSource, Instant> result = new EnumMap<>(UsdTwdSource.class);
        var fields = mapNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            UsdTwdSource source;
            try {
                source = UsdTwdSource.valueOf(field.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("舊 spot high-watermark source 非法", ex);
            }
            if (!TIMESTAMPED_SOURCES.contains(source) || !field.getValue().isTextual()) {
                throw new IllegalArgumentException("舊 spot high-watermark entry 非法");
            }
            result.put(source, Instant.parse(field.getValue().asText()));
        }
        UsdTwdSource currentSource = UsdTwdSource.valueOf(requiredText(root, "source"));
        if (TIMESTAMPED_SOURCES.contains(currentSource)) {
            JsonNode currentTimestampNode = root.get("sourceUpdatedAt");
            if (currentTimestampNode == null || !currentTimestampNode.isTextual()) {
                throw new IllegalArgumentException("舊 spot current source timestamp 缺漏");
            }
            Instant currentTimestamp = Instant.parse(currentTimestampNode.asText());
            if (!currentTimestamp.equals(result.get(currentSource))) {
                throw new IllegalArgumentException("舊 spot current source 與 high-watermark 不一致");
            }
        }
        return result;
    }

    private void validateExistingPayload(JsonNode root) {
        try {
            LocalDate rateDate = LocalDate.parse(requiredText(root, "rateDate"));
            BigDecimal buy = requiredDecimal(root, "buyRate");
            BigDecimal sell = requiredDecimal(root, "sellRate");
            UsdTwdSource source = UsdTwdSource.valueOf(requiredText(root, "source"));
            Instant polledAt = Instant.parse(requiredText(root, "polledAt"));
            JsonNode sourceNode = root.get("sourceUpdatedAt");
            Instant sourceUpdatedAt = sourceNode == null || sourceNode.isNull()
                    ? null : Instant.parse(sourceNode.asText());
            if (!isValidQuote(new UsdTwdSpotQuote(
                    rateDate, buy, sell, source, polledAt, sourceUpdatedAt))) {
                throw new IllegalArgumentException("舊 spot payload validation 失敗");
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("舊 spot payload malformed", ex);
        }
    }

    private static boolean isValidQuote(UsdTwdSpotQuote quote) {
        if (quote == null || quote.rateDate() == null || quote.buyRate() == null
                || quote.sellRate() == null || quote.source() == null || quote.polledAt() == null) {
            return false;
        }
        if (quote.buyRate().signum() <= 0 || quote.sellRate().signum() <= 0
                || quote.buyRate().compareTo(quote.sellRate()) > 0) {
            return false;
        }
        if (quote.source() == UsdTwdSource.BANK_OF_TAIWAN) {
            return quote.sourceUpdatedAt() == null
                    && quote.rateDate().equals(quote.polledAt().atZone(TAIPEI).toLocalDate());
        }
        if (!TIMESTAMPED_SOURCES.contains(quote.source()) || quote.sourceUpdatedAt() == null) return false;
        if (quote.sourceUpdatedAt().isAfter(quote.polledAt().plusSeconds(120))) return false;
        return quote.rateDate().equals(
                quote.sourceUpdatedAt().atZone(TAIPEI).toLocalDate());
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(name + " 缺漏");
        }
        return node.asText();
    }

    private static BigDecimal requiredDecimal(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException(name + " 缺漏");
        return node.decimalValue();
    }
}
