package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PriceCacheWriterNonPositiveCloseTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final IntradayHighLowTracker highLow = mock(IntradayHighLowTracker.class);
    private final IntradayTickStore ticks = mock(IntradayTickStore.class);
    private final PriceCacheWriter writer = new PriceCacheWriter(redis, source, highLow, ticks);

    private PriceResult result(String market, BigDecimal price) {
        return new PriceResult(
                "006208", market, price, null, null, "FinMind", null,
                null, null, null, null, null, null, 0L,
                LocalDate.of(2026, 8, 7), Instant.parse("2026-08-07T08:00:00Z"));
    }

    @Test
    void nullZeroAndNegativeCloseAreSkippedForAllMarketsWithoutRedis() {
        for (String market : List.of("台股", "美股", "英股")) {
            assertThat(writer.writeVerifiedClose(result(market, null)))
                    .isEqualTo(PriceCacheWriter.CacheWriteOutcome.SKIPPED_INVALID_PRICE);
            assertThat(writer.writeVerifiedClose(result(market, BigDecimal.ZERO)))
                    .isEqualTo(PriceCacheWriter.CacheWriteOutcome.SKIPPED_INVALID_PRICE);
            assertThat(writer.writeVerifiedClose(result(market, new BigDecimal("-1"))))
                    .isEqualTo(PriceCacheWriter.CacheWriteOutcome.SKIPPED_INVALID_PRICE);
        }
        verifyNoInteractions(redis, source, highLow, ticks);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void positiveVerifiedCloseUsesProductionLua() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn((List) List.of(1L, "", "", ""));

        assertThat(writer.writeVerifiedClose(result("台股", new BigDecimal("39.60"))))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
    }
}
