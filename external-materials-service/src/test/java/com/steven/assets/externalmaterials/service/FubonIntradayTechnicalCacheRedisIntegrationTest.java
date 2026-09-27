package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class FubonIntradayTechnicalCacheRedisIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-25T02:20:00Z");
    private static final LocalDate TODAY = NOW.atZone(MarketClock.TW_ZONE).toLocalDate();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private FubonIntradayTechnicalCache cache;

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
    void setup() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        cache = new FubonIntradayTechnicalCache(redis, clock);
    }

    @Test
    void nullSourceTimestampsUseObservedAtForAtomicRollbackFenceAndKeepTwelveMinuteTtl() {
        var initial = bundle(NOW.minusSeconds(30));
        assertThat(cache.write(initial)).isEqualTo(FubonIntradayTechnicalCache.Write.WRITTEN);
        String key = FubonIntradayTechnicalCache.key("2330");
        String initialDocument = redis.opsForValue().get(key);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(700_000L, 720_000L);

        assertThat(cache.write(bundle(NOW.minusSeconds(31))))
                .isEqualTo(FubonIntradayTechnicalCache.Write.REJECTED_STALE);
        assertThat(redis.opsForValue().get(key)).isEqualTo(initialDocument);

        assertThat(cache.write(bundle(NOW.minusSeconds(20))))
                .isEqualTo(FubonIntradayTechnicalCache.Write.WRITTEN);
        assertThat(redis.opsForValue().get(key)).isNotEqualTo(initialDocument);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(700_000L, 720_000L);
    }

    private static FubonIntradayTechnical.Bundle bundle(Instant observedAt) {
        return new FubonIntradayTechnical.Bundle(1, "2330", FubonMarketData.MARKET, FubonMarketData.PROVIDER,
                observedAt, frame("1", observedAt), frame("5", observedAt));
    }

    private static FubonIntradayTechnical.Frame frame(String timeframe, Instant observedAt) {
        var kdj = new FubonIntradayTechnical.Kdj(decimal("51.2"), decimal("48.9"), decimal("55.8"));
        var macd = new FubonIntradayTechnical.Macd(decimal("0.4"), decimal("0.3"));
        var bollinger = new FubonIntradayTechnical.Bollinger(decimal("1015"), decimal("1004.5"), decimal("994"));
        return new FubonIntradayTechnical.Frame(timeframe, TODAY, null, observedAt, kdj, macd, bollinger);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
