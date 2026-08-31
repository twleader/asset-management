package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** Infrastructure adapter contract for the resolver's cache port. */
class RedisRadarTechnicalCacheRepositoryTest {

    @Test
    void bulkReadUsesOneDualKeyMgetForEveryRequestedCode() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of(
                "fubon:technical:tw:{2330}:D:v2", "fubon:technical:tw:{2330}:W:v2",
                "fubon:technical:tw:{0050}:D:v2", "fubon:technical:tw:{0050}:W:v2")))
                .thenReturn(List.of("d2330", "w2330", "d0050", "w0050"));

        var pairs = new RedisRadarTechnicalCacheRepository(redis).readPairs(List.of("2330", "0050"));

        assertThat(pairs.get("2330")).isEqualTo(new RadarTechnicalCachePort.Pair("d2330", "w2330"));
        assertThat(pairs.get("0050")).isEqualTo(new RadarTechnicalCachePort.Pair("d0050", "w0050"));
        verify(values).multiGet(anyList());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pairWritePassesGenerationsAbsoluteDeadlineAndForceFlagToLuaAdapter() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("WRITTEN");
        Instant until = Instant.parse("2026-09-01T00:01:40Z");

        String outcome = new RedisRadarTechnicalCacheRepository(redis).writePair(new RadarTechnicalCachePort.PairWrite(
                "2330", new RadarTechnicalCachePort.Generations("daily-generation", "weekly-generation"),
                "daily-document", "weekly-document", until, true));

        assertThat(outcome).isEqualTo("WRITTEN");
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void marketLocalSnapshotUsesDistinctMarketSafeKeyAndAbsoluteDeadlineCas() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("WRITTEN");
        Instant until = Instant.parse("2026-09-01T00:01:40Z");

        String outcome = new RedisRadarTechnicalCacheRepository(redis).writeMarketLocal(
                new RadarTechnicalCachePort.MarketLocalWrite("美股", "0000", "old-document",
                        "local-document", until));

        assertThat(outcome).isEqualTo("WRITTEN");
        assertThat(RedisRadarTechnicalCacheRepository.marketLocalKey("美股", "0000"))
                .isNotEqualTo(RedisRadarTechnicalCacheRepository.marketLocalKey("台股", "0000"));
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
