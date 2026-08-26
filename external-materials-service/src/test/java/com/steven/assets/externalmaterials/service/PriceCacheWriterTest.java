package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PriceCacheWriterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);
    private static final Instant INSTANT = Instant.parse("2026-08-21T05:30:00.123456789Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringRedisTemplate redis;
    private StockSourceQuery source;
    private IntradayHighLowTracker highLow;
    private IntradayTickStore ticks;
    private PriceCacheWriter writer;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        source = mock(StockSourceQuery.class);
        highLow = mock(IntradayHighLowTracker.class);
        ticks = mock(IntradayTickStore.class);
        writer = new PriceCacheWriter(redis, source, highLow, ticks);
        scriptReply(List.of(1L, "", "", ""));
    }

    @Test
    void liveWrite_usesSingleLuaAndFixedNineDigitSourceTime() throws Exception {
        when(highLow.observe("2330", "台股", DATE, new BigDecimal("100.00")))
                .thenReturn(new IntradayHighLowTracker.HighLow(
                        new BigDecimal("101.00"), new BigDecimal("99.00")));

        assertThat(writer.write(result("2330", "TWSE"), false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        JsonNode payload = capturedPayload();
        assertThat(payload.path("tradingDate").asText()).isEqualTo("2026-08-21");
        assertThat(payload.path("updatedAt").asText())
                .isEqualTo("2026-08-21T13:30:00.123456789");
        assertThat(payload.path("quoteStatus").asText()).isEqualTo("LIVE");
        assertThat(payload.path("highPrice").decimalValue()).isEqualByComparingTo("101.00");
        assertThat(payload.path("lowPrice").decimalValue()).isEqualByComparingTo("99.00");
        verify(ticks).appendTick("2330", "台股", DATE,
                java.time.LocalDateTime.of(2026, 8, 21, 13, 30, 0, 123456789),
                new BigDecimal("100.00"));
        verify(redis, never()).opsForValue();
        verify(redis, never()).opsForSet();
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void staleWrite_stillUpdatesOwnDayHighLowButNeverTicks() {
        scriptReply(List.of(0L, "2026-08-21", "2026-08-21T13:31:00", "LIVE"));
        when(highLow.observe(anyString(), anyString(), any(), any()))
                .thenReturn(new IntradayHighLowTracker.HighLow(
                        new BigDecimal("100.00"), new BigDecimal("100.00")));

        assertThat(writer.write(result("2330", "TWSE"), false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);

        verify(highLow).observe("2330", "台股", DATE, new BigDecimal("100.00"));
        verifyNoInteractions(ticks);
    }

    @Test
    void nonActualSource_neverTouchesDayHighLowOrTicks() {
        PriceResult index = result("0000", "TWSE指數(5m)");

        assertThat(writer.write(index, false, false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        verifyNoInteractions(highLow, ticks);
    }

    @Test
    void fubonTaiwanIndexUsesStrictNewerLuaWithoutTicksOrDayHighLow() throws Exception {
        PriceResult index = new PriceResult(
                "0000", "台股", new BigDecimal("22345.67"), null, null, "FUBON_INDICES", "台股大盤",
                null, null, null, new BigDecimal("22000.00"), null, null, null, DATE, INSTANT);

        assertThat(writer.writeTaiwanIndexLive(index))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        Object[] args = capturedArgs();
        assertThat(args).hasSize(5);
        assertThat(args[4]).isEqualTo("1");
        JsonNode payload = MAPPER.readTree((String) args[0]);
        assertThat(payload.path("source").asText()).isEqualTo("FUBON_INDICES");
        assertThat(payload.path("quoteStatus").asText()).isEqualTo("LIVE");
        assertThat(payload.path("closed").asBoolean()).isFalse();
        assertThat(payload.has("highPrice")).isFalse();
        assertThat(payload.has("lowPrice")).isFalse();
        assertThat(payload.has("volume")).isFalse();
        verifyNoInteractions(highLow, ticks);
    }

    @Test
    void nonPositivePriceSkipsAllRedisBeforeHighLow() {
        PriceResult invalid = new PriceResult(
                "2330", "台股", BigDecimal.ZERO, null, null, "TWSE", null,
                null, null, null, null, null, null, null, DATE, INSTANT);

        assertThat(writer.write(invalid, false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.SKIPPED_INVALID_PRICE);

        verifyNoInteractions(redis, highLow, ticks);
    }

    @Test
    void verifiedCompatibilityDateMismatchFailsWithoutRedis() {
        assertThat(writer.writeVerifiedClose(result("2330", "TWSE"), DATE.minusDays(1)))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.FAILED);
        verifyNoInteractions(redis, highLow, ticks);
    }

    @Test
    void verifiedCloseLeavesMissingPreviousCloseAndNameForAtomicLuaPreservation() throws Exception {
        PriceResult close = new PriceResult(
                "2330", "台股", new BigDecimal("101.00"), null, null, "TWSE_MI_INDEX", null,
                null, null, new BigDecimal("100.00"), null,
                new BigDecimal("102.00"), new BigDecimal("99.00"), 2000L, DATE, INSTANT);

        assertThat(writer.writeVerifiedClose(close))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        Object[] args = capturedArgs();
        assertThat(args).hasSize(4); // Task 350 Lua contract remains exactly ARGV[1..4].
        JsonNode payload = MAPPER.readTree((String) args[0]);
        assertThat(payload.has("previousClose")).isFalse();
        assertThat(payload.has("stockName")).isFalse();
        assertThat(payload.path("quoteStatus").asText()).isEqualTo("VERIFIED_CLOSE");
        verifyNoInteractions(highLow, ticks);
    }

    @Test
    void dbSyncUsesSameRowDateAndPreviousClose() throws Exception {
        when(source.findPreviousCloseBefore("009804", "台股", DATE))
                .thenReturn(Optional.of(new BigDecimal("21.20")));

        assertThat(writer.syncClosedFromDb("009804", "台股",
                new StockSourceQuery.DatedClose(DATE, new BigDecimal("21.70")), INSTANT))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        Object[] args = capturedArgs();
        assertThat(args).hasSize(4); // metadata preservation is inferred from PREVIOUS_CLOSE in Lua.
        JsonNode payload = MAPPER.readTree((String) args[0]);
        assertThat(payload.path("tradingDate").asText()).isEqualTo(DATE.toString());
        assertThat(payload.path("previousClose").decimalValue()).isEqualByComparingTo("21.20");
        assertThat(payload.path("priceChange").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(payload.path("changePercent").decimalValue()).isEqualByComparingTo("2.358491");
        assertThat(payload.path("quoteStatus").asText()).isEqualTo("PREVIOUS_CLOSE");
        assertThat(payload.has("stockName")).isFalse();
        assertThat(payload.has("openPrice")).isFalse();
        assertThat(payload.has("highPrice")).isFalse();
        assertThat(payload.has("lowPrice")).isFalse();
        assertThat(payload.has("volume")).isFalse();
        verifyNoInteractions(highLow, ticks);
    }

    private PriceResult result(String code, String sourceName) {
        return new PriceResult(
                code, "台股", new BigDecimal("100.00"), null, null, sourceName, "測試",
                null, null, new BigDecimal("99.50"), new BigDecimal("98.00"),
                new BigDecimal("100.50"), new BigDecimal("99.00"), 1000L, DATE, INSTANT);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void scriptReply(List<?> reply) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn((List) reply);
    }

    @SuppressWarnings("rawtypes")
    private JsonNode capturedPayload() throws Exception {
        return MAPPER.readTree((String) capturedArgs()[0]);
    }

    @SuppressWarnings("rawtypes")
    private Object[] capturedArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        return args.getValue();
    }
}
