package com.steven.assets.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 192／Task 498：以真 PostgreSQL 驗證 Drive 備份排除 SRPP 報告計算 base／package 的 data 後，
 * 兩表 schema、immutable trigger 與複合 FK 仍完整，且對「已有資料」的 DB 以 production 相同的
 * {@code pg_restore --clean --if-exists} 還原時，兩表被清空重建、其餘表資料不受影響。
 *
 * <p>只使用 Testcontainers 自起的暫時 PostgreSQL，不連 production DB、rclone 或 Google Drive。
 * 無 Docker 時刻意失敗而非靜默略過，因為這項證據必須實跑。</p>
 */
@Testcontainers
class BackupDatabaseSrppExclusionPostgresTest {

    private static final String BASE = "srpp_report_calculation_base";
    private static final String PACKAGE = "srpp_report_calculation_package";
    /**
     * 約束名稱與型別逐一比對；PK／UNIQUE／FK 另比對完整定義。CHECK 的定義文字不比對：
     * pg_restore 會把 {@code IN (...)} 重新解析成語意相同但文字不同的 ANY(ARRAY[...])。
     */
    private static final String CONSTRAINTS_SQL =
            "SELECT conrelid::regclass::text AS tbl,conname,contype,"
                    + "CASE WHEN contype='c' THEN NULL ELSE pg_get_constraintdef(oid) END AS def"
                    + " FROM pg_constraint WHERE conrelid IN ('" + BASE + "'::regclass,'" + PACKAGE
                    + "'::regclass) ORDER BY 1,2";

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("task498_test").withUsername("assets").withPassword("test-only");

    private JdbcTemplate jdbc;
    private final String dumpFile = "/tmp/task498-" + UUID.randomUUID() + ".dump";

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
                + " CREATE TABLE api_error_log_operation(source varchar(16),operation_key varchar(96),"
                + "operation_label varchar(96),display_order int,PRIMARY KEY(source,operation_key));"
                + " CREATE TABLE app_user(id bigint PRIMARY KEY);"
                + " INSERT INTO app_user VALUES(7),(8);"
                + " CREATE TABLE srpp_policy_registry(policy_bundle_sha256 char(64) PRIMARY KEY);"
                + " CREATE FUNCTION reject_srpp_immutable_update() RETURNS trigger LANGUAGE plpgsql"
                + " AS $$ BEGIN RAISE EXCEPTION 'immutable'; END $$;");
        jdbc.execute(new ClassPathResource("db/changelog/changes/v1.150.0-srpp-report-calculations.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO srpp_policy_registry VALUES(repeat('a',64))");
        insertSentinelReportRows();
    }

    @AfterEach
    void removeDumpFile() throws Exception {
        DB.execInContainer("rm", "-f", dumpFile);
    }

    @Test
    void dumpWithProductionExclusionsKeepsSchemaButOmitsTheTwoTablesData() throws Exception {
        assertThat(count(BASE)).isEqualTo(1);
        assertThat(count(PACKAGE)).isEqualTo(1);

        dump();

        ExecResult toc = DB.execInContainer("pg_restore", "--list", dumpFile);
        assertThat(toc.getExitCode()).as(toc.getStderr()).isZero();
        String listing = toc.getStdout();
        assertThat(listing).contains("TABLE public " + BASE, "TABLE public " + PACKAGE);
        assertThat(listing).contains("TABLE DATA public app_user");
        assertThat(listing).doesNotContain("TABLE DATA public " + BASE);
        assertThat(listing).doesNotContain("TABLE DATA public " + PACKAGE);
    }

    @Test
    void restoreOverExistingDataEmptiesOnlyTheTwoTablesAndKeepsSchemaTriggersAndForeignKey() throws Exception {
        List<Map<String, Object>> columnsBefore = jdbc.queryForList(
                "SELECT table_name,column_name,ordinal_position,data_type,is_nullable,column_default"
                        + " FROM information_schema.columns WHERE table_schema='public' AND table_name IN"
                        + " ('" + BASE + "','" + PACKAGE + "') ORDER BY table_name,ordinal_position");
        List<Map<String, Object>> constraintsBefore = jdbc.queryForList(CONSTRAINTS_SQL);
        List<Map<String, Object>> indexesBefore = jdbc.queryForList(
                "SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename IN"
                        + " ('" + BASE + "','" + PACKAGE + "') ORDER BY 1");
        List<Map<String, Object>> triggersBefore = jdbc.queryForList(
                "SELECT tgname,pg_get_triggerdef(oid) AS def FROM pg_trigger WHERE NOT tgisinternal"
                        + " AND tgrelid IN ('" + BASE + "'::regclass,'" + PACKAGE + "'::regclass) ORDER BY 1");
        assertThat(columnsBefore).isNotEmpty();
        assertThat(constraintsBefore).isNotEmpty();
        assertThat(indexesBefore).isNotEmpty();
        assertThat(triggersBefore).hasSize(2);

        dump();
        // 還原目標是「已有資料」的同一 DB：兩個 SRPP 表此時各有 1 筆 sentinel。
        assertThat(count(BASE)).isEqualTo(1);
        assertThat(count(PACKAGE)).isEqualTo(1);

        ExecResult restore = DB.execInContainer("pg_restore", "-U", DB.getUsername(), "-d",
                DB.getDatabaseName(), "--clean", "--if-exists", "--no-owner", "--no-acl", dumpFile);
        assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();

        assertThat(count(BASE)).isZero();
        assertThat(count(PACKAGE)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM app_user ORDER BY id", Long.class))
                .containsExactly(7L, 8L);
        assertThat(count("srpp_policy_registry")).isEqualTo(1);

        assertThat(jdbc.queryForList(
                "SELECT table_name,column_name,ordinal_position,data_type,is_nullable,column_default"
                        + " FROM information_schema.columns WHERE table_schema='public' AND table_name IN"
                        + " ('" + BASE + "','" + PACKAGE + "') ORDER BY table_name,ordinal_position"))
                .isEqualTo(columnsBefore);
        assertThat(jdbc.queryForList(CONSTRAINTS_SQL)).isEqualTo(constraintsBefore);
        assertThat(jdbc.queryForList(
                "SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename IN"
                        + " ('" + BASE + "','" + PACKAGE + "') ORDER BY 1"))
                .isEqualTo(indexesBefore);
        assertThat(jdbc.queryForList(
                "SELECT tgname,pg_get_triggerdef(oid) AS def FROM pg_trigger WHERE NOT tgisinternal"
                        + " AND tgrelid IN ('" + BASE + "'::regclass,'" + PACKAGE + "'::regclass) ORDER BY 1"))
                .isEqualTo(triggersBefore);

        assertThat(jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal ORDER BY 1", String.class))
                .contains("trg_srpp_report_base_no_update", "trg_srpp_report_package_no_update");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid='" + PACKAGE
                        + "'::regclass AND conname='srpp_report_package_base_identity_fk'", Integer.class))
                .isEqualTo(1);

        // 還原後的兩表仍可寫入、且 immutable trigger 仍會拒絕 UPDATE。
        insertSentinelReportRows();
        assertThat(count(BASE)).isEqualTo(1);
        assertThat(count(PACKAGE)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + BASE + " SET report_slot='12:00'"))
                .hasMessageContaining("immutable");
    }

    private void dump() throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "pg_dump", "-U", DB.getUsername(), "-d", DB.getDatabaseName(),
                "--format=custom", "--no-owner", "--no-acl"));
        for (String table : ProcessBackupDatabaseProcess.EXCLUDED_DATA_TABLES) {
            command.add("--exclude-table-data=" + table);
        }
        command.add("-f");
        command.add(dumpFile);
        ExecResult result = DB.execInContainer(command.toArray(String[]::new));
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private void insertSentinelReportRows() {
        jdbc.update("INSERT INTO " + BASE + "(id,owner_user_id,trading_date,report_slot,policy_bundle_sha256,"
                + "swagger_sha256,calculator_version,source_fingerprint_sha256,captured_at,asset_generated_at,"
                + "asset_snapshot_id,valuation_capture_id,input_jcs,source_vector_jcs,shared_modules_jcs,"
                + "shared_content_sha256,created_at) VALUES("
                + "'00000000-0000-0000-0000-000000000001',7,DATE '2026-10-14','09:05',repeat('a',64),"
                + "repeat('b',64),'task498-v1',repeat('c',64),TIMESTAMPTZ '2026-10-14 01:05:00+00',"
                + "TIMESTAMPTZ '2026-10-14 01:04:00+00',1,'00000000-0000-0000-0000-0000000000f1',"
                + "'{\"k\":1}','{\"k\":2}','{\"k\":3}',repeat('d',64),TIMESTAMPTZ '2026-10-14 01:05:01+00')");
        jdbc.update("INSERT INTO " + PACKAGE + "(id,shared_calculation_id,owner_user_id,consumer,trading_date,"
                + "report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,consumer_state_sha256,"
                + "status,input_snapshot_sha256,content_jcs,content_sha256,published_at) VALUES("
                + "'00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',7,'Codex',"
                + "DATE '2026-10-14','09:05',repeat('a',64),repeat('b',64),'task498-v1',repeat('e',64),"
                + "'FINAL',repeat('f',64),'{\"k\":4}',repeat('0',64),TIMESTAMPTZ '2026-10-14 01:05:02+00')");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
