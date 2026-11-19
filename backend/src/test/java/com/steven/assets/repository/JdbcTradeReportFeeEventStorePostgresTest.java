package com.steven.assets.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static com.steven.assets.service.tradereport.TradeReportFeeEventStore.FEE_ENRICHMENT;
import static com.steven.assets.service.tradereport.TradeReportFeeEventStore.MANUAL_EDIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcTradeReportFeeEventStorePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trade_fee_fixture").withUsername("fixture").withPassword("fixture-only");
    private static final BigDecimal FEE = new BigDecimal("20"), TAX = BigDecimal.ZERO;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private JdbcTradeReportFeeEventStore store;
    private long evidence;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new JdbcTradeReportFeeEventStore(jdbc);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE asset_transaction(id BIGINT PRIMARY KEY, owner_user_id BIGINT REFERENCES app_user(id),
                    source TEXT NOT NULL, fee NUMERIC(15,2), transaction_tax NUMERIC(15,2), notes TEXT)
                """);
        jdbc.update("INSERT INTO app_user VALUES (1),(2)");
        jdbc.update("""
                INSERT INTO asset_transaction VALUES (10,1,'FUBON_EMAIL',NULL,NULL,'unchanged'),
                    (11,1,'MANUAL',NULL,NULL,NULL),(12,1,'FUBON_EMAIL',0,NULL,NULL),
                    (13,1,'FUBON_EMAIL',NULL,0,NULL)
                """);
        apply("v1.168.0-broker-trade-report-import.sql");
        apply("v1.170.0-trade-report-fee-event.sql");
        evidence = jdbc.queryForObject("""
                INSERT INTO broker_trade_report_import(owner_user_id,parser_version,identity_hash,facts_hash,
                    canonical_facts,transaction_id) VALUES(1,'synthetic',repeat('a',64),repeat('b',64),'{}',10)
                RETURNING id
                """, Long.class);
    }

    private void apply(String migration) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/changes/" + migration)) {
            jdbc.execute(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test void immutableOwnerScopedAuditSurvivesReapply() throws Exception {
        store.append(1L, evidence, MANUAL_EDIT, "{}");
        store.append(1L, evidence, MANUAL_EDIT, "{}");
        store.append(1L, evidence, FEE_ENRICHMENT, "{\"fee\":20}");
        apply("v1.170.0-trade-report-fee-event.sql");

        assertThat(store.importEvidenceId(1L, 10)).contains(evidence);
        assertThat(store.importEvidenceId(2L, 10)).isEmpty();
        assertThat(store.manuallyEdited(1L, evidence)).isTrue();
        assertThat(store.manuallyEdited(2L, evidence)).isFalse();
        assertThat(store.enrichment(1L, evidence)).hasValueSatisfying(event -> {
            assertThat(event.owner()).isEqualTo(1);
            assertThat(event.payload()).isEqualTo("{\"fee\":20}");
        });
        assertThat(store.enrichment(2L, evidence)).isEmpty();
        assertThatThrownBy(() -> store.append(2L, evidence, MANUAL_EDIT, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> store.append(1L, evidence, FEE_ENRICHMENT, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE broker_trade_report_fee_event SET payload_jcs='{}'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("TRADE_REPORT_FEE_EVENT_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM broker_trade_report_fee_event"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("TRADE_REPORT_FEE_EVENT_IMMUTABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broker_trade_report_fee_event", Integer.class))
                .isEqualTo(3);
    }

    @Test void updateRequiresBothNullExactOwnerAndEmailSource() {
        assertThat(store.updateNullFees(2L, 10, FEE, TAX)).isZero();
        for (long id : new long[]{11, 12, 13, 99})
            assertThat(store.updateNullFees(1L, id, FEE, TAX)).isZero();
        assertThat(store.updateNullFees(1L, 10, FEE, TAX)).isOne();
        assertThat(store.updateNullFees(1L, 10, new BigDecimal("25"), TAX)).isZero();
        assertThat(jdbc.queryForObject("SELECT fee FROM asset_transaction WHERE id=10", BigDecimal.class))
                .isEqualByComparingTo(FEE);
        assertThat(jdbc.queryForObject("SELECT transaction_tax FROM asset_transaction WHERE id=10", BigDecimal.class))
                .isEqualByComparingTo(TAX);
        assertThat(jdbc.queryForObject("SELECT notes FROM asset_transaction WHERE id=10", String.class))
                .isEqualTo("unchanged");
    }

    @Test void feeWriteRollsBackWhenAuditInsertFails() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(store.updateNullFees(1L, 10, FEE, TAX)).isOne();
            store.append(1L, evidence, FEE_ENRICHMENT, "{}");
            store.append(1L, evidence, FEE_ENRICHMENT, "{}");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.enrichment(1L, evidence)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT fee IS NULL AND transaction_tax IS NULL FROM asset_transaction WHERE id=10", Boolean.class))
                .isTrue();
    }
}
