package com.steven.assets.repository;

import com.steven.assets.service.DividendEventEvidenceBatch;
import com.steven.assets.service.DividendEventEvidenceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL regression for the list CTE's per-pair decision timestamp binding. */
@Testcontainers
class JdbcDividendEventEvidencePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("radar_dividend_batch").withUsername("assets").withPassword("test-only-password");

    private JdbcTemplate jdbc;

    @BeforeEach
    void createAppendOnlySnapshotTables() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS stock_dividend_snapshot_event");
        jdbc.execute("DROP TABLE IF EXISTS stock_dividend_fetch_observation");
        jdbc.execute("DROP TABLE IF EXISTS stock_dividend_snapshot");
        jdbc.execute("""
                CREATE TABLE stock_dividend_snapshot (
                    id BIGINT PRIMARY KEY,
                    stock_code TEXT NOT NULL,
                    market TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    source_url TEXT,
                    scope_from DATE NOT NULL,
                    scope_to DATE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE stock_dividend_fetch_observation (
                    id BIGINT PRIMARY KEY,
                    snapshot_id BIGINT NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    source_available_at TIMESTAMP WITH TIME ZONE,
                    status TEXT NOT NULL,
                    complete BOOLEAN NOT NULL,
                    scope_from DATE NOT NULL,
                    scope_to DATE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE stock_dividend_snapshot_event (
                    snapshot_id BIGINT NOT NULL,
                    ex_dividend_date DATE,
                    ex_rights_date DATE,
                    cash_dividend NUMERIC,
                    stock_dividend NUMERIC,
                    cash_payment_date DATE,
                    stock_payment_date DATE,
                    source_available_at TIMESTAMP WITH TIME ZONE
                )
                """);
    }

    @Test
    void multiPairBatchBindsDecisionAtAsTimestamptzAndKeepsSameCodeMarketsSeparate() {
        Instant observed = Instant.parse("2026-09-10T01:00:00Z");
        seed(1L, 11L, "SAME", "台股", "TW_PROVIDER", LocalDate.of(2026, 9, 16),
                new BigDecimal("1.23"), observed);
        seed(2L, 12L, "SAME", "美股", "US_PROVIDER", LocalDate.of(2026, 9, 18),
                new BigDecimal("0.45"), observed);
        Instant decision = Instant.parse("2026-09-12T06:00:00Z");

        List<DividendEventEvidenceBatch.Result> results = new JdbcDividendEventEvidenceRepository(jdbc)
                .resolveBatch(List.of(
                        new DividendEventEvidenceBatch.Query("SAME", "台股", decision, sessionsAfter(LocalDate.of(2026, 9, 12))),
                        new DividendEventEvidenceBatch.Query("SAME", "美股", decision, sessionsAfter(LocalDate.of(2026, 9, 12)))));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).resolution().status()).isEqualTo(DividendEventEvidenceResolver.Status.AVAILABLE);
        assertThat(results.get(0).resolution().provider()).isEqualTo("TW_PROVIDER");
        assertThat(results.get(0).resolution().nextEvent().cashDividend()).isEqualByComparingTo("1.23");
        assertThat(results.get(1).resolution().status()).isEqualTo(DividendEventEvidenceResolver.Status.AVAILABLE);
        assertThat(results.get(1).resolution().provider()).isEqualTo("US_PROVIDER");
        assertThat(results.get(1).resolution().nextEvent().cashDividend()).isEqualByComparingTo("0.45");
    }

    private void seed(long snapshotId, long observationId, String code, String market, String provider,
                      LocalDate exDividendDate, BigDecimal cashDividend, Instant observed) {
        jdbc.update("""
                INSERT INTO stock_dividend_snapshot
                    (id, stock_code, market, provider, source_url, scope_from, scope_to)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, code, market, provider, "https://example.test/" + provider,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30));
        jdbc.update("""
                INSERT INTO stock_dividend_fetch_observation
                    (id, snapshot_id, observed_at, source_available_at, status, complete, scope_from, scope_to)
                VALUES (?, ?, ?, ?, 'COMPLETE', TRUE, ?, ?)
                """, observationId, snapshotId, java.sql.Timestamp.from(observed),
                java.sql.Timestamp.from(observed), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30));
        jdbc.update("""
                INSERT INTO stock_dividend_snapshot_event
                    (snapshot_id, ex_dividend_date, ex_rights_date, cash_dividend, stock_dividend,
                     cash_payment_date, stock_payment_date, source_available_at)
                VALUES (?, ?, NULL, ?, 0, NULL, NULL, ?)
                """, snapshotId, exDividendDate, cashDividend, java.sql.Timestamp.from(observed));
    }

    private static List<LocalDate> sessionsAfter(LocalDate decisionDate) {
        List<LocalDate> sessions = new ArrayList<>();
        LocalDate date = decisionDate.plusDays(1);
        while (sessions.size() < 20) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                sessions.add(date);
            }
            date = date.plusDays(1);
        }
        return List.copyOf(sessions);
    }
}
