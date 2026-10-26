package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

class UsHistoryRepairStoreTest {
    final LocalDate day = LocalDate.now(MarketClock.US_ZONE).minusDays(3);
    final BigDecimal open = new BigDecimal("100"), high = new BigDecimal("102"),
            low = new BigDecimal("99"), close = new BigDecimal("101");

    @Test void postgresGuardPreservesCompleteBarsAndRechecksAfterAnotherWriterRepairsTheGap() {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            jdbc.execute("""
                    CREATE TABLE stock_price_history (
                      id bigserial PRIMARY KEY, stock_code varchar(20) NOT NULL, market varchar(20) NOT NULL,
                      trading_date date NOT NULL, open_price numeric(15,4), high_price numeric(15,4),
                      low_price numeric(15,4), close_price numeric(15,4) NOT NULL CHECK(close_price>0),
                      volume bigint, close_source varchar(64), UNIQUE(stock_code,market,trading_date))
                    """);
            var store = new StockSourceQuery(jdbc);
            assertThat(store.upsertIncompleteUsHistory("VOO", day, open, high, low, close, 0L)).isTrue();
            assertThat(store.upsertIncompleteUsHistory("VOO", day, open, high, low, new BigDecimal("100.5"), 100L)).isFalse();
            assertThat(jdbc.queryForObject("SELECT volume FROM stock_price_history", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT close_price FROM stock_price_history", BigDecimal.class)).isEqualByComparingTo(close);

            jdbc.update("UPDATE stock_price_history SET low_price=NULL, close_source='NASDAQ_REDIS_CLOSE'");
            assertThat(store.findEarliestIncompleteUsHistory("VOO", day.minusYears(2), day)).contains(day);
            // Another writer commits after the discovery read. The SQL guard must inspect current state.
            jdbc.update("UPDATE stock_price_history SET low_price=98, close_price=100, close_source='FINMIND_US_CLOSE'");
            assertThat(store.upsertIncompleteUsHistory("VOO", day, open, high, low, close, 100L)).isFalse();
            assertThat(jdbc.queryForObject("SELECT close_source FROM stock_price_history", String.class)).isEqualTo("FINMIND_US_CLOSE");
            assertThat(store.findEarliestIncompleteUsHistory("VOO", day.minusYears(2), day)).isEmpty();

            jdbc.update("UPDATE stock_price_history SET high_price=0, low_price=NULL");
            assertThat(store.upsertIncompleteUsHistory("VOO", day, open, high, low, close, 100L)).isTrue();
            assertThat(jdbc.queryForObject("SELECT close_source FROM stock_price_history", String.class)).isEqualTo("YAHOO_HISTORICAL_REPAIR");
            assertThat(store.findEarliestIncompleteUsHistory("VOO", day.minusYears(2), day)).isEmpty();
            assertThat(store.findEarliestIncompleteUsHistory("OTHER", day.minusYears(2), day)).isEmpty();

            for (Long volume : new Long[]{null, -1L}) {
                assertThat(store.upsertIncompleteUsHistory("BAD", day, open, high, low, close, volume)).isFalse();
            }
            assertThat(store.upsertIncompleteUsHistory("BAD", day, null, high, low, close, 10L)).isFalse();
            assertThat(store.upsertIncompleteUsHistory("BAD", day, open, BigDecimal.ZERO, low, close, 10L)).isFalse();
            assertThat(store.upsertIncompleteUsHistory("BAD", day, open, open, low, close, 10L)).isFalse();
            assertThat(store.upsertIncompleteUsHistory("BAD", day, open, high, close, close, 10L)).isFalse();
            assertThat(store.upsertIncompleteUsHistory("BAD", LocalDate.now(MarketClock.US_ZONE), open, high, low, close, 10L)).isFalse();
            assertThat(store.upsertIncompleteUsHistory("BAD", LocalDate.now(MarketClock.US_ZONE).plusDays(1), open, high, low, close, 10L)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_price_history WHERE stock_code='BAD'", Integer.class)).isZero();
        }
    }
}
