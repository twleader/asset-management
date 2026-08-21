package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Executes the production Lua against a real Redis process. This class is never conditionally skipped. */
@Testcontainers(disabledWithoutDocker = false)
class PriceCacheWriterFubonRedisIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisMessageListenerContainer listenerContainer;
    private static final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

    private PriceCacheWriter writer;
    private IntradayHighLowTracker highLowTracker;

    @BeforeAll
    static void connect() throws Exception {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(
                (Message message, byte[] pattern) -> messages.add(
                        new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic("price-update"));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        assertThat(listenerContainer.isRunning()).isTrue();
    }

    @AfterAll
    static void disconnect() {
        if (listenerContainer != null) listenerContainer.stop();
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
            connection.scriptingCommands().scriptFlush();
        }
        messages.clear();
        IntradayTickStore ticks = new IntradayTickStore(redis);
        highLowTracker = mock(IntradayHighLowTracker.class);
        writer = new PriceCacheWriter(
                redis,
                mock(StockSourceQuery.class),
                highLowTracker,
                ticks,
                mock(TradingDateResolver.class));
    }

    @Test
    void marketClosedIsFirstGateForMissingCurrentAndHasZeroSideEffects() throws Exception {
        ProviderWriteResult result = writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), false, true);

        assertThat(result.outcome()).isEqualTo(ProviderWriteOutcome.MARKET_CLOSED);
        assertThat(redis.opsForValue().get(priceKey())).isNull();
        assertThat(redis.hasKey(indexKey())).isFalse();
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isEqualTo(-2);
        assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS)).isEqualTo(-2);
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    @Test
    void marketClosedDoesNotTouchExistingBytesOrRefreshEitherTtl() throws Exception {
        String existing = currentPayload("TWSE", false, "LIVE", "2026-08-21", "2026-08-21T13:10:00");
        redis.opsForValue().set(priceKey(), existing, Duration.ofMinutes(40));
        redis.opsForSet().add(indexKey(), "2330");
        redis.expire(indexKey(), Duration.ofMinutes(35));
        Long valueTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        Long indexTtlBefore = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);

        ProviderWriteResult result = writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), false, true);

        assertThat(result.outcome()).isEqualTo(ProviderWriteOutcome.MARKET_CLOSED);
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(existing);
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(valueTtlBefore);
        assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(indexTtlBefore);
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    @Test
    void authorizedMissingWritesValueIndexTtlsPublishAndProviderTimedTick() throws Exception {
        ProviderWriteResult result = writer.writeProviderTimed(observation("2026-08-21T05:00:00.123456Z"), true, true);

        assertThat(result).isEqualTo(new ProviderWriteResult(ProviderWriteOutcome.WRITTEN, false));
        JsonNode payload = MAPPER.readTree(redis.opsForValue().get(priceKey()));
        assertThat(payload.path("source").asText()).isEqualTo("FUBON_INTRADAY");
        assertThat(payload.path("updatedAt").asText()).isEqualTo("2026-08-21T13:00:00.123456");
        assertThat(payload.path("price").decimalValue()).isEqualByComparingTo("100.1");
        assertThat(payload.path("priceChange").decimalValue()).isEqualByComparingTo("0.6");
        assertThat(payload.path("changePercent").decimalValue()).isEqualByComparingTo("0.603015");
        assertThat(redis.opsForSet().isMember(indexKey(), "2330")).isTrue();
        assertThat(redis.getExpire(priceKey(), TimeUnit.HOURS)).isBetween(23L, 24L);
        assertThat(redis.getExpire(indexKey(), TimeUnit.HOURS)).isBetween(23L, 24L);
        assertThat(ticks()).singleElement().satisfies(tick -> {
            assertThat(tick.path("t").asText()).isEqualTo("2026-08-21T13:00:00.123456");
            assertThat(tick.path("p").asText()).isEqualTo("100.1");
        });
        verify(highLowTracker, never()).observe(any(), any(), any(), any());
        assertThat(awaitMessages(1)).singleElement().isEqualTo(redis.opsForValue().get(priceKey()));
    }

    @Test
    void malformedCurrentFailsClosedWithoutTtlPublishOrTick() throws Exception {
        for (String malformed : List.of(
                "not-json",
                currentPayload("FUBON_INTRADAY", false, "LIVE", "2026-02-30", "2026-02-30T13:10:00"),
                currentPayload("FUBON_INTRADAY", false, "LIVE", "2026-08-21", "2026-08-21T25:10:00"),
                currentPayload("FUBON_INTRADAY", false, "LIVE", "2026-08-21", "2026-08-21T13:10:00Z"))) {
            setUp();
            redis.opsForValue().set(priceKey(), malformed, Duration.ofMinutes(20));
            Long ttlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);

            ProviderWriteResult result = writer.writeProviderTimed(
                    observation("2026-08-21T05:01:00Z"), true, true);

            assertThat(result.outcome()).isEqualTo(ProviderWriteOutcome.CURRENT_MALFORMED);
            assertThat(redis.opsForValue().get(priceKey())).isEqualTo(malformed);
            assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttlBefore);
            assertThat(redis.hasKey(indexKey())).isFalse();
            assertThat(ticks()).isEmpty();
            assertNoMessage();
        }
    }

    @Test
    void fubonTupleIsStrictAndOnlyNewerObservationHasSideEffects() throws Exception {
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.WRITTEN);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00.000000001Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.WRITTEN);
        awaitMessages(1);
        messages.clear();
        Long ttlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        int ticksBefore = ticks().size();

        assertThat(writer.writeProviderTimed(observation("2026-08-21T04:59:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttlBefore);
        assertThat(ticks()).hasSize(ticksBefore);
        assertNoMessage();

        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:01:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.WRITTEN);
        assertThat(ticks()).hasSize(ticksBefore + 1);
        assertThat(awaitMessages(1)).hasSize(1);
    }

    @Test
    void sameDayMisTakeoverRequiresBothFlagsAndExactOpenLiveIdentityOnlyOnce() throws Exception {
        redis.opsForValue().set(priceKey(),
                currentPayload("TWSE", false, "LIVE", "2026-08-21", "2026-08-21T13:10:00"),
                Duration.ofHours(1));

        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, false).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.PROVIDER_TAKEOVER);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T04:59:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(ticks()).hasSize(1);
        assertThat(awaitMessages(1)).hasSize(1);

        for (String payload : List.of(
                currentPayload("TWSE", true, "VERIFIED_CLOSE", "2026-08-21", "2026-08-21T13:10:00"),
                currentPayload("DB-close", false, "LIVE", "2026-08-21", "2026-08-21T13:10:00"),
                currentPayload("TWSE", false, "PREVIOUS_CLOSE", "2026-08-21", "2026-08-21T13:10:00"))) {
            setUp();
            redis.opsForValue().set(priceKey(), payload, Duration.ofHours(1));
            assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                    .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
            assertThat(ticks()).isEmpty();
            assertNoMessage();
        }
    }

    @Test
    void newerTradingDateMayTakeOverButOlderDateNeverCan() throws Exception {
        redis.opsForValue().set(priceKey(),
                currentPayload("DB-close", true, "PREVIOUS_CLOSE", "2026-08-20", "2026-08-20T13:30:00"));
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.PROVIDER_TAKEOVER);

        setUp();
        redis.opsForValue().set(priceKey(),
                currentPayload("TWSE", false, "LIVE", "2026-08-22", "2026-08-22T09:00:00"));
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
    }

    @Test
    void concurrentProductionLuaConvergesOnMaximumTuple() throws Exception {
        List<Callable<ProviderWriteOutcome>> tasks = new ArrayList<>();
        for (int minute = 0; minute < 20; minute++) {
            String timestamp = String.format("2026-08-21T05:%02d:00Z", minute);
            tasks.add(() -> writer.writeProviderTimed(observation(timestamp), true, true).outcome());
        }
        List<ProviderWriteOutcome> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (Future<ProviderWriteOutcome> future : pool.invokeAll(tasks)) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
        }

        JsonNode finalPayload = MAPPER.readTree(redis.opsForValue().get(priceKey()));
        assertThat(finalPayload.path("updatedAt").asText()).isEqualTo("2026-08-21T13:19");
        long successful = outcomes.stream().filter(outcome -> outcome == ProviderWriteOutcome.WRITTEN).count();
        assertThat(ticks()).hasSize((int) successful);
        assertThat(awaitMessages((int) successful)).hasSize((int) successful);
    }

    @Test
    void noscriptReloadUsesAtomicEvalFallbackWithAllSideEffects() throws Exception {
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.WRITTEN);
        awaitMessages(1);
        messages.clear();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.scriptingCommands().scriptFlush();
        }

        ProviderWriteResult result = writer.writeProviderTimed(observation("2026-08-21T05:01:00Z"), true, true);

        assertThat(result.outcome()).isEqualTo(ProviderWriteOutcome.WRITTEN);
        assertThat(MAPPER.readTree(redis.opsForValue().get(priceKey())).path("updatedAt").asText())
                .isEqualTo("2026-08-21T13:01");
        assertThat(redis.opsForSet().isMember(indexKey(), "2330")).isTrue();
        assertThat(ticks()).hasSize(2);
        assertThat(awaitMessages(1)).hasSize(1);
    }

    private static ProviderTimedPriceObservation observation(String timestamp) {
        Instant instant = Instant.parse(timestamp);
        PriceResult result = new PriceResult(
                "2330", "台股", new BigDecimal("100.1"), null, null,
                "FUBON_INTRADAY", "台積電", new BigDecimal("100.0"), new BigDecimal("100.2"),
                new BigDecimal("100.0"), new BigDecimal("99.5"),
                new BigDecimal("101.0"), new BigDecimal("99.0"), 54_538L);
        return new ProviderTimedPriceObservation(result, instant.atZone(MarketClock.TW_ZONE).toLocalDate(), instant);
    }

    private static String currentPayload(
            String source,
            boolean closed,
            String quoteStatus,
            String tradingDate,
            String updatedAt) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("stockCode", "2330"),
                java.util.Map.entry("market", "台股"),
                java.util.Map.entry("price", new BigDecimal("99.9")),
                java.util.Map.entry("previousClose", new BigDecimal("99.5")),
                java.util.Map.entry("source", source),
                java.util.Map.entry("tradingDate", tradingDate),
                java.util.Map.entry("updatedAt", updatedAt),
                java.util.Map.entry("closed", closed),
                java.util.Map.entry("quoteStatus", quoteStatus)));
    }

    private static String priceKey() {
        return "price:台股:2330";
    }

    private static String indexKey() {
        return "price:index:台股";
    }

    private static String tickKey() {
        return "price:ticks:台股:2330:" + DATE;
    }

    private static List<JsonNode> ticks() throws Exception {
        List<String> rows = redis.opsForList().range(tickKey(), 0, -1);
        if (rows == null) return List.of();
        List<JsonNode> parsed = new ArrayList<>();
        for (String row : rows) parsed.add(MAPPER.readTree(row));
        return parsed;
    }

    private static List<String> awaitMessages(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (messages.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return List.copyOf(messages);
    }

    private static void assertNoMessage() throws InterruptedException {
        Thread.sleep(150);
        assertThat(messages).isEmpty();
    }
}
