package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeRateSpotCacheWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ExchangeRateSpotCacheWriter writer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        writer = new ExchangeRateSpotCacheWriter(redis, mapper);
    }

    @Test
    void heartbeatUsesOnlyTheFixedSessionKeyAndTenSecondTtl() throws Exception {
        Instant now = Instant.parse("2026-08-13T15:10:14.184Z");

        assertThat(writer.writeHeartbeat(now,
                Set.of(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK))).isTrue();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(ExchangeRateSpotCacheWriter.SESSION_KEY), json.capture(),
                eq(Duration.ofSeconds(10)));
        JsonNode root = mapper.readTree(json.getValue());
        assertThat(root.path("heartbeatAt").asText()).isEqualTo(now.toString());
        assertThat(root.path("eligibleSources").get(0).asText()).isEqualTo("BANK_OF_TAIWAN");
        assertThat(root.path("eligibleSources").get(1).asText()).isEqualTo("MEGA_BANK");
    }

    @Test
    void highWatermarksArePerSourcePersistAcrossSwitchesAndRejectRegression() throws Exception {
        AtomicReference<String> stored = new AtomicReference<>();
        when(values.get(ExchangeRateSpotCacheWriter.SPOT_KEY)).thenAnswer(inv -> stored.get());
        doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(values).set(eq(ExchangeRateSpotCacheWriter.SPOT_KEY), any(String.class),
                eq(Duration.ofHours(24)));

        Instant polled = Instant.parse("2026-08-13T02:01:00Z");
        assertThat(writer.writeSpot(quote(
                UsdTwdSource.MEGA_BANK, polled, "2026-08-13T02:00:00Z"))).isTrue();
        assertThat(writer.writeSpot(quote(
                UsdTwdSource.YAHOO, polled.plusSeconds(1), "2026-08-13T01:00:00Z"))).isTrue();
        String beforeRegression = stored.get();

        assertThat(writer.writeSpot(quote(
                UsdTwdSource.MEGA_BANK, polled.plusSeconds(2), "2026-08-13T01:59:59Z"))).isFalse();
        assertThat(stored.get()).isEqualTo(beforeRegression);
        assertThat(writer.writeSpot(quote(
                UsdTwdSource.MEGA_BANK, polled.plusSeconds(3), "2026-08-13T02:00:00Z"))).isTrue();

        JsonNode map = mapper.readTree(stored.get()).path("sourceUpdatedAtHighWatermarks");
        assertThat(map.path("MEGA_BANK").asText()).isEqualTo("2026-08-13T02:00:00Z");
        assertThat(map.path("YAHOO").asText()).isEqualTo("2026-08-13T01:00:00Z");
    }

    @Test
    void sourceFutureBoundaryIsInclusiveButOneNanosecondMoreIsRejected() {
        when(values.get(ExchangeRateSpotCacheWriter.SPOT_KEY)).thenReturn(null);
        Instant polled = Instant.parse("2026-08-13T02:00:00Z");

        assertThat(writer.writeSpot(quote(
                UsdTwdSource.MEGA_BANK, polled, polled.plusSeconds(120).toString()))).isTrue();
        reset(values);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(ExchangeRateSpotCacheWriter.SPOT_KEY)).thenReturn(null);
        assertThat(writer.writeSpot(quote(
                UsdTwdSource.MEGA_BANK, polled,
                polled.plusSeconds(120).plusNanos(1).toString()))).isFalse();
        verify(values, never()).set(eq(ExchangeRateSpotCacheWriter.SPOT_KEY), any(String.class), any(Duration.class));
    }

    @Test
    void malformedExistingPayloadAndInvalidQuoteNeverOverwriteLastTruth() {
        when(values.get(ExchangeRateSpotCacheWriter.SPOT_KEY)).thenReturn("{broken-json");
        Instant polled = Instant.parse("2026-08-13T02:00:00Z");

        assertThat(writer.writeSpot(quote(
                UsdTwdSource.MEGA_BANK, polled, "2026-08-13T01:59:59Z"))).isFalse();
        assertThat(writer.writeSpot(new UsdTwdSpotQuote(
                LocalDate.of(2026, 8, 13), BigDecimal.TEN, BigDecimal.ONE,
                UsdTwdSource.BANK_OF_TAIWAN, polled, null))).isFalse();
        verify(values, never()).set(eq(ExchangeRateSpotCacheWriter.SPOT_KEY), any(String.class), any(Duration.class));
    }

    @Test
    void existingCurrentSourceWatermarkMissingOrMismatchedNeverGetsPropagated() {
        Instant polled = Instant.parse("2026-08-13T02:01:00Z");
        String base = "{\"rateDate\":\"2026-08-13\",\"buyRate\":32.1000,\"sellRate\":32.2000,"
                + "\"source\":\"MEGA_BANK\",\"polledAt\":\"2026-08-13T02:01:00Z\","
                + "\"sourceUpdatedAt\":\"2026-08-13T02:00:00Z\","
                + "\"sourceUpdatedAtHighWatermarks\":%s}";

        when(values.get(ExchangeRateSpotCacheWriter.SPOT_KEY)).thenReturn(base.formatted("{}"));
        assertThat(writer.writeSpot(quote(
                UsdTwdSource.YAHOO, polled.plusSeconds(1), "2026-08-13T02:00:01Z"))).isFalse();

        when(values.get(ExchangeRateSpotCacheWriter.SPOT_KEY)).thenReturn(base.formatted(
                "{\"MEGA_BANK\":\"2026-08-13T01:59:59Z\"}"));
        assertThat(writer.writeSpot(quote(
                UsdTwdSource.YAHOO, polled.plusSeconds(2), "2026-08-13T02:00:02Z"))).isFalse();

        verify(values, never()).set(eq(ExchangeRateSpotCacheWriter.SPOT_KEY),
                any(String.class), any(Duration.class));
    }

    private static UsdTwdSpotQuote quote(
            UsdTwdSource source,
            Instant polledAt,
            String sourceUpdatedAt) {
        Instant providerTime = Instant.parse(sourceUpdatedAt);
        return new UsdTwdSpotQuote(
                providerTime.atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),
                new BigDecimal("32.1000"), new BigDecimal("32.2000"),
                source, polledAt, providerTime);
    }
}
