package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        jdbc.update("INSERT INTO stock (code, market, name) VALUES ('2330', '台股', '台積電')");
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
        assertThat(jdbc.queryForObject("SELECT name FROM stock WHERE code='2330' AND market='台股'", String.class)).isEqualTo("台積電");
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
    void missingStockMasterRejectsWholeSnapshotWithoutWritingHeaderOrLevels() {
        jdbc.update("DELETE FROM stock WHERE code='2330' AND market='台股'");

        var persisted = store.persist(snapshot("來源名稱", "2026-08-21T05:00:00Z", "100", levels()));

        assertThat(persisted.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book_level", Integer.class)).isZero();
    }

    @Test
    void halfSideAndUnknownSourceAreRejectedWhileYahooIsAnApprovedCanonicalSource() {
        List<TwQuoteDetailFetchClient.OrderBookLevel> half = new java.util.ArrayList<>(levels());
        half.set(2, new TwQuoteDetailFetchClient.OrderBookLevel(3, BigDecimal.valueOf(98), null,
                BigDecimal.valueOf(103), 3L));
        var malformed = snapshot("台積電", "2026-08-21T05:00:00Z", "100", half);
        var yahoo = new TwQuoteDetailFetchClient.QuoteDetailResult(
                malformed.stockCode(), malformed.stockName(), malformed.market(), malformed.supported(), malformed.available(),
                "YAHOO_TW", malformed.message(), malformed.sourceTime(), malformed.fetchedAt(), malformed.marketStatus(),
                malformed.price(), malformed.previousClose(), malformed.openPrice(), malformed.highPrice(), malformed.lowPrice(),
                malformed.averagePrice(), malformed.change(), malformed.changePercent(), malformed.turnoverYi(), malformed.volumeLots(),
                malformed.previousVolumeLots(), malformed.amplitudePercent(), malformed.innerVolumeLots(), malformed.outerVolumeLots(),
                malformed.innerPercent(), malformed.outerPercent(), malformed.bidTotalLots(), malformed.askTotalLots(), levels());
        var unknownSource = new TwQuoteDetailFetchClient.QuoteDetailResult(
                yahoo.stockCode(), yahoo.stockName(), yahoo.market(), yahoo.supported(), yahoo.available(),
                "UNTRUSTED", yahoo.message(), yahoo.sourceTime(), yahoo.fetchedAt(), yahoo.marketStatus(),
                yahoo.price(), yahoo.previousClose(), yahoo.openPrice(), yahoo.highPrice(), yahoo.lowPrice(),
                yahoo.averagePrice(), yahoo.change(), yahoo.changePercent(), yahoo.turnoverYi(), yahoo.volumeLots(),
                yahoo.previousVolumeLots(), yahoo.amplitudePercent(), yahoo.innerVolumeLots(), yahoo.outerVolumeLots(),
                yahoo.innerPercent(), yahoo.outerPercent(), yahoo.bidTotalLots(), yahoo.askTotalLots(), yahoo.levels());

        assertThat(store.persist(malformed).status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.FAILED);
        assertThat(store.persist(yahoo).status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        assertThat(store.persist(unknownSource).status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT source FROM stock_intraday_order_book", String.class)).isEqualTo("YAHOO_TW");
    }

    @Test
    void twoRealConcurrentTransactionsAlwaysLeaveFubonPrimaryOverOlderYahoo() throws Exception {
        IntradayOrderBookSnapshotStore fubonStore = new IntradayOrderBookSnapshotStore(
                jdbc, new DataSourceTransactionManager(dataSource));
        IntradayOrderBookSnapshotStore yahooStore = new IntradayOrderBookSnapshotStore(
                jdbc, new DataSourceTransactionManager(dataSource));
        var fubon = snapshot("FUBON_BOOKS", "富邦名", "2026-08-21T05:00:00Z", "100", levels());
        var olderYahoo = snapshot("YAHOO_TW", "Yahoo 名", "2026-08-21T04:59:59Z", "99", levels());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<IntradayOrderBookSnapshotStore.PersistResult> fubonResult = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
                return fubonStore.persist(fubon);
            });
            Future<IntradayOrderBookSnapshotStore.PersistResult> yahooResult = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
                return yahooStore.persist(olderYahoo);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(fubonResult.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
            assertThat(yahooResult.get(10, TimeUnit.SECONDS).status())
                    .isIn(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED,
                            IntradayOrderBookSnapshotStore.PersistStatus.STALE_OR_EQUAL);
        } finally {
            workers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT source FROM stock_intraday_order_book", String.class))
                .isEqualTo("FUBON_BOOKS");
        assertThat(jdbc.queryForObject("SELECT actual_price FROM stock_intraday_order_book", BigDecimal.class))
                .isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_intraday_order_book_level", Integer.class)).isEqualTo(5);
    }

    @Test
    void revisionFenceRejectsDelayedFubonCacheWriteAfterAcceptedNewerYahoo() throws Exception {
        var fubon = store.persist(snapshot("FUBON_BOOKS", "富邦名", "2026-08-21T05:00:00Z", "100", levels()));
        assertThat(fubon.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        assertThat(cache.writeStrictNewer(fubon.canonical())).isEqualTo(QuoteDetailCache.WriteOutcome.WRITTEN);
        var yahoo = store.persist(snapshot("YAHOO_TW", "Yahoo 名", "2026-08-21T05:00:01Z", "101", levels()));
        assertThat(yahoo.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        assertThat(yahoo.canonical().canonicalRevision()).isEqualTo(fubon.canonical().canonicalRevision() + 1);
        assertThat(cache.writeStrictNewer(yahoo.canonical())).isEqualTo(QuoteDetailCache.WriteOutcome.WRITTEN);

        assertThat(cache.writeStrictNewer(fubon.canonical())).isEqualTo(QuoteDetailCache.WriteOutcome.REJECTED_STALE);
        var payload = new ObjectMapper().readTree(redis.opsForValue().get(QuoteDetailCache.key("台股", "2330")));
        assertThat(payload.path("canonicalRevision").asText()).isEqualTo(Long.toString(yahoo.canonical().canonicalRevision()));
        assertThat(payload.path("snapshot").path("source").asText()).isEqualTo("YAHOO_TW");
        assertThat(jdbc.queryForObject("SELECT source FROM stock_intraday_order_book", String.class)).isEqualTo("YAHOO_TW");
        assertThat(jdbc.queryForObject("SELECT canonical_revision FROM stock_intraday_order_book", Long.class))
                .isEqualTo(yahoo.canonical().canonicalRevision());
    }

    @Test
    void committedYahooRevisionWithRedisFailureCannotServeDelayedFubonPayload() throws Exception {
        var fubon = store.persist(snapshot("FUBON_BOOKS", "富邦名", "2026-08-21T05:00:00Z", "100", levels()));
        var yahoo = store.persist(snapshot("YAHOO_TW", "Yahoo 名", "2026-08-21T05:00:01Z", "101", levels()));
        assertThat(fubon.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        assertThat(yahoo.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        // Simulate Redis failing immediately after the accepted Yahoo DB transaction. A delayed old
        // payload can occupy an empty key, but the reader must compare its revision with PostgreSQL.
        assertThat(cache.writeStrictNewer(fubon.canonical())).isEqualTo(QuoteDetailCache.WriteOutcome.WRITTEN);
        String beforeRead = redis.opsForValue().get(QuoteDetailCache.key("台股", "2330"));

        var result = new QuoteDetailReadService(cache, store).read("2330", "台股");

        assertThat(result.available()).isTrue();
        assertThat(result.source()).isEqualTo("YAHOO_TW");
        assertThat(result.price()).isEqualByComparingTo("101");
        assertThat(redis.opsForValue().get(QuoteDetailCache.key("台股", "2330"))).isEqualTo(beforeRead);
    }

    @Test
    void canonicalReadKeepsHeaderAndLevelsAtOneRepeatableReadRevisionDuringConcurrentWriter() throws Exception {
        var older = store.persist(snapshot("FUBON_BOOKS", "富邦舊名", "2026-08-21T05:00:00Z", "100", levels()));
        assertThat(older.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);

        CountDownLatch headerRead = new CountDownLatch(1);
        CountDownLatch allowLevelRead = new CountDownLatch(1);
        JdbcTemplate hookedJdbc = new HeaderPauseJdbcTemplate(dataSource, headerRead, allowLevelRead);
        IntradayOrderBookSnapshotStore readerStore = new IntradayOrderBookSnapshotStore(
                hookedJdbc, new DataSourceTransactionManager(dataSource));
        var newerLevels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(300 - level), (long) level,
                        BigDecimal.valueOf(301 + level), (long) (level + 10)))
                .toList();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<IntradayOrderBookSnapshotStore.CanonicalLookup> reader = workers.submit(
                    () -> readerStore.findCanonical("2330", "台股"));
            assertThat(headerRead.await(5, TimeUnit.SECONDS)).isTrue();

            var newer = store.persist(snapshot("FUBON_BOOKS", "富邦新名", "2026-08-21T05:00:01Z", "101", newerLevels));
            assertThat(newer.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
            allowLevelRead.countDown();

            var result = reader.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(IntradayOrderBookSnapshotStore.ReadStatus.FOUND);
            assertThat(result.canonical().canonicalRevision()).isEqualTo(older.canonical().canonicalRevision());
            assertThat(result.canonical().snapshot().stockName()).isEqualTo("富邦舊名");
            assertThat(result.canonical().snapshot().levels().getFirst().bidPrice()).isEqualByComparingTo("99");
        } finally {
            allowLevelRead.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void readerReturnsCompleteRPlusOneCommittedBetweenItsHeaderAndFullCanonicalReadWithoutCacheRepair() {
        var fubon = store.persist(snapshot("FUBON_BOOKS", "富邦名", "2026-08-21T05:00:00Z", "100", levels()));
        assertThat(fubon.status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        AtomicReference<IntradayOrderBookSnapshotStore.PersistResult> yahooWrite = new AtomicReference<>();
        IntradayOrderBookSnapshotStore writerStore = store;
        IntradayOrderBookSnapshotStore readerStore = new AdvanceAfterRevisionStore(
                jdbc, new DataSourceTransactionManager(dataSource), () -> yahooWrite.set(writerStore.persist(
                        snapshot("YAHOO_TW", "Yahoo 名", "2026-08-21T05:00:01Z", "101", levels()))));

        var result = new QuoteDetailReadService(cache, readerStore).read("2330", "台股");

        assertThat(yahooWrite.get()).isNotNull();
        assertThat(yahooWrite.get().status()).isEqualTo(IntradayOrderBookSnapshotStore.PersistStatus.APPLIED);
        assertThat(result.available()).isTrue();
        assertThat(result.source()).isEqualTo("YAHOO_TW");
        assertThat(result.price()).isEqualByComparingTo("101");
        // Request-time read uses Redis only as a candidate; it does not repair the empty key.
        assertThat(redis.opsForValue().get(QuoteDetailCache.key("台股", "2330"))).isNull();
    }

    /** Pauses precisely after the store's header query so a writer can commit before its level query. */
    private static final class HeaderPauseJdbcTemplate extends JdbcTemplate {
        private final CountDownLatch headerRead;
        private final CountDownLatch allowLevelRead;

        private HeaderPauseJdbcTemplate(DataSource dataSource, CountDownLatch headerRead, CountDownLatch allowLevelRead) {
            super(dataSource);
            this.headerRead = headerRead;
            this.allowLevelRead = allowLevelRead;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<T> result = super.query(sql, rowMapper, args);
            if (sql.contains("FROM stock_intraday_order_book h")) {
                headerRead.countDown();
                try {
                    if (!allowLevelRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release canonical level read");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("canonical reader interrupted", interrupted);
                }
            }
            return result;
        }
    }

    /** Commits a writer exactly after the reader obtains its header revision and before full lookup. */
    private static final class AdvanceAfterRevisionStore extends IntradayOrderBookSnapshotStore {
        private final Runnable afterRevision;
        private final AtomicBoolean advanced = new AtomicBoolean();

        private AdvanceAfterRevisionStore(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager,
                                          Runnable afterRevision) {
            super(jdbc, transactionManager);
            this.afterRevision = afterRevision;
        }

        @Override
        public RevisionLookup findRevision(String code, String market) {
            RevisionLookup lookup = super.findRevision(code, market);
            if (lookup.status() == ReadStatus.FOUND && advanced.compareAndSet(false, true)) {
                afterRevision.run();
            }
            return lookup;
        }
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
        Path fallback = migration.resolveSibling("v1.116.0-yahoo-order-book-fallback.sql");
        if (!Files.isRegularFile(fallback)) throw new IllegalStateException("Task379 migration not found");
        jdbc.execute(Files.readString(fallback).replaceAll("(?m)^--.*$", "").trim());
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
        return snapshot("FUBON_BOOKS", name, sourceTime, price, levels);
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(
            String source, String name, String sourceTime, String price,
            List<TwQuoteDetailFetchClient.OrderBookLevel> levels) {
        BigDecimal actual = new BigDecimal(price);
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", name, "台股", true, true, source, null,
                Instant.parse(sourceTime), Instant.parse("2026-08-21T05:00:02Z"), "OPEN",
                actual, BigDecimal.valueOf(99), BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(98),
                BigDecimal.valueOf(100), actual.subtract(BigDecimal.valueOf(99)), BigDecimal.ONE, BigDecimal.ONE,
                10L, null, BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
