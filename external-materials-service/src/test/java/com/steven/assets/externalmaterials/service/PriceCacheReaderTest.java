package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceCacheReaderTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private PriceCacheReader reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        reader = new PriceCacheReader(redis);
    }

    @Test
    void findOne_cacheHit_parsesAllFields() {
        when(values.get("price:台股:2330")).thenReturn(
                "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":1000.00,"
                + "\"previousClose\":990.00,\"priceChange\":10.00,\"changePercent\":1.01,"
                + "\"openPrice\":995.00,\"highPrice\":1005.00,\"lowPrice\":993.00,"
                + "\"volume\":12345,\"stockName\":\"台積電\",\"source\":\"TWSE\","
                + "\"tradingDate\":\"2026-08-10\",\"updatedAt\":\"2026-08-10T10:30:00\","
                + "\"closed\":false,\"quoteStatus\":\"LIVE\"}");

        Optional<PriceCacheReader.LatestQuote> result = reader.findOne("2330", "台股");

        assertThat(result).isPresent();
        PriceCacheReader.LatestQuote q = result.get();
        assertThat(q.stockCode()).isEqualTo("2330");
        assertThat(q.price()).isEqualByComparingTo("1000.00");
        assertThat(q.volume()).isEqualTo(12345L);
        assertThat(q.closed()).isFalse();
        assertThat(q.quoteStatus()).isEqualTo("LIVE");
    }

    @Test
    void findOne_cacheMiss_returnsEmpty() {
        when(values.get("price:台股:9999")).thenReturn(null);

        assertThat(reader.findOne("9999", "台股")).isEmpty();
    }

    @Test
    void findOne_malformedJson_returnsEmptyNotException() {
        when(values.get("price:台股:BAD")).thenReturn("not-json");

        assertThat(reader.findOne("BAD", "台股")).isEmpty();
    }

    @Test
    void listAll_noMarketFilter_expandsAllThreeMarkets() {
        when(sets.members("price:index:台股")).thenReturn(Set.of("2330"));
        when(sets.members("price:index:美股")).thenReturn(Set.of("AAPL"));
        when(sets.members("price:index:英股")).thenReturn(Set.of());
        when(values.get("price:台股:2330")).thenReturn(
                "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":1000.00}");
        when(values.get("price:美股:AAPL")).thenReturn(
                "{\"stockCode\":\"AAPL\",\"market\":\"美股\",\"price\":200.00}");

        List<PriceCacheReader.LatestQuote> result = reader.listAll(null);

        assertThat(result).extracting(PriceCacheReader.LatestQuote::stockCode)
                .containsExactlyInAnyOrder("2330", "AAPL");
    }

    @Test
    void listAll_withMarketFilter_onlyQueriesThatMarket() {
        when(sets.members("price:index:台股")).thenReturn(Set.of("2330"));
        when(values.get("price:台股:2330")).thenReturn(
                "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":1000.00}");

        List<PriceCacheReader.LatestQuote> result = reader.listAll("台股");

        assertThat(result).hasSize(1);
        verify(sets, never()).members("price:index:美股");
        verify(sets, never()).members("price:index:英股");
    }

    @Test
    void listAll_emptyIndex_returnsEmptyListNotError() {
        when(sets.members("price:index:台股")).thenReturn(Set.of());
        when(sets.members("price:index:美股")).thenReturn(Set.of());
        when(sets.members("price:index:英股")).thenReturn(Set.of());

        assertThat(reader.listAll(null)).isEmpty();
    }

    @Test
    void reader_neverWritesToRedis() {
        when(sets.members("price:index:台股")).thenReturn(Set.of());
        when(sets.members("price:index:美股")).thenReturn(Set.of());
        when(sets.members("price:index:英股")).thenReturn(Set.of());

        reader.listAll(null);
        reader.findOne("2330", "台股");

        verify(values, never()).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(redis, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
