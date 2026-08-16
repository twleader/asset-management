package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisCommoditySpotCacheAdapter} 的 Redis/JSON 解析（Requirement 77 / Task 337.9）。
 *
 * <p>與 {@link RedisUsdTwdLiveCacheAdapterTest} 刻意分歧：這裡任何解析失敗只 {@code log.warn}
 * 並回該標的 {@code Optional.empty()}，不得拋出讓 {@code GET /api/market-data/commodity/live}
 * 5xx（337.9）。
 */
class RedisCommoditySpotCacheAdapterTest {

    private ValueOperations<String, String> values;
    private StringRedisTemplate redis;
    private RedisCommoditySpotCacheAdapter adapter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        adapter = new RedisCommoditySpotCacheAdapter(redis, new ObjectMapper());
    }

    @Test
    void marketOpenReflectsSessionKeyPresence() {
        when(redis.hasKey(RedisCommoditySpotCacheAdapter.SESSION_KEY)).thenReturn(true);
        assertThat(adapter.isMarketOpen()).isTrue();

        when(redis.hasKey(RedisCommoditySpotCacheAdapter.SESSION_KEY)).thenReturn(false);
        assertThat(adapter.isMarketOpen()).isFalse();

        when(redis.hasKey(RedisCommoditySpotCacheAdapter.SESSION_KEY)).thenReturn(null);
        assertThat(adapter.isMarketOpen()).isFalse();
    }

    @Test
    void parsesFullPayloadIntoImmutableSpot() {
        when(values.get(RedisCommoditySpotCacheAdapter.SPOT_KEY_PREFIX + "WTI")).thenReturn(
                "{\"commodityCode\":\"WTI\",\"price\":82.4000,\"sourcePreviousClose\":81.2500,"
                        + "\"dayHigh\":82.9900,\"dayLow\":80.7100,\"sessionDate\":\"2026-08-14\","
                        + "\"quoteTime\":\"2026-08-14T20:59:59Z\",\"lastAdvancedAt\":\"2026-08-14T20:59:31Z\","
                        + "\"polledAt\":\"2026-08-14T20:59:31Z\",\"provider\":\"YAHOO_FINANCE_CHART\","
                        + "\"sourceUrl\":\"https://query1.finance.yahoo.com/v8/finance/chart/CL%3DF?range=1d&interval=1d\","
                        + "\"status\":\"LIVE\"}");

        var spot = adapter.readSpot("WTI").orElseThrow();

        assertThat(spot.commodityCode()).isEqualTo("WTI");
        assertThat(spot.price()).isEqualByComparingTo("82.4000");
        assertThat(spot.sourcePreviousClose()).isEqualByComparingTo("81.2500");
        assertThat(spot.dayHigh()).isEqualByComparingTo("82.9900");
        assertThat(spot.dayLow()).isEqualByComparingTo("80.7100");
        assertThat(spot.sessionDate()).isEqualTo(LocalDate.parse("2026-08-14"));
        assertThat(spot.quoteTime()).isEqualTo(Instant.parse("2026-08-14T20:59:59Z"));
        assertThat(spot.polledAt()).isEqualTo(Instant.parse("2026-08-14T20:59:31Z"));
        assertThat(spot.status()).isEqualTo("LIVE");
        assertThat(spot.provider()).isEqualTo("YAHOO_FINANCE_CHART");
    }

    @Test
    void omittableFieldsMayBeAbsent() {
        when(values.get(RedisCommoditySpotCacheAdapter.SPOT_KEY_PREFIX + "GOLD")).thenReturn(
                "{\"commodityCode\":\"GOLD\",\"price\":4442.5000,\"sessionDate\":\"2026-08-14\","
                        + "\"quoteTime\":\"2026-08-14T20:59:59Z\",\"polledAt\":\"2026-08-14T20:59:31Z\","
                        + "\"provider\":\"YAHOO_FINANCE_CHART\",\"status\":\"LIVE\"}");

        var spot = adapter.readSpot("GOLD").orElseThrow();

        assertThat(spot.sourcePreviousClose()).isNull();
        assertThat(spot.dayHigh()).isNull();
        assertThat(spot.dayLow()).isNull();
    }

    @Test
    void missingKeyReturnsEmptyNotThrow() {
        when(values.get(RedisCommoditySpotCacheAdapter.SPOT_KEY_PREFIX + "BRENT")).thenReturn(null);

        assertThat(adapter.readSpot("BRENT")).isEmpty();
    }

    @Test
    void malformedPayloadReturnsEmptyInsteadOfThrowing() {
        when(values.get(RedisCommoditySpotCacheAdapter.SPOT_KEY_PREFIX + "WTI")).thenReturn(
                "{\"commodityCode\":\"WTI\",\"price\":\"not-a-number\"}");

        assertThat(adapter.readSpot("WTI")).isEmpty();
    }

    @Test
    void missingRequiredPriceReturnsEmptyInsteadOfThrowing() {
        when(values.get(RedisCommoditySpotCacheAdapter.SPOT_KEY_PREFIX + "WTI")).thenReturn(
                "{\"commodityCode\":\"WTI\",\"sessionDate\":\"2026-08-14\","
                        + "\"quoteTime\":\"2026-08-14T20:59:59Z\",\"polledAt\":\"2026-08-14T20:59:31Z\","
                        + "\"provider\":\"YAHOO_FINANCE_CHART\",\"status\":\"LIVE\"}");

        assertThat(adapter.readSpot("WTI")).isEmpty();
    }
}
