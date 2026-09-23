package com.steven.assets.repository;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** PostgreSQL proves per-pair index limits preserve the former window query's complete rows. */
@Testcontainers
class JdbcTradingRadarListBatchRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("radar_price_batch").withUsername("assets").withPassword("test-only-password");

    private JdbcTemplate jdbc;
    private JdbcTradingRadarListBatchRepository repository;

    @BeforeEach
    void createPriceTableWithProductionNaturalKey() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS stock_price_history");
        jdbc.execute("""
                CREATE TABLE stock_price_history (
                    id BIGINT PRIMARY KEY, stock_code TEXT NOT NULL, market TEXT NOT NULL,
                    trading_date DATE NOT NULL, open_price NUMERIC, high_price NUMERIC, low_price NUMERIC,
                    close_price NUMERIC NOT NULL, volume BIGINT, close_source TEXT,
                    UNIQUE (stock_code, market, trading_date)
                )
                """);
        repository = new JdbcTradingRadarListBatchRepository(jdbc,
                mock(TreasuryYieldSeriesRepository.class), mock(ExchangeRateHistoryRepository.class));
    }

    @Test
    void lateralKeepsExactPairOrderNullableFieldsAnd500RowBoundary() {
        List<StockPriceHistory> seeded = new ArrayList<>();
        seeded.addAll(prices("SAME", "台股", 701, 1));
        seeded.addAll(prices("SAME", "美股", 511, 1_000));
        seeded.addAll(prices("PARTIAL", "台股", 7, 2_000));
        seeded.addAll(prices("OUTSIDE", "台股", 20, 3_000));
        seeded.addAll(prices("SAME", "英股", 20, 4_000));
        for (StockPriceHistory row : seeded) {
            jdbc.update("""
                    INSERT INTO stock_price_history
                      (id,stock_code,market,trading_date,open_price,high_price,low_price,close_price,volume,close_source)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, row.getId(), row.getStockCode(), row.getMarket(), row.getTradingDate(),
                    row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice(),
                    row.getVolume(), row.getCloseSource());
        }
        var tw = new TradingRadarListBatchRepository.Key("SAME", "台股");
        var us = new TradingRadarListBatchRepository.Key("SAME", "美股");
        var partial = new TradingRadarListBatchRepository.Key("PARTIAL", "台股");
        var missing = new TradingRadarListBatchRepository.Key("ABSENT", "台股");
        var keys = List.of(tw, us, partial, missing);

        for (int limit : List.of(1, 500, 900)) {
            var actual = repository.findRecentPrices(Arrays.asList(us, tw, tw, null, partial, missing), limit);
            assertThat(actual).containsOnlyKeys(tw, us, partial, missing);
            for (var key : keys) {
                var expected = seeded.stream()
                        .filter(row -> key.code().equals(row.getStockCode()) && key.market().equals(row.getMarket()))
                        .sorted(Comparator.comparing(StockPriceHistory::getTradingDate).reversed())
                        .limit(limit).toList();
                assertThat(actual.get(key)).containsExactlyElementsOf(expected);
            }
            // The old row_number query independently checks selection and exact global ordering.
            List<Long> previousIds = jdbc.queryForList("""
                    WITH requested(stock_code,market) AS (VALUES ('SAME','台股'),('SAME','美股'),
                        ('PARTIAL','台股'),('ABSENT','台股')), ranked AS (
                        SELECT h.*,row_number() OVER (
                            PARTITION BY h.stock_code,h.market ORDER BY h.trading_date DESC) row_rank
                        FROM stock_price_history h JOIN requested r
                          ON r.stock_code=h.stock_code AND r.market=h.market
                    ) SELECT id FROM ranked WHERE row_rank<=?
                    ORDER BY market ASC,stock_code ASC,trading_date DESC
                    """, Long.class, limit);
            List<Long> actualIds = actual.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(Comparator
                            .comparing(TradingRadarListBatchRepository.Key::market)
                            .thenComparing(TradingRadarListBatchRepository.Key::code)))
                    .flatMap(entry -> entry.getValue().stream()).map(StockPriceHistory::getId).toList();
            assertThat(actualIds).containsExactlyElementsOf(previousIds);
            assertThrows(UnsupportedOperationException.class, () -> actual.get(tw).clear());
        }
    }

    @Test
    void emptyAndInvalidRequestsReturnNoRowsWithoutReadingDatabase() {
        jdbc.execute("DROP TABLE stock_price_history");
        assertThat(repository.findRecentPrices(null, 500)).isEmpty();
        assertThat(repository.findRecentPrices(List.of(), 500)).isEmpty();
        assertThat(repository.findRecentPrices(List.of(new TradingRadarListBatchRepository.Key("X", "台股")), 0)).isEmpty();
        assertThat(repository.findRecentPrices(Arrays.asList(null,
                new TradingRadarListBatchRepository.Key(null, "台股"),
                new TradingRadarListBatchRepository.Key("X", null)), 500)).isEmpty();
    }

    private static List<StockPriceHistory> prices(String code, String market, int count, long idStart) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(100_001L + i, 4);
            rows.add(StockPriceHistory.builder().id(idStart + i).stockCode(code).market(market)
                    .tradingDate(LocalDate.of(2026, 9, 23).minusDays(i)).closePrice(close)
                    .openPrice(i % 3 == 0 ? null : close.subtract(new BigDecimal("0.0100")))
                    .highPrice(i % 4 == 0 ? null : close.add(new BigDecimal("0.0200")))
                    .lowPrice(i % 5 == 0 ? null : close.subtract(new BigDecimal("0.0300")))
                    .volume(i % 7 == 0 ? null : 1_000L + i).closeSource(i % 6 == 0 ? null : "FIXTURE")
                    .build());
        }
        return rows;
    }
}
