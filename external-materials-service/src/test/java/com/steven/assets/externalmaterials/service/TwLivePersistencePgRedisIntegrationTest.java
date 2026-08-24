package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Production dispatcher path with real PostgreSQL strict-newer persistence and real Redis. */
class TwLivePersistencePgRedisIntegrationTest {

    @Test
    void appliedDbThenRedisFailureIsRepairedFromSameTimestampCanonicalRowAndDbFailureCannotReachRedis() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
             GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                     .withExposedPorts(6379)) {
            pg.start();
            redisContainer.start();
            DataSource dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE stock (code varchar(20), market varchar(20), name varchar(80), PRIMARY KEY(code,market))");
            applyActualV113Migration(jdbc);
            jdbc.update("INSERT INTO stock VALUES ('2330','台股','舊名')");
            StockSourceQuery source = transactionalProxy(jdbc, dataSource);

            LettuceConnectionFactory factory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
            factory.afterPropertiesSet();
            factory.start();
            try {
                StringRedisTemplate redis = new StringRedisTemplate(factory);
                redis.afterPropertiesSet();
                PriceCacheWriter writer = new PriceCacheWriter(redis, source, new IntradayHighLowTracker(redis), new IntradayTickStore(redis));
                PriceResult appliedRaw = quote("名稱一", "2026-08-24T02:00:01Z", "101", "102", "99");
                PriceResult staleRaw = quote("倒灌名稱", "2026-08-24T02:00:01Z", "99", "999", "1");
                PriceResult dbFailedRaw = quote("失敗名稱", "2026-08-24T02:00:02Z", "88", "88", "88");
                FubonNormalizedQuoteClient fubon = mock(FubonNormalizedQuoteClient.class);
                when(fubon.fetch(anyList())).thenReturn(
                        batch(appliedRaw), batch(staleRaw), batch(dbFailedRaw));
                @SuppressWarnings("unchecked") ObjectProvider<FubonNormalizedQuoteClient> provider = mock(ObjectProvider.class);
                when(provider.getIfAvailable()).thenReturn(fubon);
                PriceFetchClient prices = mock(PriceFetchClient.class);
                when(prices.fetchTwBatch(anyList())).thenReturn(new PriceFetchClient.TwQuoteBatchSummary(
                        java.util.Map.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), 0, 0));
                when(prices.getYahooTwLivePrice("2330")).thenReturn(Optional.empty());
                MarketClock clock = mock(MarketClock.class);
                when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
                TwLiveQuoteDispatcher dispatcher = new TwLiveQuoteDispatcher(
                        clock, prices, provider, source, writer, new TwLiveQuoteOutcomeCounters(), true, true);

                // The database transaction applies, but a wrong Redis key type makes Lua fail before any cache mutation.
                redis.opsForList().rightPush("price:台股:2330", "wrong-type");
                dispatcher.refresh(java.util.Set.of("2330"));
                assertThat(jdbc.queryForObject("SELECT actual_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                        .isEqualByComparingTo("101");
                assertThat(redis.opsForList().range("price:台股:2330", 0, -1)).containsExactly("wrong-type");

                // Same provider timestamp but different raw data is DB STALE_OR_EQUAL; only the canonical DB row enters Redis.
                redis.delete("price:台股:2330");
                dispatcher.refresh(java.util.Set.of("2330"));
                com.fasterxml.jackson.databind.JsonNode repaired = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(redis.opsForValue().get("price:台股:2330"));
                assertThat(repaired.path("price").decimalValue()).isEqualByComparingTo("101");
                assertThat(repaired.path("highPrice").decimalValue()).isEqualByComparingTo("102");
                assertThat(repaired.path("stockName").asText()).isEqualTo("名稱一");

                // Actual persistence failure happens before the Redis writer, so there is no replacement cache entry.
                redis.delete("price:台股:2330");
                redis.delete("price:index:台股");
                jdbc.execute("DROP TABLE stock_intraday_quote");
                dispatcher.refresh(java.util.Set.of("2330"));
                assertThat(redis.hasKey("price:台股:2330")).isFalse();
                assertThat(redis.hasKey("price:index:台股")).isFalse();
            } finally {
                factory.destroy();
            }
        }
    }

    private static FubonNormalizedQuoteClient.BatchResult batch(PriceResult result) {
        return new FubonNormalizedQuoteClient.BatchResult(FubonNormalizedQuoteClient.BatchStatus.SUCCESS,
                List.of(new ProviderTimedPriceObservation(result, result.tradingDate(), result.freshnessInstant())), 1, 0);
    }

    private static PriceResult quote(String name, String timestamp, String price, String high, String low) {
        return new PriceResult("2330", "台股", new BigDecimal(price), null, null, "FUBON_INTRADAY", name,
                new BigDecimal("100.8"), new BigDecimal("101.2"), new BigDecimal("100.5"), new BigDecimal("100"),
                new BigDecimal(high), new BigDecimal(low), 123L, LocalDate.of(2026, 8, 24), Instant.parse(timestamp));
    }

    private static void applyActualV113Migration(JdbcTemplate jdbc) throws Exception {
        Path migration = Path.of(System.getProperty("user.dir")).getParent().resolve(Path.of("backend", "src", "main", "resources",
                "db", "changelog", "changes", "v1.113.0-stock-intraday-quote.sql"));
        jdbc.execute(Files.readString(migration).replaceAll("(?m)^--.*$", "").trim());
    }

    private static StockSourceQuery transactionalProxy(JdbcTemplate jdbc, DataSource dataSource) {
        TransactionProxyFactoryBean factory = new TransactionProxyFactoryBean();
        factory.setTarget(new StockSourceQuery(jdbc));
        factory.setProxyTargetClass(true);
        factory.setTransactionManager(new DataSourceTransactionManager(dataSource));
        Properties attributes = new Properties();
        attributes.setProperty("persistIntradayQuote", "PROPAGATION_REQUIRED");
        factory.setTransactionAttributes(attributes);
        factory.afterPropertiesSet();
        return (StockSourceQuery) factory.getObject();
    }
}
