package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the production CAS Lua against Redis; it is skipped rather than failing when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class SessionReferencePriceStoreRedisIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 18);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private SessionReferencePriceStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void clean() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
            connection.scriptingCommands().scriptFlush();
        }
        store = new SessionReferencePriceStore(redis, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void atomicCasOnlyAllowsStrictlyNewerAndStaleAttemptPreservesShortExistingTtl() throws Exception {
        String key = SessionReferencePriceStore.key("00881", "台股", DAY);
        SessionReferencePriceStore.SessionReferencePrice initial = evidence(NOW.minusSeconds(10), "50.55");
        assertThat(store.put("00881", "台股", DAY, initial))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.WRITTEN);
        String rawInitial = redis.opsForValue().get(key);

        assertThat(store.put("00881", "台股", DAY, initial))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_STALE);
        assertThat(store.put("00881", "台股", DAY, evidence(NOW.minusSeconds(11), "49.00")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_STALE);
        assertThat(redis.opsForValue().get(key)).isEqualTo(rawInitial);

        String shortTtlKey = SessionReferencePriceStore.key("2330", "台股", DAY);
        redis.opsForValue().set(shortTtlKey, payload(evidence(NOW.minusSeconds(10), "100")), Duration.ofSeconds(2));
        Long before = redis.getExpire(shortTtlKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(store.put("2330", "台股", DAY, evidence(NOW.minusSeconds(10), "101")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_STALE);
        Long after = redis.getExpire(shortTtlKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(after).isPositive().isLessThanOrEqualTo(before);
        assertThat(after).isLessThan(10_000L); // proves stale CAS did not reset this key to the 35-day TTL.

        assertThat(store.put("00881", "台股", DAY, evidence(NOW.minusSeconds(1), "51.00")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.WRITTEN);
        assertThat(store.get("00881", "台股", DAY).orElseThrow().price()).isEqualByComparingTo("51.00");
    }

    @Test
    void exactDateAndCodeKeysAreIsolatedAndFutureInputHasNoSideEffect() {
        LocalDate nextDay = DAY.plusDays(1);
        assertThat(store.put("00881", "台股", DAY, evidence(NOW.minusSeconds(2), "50.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.WRITTEN);
        assertThat(store.put("00881", "台股", nextDay, evidence(NOW.minusSeconds(1), "51.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.WRITTEN);
        assertThat(store.put("2330", "台股", DAY, evidence(NOW.minusSeconds(1), "1000")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.WRITTEN);

        String dayOne = redis.opsForValue().get(SessionReferencePriceStore.key("00881", "台股", DAY));
        String dayTwo = redis.opsForValue().get(SessionReferencePriceStore.key("00881", "台股", nextDay));
        String otherCode = redis.opsForValue().get(SessionReferencePriceStore.key("2330", "台股", DAY));
        assertThat(dayOne).isNotEqualTo(dayTwo).isNotEqualTo(otherCode);
        assertThat(store.put("00881", "台股", DAY, evidence(NOW.plusSeconds(1), "99")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_INVALID);
        assertThat(redis.opsForValue().get(SessionReferencePriceStore.key("00881", "台股", DAY))).isEqualTo(dayOne);
        assertThat(redis.opsForValue().get(SessionReferencePriceStore.key("00881", "台股", nextDay))).isEqualTo(dayTwo);
        assertThat(redis.opsForValue().get(SessionReferencePriceStore.key("2330", "台股", DAY))).isEqualTo(otherCode);
    }

    private static SessionReferencePriceStore.SessionReferencePrice evidence(Instant observedAt, String price) {
        return new SessionReferencePriceStore.SessionReferencePrice(
                new BigDecimal(price), SessionReferencePriceStore.TWSE_MIS_Y, observedAt);
    }

    private static String payload(SessionReferencePriceStore.SessionReferencePrice evidence) throws Exception {
        String order = SessionReferencePriceStore.observedAtOrder(evidence.observedAt());
        return new ObjectMapper().writeValueAsString(java.util.Map.of(
                "price", evidence.price().toPlainString(), "source", evidence.source(),
                "observedAt", evidence.observedAt().toString(), "observedAtOrder", order));
    }
}
