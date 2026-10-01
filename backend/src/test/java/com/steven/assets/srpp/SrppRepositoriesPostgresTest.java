package com.steven.assets.srpp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 163／Task 452.1：以真實 PostgreSQL 套用 v1.136.0 changeset（兩次，驗證冪等），再驗證
 * native INSERT／查詢、UPDATE trigger、全零 hash CHECK、evidence cascade 與 registry 讀取驗證。
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(SrppPolicyRegistryService.class)
class SrppRepositoriesPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("srpp_test")
            .withUsername("assets")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String HASH = "a".repeat(64);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 24);

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SrppOwnerKeyRepository ownerKeys;
    @Autowired SrppContextPackageRepository packages;
    @Autowired SrppContextEvidenceRepository evidence;
    @Autowired SrppPolicyRegistryService registry;

    @BeforeEach
    void schema() throws Exception {
        String apiErrorLogChangeset = new ClassPathResource("db/changelog/changes/v1.123.0-api-error-log.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String changeset = new ClassPathResource("db/changelog/changes/v1.136.0-srpp-daily-context.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_user (id bigint PRIMARY KEY)");
        jdbc.execute(apiErrorLogChangeset);
        jdbc.execute(changeset);
        jdbc.execute(changeset);   // 冪等
        jdbc.execute("DELETE FROM srpp_context_package");
        jdbc.execute("DELETE FROM srpp_owner_key");
        jdbc.execute("DELETE FROM srpp_policy_registry");
        jdbc.execute("INSERT INTO app_user (id) VALUES (7), (8) ON CONFLICT DO NOTHING");
    }

    private void tx(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    private void registerPolicy() {
        jdbc.update("INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, policy_document, "
                        + "formula_manifest) VALUES (?, ?, ?, ?) ON CONFLICT (policy_bundle_sha256) DO NOTHING",
                HASH, SrppFormulaCatalog.FORMULA_VERSION,
                "{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"CASH:TWD:活存\":\"0.4\"}}",
                SrppFormulaCatalog.manifestJcs());
    }

    @Test
    void changesetSeedsCatalogRowButNoRegistryRows() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_policy_registry", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT operation_label FROM api_error_log_operation WHERE source='OPEN_API' "
                + "AND operation_key='OPEN_SRPP_DAILY_CONTEXT'", String.class)).isEqualTo("SRPP 共用計算結果");
        assertThat(registry.supportedPolicies()).isEmpty();
    }

    @Test
    void orchestratedRouteCatalogChangesetIsInsertOnlyIdempotentAndMatchesErrorLogForeignKey() throws Exception {
        String changeset = new ClassPathResource(
                "db/changelog/changes/v1.140.0-srpp-llm-orchestrated-error-log-catalog.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(changeset);
        jdbc.execute(changeset);

        record CatalogRow(String source, String key, String label, int displayOrder) {}
        var rows = jdbc.query("SELECT source, operation_key, operation_label, display_order "
                        + "FROM api_error_log_operation WHERE operation_key LIKE 'OPEN_SRPP_%' ORDER BY display_order",
                (rs, row) -> new CatalogRow(rs.getString("source"), rs.getString("operation_key"),
                        rs.getString("operation_label"), rs.getInt("display_order")));
        assertThat(rows).containsExactly(
                new CatalogRow("OPEN_API", "OPEN_SRPP_DAILY_CONTEXT", "SRPP 共用計算結果", 140),
                new CatalogRow("OPEN_API", "OPEN_SRPP_CALCULATION_CONTEXT", "SRPP 計算脈絡", 150),
                new CatalogRow("OPEN_API", "OPEN_SRPP_CALCULATIONS", "SRPP 按需計算", 160),
                new CatalogRow("OPEN_API", "OPEN_SRPP_MARKET_FACTS", "SRPP 批次市場事實", 170));

        for (String operationKey : java.util.List.of("OPEN_SRPP_CALCULATION_CONTEXT", "OPEN_SRPP_CALCULATIONS",
                "OPEN_SRPP_MARKET_FACTS")) {
            String label = jdbc.queryForObject("SELECT operation_label FROM api_error_log_operation "
                    + "WHERE source='OPEN_API' AND operation_key=?", String.class, operationKey);
            assertThatThrownBy(() -> jdbc.update("UPDATE api_error_log_operation SET operation_label='changed' "
                    + "WHERE source='OPEN_API' AND operation_key=?", operationKey)).hasMessageContaining("immutable");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM api_error_log_operation "
                    + "WHERE source='OPEN_API' AND operation_key=?", operationKey)).hasMessageContaining("immutable");
            assertThat(jdbc.update("INSERT INTO api_error_log (source, operation_key, api_name, message_header, "
                    + "stack_trace, occurred_at) VALUES ('OPEN_API', ?, ?, 'header', 'trace', now())",
                    operationKey, label)).isEqualTo(1);
        }
    }

    @Test
    void registryRowIsReadAndValidated() {
        registerPolicy();
        SupportedPolicy policy = registry.find(HASH).orElseThrow();
        assertThat(policy.registeredAt()).isNotNull();
        assertThat(policy.targets()).containsOnlyKeys("CASH:TWD:活存");
        assertThat(registry.supportedPolicies()).hasSize(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE srpp_policy_registry SET formula_version='X'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO srpp_policy_registry (policy_bundle_sha256, formula_version, "
                + "policy_document, formula_manifest) VALUES (repeat('0', 64), 'x', '{}', '{}')"))
                .hasMessageContaining("srpp_policy_registry_policy_bundle_sha256_check");
    }

    @Test
    void ownerKeyIsCreatedOnceAndNeverChanges() {
        UUID[] keys = new UUID[2];
        tx(() -> {
            assertThat(ownerKeys.insertIfAbsent(7L, UUID.randomUUID())).isEqualTo(1);
            keys[0] = ownerKeys.findOwnerKey(7L).orElseThrow();
        });
        tx(() -> {
            assertThat(ownerKeys.insertIfAbsent(7L, UUID.randomUUID())).isZero();
            keys[1] = ownerKeys.findOwnerKey(7L).orElseThrow();
        });
        assertThat(keys[1]).isEqualTo(keys[0]);
        assertThat(ownerKeys.findOwnerKey(8L)).isEmpty();
        assertThatThrownBy(() -> jdbc.update("UPDATE srpp_owner_key SET owner_key = gen_random_uuid()"))
                .hasMessageContaining("immutable");
    }

    @Test
    void packagesAreOwnerScopedOrderedImmutableAndCascadeOnRetention() {
        registerPolicy();
        UUID older = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID newerLow = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID newerHigh = UUID.fromString("ffffffff-0000-4000-8000-000000000003");
        UUID foreign = UUID.fromString("00000000-0000-4000-8000-000000000004");
        UUID expired = UUID.fromString("00000000-0000-4000-8000-000000000005");
        Instant t1 = Instant.parse("2026-09-24T01:05:01Z");
        Instant t2 = Instant.parse("2026-09-24T01:10:01Z");
        tx(() -> {
            packages.insertPackage(older, 7L, DATE, "09:05", HASH, t1, "{\"n\":1}");
            packages.insertPackage(newerLow, 7L, DATE, "09:05", HASH, t2, "{\"n\":2}");
            packages.insertPackage(newerHigh, 7L, DATE, "09:05", HASH, t2, "{\"n\":3}");
            packages.insertPackage(foreign, 8L, DATE, "09:05", HASH, Instant.parse("2026-09-24T02:00:00Z"), "{\"n\":4}");
            packages.insertPackage(expired, 7L, DATE.minusDays(8), "09:05", HASH, t1, "{\"n\":5}");
            evidence.insertEvidence(newerHigh, "assets", "{\"body\":\"台股\"}");
            evidence.insertEvidence(expired, "assets", "{}");
        });

        SrppContextPackage latest = packages.findLatestForOwner(7L, DATE, "09:05", HASH).orElseThrow();
        assertThat(latest.getPackageId()).isEqualTo(newerHigh);
        assertThat(latest.getGeneratedAt()).isEqualTo(t2);
        assertThat(latest.getPolicyBundleSha256()).isEqualTo(HASH);
        assertThat(packages.findLatestForOwner(7L, DATE, "11:40", HASH)).isEmpty();
        assertThat(packages.findByPackageIdAndOwner(foreign, 7L)).isEmpty();
        assertThat(packages.findByPackageIdAndOwner(foreign, 8L)).isPresent();
        assertThat(evidence.findBody(newerHigh, "assets", 7L)).contains("{\"body\":\"台股\"}");
        assertThat(evidence.findBody(newerHigh, "calendar", 7L)).isEmpty();
        // evidence body 查詢 join package 比對 owner：他人 ownerId 讀不到
        assertThat(evidence.findBody(newerHigh, "assets", 8L)).isEmpty();

        assertThatThrownBy(() -> jdbc.update("UPDATE srpp_context_package SET slot='11:40'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE srpp_context_evidence SET body='x'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM srpp_policy_registry"))
                .hasMessageContaining("srpp_context_package");

        int[] deleted = new int[1];
        tx(() -> deleted[0] = packages.deleteTradingDateBefore(DATE.minusDays(7)));
        assertThat(deleted[0]).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_context_evidence WHERE package_id = ?",
                Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_context_package", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_policy_registry", Integer.class)).isEqualTo(1);
    }
}
