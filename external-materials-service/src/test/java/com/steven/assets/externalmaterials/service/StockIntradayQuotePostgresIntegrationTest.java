package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockIntradayQuotePostgresIntegrationTest {
    @Test
    void actualV113MigrationAndSpringTransactionProxyKeepOnlyStrictlyNewerCanonicalSnapshot() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            DataSource dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE stock (code varchar(20), market varchar(20), name varchar(80), PRIMARY KEY(code,market))");
            applyActualV113Migration(jdbc);
            jdbc.update("INSERT INTO stock VALUES ('2330','台股','舊名')");

            StockSourceQuery query = transactionalProxy(jdbc, dataSource);
            assertThat(query.persistIntradayQuote(quote("新名", "2026-08-24T02:00:01Z", "101")).status())
                    .isEqualTo(StockSourceQuery.IntradayPersistenceResult.Status.APPLIED);
            java.util.Map<String, Object> appliedSnapshot = jdbc.queryForMap(
                    "SELECT stock_code, market, trading_date, provider_updated_at, source, actual_price, previous_close, "
                            + "open_price, high_price, low_price, buy_price, sell_price, volume "
                            + "FROM stock_intraday_quote WHERE stock_code='2330' AND market='台股'");
            assertThat(query.persistIntradayQuote(quote("倒灌名", "2026-08-24T02:00:01Z", "99")).status())
                    .isEqualTo(StockSourceQuery.IntradayPersistenceResult.Status.STALE_OR_EQUAL);
            StockSourceQuery.IntradayPersistenceResult older =
                    query.persistIntradayQuote(quote("更舊名", "2026-08-24T02:00:00Z", "98"));
            assertThat(older.status()).isEqualTo(StockSourceQuery.IntradayPersistenceResult.Status.STALE_OR_EQUAL);
            assertThat(older.canonical().actualPrice()).isEqualByComparingTo("101");
            assertThat(jdbc.queryForMap(
                    "SELECT stock_code, market, trading_date, provider_updated_at, source, actual_price, previous_close, "
                            + "open_price, high_price, low_price, buy_price, sell_price, volume "
                            + "FROM stock_intraday_quote WHERE stock_code='2330' AND market='台股'"))
                    .isEqualTo(appliedSnapshot);

            assertThat(jdbc.queryForObject("SELECT trading_date FROM stock_intraday_quote WHERE stock_code='2330'", java.sql.Date.class))
                    .isEqualTo(java.sql.Date.valueOf("2026-08-24"));
            assertThat(jdbc.queryForObject("SELECT source FROM stock_intraday_quote WHERE stock_code='2330'", String.class))
                    .isEqualTo("FUBON_INTRADAY");
            assertThat(jdbc.queryForObject("SELECT actual_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("101");
            assertThat(jdbc.queryForObject("SELECT previous_close FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("100");
            assertThat(jdbc.queryForObject("SELECT open_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("100.5");
            assertThat(jdbc.queryForObject("SELECT high_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("102");
            assertThat(jdbc.queryForObject("SELECT low_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("99");
            assertThat(jdbc.queryForObject("SELECT buy_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("100.8");
            assertThat(jdbc.queryForObject("SELECT sell_price FROM stock_intraday_quote WHERE stock_code='2330'", BigDecimal.class))
                    .isEqualByComparingTo("101.2");
            assertThat(jdbc.queryForObject("SELECT volume FROM stock_intraday_quote WHERE stock_code='2330'", Long.class))
                    .isEqualTo(123L);
            assertThat(jdbc.queryForObject("SELECT name FROM stock WHERE code='2330'", String.class)).isEqualTo("新名");

            assertThatThrownBy(() -> jdbc.update("INSERT INTO stock_intraday_quote "
                            + "(stock_code, market, trading_date, provider_updated_at, source, actual_price) "
                            + "VALUES ('bad', '台股', DATE '2026-08-24', TIMESTAMPTZ '2026-08-24 02:00:00+00', 'TEST', -1)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static void applyActualV113Migration(JdbcTemplate jdbc) throws Exception {
        Path migration = Path.of(System.getProperty("user.dir")).getParent().resolve(Path.of("backend", "src", "main", "resources",
                "db", "changelog", "changes", "v1.113.0-stock-intraday-quote.sql"));
        String sql = Files.readString(migration).replaceAll("(?m)^--.*$", "").trim();
        jdbc.execute(sql);
    }

    private static StockSourceQuery transactionalProxy(JdbcTemplate jdbc, DataSource dataSource) {
        TransactionProxyFactoryBean proxyFactory = new TransactionProxyFactoryBean();
        proxyFactory.setTarget(new StockSourceQuery(jdbc));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.setTransactionManager(new DataSourceTransactionManager(dataSource));
        Properties attributes = new Properties();
        attributes.setProperty("persistIntradayQuote", "PROPAGATION_REQUIRED");
        proxyFactory.setTransactionAttributes(attributes);
        proxyFactory.afterPropertiesSet();
        return (StockSourceQuery) proxyFactory.getObject();
    }

    private static PriceResult quote(String name, String time, String price) {
        return new PriceResult("2330", "台股", new BigDecimal(price), null, null, "FUBON_INTRADAY", name,
                new BigDecimal("100.8"), new BigDecimal("101.2"), new BigDecimal("100.5"), new BigDecimal("100"),
                new BigDecimal("102"), new BigDecimal("99"), 123L, LocalDate.of(2026, 8, 24), Instant.parse(time));
    }
}
