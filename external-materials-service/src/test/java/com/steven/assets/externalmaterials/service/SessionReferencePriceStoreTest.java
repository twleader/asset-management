package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionReferencePriceStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 18);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SessionReferencePriceStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        store = new SessionReferencePriceStore(redis, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exactDateKeyUsesAtomicStrictNewerScriptAndSerializesOrderingToken() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("WRITTEN");
        var evidence = evidence(NOW.minusSeconds(5), "50.55");

        assertThat(store.put("00881", "台股", DAY, evidence))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.WRITTEN);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> expiresAt = ArgumentCaptor.forClass(String.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), payload.capture(), order.capture(), expiresAt.capture());
        assertThat(keys.getValue()).containsExactly("price:session-reference:台股:00881:2026-08-18");
        assertThat(payload.getValue()).contains("\"price\":\"50.55\"")
                .contains("\"source\":\"TWSE_MIS_Y\"")
                .contains("\"observedAtOrder\":\"" + SessionReferencePriceStore.observedAtOrder(evidence.observedAt()) + "\"");
        assertThat(order.getValue()).isEqualTo(SessionReferencePriceStore.observedAtOrder(evidence.observedAt()));
        assertThat(Long.parseLong(expiresAt.getValue())).isGreaterThan(NOW.toEpochMilli());
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void equalOrOlderScriptOutcomeDoesNotFallBackToDirectSetOrTtlMutation() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("STALE");

        assertThat(store.put("00881", "台股", DAY, evidence(NOW.minusSeconds(5), "50.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_STALE);

        verify(redis).execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString());
        verify(values, never()).set(anyString(), anyString());
        verify(redis, never()).expireAt(anyString(), any(Instant.class));
        verify(redis, never()).expire(anyString(), any());
    }

    @Test
    void futureInvalidCodeIndexAndWrongMarketMakeZeroRedisCalls() {
        assertThat(store.put("00881", "台股", DAY, evidence(NOW.plusSeconds(1), "50.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_INVALID);
        assertThat(store.put("0000", "台股", DAY, evidence(NOW.minusSeconds(1), "50.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_INVALID);
        assertThat(store.put("00881;bad", "台股", DAY, evidence(NOW.minusSeconds(1), "50.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_INVALID);
        assertThat(store.put("00881", "美股", DAY, evidence(NOW.minusSeconds(1), "50.55")))
                .isEqualTo(SessionReferencePriceStore.WriteOutcome.REJECTED_INVALID);
        verifyNoInteractions(redis);
    }

    @Test
    void readRejectsMalformedFutureAndDateIndependentPayloadsFailClosed() throws Exception {
        String key = SessionReferencePriceStore.key("00881", "台股", DAY);
        when(values.get(key)).thenReturn("{\"price\":\"50.55\",\"source\":\"TWSE_MIS_Y\",\"observedAt\":\"2026-09-01T01:00:01Z\",\"observedAtOrder\":\"0000000001756688401000000000\"}");
        assertThat(store.get("00881", "台股", DAY)).isEmpty();
        assertThat(store.get("0000", "台股", DAY)).isEmpty();
        assertThat(store.get("00881;bad", "台股", DAY)).isEmpty();
        verify(values).get(key);
    }

    private static SessionReferencePriceStore.SessionReferencePrice evidence(Instant observedAt, String price) {
        return new SessionReferencePriceStore.SessionReferencePrice(
                new BigDecimal(price), SessionReferencePriceStore.TWSE_MIS_Y, observedAt);
    }
}
