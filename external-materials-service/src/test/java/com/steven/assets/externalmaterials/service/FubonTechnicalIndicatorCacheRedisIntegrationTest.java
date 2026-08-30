package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketJson;
import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import com.steven.assets.externalmaterials.config.FubonScheduledMarketTokenFilter;
import com.steven.assets.externalmaterials.controller.FubonTechnicalIndicatorController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static com.steven.assets.externalmaterials.service.FubonMarketTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = false)
class FubonTechnicalIndicatorCacheRedisIntegrationTest {
    @TempDir Path temporary;
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    FubonTechnicalIndicatorCacheRepository repository;
    FubonTechnicalIndicatorCacheWriter writer;
    Instant now;
    LocalDate day;

    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
    }
    @AfterAll static void close() { factory.destroy(); }
    @BeforeEach void setup() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushAll();
            now = Instant.ofEpochMilli(connection.serverCommands().time());
        }
        day = now.atZone(MarketClock.TW_ZONE).toLocalDate();
        repository = new FubonTechnicalIndicatorCacheRepository(redis);
        writer = new FubonTechnicalIndicatorCacheWriter(redis, repository);
    }
    @Test void normalThreeGroupRoundTripUsesExactSignedDecimalsAndFixedSourceExpiry() {
        var result = writer.write(technical("2330", day, now, "0"));
        assertThat(result.outcomes().values()).containsOnly("WRITTEN");
        var stored = repository.read("2330").document();
        assertThat(stored.schemaVersion()).isEqualTo(1);
        assertThat(stored.groups().get("macd").payload().get("macdLine")).isEqualTo("-12345678901234567890.123456789012345678");
        long expected = day.atStartOfDay(MarketClock.TW_ZONE).plusDays(7).toInstant().toEpochMilli();
        long ttl = redis.getExpire(FubonTechnicalCache.key("2330"), TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(expected - now.toEpochMilli() - 5000, expected - now.toEpochMilli());
        assertThat(redis.keys("*")).containsExactly(FubonTechnicalCache.key("2330"));
    }
    @Test void equalContentIsIdempotentAndSameDateConflictCannotExtendExpiryOrReplaceValue() {
        writer.write(technical("2330", day, now, "0"));
        var initial = repository.read("2330").document().groups().get("kdj");
        assertThat(writer.write(technical("2330", day, now.plusSeconds(1), "0")).outcomes().values()).containsOnly("UNCHANGED");
        var conflict = writer.write(technical("2330", day, now.plusSeconds(2), "2"));
        assertThat(conflict.outcomes().get("kdj")).isEqualTo("CONFLICT_NO_SOURCE_REVISION");
        assertThat(conflict.hasFailure()).isTrue();
        var retained = repository.read("2330").document().groups().get("kdj");
        assertThat(retained.payload()).isEqualTo(initial.payload());
        assertThat(retained.observedAt()).isEqualTo(initial.observedAt());
        assertThat(retained.expiresAt()).isEqualTo(initial.expiresAt());
        assertThat(retained.lastAttempt().status()).isEqualTo("CONFLICT_NO_SOURCE_REVISION");
    }
    @Test void olderGroupRejectedNewerGroupAtomicallyReplacesAndPartialFailurePreservesPeers() {
        writer.write(technical("2330", day.minusDays(1), now, "0"));
        var today = technical("2330", day, now.plusSeconds(1), "2");
        today = group(today, "bb", failure("bb", "UPSTREAM_UNAVAILABLE"));
        var updated = writer.write(today);
        assertThat(updated.outcomes().get("kdj")).isEqualTo("WRITTEN");
        assertThat(updated.outcomes().get("bb")).isEqualTo("UNAVAILABLE");
        var stored = repository.read("2330").document();
        assertThat(stored.groups().get("kdj").sourceDate()).isEqualTo(day);
        assertThat(stored.groups().get("bb").sourceDate()).isEqualTo(day.minusDays(1));
        assertThat(stored.groups().get("bb").payload()).isNotNull();
        assertThat(writer.write(technical("2330", day.minusDays(1), now.plusSeconds(2), "3")).outcomes().get("kdj"))
                .isEqualTo("REJECTED_STALE");
    }
    @Test void concurrentWritersCannotRollBackNewerDateOrLoseOtherGroups() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var older = pool.submit(() -> { start.await(); return writer.write(technical("2330", day.minusDays(1), now, "0")); });
            var newer = pool.submit(() -> { start.await(); return writer.write(technical("2330", day, now.plusSeconds(1), "2")); });
            start.countDown();
            assertThat(older.get(10, TimeUnit.SECONDS).outcomes()).doesNotContainValue("FAILED");
            assertThat(newer.get(10, TimeUnit.SECONDS).outcomes()).doesNotContainValue("FAILED");
        }
        assertThat(repository.read("2330").document().groups().values()).allMatch(g -> day.equals(g.sourceDate()));
    }
    @Test void expiredSourceCannotBeInsertedAndFailureOnlyDiagnosticHasAtMostFifteenMinutes() {
        assertThat(writer.write(technical("2330", day.minusDays(8), now, "0")).outcomes().values()).containsOnly("EXPIRED");
        var stored = repository.read("2330").document();
        assertThat(stored.groups().values()).allMatch(g -> g.payload() == null);
        assertThat(redis.getExpire(FubonTechnicalCache.key("2330"), TimeUnit.MILLISECONDS)).isBetween(895000L, 900000L);
    }
    @Test void corruptCacheIsNeitherReturnedNorRepairedAndReadNeverRefreshesTtl() {
        String key = FubonTechnicalCache.key("2330");
        redis.opsForValue().set(key, "{\"schemaVersion\":2}", java.time.Duration.ofMinutes(1));
        String before = redis.opsForValue().get(key);
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(repository.read("2330").outcome()).isEqualTo("CORRUPT_CACHE");
        assertThat(writer.write(technical("2330", day, now, "0")).outcomes().values()).containsOnly("CORRUPT_CACHE");
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
    }
    @Test void perGroupExpiryAndColdCalendarReadOnlyUseCacheAndDoNotTouchTtl() {
        var read = technical("2330", day, now, "0");
        var old = read.groups().get("bb");
        var seeded = FubonTechnicalCache.candidate(group(read, "bb", new TechnicalGroup("AVAILABLE", null,
                old.parameters(), day.minusDays(8), null, old.payload())));
        String key = FubonTechnicalCache.key("2330");
        redis.opsForValue().set(key, FubonTechnicalCache.encode(seeded), java.time.Duration.ofDays(1));
        FubonRadarScope radar = mock(FubonRadarScope.class);
        when(radar.current(Integer.MAX_VALUE)).thenReturn(List.of("2330"));
        MarketCalendar calendar = mock(MarketCalendar.class);
        when(calendar.peekTwTradingDayKnown(any())).thenReturn(Optional.empty());
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(now);
        var service = new FubonTechnicalIndicatorReadService(writer, radar, calendar, clock);
        String before = redis.opsForValue().get(key);
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        var result = service.read("2330");
        assertThat(result.calendarKnown()).isFalse();
        assertThat(result.reason()).isEqualTo("CALENDAR_UNKNOWN");
        assertThat(result.groups().get("kdj").payload()).isNotNull();
        assertThat(result.groups().get("bb").payload()).isNull();
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        verify(calendar, never()).isTwTradingDayKnown(any());
        assertThat(service.read("0050").outcome()).isEqualTo("NOT_RADAR");
    }
    @Test void failuresNeverRefreshValidGroupExpiryAndMalformedHashFailsClosed() {
        var original = technical("2330", day.minusDays(2), now, "0");
        writer.write(original);
        String key = FubonTechnicalCache.key("2330");
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        var failed = original;
        for (String name : GROUPS) failed = group(failed, name, failure(name, "UPSTREAM_UNAVAILABLE"));
        writer.write(failed);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode)FubonMarketJson.parse(redis.opsForValue().get(key));
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.path("groups").path("kdj")).put("contentHash", "0".repeat(64));
        redis.opsForValue().set(key, raw.toString());
        assertThat(repository.read("2330").outcome()).isEqualTo("CORRUPT_CACHE");
        assertThat(writer.write(original).outcomes().values()).containsOnly("CORRUPT_CACHE");
    }
    @Test void authenticatedHttpReadbackOfNormalCacheWithColdAuthorityIsPureAndPreservesTypedIsoDates() throws Exception {
        writer.write(technical("2330", day, now, "0"));
        var radar = mock(FubonRadarScope.class);
        when(radar.current(Integer.MAX_VALUE)).thenReturn(List.of("2330"));
        var closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        var dgpa = mock(DgpaCalendarAuthority.class);
        var source = mock(StockSourceQuery.class);
        var authority = new MarketDataFetchService(source, closures, "", dgpa) {
            @Override Map<String, String> fetchTwHolidaysFromTwse(int year) {
                throw new AssertionError("GET must never warm the authority");
            }
        };
        var calendar = new MarketCalendar(authority);
        var clock = mock(MarketClock.class); when(clock.instant()).thenReturn(now);
        var readService = new FubonTechnicalIndicatorReadService(writer, radar, calendar, clock);
        var syncService = mock(FubonTechnicalIndicatorSyncService.class);
        Path token = temporary.resolve("fake-token"); Files.writeString(token, "fake-readback-token");
        var config = new FubonMarketConfigState("true", "http://fake.invalid", token.toString());
        var mvc = MockMvcBuilders.standaloneSetup(new FubonTechnicalIndicatorController(syncService, readService))
                .addFilters(new FubonScheduledMarketTokenFilter(config)).build();
        String before = redis.opsForValue().get(FubonTechnicalCache.key("2330"));
        long ttl = redis.getExpire(FubonTechnicalCache.key("2330"), TimeUnit.MILLISECONDS);
        mvc.perform(get("/internal/technical-indicators/fubon-cache").param("symbol", "2330"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/technical-indicators/fubon-cache").param("symbol", "2330")
                        .header("X-Internal-Service-Token", "fake-readback-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.calendarKnown").value(false))
                .andExpect(jsonPath("$.reason").value("CALENDAR_UNKNOWN"))
                .andExpect(jsonPath("$.groups.kdj.sourceDate").value(day.toString()))
                .andExpect(jsonPath("$.groups.macd.payload.macdLine").value("-12345678901234567890.123456789012345678"));
        assertThat(redis.opsForValue().get(FubonTechnicalCache.key("2330"))).isEqualTo(before);
        assertThat(redis.getExpire(FubonTechnicalCache.key("2330"), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        verifyNoInteractions(dgpa, source, syncService);
        verify(closures, never()).loadFromDb();
    }
    @Test void incomingFutureSourceAndExistingWrongTypeRemainUnavailableWithoutAFalseFreshValue() {
        var future = technical("2330", day.plusDays(1), now.plusSeconds(86400), "0");
        assertThat(writer.write(future).outcomes().values()).containsOnly("SCHEMA_INVALID");
        assertThat(repository.read("2330").document().groups().values()).allMatch(group -> group.payload() == null);
        redis.delete(FubonTechnicalCache.key("2330"));
        redis.opsForList().rightPush(FubonTechnicalCache.key("2330"), "wrong-type");
        assertThat(writer.write(technical("2330", day, now, "0")).outcomes().values()).containsOnly("FAILED");
        assertThat(repository.read("2330").outcome()).isEqualTo("UNAVAILABLE");
        assertThat(redis.opsForList().range(FubonTechnicalCache.key("2330"), 0, -1)).containsExactly("wrong-type");
    }
}
