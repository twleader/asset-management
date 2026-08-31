package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Executes the non-TW LOCAL CAS script through the production Redis adapter.
 * Mocking {@code StringRedisTemplate.execute} cannot prove Redis TIME,
 * PEXPIREAT, or the key's same-market compare-and-swap behavior.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRadarTechnicalCacheRepositoryRedisIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CODE = "0000";
    private static final String US_MARKET = "美股";
    private static final String JP_MARKET = "日股";
    private static final String FINGERPRINT = "a".repeat(64);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    RedisRadarTechnicalCacheRepository cache;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void reset() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        cache = new RedisRadarTechnicalCacheRepository(redis);
    }

    @Test
    void createsAndCasReusesOneMarketLocalDocumentWithoutExtendingItsAbsoluteDeadline() throws Exception {
        Instant calculatedAt = redisNow();
        Instant deadline = calculatedAt.plusSeconds(FubonTechnicalFreshness.SECONDS);
        String document = document(US_MARKET, CODE, calculatedAt, deadline);
        String key = RedisRadarTechnicalCacheRepository.marketLocalKey(US_MARKET, CODE);

        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, null, document, deadline))).isEqualTo("WRITTEN");
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isEqualTo(document);
        long ttlBeforeReuse = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertAbsoluteDeadline(key, deadline);

        // A resolver retry may re-project the same document.  PEXPIREAT must
        // retain the first deadline, not reset this cache item for 100 seconds.
        Thread.sleep(100L);
        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, document, document, deadline))).isEqualTo("WRITTEN");
        long ttlAfterReuse = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertAbsoluteDeadline(key, deadline);
        assertThat(ttlAfterReuse).isLessThan(ttlBeforeReuse);

        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, "another-raw-document", document, deadline))).isEqualTo("CAS_MISS");
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isEqualTo(document);
    }

    @Test
    void expiredOneHundredSecondDocumentCanBeReplacedButExpiredOrInvalidIncomingDocumentsFailClosed() {
        Instant now = redisNow();
        Instant staleCalculatedAt = now.minusSeconds(FubonTechnicalFreshness.SECONDS);
        String stale = document(US_MARKET, CODE, staleCalculatedAt, now);
        String key = RedisRadarTechnicalCacheRepository.marketLocalKey(US_MARKET, CODE);
        // The exact 100-second-old document is not admitted as a fresh write.
        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, null, stale, now))).isEqualTo("EXPIRED");
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isNull();
        // A persistent stale raw value models a failed previous expiry clean-up;
        // the CAS rewrite is still safe because it replaces the exact raw value.
        redis.opsForValue().set(key, stale);

        Instant replacementCalculatedAt = redisNow();
        Instant replacementDeadline = replacementCalculatedAt.plusSeconds(FubonTechnicalFreshness.SECONDS);
        String replacement = document(US_MARKET, CODE, replacementCalculatedAt, replacementDeadline);
        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, stale, replacement, replacementDeadline))).isEqualTo("WRITTEN");
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isEqualTo(replacement);
        assertAbsoluteDeadline(key, replacementDeadline);

        String beforeInvalid = cache.readMarketLocal(US_MARKET, CODE);
        long ttlBeforeInvalid = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, beforeInvalid, "{not-json", replacementDeadline))).isEqualTo("REJECT_INVALID_INCOMING");
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isEqualTo(beforeInvalid);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttlBeforeInvalid);

        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, beforeInvalid, replacement, redisNow().minusMillis(1)))).isEqualTo("EXPIRED");
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isEqualTo(beforeInvalid);
    }

    @Test
    void sameCodeInDistinctMarketsUsesIndependentRedisDocumentsAndCasKeys() {
        Instant now = redisNow();
        Instant deadline = now.plusSeconds(FubonTechnicalFreshness.SECONDS);
        String us = document(US_MARKET, CODE, now, deadline);
        String jp = document(JP_MARKET, CODE, now, deadline);

        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                US_MARKET, CODE, null, us, deadline))).isEqualTo("WRITTEN");
        assertThat(cache.writeMarketLocal(new RadarTechnicalCachePort.MarketLocalWrite(
                JP_MARKET, CODE, null, jp, deadline))).isEqualTo("WRITTEN");

        String usKey = RedisRadarTechnicalCacheRepository.marketLocalKey(US_MARKET, CODE);
        String jpKey = RedisRadarTechnicalCacheRepository.marketLocalKey(JP_MARKET, CODE);
        assertThat(usKey).isNotEqualTo(jpKey);
        assertThat(redis.keys("radar:technical:local:*")).containsExactlyInAnyOrder(usKey, jpKey);
        assertThat(cache.readMarketLocal(US_MARKET, CODE)).isEqualTo(us);
        assertThat(cache.readMarketLocal(JP_MARKET, CODE)).isEqualTo(jp);
    }

    private static String document(String market, String code, Instant calculatedAt, Instant freshUntil) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("code", code);
        root.put("market", market);
        root.put("origin", "LOCAL_CALCULATED");
        root.put("binding", "BOUND_CONTEXT");
        root.put("contextFingerprint", FINGERPRINT);
        root.put("decisionInputVersion", "TW_RULES_V18|FUBON_OVERLAY_V1");
        root.put("calculatedAt", calculatedAt.toString());
        root.put("freshUntil", freshUntil.toString());
        root.put("freshUntilEpochMillis", freshUntil.toEpochMilli());
        root.set("localSnapshot", JSON.createObjectNode());
        return root.toString();
    }

    private static Instant redisNow() {
        try (var connection = factory.getConnection()) {
            return Instant.ofEpochMilli(connection.serverCommands().time());
        }
    }

    private static void assertAbsoluteDeadline(String key, Instant deadline) {
        long remaining = deadline.toEpochMilli() - redisNow().toEpochMilli();
        long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(remaining);
        assertThat(ttlMillis).isGreaterThan(remaining - 500L);
    }
}
