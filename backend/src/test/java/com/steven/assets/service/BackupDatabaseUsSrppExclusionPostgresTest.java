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

/** Synthetic, isolated PG dump/restore only; never invokes a production backup, rclone or Drive. */
@Testcontainers
class BackupDatabaseUsSrppExclusionPostgresTest {
    private static final String IMPORT="srpp_us_watch_import", STATE="srpp_us_watch_state";
    private static final String PACKAGE="srpp_us_report_package", TRANSITION="srpp_us_watch_transition";
    private static final String REPORT_SENTINEL="SYNTHETIC_REPORT_FINANCIAL_RAW_DO_NOT_EXPORT";
    private static final String TRANSITION_SENTINEL="SYNTHETIC_TRANSITION_RAW_DO_NOT_EXPORT";
    private static final String TABLE_SET="('"+IMPORT+"','"+STATE+"','"+PACKAGE+"','"+TRANSITION+"')";
    private static final String RELATION_SET="('"+IMPORT+"'::regclass,'"+STATE+"'::regclass,'"+PACKAGE+"'::regclass,'"+TRANSITION+"'::regclass)";
    private static final String COLUMNS="SELECT table_name,column_name,ordinal_position,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public' AND table_name IN "+TABLE_SET+" ORDER BY table_name,ordinal_position";
    private static final String CONSTRAINTS="SELECT conrelid::regclass::text AS tbl,conname,contype,CASE WHEN contype='c' THEN NULL ELSE pg_get_constraintdef(oid) END AS def FROM pg_constraint WHERE conrelid IN "+RELATION_SET+" ORDER BY 1,2";
    private static final String TRIGGERS="SELECT tgname,pg_get_triggerdef(oid) AS def FROM pg_trigger WHERE NOT tgisinternal AND tgrelid IN "+RELATION_SET+" ORDER BY 1";

    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("task542_backup_test").withUsername("assets").withPassword("test-only");
    private JdbcTemplate jdbc;
    private final String dumpFile="/tmp/task542-backup-"+UUID.randomUUID()+".dump";

    @BeforeEach void setup()throws Exception{
        jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
        jdbc.execute("DROP SCHEMA public CASCADE;CREATE SCHEMA public;"
                +"CREATE TABLE app_user(id bigint PRIMARY KEY);INSERT INTO app_user VALUES(7),(8);"
                +"CREATE TABLE srpp_policy_registry(policy_bundle_sha256 char(64) PRIMARY KEY);INSERT INTO srpp_policy_registry VALUES(repeat('a',64));"
                +"CREATE TABLE api_error_log_operation(source varchar(16),operation_key varchar(96),operation_label varchar(96),display_order int,PRIMARY KEY(source,operation_key));"
                +"CREATE FUNCTION reject_srpp_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable';END $$;");
        jdbc.execute(new ClassPathResource("db/changelog/changes/v1.160.0-srpp-us-report-calculations.sql").getContentAsString(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO srpp_us_watch_import(owner_user_id,consumer,market,ledger_sha256,watch_policy_sha256,evidence_jcs,imported_at) VALUES(7,'Codex','US',repeat('b',64),repeat('c',64),?,TIMESTAMPTZ '2026-11-03 14:00:00+00')","{\"sentinel\":\"CANONICAL_IMPORT_TO_KEEP\"}");
        jdbc.update("INSERT INTO srpp_us_watch_state(owner_user_id,consumer,market,symbol,version,state_jcs,state_sha256,updated_at) VALUES(7,'Codex','US','SYNTH',1,?,repeat('d',64),TIMESTAMPTZ '2026-11-03 14:00:00+00')","{\"sentinel\":\"CANONICAL_STATE_TO_KEEP\"}");
        insertReportAndTransition();
    }

    @AfterEach void cleanup()throws Exception{DB.execInContainer("rm","-f",dumpFile);}

    @Test void dumpRetainsAllUsSchemasAndCanonicalStateButOmitsFinancialPackageAndDependentTransitionData()throws Exception{
        dump();
        ExecResult toc=DB.execInContainer("pg_restore","--list",dumpFile);assertThat(toc.getExitCode()).as(toc.getStderr()).isZero();
        for(String table:List.of(IMPORT,STATE,PACKAGE,TRANSITION))assertThat(toc.getStdout()).contains("TABLE public "+table);
        assertThat(toc.getStdout()).contains("TABLE DATA public "+IMPORT,"TABLE DATA public "+STATE,"TABLE DATA public app_user");
        assertThat(toc.getStdout()).doesNotContain("TABLE DATA public "+PACKAGE,"TABLE DATA public "+TRANSITION);
        ExecResult data=DB.execInContainer("pg_restore","--data-only","--file=-",dumpFile);assertThat(data.getExitCode()).as(data.getStderr()).isZero();
        assertThat(data.getStdout()).contains("CANONICAL_IMPORT_TO_KEEP","CANONICAL_STATE_TO_KEEP");
        assertThat(data.getStdout()).doesNotContain(REPORT_SENTINEL,TRANSITION_SENTINEL);
        for(String table:List.of(IMPORT,STATE,PACKAGE,TRANSITION))assertThat(count(table)).isEqualTo(1);
    }

    @Test void restoreOverExistingRowsKeepsCanonicalStateAndAllConstraintsWithoutDanglingPackageForeignKeys()throws Exception{
        List<Map<String,Object>> columns=jdbc.queryForList(COLUMNS),constraints=jdbc.queryForList(CONSTRAINTS),triggers=jdbc.queryForList(TRIGGERS);
        List<Map<String,Object>> imported=jdbc.queryForList("SELECT * FROM "+IMPORT),states=jdbc.queryForList("SELECT * FROM "+STATE);
        assertThat(triggers).hasSize(4);dump();
        ExecResult restored=DB.execInContainer("pg_restore","-U",DB.getUsername(),"-d",DB.getDatabaseName(),"--clean","--if-exists","--no-owner","--no-acl",dumpFile);
        assertThat(restored.getExitCode()).as(restored.getStderr()).isZero();
        assertThat(count(PACKAGE)).isZero();assertThat(count(TRANSITION)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM "+IMPORT)).isEqualTo(imported);assertThat(jdbc.queryForList("SELECT * FROM "+STATE)).isEqualTo(states);
        assertThat(jdbc.queryForList(COLUMNS)).isEqualTo(columns);assertThat(jdbc.queryForList(CONSTRAINTS)).isEqualTo(constraints);assertThat(jdbc.queryForList(TRIGGERS)).isEqualTo(triggers);
        assertThat(jdbc.queryForList("SELECT id FROM app_user ORDER BY id",Long.class)).containsExactly(7L,8L);assertThat(count("srpp_policy_registry")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid='srpp_us_watch_transition'::regclass AND confrelid='srpp_us_report_package'::regclass",Integer.class)).isEqualTo(1);
        insertReportAndTransition();
        assertThatThrownBy(()->jdbc.update("DELETE FROM "+TRANSITION)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE "+PACKAGE+" SET consumer='Claude'")).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE "+IMPORT+" SET ledger_sha256=repeat('f',64)")).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE "+STATE+" SET version=version+2")).hasMessageContaining("US_WATCH_VERSION_OR_IDENTITY_CONFLICT");
    }

    private void dump()throws Exception{
        List<String> command=new ArrayList<>(List.of("pg_dump","-U",DB.getUsername(),"-d",DB.getDatabaseName(),"--format=custom","--compress=9","--no-owner","--no-acl"));
        for(String table:ProcessBackupDatabaseProcess.EXCLUDED_DATA_TABLES)command.add("--exclude-table-data="+table);
        command.add("-f");command.add(dumpFile);ExecResult result=DB.execInContainer(command.toArray(String[]::new));assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private void insertReportAndTransition(){
        jdbc.update("INSERT INTO srpp_us_report_package(id,owner_user_id,consumer,market,session_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,source_fingerprint_sha256,input_snapshot_sha256,content_sha256,content_jcs,published_at) VALUES('00000000-0000-0000-0000-000000000051',7,'Codex','US',DATE '2026-11-03','US_OPEN_0945',repeat('a',64),repeat('e',64),'SYNTHETIC',repeat('f',64),repeat('1',64),repeat('2',64),?,TIMESTAMPTZ '2026-11-03 14:01:00+00')","{\"sentinel\":\""+REPORT_SENTINEL+"\"}");
        jdbc.update("INSERT INTO srpp_us_watch_transition(id,package_id,owner_user_id,consumer,market,symbol,version_before,version_after,state_before_jcs,state_after_jcs,state_before_sha256,state_after_sha256,source_fingerprint_sha256,calculator_version,created_at) VALUES('00000000-0000-0000-0000-000000000052','00000000-0000-0000-0000-000000000051',7,'Codex','US','SYNTH',1,2,?,?,repeat('3',64),repeat('4',64),repeat('5',64),'SYNTHETIC',TIMESTAMPTZ '2026-11-03 14:01:00+00')","{\"sentinel\":\""+TRANSITION_SENTINEL+"\"}","{\"sentinel\":\""+TRANSITION_SENTINEL+"\"}");
    }
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
}
