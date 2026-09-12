package com.steven.assets.apierrorlog;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL evidence (Requirement 143 / Task 421, spec item 421.7) that
 * {@code uq_api_error_log_dedupe_key} — the partial unique index added by
 * {@code v1.125.0-api-error-log-status-and-dedupe.sql} — actually enforces the dedupe-on-insert
 * invariant that {@link NginxGatewayFailureLogTailer} depends on: a second row with the same
 * {@code dedupe_key} is accepted as a named {@code ON CONFLICT DO NOTHING} no-op, never
 * merged/updated (the existing {@code guard_api_error_log_retention} trigger rejects every UPDATE
 * on this table). This is deliberately not H2 or a mocked repository, matching
 * {@code FubonTradeSyncUniqueIndexPostgresTest}'s existing rationale in
 * {@code backend/src/test/java/com/steven/assets/integration/fubon/}: partial unique index
 * enforcement on real PostgreSQL cannot be observed through a mock.
 *
 * <p>{@code ddl-auto=create-drop} builds {@code api_error_log} from the JPA entity mapping (which
 * already declares {@code http_status}/{@code dedupe_key} — see {@link ApiErrorLog}); the partial
 * unique index itself is not expressible via JPA annotations, so this test replays the exact DDL
 * statement from the changeset once at startup, against the same container the inserts run on.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiErrorLogDedupeKeyPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("api_error_log_dedupe_test")
            .withUsername("assets")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApiErrorLogRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_api_error_log_dedupe_key "
                            + "ON api_error_log (dedupe_key) WHERE dedupe_key IS NOT NULL").executeUpdate();
            repository.deleteAll();
            repository.flush();
        });
    }

    @Test
    void secondNativeInsertWithSameDedupeKeyIsSilentNoOpAndOnlyOneRowRemains() {
        Instant occurredAt = Instant.parse("2026-09-07T01:48:59Z");
        String dedupeKey = "a".repeat(64);

        int[] affected = new int[2];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            affected[0] = repository.insertIgnoringDuplicateDedupeKey("OPEN_API", "OPEN_MARKET_INDEX", "測試",
                    "header", "trace", occurredAt, 502, dedupeKey);
            affected[1] = repository.insertIgnoringDuplicateDedupeKey("OPEN_API", "OPEN_MARKET_INDEX", "測試",
                    "header", "trace", occurredAt, 502, dedupeKey);
        });

        List<ApiErrorLog> persisted = repository.findAll();
        assertThat(affected[0]).isEqualTo(1);
        assertThat(affected[1]).isZero();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getDedupeKey()).isEqualTo(dedupeKey);
    }

    @Test
    void nullDedupeKeyRowsAreExemptFromTheUniqueIndex() {
        Instant occurredAt = Instant.parse("2026-09-07T01:48:59Z");

        // Mirrors every filter-driven (WebFilter) capture and every FUBON_API producer row: dedupeKey
        // is always null there, and must never collide with each other under the partial index.
        repository.saveAndFlush(row("OPEN_QUOTES_LIST", occurredAt, 400, null));
        repository.saveAndFlush(row("OPEN_QUOTES_LIST", occurredAt, 400, null));

        assertThat(repository.findAll()).hasSize(2);
    }

    private static ApiErrorLog row(String operationKey, Instant occurredAt, Integer httpStatus, String dedupeKey) {
        return new ApiErrorLog("OPEN_API", operationKey, "測試", "header", "trace", occurredAt, httpStatus, dedupeKey);
    }
}
