package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static com.steven.assets.externalmaterials.service.FubonMarketTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fake normalized SSE -> real existing quote Lua/ticks/index/publish; no broker or canonical SQL. */
@Testcontainers(disabledWithoutDocker = false)
class FubonStockPushRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static RedisMessageListenerContainer listener;
    static final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    static final Instant TRADE = Instant.parse("2026-08-28T02:15:00Z");
    final MarketClock clock = mock(MarketClock.class);
    final FubonRadarScope radar = mock(FubonRadarScope.class);
    final StockSourceQuery sql = mock(StockSourceQuery.class);
    final IntradayHighLowTracker highLow = mock(IntradayHighLowTracker.class);
    FubonStockPushConsumer consumer;
    PriceCacheWriter writer;
    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        listener = new RedisMessageListenerContainer(); listener.setConnectionFactory(factory);
        listener.addMessageListener((message, pattern) -> messages.add(message.toString()), new ChannelTopic("price-update"));
        listener.afterPropertiesSet(); listener.start();
    }
    @AfterAll static void close() { listener.stop(); factory.destroy(); }
    @BeforeEach void setup() {
        try (var connection = factory.getConnection()) { connection.serverCommands().flushAll(); }
        messages.clear();
        when(clock.instant()).thenReturn(TRADE.plusSeconds(10));
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(radar.current(300)).thenReturn(List.of("2330"));
        writer = new PriceCacheWriter(redis, sql, highLow, new IntradayTickStore(redis));
        consumer = new FubonStockPushConsumer(clock, radar, writer);
        consumer.authorize(List.of("2330"), TRADE.plusSeconds(45));
    }
    private StockEvent event(Instant instant) {
        var json = stock("2330", instant);
        return FubonMarketJson.stock(json, "2330:" + json.get("tradeTimeMicros").longValue(), TRADE.plusSeconds(10));
    }
    private static String key() { return "price:台股:2330"; }
    private static String ticks() { return "price:ticks:台股:2330:2026-08-28"; }
    @Test void fakeSseWritesWholeMetadataAndPublishesOneTrustedTickThroughOriginalLua() throws Exception {
        var json = stock("2330", TRADE);
        String body = "event: stock-price\nid: 2330:" + json.get("tradeTimeMicros").longValue() + "\ndata: " + json + "\n\n";
        var consumed = new CountDownLatch(1);
        var client = new FubonStockPushStreamClient("true", config(), clock,
                (uri, token) -> new FubonStockPushStreamClient.Response(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), "text/event-stream"),
                millis -> new CountDownLatch(1).await());
        try {
            client.start(value -> { assertThat(consumer.accept(value)).isEqualTo("WRITTEN"); consumed.countDown(); });
            assertThat(consumed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(messages.poll(2, TimeUnit.SECONDS)).isNotNull();
            var actual = FubonMarketJson.parse(redis.opsForValue().get(key()));
            assertThat(actual.get("source").asText()).isEqualTo(STOCK_SOURCE);
            assertThat(actual.get("price").decimalValue()).isEqualByComparingTo("123.5");
            assertThat(actual.get("previousClose").decimalValue()).isEqualByComparingTo("122");
            assertThat(actual.get("openPrice").decimalValue()).isEqualByComparingTo("122.5");
            assertThat(actual.get("highPrice").decimalValue()).isEqualByComparingTo("124");
            assertThat(actual.get("lowPrice").decimalValue()).isEqualByComparingTo("121.5");
            assertThat(actual.get("priceChange").decimalValue()).isEqualByComparingTo("1.5");
            assertThat(actual.get("tradingDate").asText()).isEqualTo("2026-08-28");
            assertThat(actual.get("updatedAt").asText()).isEqualTo("2026-08-28T10:15:00.000000000");
            assertThat(actual.get("quoteStatus").asText()).isEqualTo("LIVE");
            assertThat(actual.get("closed").asBoolean()).isFalse();
            assertThat(actual.hasNonNull("volume")).isFalse();
            assertThat(actual.hasNonNull("buyPrice")).isFalse();
            assertThat(redis.opsForSet().members("price:index:台股")).containsExactly("2330");
            assertThat(redis.opsForList().size(ticks())).isEqualTo(1);
            verifyNoInteractions(sql, highLow);
        } finally { client.stop(); }
    }
    @Test void staleAndEqualLeaveEntireQuoteTtlPublishAndTicksUntouched() throws Exception {
        assertThat(consumer.accept(event(TRADE))).isEqualTo("WRITTEN");
        assertThat(messages.poll(2, TimeUnit.SECONDS)).isNotNull();
        String before = redis.opsForValue().get(key());
        long ttl = redis.getExpire(key(), TimeUnit.MILLISECONDS);
        assertThat(consumer.accept(event(TRADE))).isEqualTo("REJECTED_STALE");
        assertThat(consumer.accept(event(TRADE.minusSeconds(1)))).isEqualTo("REJECTED_STALE");
        assertThat(redis.opsForValue().get(key())).isEqualTo(before);
        assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(redis.opsForList().size(ticks())).isEqualTo(1);
        assertThat(messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
        verifyNoInteractions(sql, highLow);
    }
    @Test void verifiedCloseCannotBeDowngradedAndMissingMetadataNeverMergesPreviousQuote() {
        consumer.accept(event(TRADE));
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode)FubonMarketJson.parse(redis.opsForValue().get(key()));
        raw.put("closed", true).put("quoteStatus", "VERIFIED_CLOSE");
        redis.opsForValue().set(key(), raw.toString());
        assertThat(consumer.accept(event(TRADE.plusSeconds(1)))).isEqualTo("REJECTED_STALE");
        assertThat(FubonMarketJson.parse(redis.opsForValue().get(key())).get("closed").asBoolean()).isTrue();
        redis.delete(key());
        consumer.accept(event(TRADE));
        var missing = stock("2330", TRADE.plusSeconds(1));
        for (String field : List.of("previousClose", "openPrice", "highPrice", "lowPrice", "name")) missing.putNull(field);
        assertThat(consumer.accept(FubonMarketJson.stock(missing, "2330:" + missing.get("tradeTimeMicros").longValue(), TRADE.plusSeconds(10))))
                .isEqualTo("WRITTEN");
        var updated = FubonMarketJson.parse(redis.opsForValue().get(key()));
        for (String field : List.of("previousClose", "openPrice", "highPrice", "lowPrice", "stockName", "priceChange"))
            assertThat(updated.hasNonNull(field)).as(field).isFalse();
    }
    @Test void removedRadarUnknownCalendarCloseRevocationAndInvalidDirectWriterInputCannotWrite() {
        when(radar.current(300)).thenReturn(List.of());
        assertThat(consumer.accept(event(TRADE))).isEqualTo("NOT_RADAR");
        when(radar.current(300)).thenReturn(List.of("2330"));
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.empty());
        assertThat(consumer.accept(event(TRADE))).isEqualTo("CALENDAR_UNKNOWN");
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(clock.instant()).thenReturn(Instant.parse("2026-08-28T05:30:00Z"));
        assertThat(consumer.accept(event(TRADE))).isNotEqualTo("WRITTEN");
        when(clock.instant()).thenReturn(TRADE.plusSeconds(10));
        consumer.revoke();
        assertThat(consumer.accept(event(TRADE))).isEqualTo("DISABLED");
        var good = event(TRADE);
        var invalid = new StockEvent(good.symbol(), good.sourceDate(), good.tradeTime(), good.tradeTimeMicros(), 0,
                good.price(), good.previousClose(), good.openPrice(), good.highPrice(), good.lowPrice(), good.name());
        assertThat(writer.writeFubonStockPush(invalid, TRADE.plusSeconds(10))).isEqualTo(PriceCacheWriter.CacheWriteOutcome.FAILED);
        assertThat(redis.keys("*")).isEmpty();
    }
    private FubonMarketConfigState config() {
        return new FubonMarketConfigState("true", "http://fake.invalid", "unused", path -> "fake-token");
    }
}
