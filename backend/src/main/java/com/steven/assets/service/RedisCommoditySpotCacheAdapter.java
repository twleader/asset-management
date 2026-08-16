package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * {@link CommoditySpotCachePort} 的 Redis 實作（Requirement 77 / Task 337）。
 *
 * <p>只讀 Redis、不寫、不連外部行情 API（CLAUDE.md：即時價走 Redis、business-services 不直連
 * 外部行情 API）。刻意與 {@link RedisUsdTwdLiveCacheAdapter} 的 fail-closed 政策相反：
 * 任何一支標的的 payload 解析失敗只 {@code log.warn} 並回該標的 empty，不得讓
 * {@code GET /api/market-data/commodity/live} 因為單一標的損毀而整支 5xx（337.9）。
 */
@Slf4j
@Component
public class RedisCommoditySpotCacheAdapter implements CommoditySpotCachePort {

    static final String SESSION_KEY = "commodity:session";
    static final String SPOT_KEY_PREFIX = "commodity:spot:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisCommoditySpotCacheAdapter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public boolean isMarketOpen() {
        Boolean exists = redis.hasKey(SESSION_KEY);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<Spot> readSpot(String commodityCode) {
        String json = redis.opsForValue().get(SPOT_KEY_PREFIX + commodityCode);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(parseSpot(commodityCode, json));
        } catch (Exception ex) {
            log.warn("解析 commodity:spot:{} payload 失敗，視為缺值：{}", commodityCode, ex.getMessage());
            return Optional.empty();
        }
    }

    private Spot parseSpot(String commodityCode, String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("payload 非 object");

        BigDecimal price = requiredDecimal(root, "price");
        LocalDate sessionDate = LocalDate.parse(requiredText(root, "sessionDate"));
        Instant quoteTime = Instant.parse(requiredText(root, "quoteTime"));
        Instant polledAt = Instant.parse(requiredText(root, "polledAt"));
        String status = requiredText(root, "status");
        String provider = requiredText(root, "provider");
        BigDecimal sourcePreviousClose = optionalDecimal(root, "sourcePreviousClose");
        BigDecimal dayHigh = optionalDecimal(root, "dayHigh");
        BigDecimal dayLow = optionalDecimal(root, "dayLow");

        return new Spot(commodityCode, price, sourcePreviousClose, dayHigh, dayLow,
                sessionDate, quoteTime, polledAt, status, provider);
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

    private static BigDecimal optionalDecimal(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException(name + " 型別非法");
        return node.decimalValue();
    }
}
