package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 比照既有 {@link ExchangeRateSpotCacheWriterTest} 的形狀（見任務檔 337.19）。 */
class CommoditySpotCacheWriterTest {

    private static final String CODE = "WTI";
    private static final String KEY = CommoditySpotCacheWriter.SPOT_KEY_PREFIX + CODE;

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CommoditySpotCacheWriter writer;
    private AtomicReference<String> stored;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        writer = new CommoditySpotCacheWriter(redis, mapper);
        stored = new AtomicReference<>();
        when(values.get(eq(KEY))).thenAnswer(inv -> stored.get());
        doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(values).set(eq(KEY), any(String.class), eq(CommoditySpotCacheWriter.SPOT_TTL));
    }

    @Test
    void quoteTimeNotAdvancedKeepsPriceAndLastAdvancedAtUnchanged() throws Exception {
        Instant firstQuoteTime = Instant.parse("2026-08-14T20:59:31Z");
        Instant firstPolledAt = Instant.parse("2026-08-14T20:59:32Z");
        assertThat(writer.applyLiveTick(quote(firstQuoteTime), firstPolledAt)).isTrue();
        JsonNode firstWrite = mapper.readTree(stored.get());
        assertThat(firstWrite.path("price").decimalValue()).isEqualByComparingTo("82.4000");
        assertThat(firstWrite.path("lastAdvancedAt").asText()).isEqualTo(firstPolledAt.toString());

        // 同一 quoteTime 再收到一輪（未推進）：本輪 polledAt 較晚，但價格／quoteTime／lastAdvancedAt 不得改寫
        Instant secondPolledAt = firstPolledAt.plusSeconds(60);
        assertThat(writer.applyLiveTick(quote(firstQuoteTime), secondPolledAt)).isTrue();
        JsonNode secondWrite = mapper.readTree(stored.get());
        assertThat(secondWrite.path("price").decimalValue()).isEqualByComparingTo("82.4000");
        assertThat(secondWrite.path("quoteTime").asText()).isEqualTo(firstQuoteTime.toString());
        assertThat(secondWrite.path("lastAdvancedAt").asText()).isEqualTo(firstPolledAt.toString());
        assertThat(secondWrite.path("polledAt").asText()).isEqualTo(secondPolledAt.toString());
        assertThat(secondWrite.path("status").asText()).isEqualTo("LIVE");
    }

    @Test
    void quoteTimeAdvancedUpdatesLastAdvancedAtToCurrentRoundPolledAt() throws Exception {
        Instant firstQuoteTime = Instant.parse("2026-08-14T20:58:31Z");
        Instant firstPolledAt = Instant.parse("2026-08-14T20:58:32Z");
        writer.applyLiveTick(quote(firstQuoteTime), firstPolledAt);

        Instant secondQuoteTime = Instant.parse("2026-08-14T20:59:31Z");
        Instant secondPolledAt = Instant.parse("2026-08-14T20:59:32Z");
        assertThat(writer.applyLiveTick(
                quote(secondQuoteTime, new BigDecimal("82.5000")), secondPolledAt)).isTrue();

        JsonNode write = mapper.readTree(stored.get());
        assertThat(write.path("price").decimalValue()).isEqualByComparingTo("82.5000");
        assertThat(write.path("quoteTime").asText()).isEqualTo(secondQuoteTime.toString());
        assertThat(write.path("lastAdvancedAt").asText()).isEqualTo(secondPolledAt.toString());
        assertThat(write.path("status").asText()).isEqualTo("LIVE");
    }

    @Test
    void fiveMinutesWithoutAdvancingFlipsToStaleAndKeepsPrice() throws Exception {
        Instant quoteTime = Instant.parse("2026-08-14T20:59:31Z");
        Instant firstPolledAt = Instant.parse("2026-08-14T20:59:32Z");
        writer.applyLiveTick(quote(quoteTime, new BigDecimal("82.4000")), firstPolledAt);

        Instant staleTick = firstPolledAt.plus(Duration.ofMinutes(5));
        assertThat(writer.applyLiveTick(quote(quoteTime), staleTick)).isTrue();

        JsonNode write = mapper.readTree(stored.get());
        assertThat(write.path("status").asText()).isEqualTo("STALE");
        assertThat(write.path("price").decimalValue()).isEqualByComparingTo("82.4000");
        assertThat(write.path("lastAdvancedAt").asText()).isEqualTo(firstPolledAt.toString());

        // 未滿 5 分鐘仍是 LIVE
        Instant almostStaleTick = firstPolledAt.plus(Duration.ofMinutes(5)).minusSeconds(1);
        assertThat(writer.applyLiveTick(quote(quoteTime), almostStaleTick)).isTrue();
        JsonNode notYetStale = mapper.readTree(stored.get());
        assertThat(notYetStale.path("status").asText()).isEqualTo("LIVE");
    }

    @Test
    void malformedExistingPayloadStillWritesCurrentRoundResult() throws Exception {
        stored.set("{broken-json");
        Instant quoteTime = Instant.parse("2026-08-14T20:59:31Z");
        Instant polledAt = Instant.parse("2026-08-14T20:59:32Z");

        assertThat(writer.applyLiveTick(quote(quoteTime), polledAt)).isTrue();

        JsonNode write = mapper.readTree(stored.get());
        assertThat(write.path("price").decimalValue()).isEqualByComparingTo("82.4000");
        assertThat(write.path("quoteTime").asText()).isEqualTo(quoteTime.toString());
        assertThat(write.path("lastAdvancedAt").asText()).isEqualTo(polledAt.toString());
        assertThat(write.path("status").asText()).isEqualTo("LIVE");
    }

    @Test
    void settlementWriteUsesSourceSuppliedQuoteTimeNotCorrectionPolledAt() throws Exception {
        Instant sourceQuoteTime = Instant.parse("2026-08-14T20:59:59Z");
        Instant correctionPolledAt = Instant.parse("2026-08-14T21:05:03Z");

        assertThat(writer.applySettlement(
                CODE, new BigDecimal("82.4000"), LocalDate.of(2026, 8, 14),
                sourceQuoteTime, new BigDecimal("81.2500"), new BigDecimal("82.9900"),
                new BigDecimal("80.7100"), CommodityFetchClient.PROVIDER, "https://example",
                correctionPolledAt)).isTrue();

        JsonNode write = mapper.readTree(stored.get());
        assertThat(write.path("status").asText()).isEqualTo("SETTLED");
        assertThat(write.path("quoteTime").asText()).isEqualTo(sourceQuoteTime.toString());
        assertThat(write.path("quoteTime").asText()).isNotEqualTo(correctionPolledAt.toString());
        assertThat(write.path("lastAdvancedAt").asText()).isEqualTo(correctionPolledAt.toString());
    }

    private static CommodityFetchClient.LiveQuote quote(Instant quoteTime) {
        return quote(quoteTime, new BigDecimal("82.4000"));
    }

    private static CommodityFetchClient.LiveQuote quote(Instant quoteTime, BigDecimal price) {
        return new CommodityFetchClient.LiveQuote(
                CODE, price, new BigDecimal("81.2500"), new BigDecimal("82.9900"),
                new BigDecimal("80.7100"), LocalDate.of(2026, 8, 14), quoteTime,
                CommodityFetchClient.PROVIDER, "https://example");
    }
}
