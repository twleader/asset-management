package com.steven.assets.externalmaterials.service;

import static com.steven.assets.externalmaterials.service.FubonMarketData.MARKET;
import static com.steven.assets.externalmaterials.service.FubonMarketData.PROVIDER;
import static com.steven.assets.externalmaterials.service.FubonMarketData.TECHNICAL_PROFILES;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the deployed Lua script against Redis rather than mocking its
 * compare-and-swap outcome.  The delayed C1 has the same fact vector as C2
 * but a lower member-min observation/deadline and must not roll C2 back just
 * because its binding differs.
 */
@Testcontainers(disabledWithoutDocker = true)
class FubonTechnicalV2CacheRaceRedisIntegrationTest {
    private static final String CODE = "2330";
    private static final String FINGERPRINT = "a".repeat(64);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    FubonTechnicalV2CacheRepository cache;
    Instant now;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void setup() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushAll();
            now = Instant.ofEpochMilli(connection.serverCommands().time());
        }
        cache = new FubonTechnicalV2CacheRepository(redis);
    }

    @Test
    void delayedOlderUnboundCaptureCannotDowngradeNewerBoundSameFactVector() {
        Instant c1Oldest = now.minusSeconds(20);
        Instant c2Oldest = now.minusSeconds(10);
        FubonTechnicalV2Cache.Pair c1 = pair(c1Oldest, UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "UNBOUND_FUBON_SOURCE", null);
        FubonTechnicalV2Cache.Pair c2 = pair(c2Oldest, UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "BOUND_CONTEXT", FINGERPRINT);

        var empty = cache.read(CODE, now, null, false);
        assertThat(cache.write(CODE, c1, empty, true).outcome()).isEqualTo("WRITTEN");

        var afterC1 = cache.read(CODE, now, FINGERPRINT, false);
        assertThat(afterC1.outcome()).isEqualTo("AVAILABLE");
        assertThat(cache.write(CODE, c2, afterC1, false).outcome()).isEqualTo("WRITTEN");

        var afterC2 = cache.read(CODE, now, FINGERPRINT, true);
        assertThat(afterC2.outcome()).isEqualTo("AVAILABLE");
        assertThat(afterC2.pair().daily().captureId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(afterC2.pair().daily().binding()).isEqualTo("BOUND_CONTEXT");

        // C1 read C2's generation before its delayed write, so this is a real
        // same-generation CAS race rather than a trivial CAS_MISS.
        assertThat(cache.write(CODE, c1, afterC2, true).outcome()).isEqualTo("REJECTED_FENCE");

        var retained = cache.read(CODE, now, FINGERPRINT, true);
        assertThat(retained.outcome()).isEqualTo("AVAILABLE");
        assertThat(retained.pair().daily().captureId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(retained.pair().daily().oldestObservedAt()).isEqualTo(c2Oldest);
    }

    @Test
    void delayedUnboundProjectionOfSameCaptureCannotEraseBoundContext() {
        Instant oldest = now.minusSeconds(10);
        UUID capture = UUID.fromString("33333333-3333-3333-3333-333333333333");
        FubonTechnicalV2Cache.Pair bound = pair(oldest, capture,
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"), "BOUND_CONTEXT", FINGERPRINT);
        FubonTechnicalV2Cache.Pair delayedUnbound = pair(oldest, capture,
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"), "UNBOUND_FUBON_SOURCE", null);

        var empty = cache.read(CODE, now, FINGERPRINT, false);
        assertThat(cache.write(CODE, bound, empty, false).outcome()).isEqualTo("WRITTEN");

        var afterBound = cache.read(CODE, now, FINGERPRINT, true);
        assertThat(cache.write(CODE, delayedUnbound, afterBound, true).outcome()).isEqualTo("REJECTED_FENCE");

        var retained = cache.read(CODE, now, FINGERPRINT, true);
        assertThat(retained.pair().daily().binding()).isEqualTo("BOUND_CONTEXT");
        assertThat(retained.pair().daily().captureId()).isEqualTo(capture.toString());
    }

    private static FubonTechnicalV2Cache.Pair pair(
            Instant oldest,
            UUID capture,
            UUID generation,
            String binding,
            String fingerprint) {
        return new FubonTechnicalV2Cache.Pair(
                document("D", oldest, capture, generation, binding, fingerprint),
                document("W", oldest, capture, generation, binding, fingerprint));
    }

    private static FubonTechnicalV2Cache.Document document(
            String timeframe,
            Instant oldest,
            UUID capture,
            UUID generation,
            String binding,
            String fingerprint) {
        LocalDate sourceDate = LocalDate.of(2026, 8, 28);
        List<FubonTechnicalV2Cache.Profile> profiles = TECHNICAL_PROFILES.stream()
                .filter(profile -> timeframe.equals(profile.timeframe()))
                .map(profile -> profile(profile, sourceDate, oldest))
                .toList();
        return new FubonTechnicalV2Cache.Document(
                FubonTechnicalV2Cache.SCHEMA_VERSION, CODE, MARKET, PROVIDER, timeframe,
                FubonTechnicalV2Cache.manifest(timeframe), generation.toString(), capture.toString(), profiles,
                oldest, oldest.plusSeconds(FubonTechnicalV2Cache.FRESHNESS_SECONDS), "FUBON_SDK", binding,
                fingerprint, FubonTechnicalV2Cache.DECISION_INPUT_VERSION, null, null);
    }

    private static FubonTechnicalV2Cache.Profile profile(
            FubonMarketData.TechnicalProfile profile,
            LocalDate sourceDate,
            Instant observedAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        for (String field : profile.payloadFields()) {
            String value = switch (field) {
                case "upper" -> "3";
                case "middle" -> "2";
                case "lower" -> "1";
                default -> "1";
            };
            payload.put(field, value);
        }
        return new FubonTechnicalV2Cache.Profile(profile.profileId(), sourceDate,
                FubonCanonicalHash.technical(profile, sourceDate, payload), profile.parameters(), payload, observedAt,
                null, null, null);
    }
}
