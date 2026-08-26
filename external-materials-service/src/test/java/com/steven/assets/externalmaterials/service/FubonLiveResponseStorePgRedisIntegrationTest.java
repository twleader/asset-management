package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL/Redis receipt fencing for Task 380's full normalized Fubon response mirror. */
class FubonLiveResponseStorePgRedisIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void atomicLatestRowsKeepFullEnvelopeAndRejectADelayedMirrorAfterB() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
             GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                     .withExposedPorts(6379)) {
            postgres.start();
            redisContainer.start();
            DataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            applyV117(jdbc);
            FubonLiveResponseStore store = new FubonLiveResponseStore(jdbc, new DataSourceTransactionManager(dataSource));

            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    redisContainer.getHost(), redisContainer.getMappedPort(6379));
            factory.afterPropertiesSet();
            factory.start();
            try {
                StringRedisTemplate redis = new StringRedisTemplate(factory);
                redis.afterPropertiesSet();
                FubonLiveResponseCache cache = new FubonLiveResponseCache(redis, JSON);
                FubonLiveResponseReadService bridge = new FubonLiveResponseReadService(cache, store, JSON);

                FubonNormalizedQuoteClient.ValidatedEnvelope atomicFailure = envelope("atomic-failure", Map.of(
                        "2330", successRow("2330", "100.00", "2026-08-24T02:00:00Z"),
                        "2317", ""));
                assertThat(store.persist(atomicFailure, Instant.parse("2026-08-24T02:00:00.100001Z")).status())
                        .isEqualTo(FubonLiveResponseStore.WriteStatus.FAILED);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_tw_live_quote_response", Integer.class)).isZero();

                Instant receiptA = Instant.parse("2026-08-24T02:00:00.100001Z");
                Instant receiptB = Instant.parse("2026-08-24T02:00:01.200002Z");
                CountDownLatch aCommitted = new CountDownLatch(1);
                CountDownLatch releaseAMirror = new CountDownLatch(1);
                AtomicReference<FubonLiveResponseStore.PersistResult> persistedA = new AtomicReference<>();
                AtomicReference<FubonLiveResponseCache.WriteOutcome> delayedOutcome = new AtomicReference<>();
                Thread delayedA = new Thread(() -> {
                    persistedA.set(store.persist(envelope("batch-a", Map.of(
                            "2330", successRow("2330", "100.00", "2026-08-24T02:00:00Z"),
                            "2317", failureRow("2317", "QUOTE_FAILED"))), receiptA));
                    aCommitted.countDown();
                    try {
                        if (!releaseAMirror.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release delayed A mirror");
                        }
                        delayedOutcome.set(cache.writeStrictNewer(rowFor(persistedA.get(), "2330")));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }, "task-380-delayed-a");
                delayedA.start();
                assertThat(aCommitted.await(3, TimeUnit.SECONDS)).isTrue();

                FubonLiveResponseStore.PersistResult persistedB = store.persist(envelope("batch-b", Map.of(
                        "2330", successRow("2330", "101.50", "2026-08-24T02:00:01Z"),
                        "2317", failureRow("2317", "QUOTE_FAILED"))), receiptB);
                assertThat(persistedB.status()).isEqualTo(FubonLiveResponseStore.WriteStatus.STORED);
                assertThat(cache.writeStrictNewer(rowFor(persistedB, "2330")))
                        .isEqualTo(FubonLiveResponseCache.WriteOutcome.WRITTEN);
                releaseAMirror.countDown();
                delayedA.join(3_000);
                assertThat(delayedA.isAlive()).isFalse();
                assertThat(delayedOutcome.get()).isEqualTo(FubonLiveResponseCache.WriteOutcome.REJECTED_STALE);

                FubonLiveResponseStore.CanonicalResponse canonical = store.find("2330", "台股").canonical();
                assertThat(canonical.receivedAt()).isEqualTo(receiptB);
                assertThat(canonical.batchId()).isEqualTo("batch-b");
                JsonNode databaseRow = JSON.readTree(canonical.responseRowJson());
                assertThat(databaseRow.path("quote").path("actualPrice").asText()).isEqualTo("101.50");
                assertThat(databaseRow.path("quote").path("orderBook").path("levels").get(1).path("bidPrice").isNull())
                        .isTrue();
                JsonNode counters = JSON.readTree(canonical.countersJson());
                assertThat(counters).hasSize(13);
                assertThat(counters.path("SUCCESS").asLong()).isEqualTo(1L);

                String key = FubonLiveResponseCache.key("台股", "2330");
                JsonNode redisRow = JSON.readTree(redis.opsForValue().get(key));
                assertThat(redisRow.path("receivedAt").asText()).isEqualTo(receiptB.toString());
                assertThat(redis.getExpire(key)).isPositive();
                assertThat(redisRow.path("responseRow").path("quote").path("actualPrice").asText()).isEqualTo("101.50");

                FubonLiveResponseReadService.BridgeResponse response = bridge.read("2330", "台股");
                assertThat(response.supported()).isTrue();
                assertThat(response.available()).isTrue();
                assertThat(response.batchId()).isEqualTo("batch-b");
                assertThat(response.quote().actualPrice()).hasToString("101.50");
                assertThat(response.quote().orderBook().levels().get(1).bidPrice()).isNull();
                assertThat(JSON.valueToTree(response).toString()).doesNotContain("counters");

                // A stale Redis candidate with the old receipt can never win over PostgreSQL.  This
                // also covers a later mirror failure: the pure reader falls back to DB without repair.
                redis.opsForValue().set(key, mirrorPayload(rowFor(persistedA.get(), "2330")));
                FubonLiveResponseReadService.BridgeResponse dbWins = bridge.read("2330", "台股");
                assertThat(dbWins.receivedAt()).isEqualTo(receiptB);
                assertThat(dbWins.batchId()).isEqualTo("batch-b");
                assertThat(dbWins.quote().actualPrice()).hasToString("101.50");
                assertThat(bridge.read("0000", "台股").supported()).isFalse();
                assertThat(bridge.read("AAPL", "美股").supported()).isFalse();
            } finally {
                factory.destroy();
            }
        }
    }

    private static FubonLiveResponseStore.CanonicalResponse rowFor(
            FubonLiveResponseStore.PersistResult result, String code) {
        return result.canonicalRows().stream().filter(row -> code.equals(row.stockCode())).findFirst().orElseThrow();
    }

    private static FubonNormalizedQuoteClient.ValidatedEnvelope envelope(String batchId, Map<String, String> rows) {
        return new FubonNormalizedQuoteClient.ValidatedEnvelope(batchId, counters(), new LinkedHashMap<>(rows));
    }

    private static String counters() {
        return "{\"DISABLED\":0,\"MISCONFIGURED\":0,\"CALENDAR_UNKNOWN\":0,\"ACCOUNTING_FAILED\":0,"
                + "\"RECONCILE_FAILED\":0,\"QUOTE_FAILED\":0,\"NO_OWNER\":0,\"NO_TODAY_SNAPSHOT\":0,"
                + "\"BROKER_MISSING\":0,\"DRY_RUN\":0,\"SUCCESS\":1,\"EMPTY_CLEARED\":0,\"ROLLED_BACK\":0}";
    }

    private static String successRow(String code, String price, String updatedAt) {
        return "{\"stockCode\":\"" + code + "\",\"status\":\"SUCCESS\",\"reason\":null,\"quote\":{"
                + "\"stockCode\":\"" + code + "\",\"stockName\":\"測試股\",\"market\":\"台股\","
                + "\"actualPrice\":\"" + price + "\",\"previousClose\":\"100.00\",\"openPrice\":\"100.00\","
                + "\"highPrice\":\"102.00\",\"lowPrice\":\"99.00\",\"buyPrice\":null,\"sellPrice\":null,"
                + "\"volume\":0,\"updatedAt\":\"" + updatedAt + "\",\"tradingDate\":\"2026-08-24\","
                + "\"source\":\"FUBON_INTRADAY\",\"closed\":true,\"quoteStatus\":\"LIVE\",\"orderBook\":{"
                + "\"bookUpdatedAt\":\"2026-08-24T02:00:00Z\",\"averagePrice\":null,\"turnoverYi\":null,"
                + "\"innerVolumeLots\":null,\"outerVolumeLots\":null,\"levels\":["
                + "{\"level\":1,\"bidPrice\":\"101.00\",\"bidVolumeLots\":1,\"askPrice\":\"102.00\",\"askVolumeLots\":2},"
                + "{\"level\":2,\"bidPrice\":null,\"bidVolumeLots\":null,\"askPrice\":\"103.00\",\"askVolumeLots\":3},"
                + "{\"level\":3,\"bidPrice\":\"99.00\",\"bidVolumeLots\":4,\"askPrice\":\"104.00\",\"askVolumeLots\":5},"
                + "{\"level\":4,\"bidPrice\":\"98.00\",\"bidVolumeLots\":6,\"askPrice\":\"105.00\",\"askVolumeLots\":7},"
                + "{\"level\":5,\"bidPrice\":\"97.00\",\"bidVolumeLots\":8,\"askPrice\":\"106.00\",\"askVolumeLots\":9}]}}}";
    }

    private static String failureRow(String code, String reason) {
        return "{\"stockCode\":\"" + code + "\",\"status\":\"FAILURE\",\"reason\":\"" + reason
                + "\",\"quote\":null}";
    }

    private static String mirrorPayload(FubonLiveResponseStore.CanonicalResponse canonical) throws Exception {
        return "{\"receivedAt\":\"" + canonical.receivedAt() + "\",\"receivedEpochMicros\":\""
                + FubonLiveResponseCache.receiptMicros(canonical.receivedAt()) + "\",\"batchId\":\""
                + canonical.batchId() + "\",\"counters\":" + canonical.countersJson() + ",\"responseRow\":"
                + canonical.responseRowJson() + "}";
    }

    private static void applyV117(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE fubon_tw_live_quote_response ("
                + "stock_code varchar(20) NOT NULL, market varchar(20) NOT NULL, received_at timestamptz NOT NULL,"
                + "batch_id varchar(64) NOT NULL, counters jsonb NOT NULL, response_row jsonb NOT NULL,"
                + "PRIMARY KEY(stock_code, market), CHECK (market = '台股'),"
                + "CHECK (jsonb_typeof(counters) = 'object'), CHECK (jsonb_typeof(response_row) = 'object'),"
                + "CHECK (btrim(batch_id) <> ''))");
    }
}
