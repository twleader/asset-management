package com.steven.assets.repository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Public JDBC getters must preserve complete observation rows in both PostgreSQL wire formats. */
@Testcontainers
class JdbcFundamentalAnalysisBatchRepositoryTest {
    private static final Instant DECISION = Instant.parse("2026-11-02T14:00:00Z");
    private static final FundamentalAnalysisBatchRepository.Key TW =
            new FundamentalAnalysisBatchRepository.Key("SAME", "台股");
    private static final FundamentalAnalysisBatchRepository.Key US =
            new FundamentalAnalysisBatchRepository.Key("SAME", "美股");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("radar_fundamental_batch").withUsername("assets").withPassword("test-only-password");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void textAndPreparedBinaryRowsKeepOriginalGetterPrecisionNullsTimezoneAndRevisions(boolean binary) throws Exception {
        String url = POSTGRES.getJdbcUrl() + "&prepareThreshold=1&binaryTransfer=" + binary;
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            createTables(jdbc);
            jdbc.execute("SET TIME ZONE 'America/New_York'");
            // Verify the test really exercises the requested wire format without driver internals.
            try (var statement = connection.prepareStatement("SELECT DATE '2026-11-01', TIMESTAMPTZ '2026-11-01T06:30:00.123456Z'")) {
                try (var ignored = statement.executeQuery()) { ignored.next(); }
                try (var row = statement.executeQuery()) {
                    row.next();
                    assertEquals(binary ? 4 : 10, row.getBytes(1).length);
                    assertEquals(binary, row.getBytes(2).length == 8);
                }
            }
            Instant available = Instant.parse("2026-11-01T05:30:00.123456Z");
            for (String market : List.of("台股", "美股")) {
                for (int i = 0; i < 24; i++) {
                    jdbc.update("""
                            INSERT INTO stock_valuation_daily VALUES (?,?,?,?,?,?,?,?,?,?,?)
                            """, "SAME", market, i % 7 == 0 ? null : LocalDate.of(2026, 11, 1).minusDays(i % 3),
                            i % 4 == 0 ? null : new BigDecimal("12.345600"), new BigDecimal("-0.000001"),
                            new BigDecimal("4.01000000"), i % 5 == 0 ? null : i % 2 == 0,
                            i % 2 == 0 ? "EXCHANGE" : "YAHOO", i % 6 == 0 ? null : "[\"https://example.test/原文\"]",
                            Timestamp.from(i % 2 == 0 ? available : available.plusSeconds(3600)),
                            Timestamp.from(available.plusSeconds(7200).plusNanos(i * 1000L)));
                }
            }
            // Neither null availability nor an observation after this decision may leak into the batch.
            jdbc.update("INSERT INTO stock_valuation_daily (stock_code,market,source_available_at,observed_at) VALUES ('SAME','台股',NULL,?)",
                    Timestamp.from(available));
            jdbc.update("INSERT INTO stock_valuation_daily (stock_code,market,source_available_at,observed_at) VALUES ('SAME','台股',?,?)",
                    Timestamp.from(available), Timestamp.from(DECISION.plusNanos(1000)));
            JdbcFundamentalAnalysisBatchRepository repository = new JdbcFundamentalAnalysisBatchRepository(jdbc);
            var baseline = Map.of(TW, repository.findSnapshot(TW, DECISION), US, repository.findSnapshot(US, DECISION));
            // First and reused prepared execution must both match every original field, including scale.
            for (int run = 0; run < 2; run++) {
                var actual = repository.findSnapshots(List.of(US, TW, TW), DECISION);
                for (var key : List.of(TW, US)) {
                    assertThat(actual.get(key).valuations()).hasSize(24)
                            .containsExactlyInAnyOrderElementsOf(baseline.get(key).valuations());
                    assertEquals(baseline.get(key).financials(), actual.get(key).financials());
                    assertEquals(baseline.get(key).revenues(), actual.get(key).revenues());
                    assertEquals(baseline.get(key).industries(), actual.get(key).industries());
                    var sample = actual.get(key).valuations().stream().filter(row -> row.pe() != null).findFirst().orElseThrow();
                    assertEquals(new BigDecimal("12.345600"), sample.pe());
                    assertEquals(new BigDecimal("4.01000000"), sample.dividendYieldPct());
                    assertEquals(123456000, sample.availableAt().getNano());
                }
            }
        }
    }

    private static void createTables(JdbcTemplate jdbc) {
        for (String table : List.of("stock_financial_quarter", "stock_monthly_revenue", "stock_valuation_daily", "industry_monthly_revenue")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE stock_financial_quarter (stock_code TEXT,market TEXT,fiscal_year INT,fiscal_quarter INT,
                  eps NUMERIC,net_income_parent NUMERIC,equity_parent NUMERIC,provider TEXT,source_urls TEXT,
                  source_available_at TIMESTAMPTZ,observed_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE stock_monthly_revenue (stock_code TEXT,market TEXT,revenue_year INT,revenue_month INT,
                  industry_name TEXT,revenue_yoy_pct NUMERIC,provider TEXT,source_urls TEXT,
                  source_available_at TIMESTAMPTZ,observed_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE stock_valuation_daily (stock_code TEXT,market TEXT,trading_date DATE,pe_ratio NUMERIC,
                  pb_ratio NUMERIC,dividend_yield_pct NUMERIC,pe_loss_flag BOOLEAN,provider TEXT,source_urls TEXT,
                  source_available_at TIMESTAMPTZ,observed_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE industry_monthly_revenue (industry_name TEXT,revenue_year INT,revenue_month INT,
                  revenue_yoy_pct NUMERIC,company_count INT,provider TEXT,source_urls TEXT,
                  source_available_at TIMESTAMPTZ,observed_at TIMESTAMPTZ)
                """);
    }
}
