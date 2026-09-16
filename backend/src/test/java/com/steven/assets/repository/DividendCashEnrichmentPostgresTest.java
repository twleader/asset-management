package com.steven.assets.repository;

import com.steven.assets.service.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class DividendCashEnrichmentPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dividend_fixture").withUsername("fixture").withPassword("fixture-only");
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    JdbcDividendCurrentStateRepository repo;
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcDividendCurrentStateRepository repo(JdbcTemplate jdbc) { return new JdbcDividendCurrentStateRepository(jdbc); }
        @Bean StockPriceHistoryRepository prices() { return mock(StockPriceHistoryRepository.class); }
        @Bean MarketDataService calendar() { return mock(MarketDataService.class); }
        @Bean DividendCashEnrichmentService service(JdbcDividendCurrentStateRepository repo,StockPriceHistoryRepository prices,MarketDataService calendar) {
            return new DividendCashEnrichmentService(repo,prices,calendar);
        }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class); jdbc=context.getBean(JdbcTemplate.class); repo=context.getBean(JdbcDividendCurrentStateRepository.class);
        jdbc.execute("DROP TABLE IF EXISTS stock_dividend_history");
        jdbc.execute("""
            CREATE TABLE stock_dividend_history(id BIGINT PRIMARY KEY, stock_code TEXT,market TEXT,
            event_key TEXT,year INTEGER,ex_dividend_date DATE,ex_rights_date DATE,cash_dividend NUMERIC(15,4),
            stock_dividend NUMERIC(15,4),cash_payment_date DATE,stock_payment_date DATE,
            previous_close NUMERIC(15,4),yield_pct NUMERIC(10,4),fill_days INTEGER,event_status TEXT,
            updated_at TIMESTAMPTZ DEFAULT '2026-01-01T00:00:00Z')
            """);
        jdbc.update("INSERT INTO stock_dividend_history(id,stock_code,market,year,ex_dividend_date,cash_dividend,event_status) VALUES(1,'2330','台股',2026,'2026-09-15',2,'ACTIVE')");
    }
    @AfterEach void close() { context.close(); }
    @Test void exactIdentityRaceAndConcurrentWriterNeverOverwriteOrRevive() throws Exception {
        for(String mutation:List.of("UPDATE stock_dividend_history SET event_status='CANCELLED' WHERE id=1",
                "UPDATE stock_dividend_history SET cash_dividend=3 WHERE id=1",
                "UPDATE stock_dividend_history SET ex_rights_date='2026-09-16' WHERE id=1",
                "DELETE FROM stock_dividend_history WHERE id=1")) {
            jdbc.update("DELETE FROM stock_dividend_history");
            jdbc.update("INSERT INTO stock_dividend_history(id,stock_code,market,year,ex_dividend_date,cash_dividend,event_status) VALUES(1,'2330','台股',2026,'2026-09-15',2,'ACTIVE')");
            var captured=repo.findActiveEventDetails("2330","台股").getFirst();
            CountDownLatch ready=new CountDownLatch(1), release=new CountDownLatch(1);
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                Future<Integer> pending=pool.submit(()->{ ready.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                    return repo.fillMissingCashEnrichment("2330","台股",captured,new BigDecimal("100"),new BigDecimal("2"),0); });
                assertTrue(ready.await(5,TimeUnit.SECONDS)); jdbc.update(mutation); release.countDown(); assertEquals(0,pending.get(5,TimeUnit.SECONDS));
            } finally { release.countDown();pool.shutdownNow(); }
        }
        jdbc.update("INSERT INTO stock_dividend_history(id,stock_code,market,year,ex_dividend_date,cash_dividend,event_status) VALUES(1,'2330','台股',2026,'2026-09-15',2,'ACTIVE')");
        var captured=repo.findActiveEventDetails("2330","台股").getFirst();
        jdbc.update("UPDATE stock_dividend_history SET previous_close=100,yield_pct=7,fill_days=9 WHERE id=1");
        assertEquals(0,repo.fillMissingCashEnrichment("2330","台股",captured,new BigDecimal("100"),new BigDecimal("2"),0));
        assertEquals(new BigDecimal("7.0000"),jdbc.queryForObject("SELECT yield_pct FROM stock_dividend_history",BigDecimal.class));
        assertEquals(9,jdbc.queryForObject("SELECT fill_days FROM stock_dividend_history",Integer.class));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                jdbc.queryForObject("SELECT updated_at FROM stock_dividend_history",java.sql.Timestamp.class).toInstant());
    }
    @Test void springProxyRunsLocalReadsAndWriteInRealNewTransaction() {
        LocalDate ex=LocalDate.of(2026,9,15);
        var calendar=context.getBean(MarketDataService.class);
        when(calendar.isTradingDayCachedOnly(anyString(),any())).thenReturn(Optional.of(true));
        var prices=context.getBean(StockPriceHistoryRepository.class);
        when(prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(any(),any(),any(),any())).thenAnswer(inv->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(com.steven.assets.model.StockPriceHistory.builder().tradingDate(ex.minusDays(1)).closePrice(new BigDecimal("100")).build(),
                    com.steven.assets.model.StockPriceHistory.builder().tradingDate(ex).closePrice(new BigDecimal("101")).build()); });
        context.getBean(DividendCashEnrichmentService.class).enrich("2330","台股",Instant.parse("2026-09-15T06:00:00Z"));
        assertEquals(new BigDecimal("100.0000"),jdbc.queryForObject("SELECT previous_close FROM stock_dividend_history",BigDecimal.class));
        assertEquals(0,jdbc.queryForObject("SELECT fill_days FROM stock_dividend_history",Integer.class));
    }
}
