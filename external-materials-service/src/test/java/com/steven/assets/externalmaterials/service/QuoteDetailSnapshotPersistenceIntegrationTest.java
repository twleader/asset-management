package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL/Redis coverage for the strict canonical header-plus-five-level transaction. */
@Testcontainers(disabledWithoutDocker = true)
class QuoteDetailSnapshotPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;

    private IntradayOrderBookSnapshotStore store;
    private QuoteDetailCache cache;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (redisFactory != null) redisFactory.destroy();
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS stock_intraday_order_book_level");
        jdbc.execute("DROP TABLE IF EXISTS stock_intraday_order_book");
        jdbc.execute("DROP TABLE IF EXISTS stock");
        jdbc.execute("CREATE TABLE stock (code varchar(20), market varchar(20), name varchar(100) NOT NULL, PRIMARY KEY(code, market))");
        applyMigration();
        try (RedisConnection connection = redisFactory.getConnection()) {
            connection.serverCommands().flushAll();
            connection.scriptingCommands().scriptFlush();
        }
        store = new IntradayOrderBookSnapshotStore(jdbc, new DataSourceTransactionManager(dataSource));
        cache = new QuoteDetailCache(redis, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void appliedSnapshotWritesHeaderLevelsNameAndOnlyDedicatedRedisKey() {
        var incoming = snapshot("新名", "2026-08-21T05:00:00.123456Z", "100", levels());

        var persisted = store.persist(incoming);

        assertThat(persisted.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book_level", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT name FROM stock WHERE code='2330' AND market='台股'", String.class)).isEqualTo("新名");
        assertThat(cache.writeStrictNewer(persisted.canonical())).isEqualTo(QuoteDetailCache.WriteOutcome.WRITTEN);
        assertThat(redis.opsForValue().get(QuoteDetailCache.key("台股", "2330"))).contains("FUBON_BOOKS");
        assertThat(redis.getExpire(QuoteDetailCache.key("台股", "2330"))).isBetween(86_390L, 86_400L);
        assertThat(redis.hasKey("price:台股:2330")).isFalse();
        assertThat(redis.keys("price:index:*")).isEmpty();
        assertThat(redis.keys("price:ticks:*")).isEmpty();
        assertThat(redis.keys("price:dayhl:*")).isEmpty();
    }

    @Test
    void equalOrOlderCannotMutateHeaderLevelsFetchedTimeNameOrRedisTtl() throws Exception {
        var current = snapshot("新名", "2026-08-21T05:00:00Z", "100", levels());
        var applied = store.persist(current);
        assertThat(cache.writeStrictNewer(applied.canonical())).isEqualTo(QuoteDetailCache.WriteOutcome.WRITTEN);
        String beforePayload = redis.opsForValue().get(QuoteDetailCache.key("台股", "2330"));
        Instant beforeFetched = jdbc.queryForObject("SELECT fetched_at FROM stock_intraday_order_book",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        Long beforeTtl = redis.getExpire(QuoteDetailCache.key("台股", "2330"));
        Thread.sleep(1_100L);
        var olderLevels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(70 - level), (long) (100 + level),
                        BigDecimal.valueOf(80 + level), (long) (200 + level)))
                .toList();
        var older = snapshot("舊名", "2026-08-21T04:59:59Z", "99", olderLevels);
        var equal = snapshot("等名", "2026-08-21T05:00:00Z", "98", olderLevels);
        for (var stale : List.of(older, equal)) {
            var persisted = store.persist(stale);
            assertThat(persisted.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.STALE_OR_EQUAL);
            assertThat(cache.writeStrictNewer(persisted.canonical()))
                    .isEqualTo(QuoteDetailCache.WriteOutcome.REJECTED_STALE);
        }

        assertThat(jdbc.queryForObject("SELECT name FROM stock WHERE code='2330' AND market='台股'", String.class)).isEqualTo("新名");
        Instant afterFetched = jdbc.queryForObject("SELECT fetched_at FROM stock_intraday_order_book",
                (org.springframework.jdbc.core.RowMapper<Instant>) (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(afterFetched).isEqualTo(beforeFetched);
        assertThat(jdbc.queryForObject("SELECT actual_price FROM stock_intraday_order_book", BigDecimal.class))
                .isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book_level", Integer.class)).isEqualTo(5);
        assertThat(redis.opsForValue().get(QuoteDetailCache.key("台股", "2330"))).isEqualTo(beforePayload);
        assertThat(redis.getExpire(QuoteDetailCache.key("台股", "2330"))).isLessThan(beforeTtl);
    }

    @Test
    void halfSideAndWrongSourceAreRejectedBeforeAnyDatabaseMutation() {
        List<TwQuoteDetailFetchClient.OrderBookLevel> half = new java.util.ArrayList<>(levels());
        half.set(2, new TwQuoteDetailFetchClient.OrderBookLevel(3, BigDecimal.valueOf(98), null,
                BigDecimal.valueOf(103), 3L));
        var malformed = snapshot("台積電", "2026-08-21T05:00:00Z", "100", half);
        var wrongSource = new TwQuoteDetailFetchClient.QuoteDetailResult(
                malformed.stockCode(), malformed.stockName(), malformed.market(), malformed.supported(), malformed.available(),
                "YAHOO_TW", malformed.message(), malformed.sourceTime(), malformed.fetchedAt(), malformed.marketStatus(),
                malformed.price(), malformed.previousClose(), malformed.openPrice(), malformed.highPrice(), malformed.lowPrice(),
                malformed.averagePrice(), malformed.change(), malformed.changePercent(), malformed.turnoverYi(), malformed.volumeLots(),
                malformed.previousVolumeLots(), malformed.amplitudePercent(), malformed.innerVolumeLots(), malformed.outerVolumeLots(),
                malformed.innerPercent(), malformed.outerPercent(), malformed.bidTotalLots(), malformed.askTotalLots(), malformed.levels());

        assertThat(store.persist(malformed).status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.FAILED);
        assertThat(store.persist(wrongSource).status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book", Integer.class)).isZero();
    }

    private static void applyMigration() throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path migration = null;
        while (cursor != null) {
            Path candidate = cursor.resolve("backend/src/main/resources/db/changelog/changes/v1.114.0-stock-intraday-order-book.sql");
            if (Files.isRegularFile(candidate)) {
                migration = candidate;
                break;
            }
            cursor = cursor.getParent();
        }
        if (migration == null) throw new IllegalStateException("Task373 migration not found");
        jdbc.execute(Files.readString(migration).replaceAll("(?m)^--.*$", "").trim());
    }

    private static List<TwQuoteDetailFetchClient.OrderBookLevel> levels() {
        return java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(100 - level), (long) level,
                        BigDecimal.valueOf(100 + level), (long) (level + 10)))
                .toList();
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(
            String name, String sourceTime, String price, List<TwQuoteDetailFetchClient.OrderBookLevel> levels) {
        BigDecimal actual = new BigDecimal(price);
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", name, "台股", true, true, "FUBON_BOOKS", null,
                Instant.parse(sourceTime), Instant.parse("2026-08-21T05:00:02Z"), "OPEN",
                actual, BigDecimal.valueOf(99), BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(98),
                BigDecimal.valueOf(100), actual.subtract(BigDecimal.valueOf(99)), BigDecimal.ONE, BigDecimal.ONE,
                10L, null, BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
