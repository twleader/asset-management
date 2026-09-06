package com.steven.assets.integration.fubon;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Applies the v1.124 change through Liquibase against a real PostgreSQL engine. */
@Testcontainers
class FubonRealizedGainSyncMigrationPostgresTest {
    private static final String MASTER = "db/changelog/db.changelog-master.yaml";
    private static final String MIGRATION = "db/changelog/changes/v1.124.0-realized-gain-fubon-sync.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fubon_realized_gain_migration")
            .withUsername("assets")
            .withPassword("test-only-password");

    @Test
    void liquibaseCreatesTheProvenanceCheckAndPartialUniqueIndex() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE realized_gain (
                        id BIGINT PRIMARY KEY,
                        owner_user_id BIGINT NOT NULL,
                        asset_name VARCHAR(50) NOT NULL,
                        investment_cost NUMERIC(20,2) NOT NULL,
                        proceeds NUMERIC(20,2) NOT NULL,
                        trade_date DATE NOT NULL
                    )
                    """);
        }

        applyThroughLiquibase();

        try (Connection connection = open()) {
            assertThat(singleString(connection, """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'realized_gain' AND column_name = 'sync_source'
                    """)).isEqualTo("character varying");
            assertThat(singleString(connection, """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'realized_gain' AND column_name = 'sync_fingerprint'
                    """)).isEqualTo("character");
            assertThat(singleString(connection, """
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'realized_gain' AND column_name = 'sync_occurrence'
                    """)).isEqualTo("integer");

            String check = singleString(connection, """
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid = 'realized_gain'::regclass
                      AND conname = 'ck_realized_gain_sync_provenance'
                    """);
            assertThat(check).contains("FUBON_REALIZED_GAIN_SYNC", "sync_fingerprint", "sync_occurrence");
            String index = singleString(connection, """
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = 'public' AND indexname = 'uq_realized_gain_fubon_sync_occurrence'
                    """);
            assertThat(index).contains("owner_user_id, sync_source, sync_fingerprint, sync_occurrence")
                    .contains("WHERE (sync_source IS NOT NULL)");

            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO realized_gain (id, owner_user_id, asset_name, investment_cost, proceeds, trade_date,
                                sync_source)
                        VALUES (1, 7, 'test', 1.00, 1.00, DATE '2026-09-06', 'invalid')
                        """))
                        .isInstanceOf(SQLException.class);
                statement.executeUpdate("""
                        INSERT INTO realized_gain (id, owner_user_id, asset_name, investment_cost, proceeds, trade_date,
                                sync_source, sync_fingerprint, sync_occurrence)
                        VALUES (2, 7, 'test', 1.00, 1.00, DATE '2026-09-06',
                                'FUBON_REALIZED_GAIN_SYNC',
                                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1)
                        """);
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO realized_gain (id, owner_user_id, asset_name, investment_cost, proceeds, trade_date,
                                sync_source, sync_fingerprint, sync_occurrence)
                        VALUES (3, 7, 'test', 1.00, 1.00, DATE '2026-09-06',
                                'FUBON_REALIZED_GAIN_SYNC',
                                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 1)
                        """))
                        .isInstanceOf(SQLException.class);
            }
            assertThat(singleString(connection, """
                    SELECT id FROM databasechangelog
                    WHERE id = 'v1.124.0-realized-gain-fubon-sync' AND author = 'codex'
                    """)).isEqualTo("v1.124.0-realized-gain-fubon-sync");
        }
    }

    private static void applyThroughLiquibase() throws Exception {
        try (Connection connection = open(); ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            try (InputStream input = resources.getExisting(MASTER).openInputStream()) {
                String master = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(master).contains(MIGRATION);
            }
            var changeLog = new FormattedSqlChangeLogParser().parse(
                    MIGRATION, new ChangeLogParameters(), resources);
            assertThat(changeLog.getChangeSets()).hasSize(1);
            assertThat(changeLog.getChangeSets().getFirst().getId())
                    .isEqualTo("v1.124.0-realized-gain-fubon-sync");
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(changeLog, resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String singleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
