package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class QuoteDetailCacheTest {

    @Test
    void validEnvelopeRoundTripsAsAPureDedicatedRead() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var snapshot = snapshot();
        String token = QuoteDetailCache.sourceUpdatedEpochMicros(snapshot.sourceTime());
        when(values.get(QuoteDetailCache.key("台股", "2330"))).thenReturn(mapper.writeValueAsString(Map.of(
                "canonicalRevision", "7", "sourceUpdatedEpochMicros", token, "snapshot", snapshot)));

        var result = new QuoteDetailCache(redis, mapper).find("2330", "台股");

        assertThat(result).isPresent().get().extracting(value -> value.snapshot().source())
                .isEqualTo("FUBON_BOOKS");
        verify(values).get(QuoteDetailCache.key("台股", "2330"));
        verifyNoMoreInteractions(values);
    }

    @Test
    void malformedEnvelopeIsAMissAndDoesNotAttemptAnyRepair() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var snapshot = snapshot();
        when(values.get(QuoteDetailCache.key("台股", "2330"))).thenReturn(mapper.writeValueAsString(Map.of(
                "canonicalRevision", "7", "sourceUpdatedEpochMicros", "1", "snapshot", snapshot)));

        var result = new QuoteDetailCache(redis, mapper).find("2330", "台股");

        assertThat(result).isEmpty();
        verify(values).get(QuoteDetailCache.key("台股", "2330"));
        verifyNoMoreInteractions(values);
    }

    @Test
    void legacyPayloadWithoutCanonicalRevisionIsACacheMissWhileYahooSnapshotIsAllowed() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var yahoo = snapshot("YAHOO_TW");
        assertThat(QuoteDetailCache.validSnapshot(yahoo)).isTrue();
        when(values.get(QuoteDetailCache.key("台股", "2330"))).thenReturn(mapper.writeValueAsString(Map.of(
                "sourceUpdatedEpochMicros", QuoteDetailCache.sourceUpdatedEpochMicros(yahoo.sourceTime()),
                "snapshot", yahoo)));

        assertThat(new QuoteDetailCache(redis, mapper).find("2330", "台股")).isEmpty();
        verify(values).get(QuoteDetailCache.key("台股", "2330"));
        verifyNoMoreInteractions(values);
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot() {
        return snapshot("FUBON_BOOKS");
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(String source) {
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(100 - level), (long) level,
                        BigDecimal.valueOf(100 + level), (long) (level + 10)))
                .toList();
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", "台積電", "台股", true, true, source, null,
                Instant.parse("2026-08-21T05:00:00.123456Z"), Instant.parse("2026-08-21T05:00:01Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(98), BigDecimal.valueOf(100),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10L, null,
                BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
