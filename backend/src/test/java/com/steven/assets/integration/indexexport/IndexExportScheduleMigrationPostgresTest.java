package com.steven.assets.integration.indexexport;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexExportScheduleMigrationPostgresTest {
    private static final String MIGRATION = "db/changelog/changes/v1.137.0-index-export-schedules-multi.sql";

    @Test
    void splitsLegacyTimesPreservesSettingsAddsPlaceholderAndIsIdempotent() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                createLegacySchema(sql);
                seedMigrationRows(sql);

                apply(postgres);

                assertThat(value(connection, "SELECT id FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=8")).isEqualTo("100");
                assertThat(value(connection, "SELECT id FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isNotEqualTo("100");
                assertThat(value(connection, "SELECT name FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=8")).isEqualTo("legacy_0830");
                assertThat(value(connection, "SELECT name FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("legacy_0945");
                assertThat(value(connection, "SELECT CASE WHEN enabled THEN 'true' ELSE 'false' END FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=8")).isEqualTo("false");
                assertThat(value(connection, "SELECT CASE WHEN enabled THEN 'true' ELSE 'false' END FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("true");
                assertThat(value(connection, "SELECT last_run_date FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=8")).isEqualTo("2026-09-10");
                assertThat(value(connection, "SELECT last_run_status FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("later status");
                assertThat(value(connection, "SELECT output_subpath FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("index/archive");
                assertThat(value(connection, "SELECT range_months FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("6");
                assertThat(value(connection, "SELECT CASE WHEN gdrive_enabled THEN 'true' ELSE 'false' END FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("true");
                assertThat(value(connection, "SELECT gdrive_subpath FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9")).isEqualTo("reports/market");
                assertThat(value(connection, "SELECT gdrive_last_status FROM index_export_schedule WHERE owner_user_id=11")).isNull();
                assertThat(value(connection, "SELECT string_agg(market, ',' ORDER BY market) FROM index_export_schedule_market WHERE schedule_id=(SELECT id FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=8)"))
                        .isEqualTo("TPEX,TWSE");
                assertThat(value(connection, "SELECT market FROM index_export_schedule_market WHERE schedule_id=(SELECT id FROM index_export_schedule WHERE owner_user_id=11 AND run_hour=9)"))
                        .isEqualTo("TWSE");

                assertThat(value(connection, "SELECT CASE WHEN enabled THEN 'true' ELSE 'false' END FROM index_export_schedule WHERE id=200")).isEqualTo("false");
                assertThat(value(connection, "SELECT run_hour || ':' || run_minute FROM index_export_schedule WHERE id=200")).isEqualTo("8:0");
                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule_market WHERE schedule_id=200")).isEqualTo("0");
                assertThat(value(connection, "SELECT output_subpath FROM index_export_schedule WHERE id=200")).isEqualTo("placeholder-folder");
                assertThat(value(connection, "SELECT range_months FROM index_export_schedule WHERE id=200")).isEqualTo("36");
                assertThat(value(connection, "SELECT gdrive_subpath FROM index_export_schedule WHERE id=200")).isEqualTo("placeholder-drive");
                assertThat(value(connection, "SELECT CASE WHEN gdrive_last_run_at IS NULL AND gdrive_last_status IS NULL THEN 'true' ELSE 'false' END FROM index_export_schedule WHERE id=200"))
                        .isEqualTo("true");
                assertThat(value(connection, "SELECT CASE WHEN to_regclass('public.index_export_schedule_time') IS NULL AND to_regclass('public.index_export_schedule_time_market') IS NULL THEN 'true' ELSE 'false' END"))
                        .isEqualTo("true");
                assertThat(value(connection, "SELECT count(*) FROM pg_constraint WHERE conrelid='index_export_schedule'::regclass AND conname IN ('ck_index_export_schedule_hour','ck_index_export_schedule_minute','ck_index_export_schedule_range')"))
                        .isEqualTo("3");
                assertThat(value(connection, "SELECT count(*) FROM pg_indexes WHERE tablename='index_export_schedule' AND indexname='idx_index_export_schedule_owner'"))
                        .isEqualTo("1");

                replaySql(connection);
                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule WHERE owner_user_id=11")).isEqualTo("2");
                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule_market")).isEqualTo("3");
            }
        }
    }

    @Test
    void ownerOverLimitRollsBackBeforeChangingLegacySchemaOrRows() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                createLegacySchema(sql);
                sql.execute("INSERT INTO index_export_schedule(id, owner_user_id, enabled, output_subpath) VALUES (500, 77, true, 'keep')");
                for (int i = 0; i < 11; i++) {
                    sql.execute("INSERT INTO index_export_schedule_time(schedule_id,run_hour,run_minute,enabled) VALUES (500,8," + i + ",true)");
                }

                assertThatThrownBy(() -> apply(postgres))
                        .hasMessageContaining("owner 77 has 11 index export schedules after migration; maximum is 10");

                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule_time WHERE schedule_id=500")).isEqualTo("11");
                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule WHERE id=500 AND output_subpath='keep' AND enabled"))
                        .isEqualTo("1");
                assertThat(value(connection, "SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='index_export_schedule' AND column_name='run_hour') THEN 'true' ELSE 'false' END"))
                        .isEqualTo("false");
                assertThat(value(connection, "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='index_export_schedule'::regclass AND conname='uq_index_export_schedule_owner') THEN 'true' ELSE 'false' END"))
                        .isEqualTo("true");
            }
        }
    }

    @Test
    void refusesToDropLegacyTimesWhenMarketMappingTableIsMissing() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = connect(postgres); Statement sql = connection.createStatement()) {
                createLegacySchema(sql);
                sql.execute("INSERT INTO index_export_schedule(id, owner_user_id, enabled, output_subpath) VALUES (600, 88, true, 'keep')");
                sql.execute("INSERT INTO index_export_schedule_time(id, schedule_id, run_hour, run_minute, enabled) VALUES (601, 600, 9, 30, true)");
                sql.execute("DROP TABLE index_export_schedule_time_market");

                assertThatThrownBy(() -> apply(postgres))
                        .hasMessageContaining("index_export_schedule_time_market is required to preserve selected markets during migration");

                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule_time WHERE id=601 AND schedule_id=600")).isEqualTo("1");
                assertThat(value(connection, "SELECT count(*) FROM index_export_schedule WHERE id=600 AND enabled AND output_subpath='keep'")).isEqualTo("1");
                assertThat(value(connection, "SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='index_export_schedule' AND column_name='run_hour') THEN 'true' ELSE 'false' END"))
                        .isEqualTo("false");
                assertThat(value(connection, "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='index_export_schedule'::regclass AND conname='uq_index_export_schedule_owner') THEN 'true' ELSE 'false' END"))
                        .isEqualTo("true");
                assertThat(value(connection, "SELECT CASE WHEN to_regclass('public.index_export_schedule_market') IS NULL AND to_regclass('public.index_export_schedule_time') IS NOT NULL THEN 'true' ELSE 'false' END"))
                        .isEqualTo("true");
            }
        }
    }

    private static Connection connect(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void createLegacySchema(Statement sql) throws Exception {
        sql.execute("CREATE TABLE index_export_schedule (id BIGSERIAL PRIMARY KEY, owner_user_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT false, output_subpath VARCHAR(255) NOT NULL DEFAULT 'input', range_months INTEGER, updated_at TIMESTAMP, gdrive_enabled BOOLEAN NOT NULL DEFAULT false, gdrive_subpath VARCHAR(512), gdrive_last_run_at TIMESTAMP, gdrive_last_status VARCHAR(512), CONSTRAINT uq_index_export_schedule_owner UNIQUE(owner_user_id), CONSTRAINT ck_index_export_schedule_range CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120))");
        sql.execute("CREATE TABLE index_export_schedule_time (id BIGSERIAL PRIMARY KEY, schedule_id BIGINT NOT NULL REFERENCES index_export_schedule(id) ON DELETE CASCADE, run_hour INTEGER NOT NULL DEFAULT 8, run_minute INTEGER NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT true, last_run_date DATE, last_run_at TIMESTAMP, last_run_status VARCHAR(500), updated_at TIMESTAMP, CONSTRAINT uq_index_export_schedule_time UNIQUE(schedule_id,run_hour,run_minute), CONSTRAINT ck_index_export_schedule_time_hour CHECK(run_hour BETWEEN 0 AND 23), CONSTRAINT ck_index_export_schedule_time_minute CHECK(run_minute BETWEEN 0 AND 59))");
        sql.execute("CREATE TABLE index_export_schedule_time_market (schedule_time_id BIGINT NOT NULL REFERENCES index_export_schedule_time(id) ON DELETE CASCADE, market VARCHAR(16) NOT NULL, PRIMARY KEY(schedule_time_id,market))");
    }

    private static void seedMigrationRows(Statement sql) throws Exception {
        sql.execute("INSERT INTO index_export_schedule(id,owner_user_id,enabled,output_subpath,range_months,gdrive_enabled,gdrive_subpath,gdrive_last_run_at,gdrive_last_status) VALUES (100,11,true,'index/archive',6,true,'reports/market',TIMESTAMP '2026-09-09 07:00:00','owner summary'),(200,22,true,'placeholder-folder',36,true,'placeholder-drive',TIMESTAMP '2026-09-08 07:00:00','cannot attribute')");
        sql.execute("INSERT INTO index_export_schedule_time(id,schedule_id,run_hour,run_minute,enabled,last_run_date,last_run_at,last_run_status,updated_at) VALUES (501,100,9,45,true,DATE '2026-09-11',TIMESTAMP '2026-09-11 09:46:00','later status',TIMESTAMP '2026-09-11 09:47:00'),(502,100,8,30,false,DATE '2026-09-10',TIMESTAMP '2026-09-10 08:31:00','first status',TIMESTAMP '2026-09-10 08:32:00')");
        sql.execute("INSERT INTO index_export_schedule_time_market VALUES (501,'TWSE'),(502,'TWSE'),(502,'TPEX')");
    }

    private static void apply(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = connect(postgres); ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changeLog = new FormattedSqlChangeLogParser().parse(MIGRATION, new ChangeLogParameters(), resources);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(changeLog, resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static void replaySql(Connection connection) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private static String value(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
