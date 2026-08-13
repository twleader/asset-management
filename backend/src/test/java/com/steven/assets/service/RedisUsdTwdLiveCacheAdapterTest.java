package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisUsdTwdLiveCacheAdapterTest {

    private ValueOperations<String, String> values;
    private RedisUsdTwdLiveCacheAdapter adapter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        adapter = new RedisUsdTwdLiveCacheAdapter(redis, new ObjectMapper());
    }

    @Test
    void parsesUtcInstantPayloadIntoImmutableSnapshot() {
        when(values.get(RedisUsdTwdLiveCacheAdapter.SESSION_KEY)).thenReturn(
                "{\"heartbeatAt\":\"2026-08-13T15:10:14Z\",\"eligibleSources\":[\"MEGA_BANK\"]}");
        when(values.get(RedisUsdTwdLiveCacheAdapter.SPOT_KEY)).thenReturn(
                "{\"rateDate\":\"2026-08-13\",\"buyRate\":32.1000,\"sellRate\":32.2000,"
                        + "\"source\":\"MEGA_BANK\",\"polledAt\":\"2026-08-13T15:10:14Z\","
                        + "\"sourceUpdatedAt\":\"2026-08-13T15:10:12Z\","
                        + "\"sourceUpdatedAtHighWatermarks\":{\"MEGA_BANK\":\"2026-08-13T15:10:12Z\"}}");

        var snapshot = adapter.readSnapshot();

        assertThat(snapshot.heartbeat().orElseThrow().heartbeatAt())
                .isEqualTo(Instant.parse("2026-08-13T15:10:14Z"));
        assertThat(snapshot.spot().orElseThrow().sourceUpdatedAt())
                .isEqualTo(Instant.parse("2026-08-13T15:10:12Z"));
        assertThatThrownBy(() -> snapshot.spot().orElseThrow()
                .sourceUpdatedAtHighWatermarks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void malformedTimestampFailsClosedAtAdapterBoundary() {
        when(values.get(RedisUsdTwdLiveCacheAdapter.SESSION_KEY)).thenReturn(
                "{\"heartbeatAt\":\"not-an-instant\",\"eligibleSources\":[\"MEGA_BANK\"]}");
        when(values.get(RedisUsdTwdLiveCacheAdapter.SPOT_KEY)).thenReturn(null);

        assertThatThrownBy(adapter::readSnapshot)
                .isInstanceOf(MalformedUsdTwdRateException.class);
    }
}
