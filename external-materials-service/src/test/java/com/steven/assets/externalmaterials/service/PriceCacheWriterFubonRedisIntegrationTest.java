package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Executes the production Lua against a real Redis process. This class is never conditionally skipped. */
@Testcontainers(disabledWithoutDocker = false)
class PriceCacheWriterFubonRedisIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RedisScript<String> PROVIDER_TIMED_WRITE = providerTimedWriteScript();

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
                ticks);
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
    void taiwanPriorityStrictEqualRejectsWithoutRedisTickOrDayHlMutation() throws Exception {
        PriceResult first = observation("2026-08-21T05:00:00Z").result().withTiming(DATE, Instant.parse("2026-08-21T05:00:00Z"));
        PriceResult equal = new PriceResult("2330", "台股", new BigDecimal("101"), null, null, "Yahoo", "台積電",
                null, null, null, new BigDecimal("99"), null, null, null, DATE, Instant.parse("2026-08-21T05:00:00Z"));
        PriceResult older = new PriceResult("2330", "台股", new BigDecimal("98"), null, null, "TWSE_MIS", "台積電",
                null, null, null, new BigDecimal("99"), null, null, null, DATE, Instant.parse("2026-08-21T04:59:59Z"));
        assertThat(writer.writeTaiwanLive(first, true)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        awaitMessages(1); messages.clear();
        String before = redis.opsForValue().get(priceKey());
        long ticksBefore = ticks().size();
        Long latestTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        Long indexTtlBefore = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);
        assertThat(redis.opsForSet().members(indexKey())).containsExactly("2330");
        assertThat(writer.writeTaiwanLive(equal, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);
        assertThat(writer.writeTaiwanLive(older, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(before);
        assertThat(redis.opsForSet().members(indexKey())).containsExactly("2330");
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(latestTtlBefore);
        assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(indexTtlBefore);
        assertThat(ticks()).hasSize((int) ticksBefore);
        verify(highLowTracker, never()).observe(any(), any(), any(), any());
        assertNoMessage();
    }

    @Test
    void taiwanPriorityAcceptsStrictlyNewerFubonThenMisThenYahooButNeverRegressesAcrossSources() throws Exception {
        PriceResult fubon = live("FUBON_INTRADAY", "2026-08-21T05:00:00Z", "100");
        PriceResult mis = live("TWSE_MIS", "2026-08-21T05:01:00Z", "101");
        PriceResult yahoo = live("Yahoo", "2026-08-21T05:02:00Z", "102");

        assertThat(writer.writeTaiwanLive(fubon, true)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        assertThat(writer.writeTaiwanLive(mis, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        assertThat(writer.writeTaiwanLive(yahoo, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        awaitMessages(3); messages.clear();
        String latest = redis.opsForValue().get(priceKey());
        int ticksBefore = ticks().size();

        assertThat(writer.writeTaiwanLive(fubon, true)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);
        assertThat(writer.writeTaiwanLive(mis, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);

        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(latest);
        assertThat(MAPPER.readTree(latest).path("source").asText()).isEqualTo("Yahoo");
        assertThat(ticks()).hasSize(ticksBefore);
        verify(highLowTracker).observe("2330", "台股", DATE, new BigDecimal("101"));
        verify(highLowTracker).observe("2330", "台股", DATE, new BigDecimal("102"));
        assertNoMessage();
    }

    @Test
    void taiwanPriorityMalformedCurrentFailsClosedWithNoLatestIndexTtlPublishTickOrDayHlMutation() throws Exception {
        for (String malformed : List.of(
                "not-json",
                currentPayload("TWSE_MIS", false, "LIVE", "2026-02-30", "2026-08-21T13:00:00"),
                currentPayload("TWSE_MIS", false, "LIVE", DATE.toString(), "2026-08-21T25:00:00"),
                currentPayload("TWSE_MIS", false, " ", DATE.toString(), "2026-08-21T13:00:00"))) {
            setUp();
            redis.opsForValue().set(priceKey(), malformed, Duration.ofMinutes(25));
            redis.opsForSet().add(indexKey(), "sentinel-index-member");
            redis.expire(indexKey(), Duration.ofMinutes(20));
            Long latestTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
            Long indexTtlBefore = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);

            assertThat(writer.writeTaiwanLive(live("TWSE_MIS", "2026-08-21T05:01:00Z", "101"), false))
                    .isEqualTo(PriceCacheWriter.CacheWriteOutcome.FAILED);

            assertThat(redis.opsForValue().get(priceKey())).isEqualTo(malformed);
            assertThat(redis.opsForSet().members(indexKey())).containsExactly("sentinel-index-member");
            assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(latestTtlBefore);
            assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(indexTtlBefore);
            assertThat(ticks()).isEmpty();
            verify(highLowTracker, never()).observe(any(), any(), any(), any());
            assertNoMessage();
        }
    }

    @Test
    void taiwanPriorityRejectsDamagedCurrentIdentityAndCoreSchemaWithoutAnySideEffect() throws Exception {
        List<Consumer<ObjectNode>> corruptions = List.of(
                node -> node.remove("stockCode"),
                node -> node.put("stockCode", "2317"),
                node -> node.put("market", "美股"),
                node -> node.put("closed", "false"),
                node -> node.remove("price"),
                node -> node.put("price", "101"),
                node -> node.remove("source"),
                node -> node.put("source", " "));
        for (Consumer<ObjectNode> corruption : corruptions) {
            setUp();
            ObjectNode malformed = (ObjectNode) MAPPER.readTree(currentPayload(
                    "TWSE_MIS", false, "LIVE", DATE.toString(), "2026-08-21T13:00:00"));
            corruption.accept(malformed);
            String raw = MAPPER.writeValueAsString(malformed);
            redis.opsForValue().set(priceKey(), raw, Duration.ofMinutes(25));
            redis.opsForSet().add(indexKey(), "sentinel-index-member");
            redis.expire(indexKey(), Duration.ofMinutes(20));
            Long latestTtl = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
            Long indexTtl = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);

            assertThat(writer.writeTaiwanLive(live("TWSE_MIS", "2026-08-21T05:01:00Z", "101"), false))
                    .isEqualTo(PriceCacheWriter.CacheWriteOutcome.FAILED);

            assertThat(redis.opsForValue().get(priceKey())).isEqualTo(raw);
            assertThat(redis.opsForSet().members(indexKey())).containsExactly("sentinel-index-member");
            assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(latestTtl);
            assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(indexTtl);
            assertThat(ticks()).isEmpty();
            verify(highLowTracker, never()).observe(any(), any(), any(), any());
            assertNoMessage();
        }
    }

    @Test
    void nonStrictVerifiedCloseRetainsLegacyMalformedCurrentRepairBehavior() throws Exception {
        redis.opsForValue().set(priceKey(), "{not-json", Duration.ofMinutes(25));

        assertThat(writer.writeVerifiedClose(live("TWSE", "2026-08-21T05:01:00Z", "101")))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        assertThat(MAPPER.readTree(redis.opsForValue().get(priceKey())).path("quoteStatus").asText())
                .isEqualTo("VERIFIED_CLOSE");
    }

    @Test
    void newerPreviousCloseReplacesStaleLiveFromPriorCacheWarmup() throws Exception {
        StockSourceQuery sourceQuery = mock(StockSourceQuery.class);
        writer = new PriceCacheWriter(redis, sourceQuery, highLowTracker, new IntradayTickStore(redis));
        redis.opsForValue().set(priceKey(),
                currentPayload("Yahoo", false, "LIVE", DATE.toString(), "2026-08-21T03:58:00"),
                Duration.ofHours(1));
        when(sourceQuery.findPreviousCloseBefore("2330", "台股", DATE))
                .thenReturn(java.util.Optional.of(new BigDecimal("101.25")));

        assertThat(writer.syncClosedFromDb(
                "2330", "台股", new StockSourceQuery.DatedClose(DATE, new BigDecimal("101.25")),
                Instant.parse("2026-08-21T05:00:00Z")))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);

        JsonNode payload = MAPPER.readTree(redis.opsForValue().get(priceKey()));
        assertThat(payload.path("quoteStatus").asText()).isEqualTo("PREVIOUS_CLOSE");
        assertThat(payload.path("closed").asBoolean()).isTrue();
        assertThat(payload.path("updatedAt").asText()).isEqualTo("2026-08-21T13:00:00.000000000");
    }

    @Test
    void realRedisDayHlTracksOnlyAcceptedMisYahooAndSurvivesRejectedOrVerifiedCloseCases() throws Exception {
        PriceCacheWriter realWriter = new PriceCacheWriter(redis, mock(StockSourceQuery.class),
                new IntradayHighLowTracker(redis), new IntradayTickStore(redis));
        PriceResult fubon = live("FUBON_INTRADAY", "2026-08-21T05:00:00Z", "100");
        PriceResult mis = live("TWSE_MIS", "2026-08-21T05:01:00Z", "101");
        PriceResult yahoo = live("Yahoo", "2026-08-21T05:02:00Z", "102");

        assertThat(realWriter.writeTaiwanLive(fubon, true)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        assertThat(redis.hasKey(dayHlKey())).isFalse();
        assertThat(realWriter.writeTaiwanLive(mis, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        assertThat(realWriter.writeTaiwanLive(yahoo, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        String dayHl = redis.opsForValue().get(dayHlKey());
        Long dayHlTtlBefore = redis.getExpire(dayHlKey(), TimeUnit.MILLISECONDS);
        assertThat(MAPPER.readTree(dayHl).path("high").asText()).isEqualTo("102");
        assertThat(MAPPER.readTree(dayHl).path("low").asText()).isEqualTo("101");

        assertThat(realWriter.writeTaiwanLive(mis, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);
        assertThat(realWriter.writeTaiwanLive(yahoo, false)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);
        assertThat(redis.opsForValue().get(dayHlKey())).isEqualTo(dayHl);
        assertThat(redis.getExpire(dayHlKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(dayHlTtlBefore);

        PriceResult verified = live("TWSE", "2026-08-21T05:03:00Z", "103");
        assertThat(realWriter.writeVerifiedClose(verified)).isEqualTo(PriceCacheWriter.CacheWriteOutcome.WRITTEN);
        String verifiedPayload = redis.opsForValue().get(priceKey());
        assertThat(realWriter.writeTaiwanLive(live("Yahoo", "2026-08-21T05:04:00Z", "104"), false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.REJECTED_STALE);
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(verifiedPayload);
        assertThat(redis.opsForValue().get(dayHlKey())).isEqualTo(dayHl);

        redis.opsForValue().set(priceKey(), "not-json", Duration.ofMinutes(25));
        String dayHlBeforeMalformed = redis.opsForValue().get(dayHlKey());
        Long malformedDayHlTtl = redis.getExpire(dayHlKey(), TimeUnit.MILLISECONDS);
        assertThat(realWriter.writeTaiwanLive(live("TWSE_MIS", "2026-08-21T05:05:00Z", "105"), false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.FAILED);
        assertThat(redis.opsForValue().get(dayHlKey())).isEqualTo(dayHlBeforeMalformed);
        assertThat(redis.getExpire(dayHlKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(malformedDayHlTtl);

        ObjectNode badCore = (ObjectNode) MAPPER.readTree(verifiedPayload);
        badCore.put("closed", "false");
        redis.opsForValue().set(priceKey(), MAPPER.writeValueAsString(badCore), Duration.ofMinutes(25));
        String dayHlBeforeCoreReject = redis.opsForValue().get(dayHlKey());
        Long coreRejectDayHlTtl = redis.getExpire(dayHlKey(), TimeUnit.MILLISECONDS);
        assertThat(realWriter.writeTaiwanLive(live("TWSE_MIS", "2026-08-21T05:06:00Z", "106"), false))
                .isEqualTo(PriceCacheWriter.CacheWriteOutcome.FAILED);
        assertThat(redis.opsForValue().get(dayHlKey())).isEqualTo(dayHlBeforeCoreReject);
        assertThat(redis.getExpire(dayHlKey(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(coreRejectDayHlTtl);
    }

    @Test
    void marketClosedAuthorizationPrecedesArityAndWrongTypePreflight() throws Exception {
        redis.opsForList().rightPush(priceKey(), "sentinel-latest-list");
        redis.expire(priceKey(), Duration.ofMinutes(25));
        Long ttlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);

        String outcome = redis.execute(PROVIDER_TIMED_WRITE, List.of(priceKey()), "0");

        assertThat(outcome).isEqualTo("MARKET_CLOSED");
        assertThat(redis.type(priceKey())).isEqualTo(DataType.LIST);
        assertThat(redis.opsForList().range(priceKey(), 0, -1))
                .containsExactly("sentinel-latest-list");
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(ttlBefore);
        assertThat(redis.hasKey(indexKey())).isFalse();
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    @Test
    void preflightRejectsWrongLatestOrIndexTypeWithoutPartialMutation() throws Exception {
        String valid = providerPayload(
                "2330", "台股", DATE.toString(), "2026-08-21T13:01:00.000000000",
                "FUBON_INTRADAY", "LIVE", false);

        redis.opsForList().rightPush(priceKey(), "sentinel-latest-list");
        redis.expire(priceKey(), Duration.ofMinutes(25));
        redis.opsForSet().add(indexKey(), "sentinel-index-member");
        redis.expire(indexKey(), Duration.ofMinutes(20));
        Long latestTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        Long indexTtlBefore = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);

        assertThat(rawAuthorizedWrite(valid, "86400")).isEqualTo("WRITE_FAILED");
        assertThat(redis.type(priceKey())).isEqualTo(DataType.LIST);
        assertThat(redis.opsForList().range(priceKey(), 0, -1))
                .containsExactly("sentinel-latest-list");
        assertThat(redis.type(indexKey())).isEqualTo(DataType.SET);
        assertThat(redis.opsForSet().members(indexKey())).containsExactly("sentinel-index-member");
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(latestTtlBefore);
        assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(indexTtlBefore);
        assertThat(ticks()).isEmpty();
        assertNoMessage();

        setUp();
        String existing = currentPayload(
                "FUBON_INTRADAY", false, "LIVE", DATE.toString(), "2026-08-21T13:00:00");
        redis.opsForValue().set(priceKey(), existing, Duration.ofMinutes(25));
        redis.opsForValue().set(indexKey(), "sentinel-index-string", Duration.ofMinutes(20));
        latestTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        indexTtlBefore = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);

        assertThat(rawAuthorizedWrite(valid, "86400")).isEqualTo("WRITE_FAILED");
        assertThat(redis.type(priceKey())).isEqualTo(DataType.STRING);
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(existing);
        assertThat(redis.type(indexKey())).isEqualTo(DataType.STRING);
        assertThat(redis.opsForValue().get(indexKey())).isEqualTo("sentinel-index-string");
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(latestTtlBefore);
        assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(indexTtlBefore);
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    @Test
    void preflightRejectsInvalidArityAliasedKeysAndTtlWithoutMutation() throws Exception {
        String valid = providerPayload(
                "2330", "台股", DATE.toString(), "2026-08-21T13:01:00.000000000",
                "FUBON_INTRADAY", "LIVE", false);
        for (String invalidTtl : List.of("0", "-1", "1.5", "abc", "99999999999999999")) {
            assertPreflightRejected(valid, invalidTtl);
        }
        for (List<String> invalidArgs : List.of(
                List.of("1", " ", "台股", "price-update"),
                List.of("1", "2330", " ", "price-update"),
                List.of("1", "2330", "台股", " "),
                List.of("2", "2330", "台股", "price-update"))) {
            setUp();
            preparePreflightSentinels();
            assertThat(redis.execute(
                    PROVIDER_TIMED_WRITE,
                    List.of(priceKey(), indexKey()),
                    "1", invalidArgs.get(0), valid, invalidArgs.get(1), invalidArgs.get(2), DATE.toString(),
                    "2026-08-21T13:01:00.000000000", "86400", invalidArgs.get(3)))
                    .isEqualTo("WRITE_FAILED");
            assertPreflightSentinelsUnchanged();
        }

        preparePreflightSentinels();
        assertThat(redis.execute(
                PROVIDER_TIMED_WRITE,
                List.of(priceKey()),
                "1", "1", valid, "2330", "台股", DATE.toString(),
                "2026-08-21T13:01:00.000000000", "86400", "price-update"))
                .isEqualTo("WRITE_FAILED");
        assertPreflightSentinelsUnchanged();

        setUp();
        preparePreflightSentinels();
        assertThat(redis.execute(
                PROVIDER_TIMED_WRITE,
                List.of(priceKey(), indexKey()),
                "1", "1", valid, "2330", "台股", DATE.toString(),
                "2026-08-21T13:01:00.000000000", "86400"))
                .isEqualTo("WRITE_FAILED");
        assertPreflightSentinelsUnchanged();

        setUp();
        redis.opsForValue().set(priceKey(), "alias-sentinel", Duration.ofMinutes(25));
        Long aliasTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        assertThat(redis.execute(
                PROVIDER_TIMED_WRITE,
                List.of(priceKey(), priceKey()),
                "1", "1", valid, "2330", "台股", DATE.toString(),
                "2026-08-21T13:01:00.000000000", "86400", "price-update"))
                .isEqualTo("WRITE_FAILED");
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo("alias-sentinel");
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(aliasTtlBefore);
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    @Test
    void preflightRejectsMalformedOrJsonArgvMismatchedIncomingWithoutMutation() throws Exception {
        String updatedAt = "2026-08-21T13:01:00.000000000";
        List<String> invalidPayloads = List.of(
                "not-json",
                "{broken}",
                "[]",
                "{}",
                providerPayload("2317", "台股", DATE.toString(), updatedAt,
                        "FUBON_INTRADAY", "LIVE", false),
                providerPayload("2330", "美股", DATE.toString(), updatedAt,
                        "FUBON_INTRADAY", "LIVE", false),
                providerPayload("2330", "台股", "2026-08-20", updatedAt,
                        "FUBON_INTRADAY", "LIVE", false),
                providerPayload("2330", "台股", DATE.toString(), "2026-08-21T13:02:00.000000000",
                        "FUBON_INTRADAY", "LIVE", false),
                providerPayload("2330", "台股", DATE.toString(), updatedAt,
                        "TWSE", "LIVE", false),
                providerPayload("2330", "台股", DATE.toString(), updatedAt,
                        "FUBON_INTRADAY", "VERIFIED_CLOSE", false),
                providerPayload("2330", "台股", DATE.toString(), updatedAt,
                        "FUBON_INTRADAY", "LIVE", true));

        for (String invalidPayload : invalidPayloads) {
            assertPreflightRejected(invalidPayload, "86400");
        }
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
        assertThat(payload.path("updatedAt").asText()).isEqualTo("2026-08-21T13:00:00.123456000");
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
    void sameDayFubonVerifiedCloseCannotBeDowngradedByNewerLive() throws Exception {
        String verified = currentPayload(
                "FUBON_INTRADAY", true, "VERIFIED_CLOSE",
                DATE.toString(), "2026-08-21T13:00:00");
        redis.opsForValue().set(priceKey(), verified, Duration.ofMinutes(25));
        redis.opsForSet().add(indexKey(), "2330");
        redis.expire(indexKey(), Duration.ofMinutes(20));
        Long latestTtlBefore = redis.getExpire(priceKey(), TimeUnit.MILLISECONDS);
        Long indexTtlBefore = redis.getExpire(indexKey(), TimeUnit.MILLISECONDS);

        ProviderWriteResult result = writer.writeProviderTimed(
                observation("2026-08-21T05:01:00Z"), true, true);

        assertThat(result.outcome()).isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(verified);
        assertThat(redis.type(indexKey())).isEqualTo(DataType.SET);
        assertThat(redis.opsForSet().members(indexKey())).containsExactly("2330");
        assertThat(redis.getExpire(priceKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(latestTtlBefore);
        assertThat(redis.getExpire(indexKey(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(indexTtlBefore);
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    @Test
    void sameDayMisTakeoverRequiresBothFlagsExactOpenLiveIdentityAndStrictlyNewerTime() throws Exception {
        redis.opsForValue().set(priceKey(),
                currentPayload("TWSE", false, "LIVE", "2026-08-21", "2026-08-21T13:10:00"),
                Duration.ofHours(1));

        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, false).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:00:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T05:10:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(writer.writeProviderTimed(
                observation("2026-08-21T05:10:00.000000001Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.PROVIDER_TAKEOVER);
        assertThat(writer.writeProviderTimed(observation("2026-08-21T04:59:00Z"), true, true).outcome())
                .isEqualTo(ProviderWriteOutcome.STALE_OR_EQUAL);
        assertThat(ticks()).hasSize(1);
        assertThat(awaitMessages(1)).hasSize(1);

        for (String payload : List.of(
                currentPayload("TWSE", true, "VERIFIED_CLOSE", "2026-08-21", "2026-08-21T13:10:00"),
                currentPayload("DB-close", false, "LIVE", "2026-08-21", "2026-08-21T13:10:00"),
                currentPayload("TWSE", false, " LIVE ", "2026-08-21", "2026-08-21T13:10:00"),
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
        assertThat(finalPayload.path("updatedAt").asText()).isEqualTo("2026-08-21T13:19:00.000000000");
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
                .isEqualTo("2026-08-21T13:01:00.000000000");
        assertThat(redis.opsForSet().isMember(indexKey(), "2330")).isTrue();
        assertThat(ticks()).hasSize(2);
        assertThat(awaitMessages(1)).hasSize(1);
    }

    private static RedisScript<String> providerTimedWriteScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/provider-timed-price-write.lua"));
        script.setResultType(String.class);
        return script;
    }

    private static String rawAuthorizedWrite(String payload, String ttl) {
        return redis.execute(
                PROVIDER_TIMED_WRITE,
                List.of(priceKey(), indexKey()),
                "1", "1", payload, "2330", "台股", DATE.toString(),
                "2026-08-21T13:01:00.000000000", ttl, "price-update");
    }

    private void assertPreflightRejected(String payload, String ttl) throws Exception {
        setUp();
        preparePreflightSentinels();

        assertThat(rawAuthorizedWrite(payload, ttl)).isEqualTo("WRITE_FAILED");
        assertPreflightSentinelsUnchanged();
    }

    private static void preparePreflightSentinels() throws Exception {
        redis.opsForValue().set(
                priceKey(),
                currentPayload(
                        "FUBON_INTRADAY", false, "LIVE",
                        DATE.toString(), "2026-08-21T13:00:00"),
                Duration.ofMinutes(25));
        redis.opsForSet().add(indexKey(), "sentinel-index-member");
        redis.expire(indexKey(), Duration.ofMinutes(20));
    }

    private static void assertPreflightSentinelsUnchanged() throws Exception {
        assertThat(redis.opsForValue().get(priceKey())).isEqualTo(currentPayload(
                "FUBON_INTRADAY", false, "LIVE",
                DATE.toString(), "2026-08-21T13:00:00"));
        assertThat(redis.type(priceKey())).isEqualTo(DataType.STRING);
        assertThat(redis.type(indexKey())).isEqualTo(DataType.SET);
        assertThat(redis.opsForSet().members(indexKey())).containsExactly("sentinel-index-member");
        assertThat(redis.getExpire(priceKey(), TimeUnit.SECONDS)).isBetween(1_490L, 1_500L);
        assertThat(redis.getExpire(indexKey(), TimeUnit.SECONDS)).isBetween(1_190L, 1_200L);
        assertThat(ticks()).isEmpty();
        assertNoMessage();
    }

    private static String providerPayload(
            String code,
            String market,
            String tradingDate,
            String updatedAt,
            String source,
            String quoteStatus,
            boolean closed) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("stockCode", code),
                java.util.Map.entry("market", market),
                java.util.Map.entry("price", new BigDecimal("100.1")),
                java.util.Map.entry("source", source),
                java.util.Map.entry("tradingDate", tradingDate),
                java.util.Map.entry("updatedAt", updatedAt),
                java.util.Map.entry("closed", closed),
                java.util.Map.entry("quoteStatus", quoteStatus)));
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

    private static PriceResult live(String source, String timestamp, String price) {
        Instant instant = Instant.parse(timestamp);
        return new PriceResult("2330", "台股", new BigDecimal(price), null, null, source, "台積電",
                null, null, new BigDecimal(price), new BigDecimal("99"), new BigDecimal(price), new BigDecimal(price),
                10L, DATE, instant);
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

    private static String dayHlKey() {
        return "price:dayhl:台股:2330:" + DATE;
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
