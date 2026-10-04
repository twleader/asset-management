package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.steven.assets.externalmaterials.service.FubonMarketData.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual PostgreSQL writes for partial capture, rerun, conflict, and exact-17 provenance. */
class FubonTechnicalHistoryStorePostgresTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);
    private static final Instant NOW = Instant.parse("2026-08-28T05:40:00Z");
    private static final String FIRST = "sma_d_5";

    @Test void partialFactsAreImmutableAndOnlyCompleteCaptureCreatesMembers() {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            var source = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE stock_technical_indicator (
                      stock_code varchar(20) NOT NULL, market varchar(20) NOT NULL, provider varchar(32) NOT NULL,
                      timeframe char(1) NOT NULL, profile_id varchar(32) NOT NULL, source_date date NOT NULL,
                      indicator_kind varchar(16) NOT NULL, parameters jsonb NOT NULL, payload jsonb NOT NULL,
                      source_timestamp timestamptz, capture_id uuid NOT NULL, observed_at timestamptz NOT NULL,
                      first_observed_at timestamptz NOT NULL, content_hash char(64) NOT NULL,
                      PRIMARY KEY (stock_code, market, provider, timeframe, profile_id, source_date))
                    """);
            jdbc.execute("""
                    CREATE TABLE fubon_technical_capture_member (
                      capture_id uuid NOT NULL, profile_id varchar(32) NOT NULL, stock_code varchar(20) NOT NULL,
                      market varchar(20) NOT NULL, provider varchar(32) NOT NULL, timeframe char(1) NOT NULL,
                      source_date date NOT NULL, content_hash char(64) NOT NULL, observed_at timestamptz NOT NULL,
                      previous_source_date date, previous_content_hash char(64),
                      PRIMARY KEY (capture_id, profile_id))
                    """);
            var store = new FubonMarketDataHistoryStore(jdbc, new DataSourceTransactionManager(source));

            var partial = bundle(List.of(new TechnicalHistory(DAY.minusDays(1), null, Map.of("sma", "1"))), false);
            var first = store.persistTechnical(partial);
            assertThat(first.facts()).isEqualTo(FubonMarketDataHistoryStore.Status.WRITTEN);
            assertThat(first.factWritten()).isEqualTo(1);
            assertThat(first.completeCaptureCommitted()).isFalse();
            assertThat(first.committedDatesByProfile()).containsEntry(FIRST, List.of(DAY.minusDays(1)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_technical_indicator", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_technical_capture_member", Integer.class)).isZero();

            var rerun = store.persistTechnical(bundle(partial.profiles().getFirst().history(), false));
            assertThat(rerun.facts()).isEqualTo(FubonMarketDataHistoryStore.Status.UNCHANGED);
            assertThat(rerun.factWritten()).isZero();
            assertThat(rerun.factUnchanged()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_technical_indicator", Integer.class)).isEqualTo(1);

            var conflicted = store.persistTechnical(bundle(List.of(
                    new TechnicalHistory(DAY.minusDays(1), null, Map.of("sma", "2")),
                    new TechnicalHistory(DAY, null, Map.of("sma", "1"))), false));
            assertThat(conflicted.facts()).isEqualTo(FubonMarketDataHistoryStore.Status.CONFLICT_NO_SOURCE_REVISION);
            assertThat(conflicted.conflicts()).isEqualTo(1);
            assertThat(conflicted.factWritten()).isEqualTo(1);
            assertThat(conflicted.committedDatesByProfile()).containsEntry(FIRST, List.of(DAY));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_technical_indicator", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT payload ->> 'sma' FROM stock_technical_indicator WHERE profile_id=? AND source_date=?
                    """, String.class, FIRST, DAY.minusDays(1))).isEqualTo("1");

            var tooWide = new TechnicalBundle(UUID.randomUUID(), "2330", DAY.minusDays(365), DAY,
                    partial.profiles());
            assertThat(store.persistTechnical(tooWide).facts()).isEqualTo(FubonMarketDataHistoryStore.Status.FAILED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_technical_indicator", Integer.class)).isEqualTo(2);

            var complete = store.persistTechnical(bundle(List.of(
                    new TechnicalHistory(DAY, null, Map.of("sma", "1"))), true));
            assertThat(complete.completeCaptureCommitted()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_technical_capture_member", Integer.class))
                    .isEqualTo(TECHNICAL_PROFILES.size());
        }
    }

    private static TechnicalBundle bundle(List<TechnicalHistory> firstRows, boolean complete) {
        List<TechnicalProfileRead> reads = new ArrayList<>();
        for (TechnicalProfile profile : TECHNICAL_PROFILES) {
            boolean available = FIRST.equals(profile.profileId()) || complete;
            Map<String, String> payload = profile.payloadFields().stream()
                    .collect(java.util.stream.Collectors.toMap(field -> field, field -> "1"));
            reads.add(new TechnicalProfileRead(profile.profileId(), available ? "AVAILABLE" : "NO_DATA",
                    available ? null : "NO_DATA", profile.parameters(), NOW,
                    !available ? List.of() : FIRST.equals(profile.profileId()) ? firstRows
                            : List.of(new TechnicalHistory(DAY, null, payload))));
        }
        return new TechnicalBundle(UUID.randomUUID(), "2330", DAY.minusDays(TECHNICAL_MAX_SPAN_DAYS), DAY, reads);
    }
}
