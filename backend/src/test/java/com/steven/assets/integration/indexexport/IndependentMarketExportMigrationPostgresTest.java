package com.steven.assets.integration.indexexport;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** Isolated PostgreSQL migration fixtures for both features, including all-or-nothing replay. */
@Testcontainers
class IndependentMarketExportMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("market_migration_512").withUsername("fixture").withPassword("fixture-only");
    static final String PATH="db/changelog/changes/v1.158.0-independent-market-export-schedules.sql";
    static String migration() throws Exception {try(var stream=IndependentMarketExportMigrationPostgresTest.class.getClassLoader().getResourceAsStream(PATH)){
        return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}}
    Connection connect() throws Exception{return DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
    @BeforeEach void schema() throws Exception {try(var c=connect();var q=c.createStatement()){
        q.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        q.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY); INSERT INTO app_user VALUES (11),(22),(33),(44)");
        for(String p:new String[]{"commodity_export_schedule","exchange_rate_export_schedule"}){
            String child=p.equals("commodity_export_schedule")?"commodity_export_schedule_time":"exchange_rate_export_schedule_time";
            String unique=p.equals("commodity_export_schedule")?"uq_commodity_export_schedule_owner":"uq_exchange_rate_export_schedule_owner";
            q.execute("CREATE TABLE "+p+"(id BIGSERIAL PRIMARY KEY,owner_user_id BIGINT NOT NULL REFERENCES app_user(id),enabled BOOLEAN NOT NULL DEFAULT FALSE,"
                    +"run_hour INTEGER NOT NULL DEFAULT 8 CHECK(run_hour BETWEEN 0 AND 23),run_minute INTEGER NOT NULL DEFAULT 0 CHECK(run_minute BETWEEN 0 AND 59),"
                    +"output_subpath VARCHAR(255) NOT NULL DEFAULT 'input',range_months INTEGER CHECK(range_months IS NULL OR range_months BETWEEN 1 AND 120),last_run_date DATE,last_run_at TIMESTAMP,last_run_status VARCHAR(500),"
                    +"gdrive_enabled BOOLEAN NOT NULL DEFAULT FALSE,gdrive_subpath VARCHAR(512),gdrive_last_run_at TIMESTAMP,gdrive_last_status VARCHAR(512),updated_at TIMESTAMP,"
                    +"CONSTRAINT "+unique+" UNIQUE(owner_user_id))");
            q.execute("CREATE TABLE "+child+"(id BIGSERIAL PRIMARY KEY,schedule_id BIGINT NOT NULL REFERENCES "+p+"(id) ON DELETE CASCADE,"
                    +"run_hour INTEGER NOT NULL,run_minute INTEGER NOT NULL,enabled BOOLEAN NOT NULL,last_run_date DATE,last_run_at TIMESTAMP,last_run_status VARCHAR(500),updated_at TIMESTAMP)");
        }
    }}
    void seed(Statement q,String p)throws Exception {
        String child=p.equals("commodity_export_schedule")?"commodity_export_schedule_time":"exchange_rate_export_schedule_time";
        q.execute("INSERT INTO "+p+"(id,owner_user_id,enabled,run_hour,run_minute,output_subpath,last_run_date,last_run_status,gdrive_enabled,gdrive_subpath,gdrive_last_status) VALUES "
                +"(100,11,true,23,30,'shared','2026-10-24','parent',true,'drive/shared','shared-upload'),"
                +"(200,22,false,23,30,'disabled','2026-10-24','parent',true,'drive/disabled','shared-upload'),"
                +"(300,33,true,17,45,'no-child','2026-10-25','no-child-status',true,'drive/no-child','shared-upload')");
        q.execute("UPDATE "+p+" SET range_months=17 WHERE id IN (100,200)");
        q.execute("INSERT INTO "+child+"(id,schedule_id,run_hour,run_minute,enabled,last_run_date,last_run_at,last_run_status) VALUES "
                +"(2,100,12,30,true,'2026-10-25','2026-10-25 12:30:01','late'),"
                +"(1,100,8,15,false,'2026-10-26','2026-10-26 08:15:01','early'),"
                +"(3,200,9,45,true,'2026-10-26','2026-10-26 09:45:01','disabled-child')");
    }
    String value(Statement q,String sql)throws Exception{try(var r=q.executeQuery(sql)){return r.next()?r.getString(1):null;}}
    @Test void splitsEachChildAndPreservesOriginalEnabledSettingsGuardsAndNoChildRows()throws Exception{
        try(var c=connect();var q=c.createStatement()){
            for(String p:new String[]{"commodity_export_schedule","exchange_rate_export_schedule"})seed(q,p);
            q.execute(migration());
            for(String p:new String[]{"commodity_export_schedule","exchange_rate_export_schedule"}){
                assertThat(value(q,"SELECT count(*) FROM "+p)).isEqualTo("4");
                assertThat(value(q,"SELECT run_hour||':'||run_minute FROM "+p+" WHERE id=100")).isEqualTo("8:15");
                assertThat(value(q,"SELECT enabled::text FROM "+p+" WHERE id=100")).isEqualTo("false");
                assertThat(value(q,"SELECT last_run_date FROM "+p+" WHERE id=100")).isEqualTo("2026-10-26");
                assertThat(value(q,"SELECT last_run_status FROM "+p+" WHERE id=100")).isEqualTo("early");
                assertThat(value(q,"SELECT count(*) FROM "+p+" WHERE owner_user_id=11 AND run_hour=12 AND enabled AND last_run_status='late' AND output_subpath='shared' AND gdrive_enabled AND gdrive_subpath='drive/shared' AND name IS NULL AND range_months=17")).isEqualTo("1");
                assertThat(value(q,"SELECT range_months FROM "+p+" WHERE id=100")).isEqualTo("17");
                assertThat(value(q,"SELECT range_months FROM "+p+" WHERE id=300")).isNull();
                assertThat(value(q,"SELECT id FROM "+p+" WHERE owner_user_id=11 AND run_hour=12")).isNotEqualTo("100");
                assertThat(value(q,"SELECT enabled::text FROM "+p+" WHERE id=200")).isEqualTo("false");
                assertThat(value(q,"SELECT run_hour||':'||run_minute||':'||enabled FROM "+p+" WHERE id=300")).isEqualTo("17:45:true");
                assertThat(value(q,"SELECT last_run_date||':'||last_run_status FROM "+p+" WHERE id=300")).isEqualTo("2026-10-25:no-child-status");
                assertThat(value(q,"SELECT count(*) FROM "+p+" WHERE gdrive_last_run_at IS NOT NULL OR gdrive_last_status IS NOT NULL OR name IS NOT NULL")).isEqualTo("0");
                assertThat(value(q,"SELECT count(*) FROM pg_constraint WHERE conrelid='"+p+"'::regclass AND contype='f'")).isEqualTo("1");
                assertThat(value(q,"SELECT count(*) FROM pg_constraint WHERE conrelid='"+p+"'::regclass AND contype='c'")).isEqualTo("3");
                assertThat(value(q,"SELECT count(*) FROM pg_constraint WHERE conrelid='"+p+"'::regclass AND contype='u'")).isEqualTo("0");
            }
            assertThat(value(q,"SELECT to_regclass('commodity_export_schedule_time')")).isNull();assertThat(value(q,"SELECT to_regclass('exchange_rate_export_schedule_time')")).isNull();
        }
    }
    @Test void replayPreservesNewNamesAndDriveExecutionResultsAndNeverSplitsAgain()throws Exception{
        try(var c=connect();var q=c.createStatement()){
            for(String p:new String[]{"commodity_export_schedule","exchange_rate_export_schedule"})seed(q,p);
            q.execute(migration());
            for(String p:new String[]{"commodity_export_schedule","exchange_rate_export_schedule"})q.execute("UPDATE "+p+" SET name='new',gdrive_last_status='new-upload',gdrive_last_run_at='2026-10-26 12:00:00' WHERE id=100");
            q.execute(migration());
            for(String p:new String[]{"commodity_export_schedule","exchange_rate_export_schedule"}){
                assertThat(value(q,"SELECT count(*) FROM "+p)).isEqualTo("4");
                assertThat(value(q,"SELECT name||':'||gdrive_last_status FROM "+p+" WHERE id=100")).isEqualTo("new:new-upload");
            }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"commodity_export_schedule","exchange_rate_export_schedule"})
    void overLimitInEitherFeatureRollsBackBothSchemasAndEveryRow(String overLimitParent)throws Exception{
        String overLimitChild=overLimitParent+"_time";
        try(var c=connect();var q=c.createStatement()){
            seed(q,"commodity_export_schedule");seed(q,"exchange_rate_export_schedule");
            q.execute("INSERT INTO "+overLimitParent+"(id,owner_user_id) VALUES (400,44)");
            q.execute("INSERT INTO "+overLimitChild+"(id,schedule_id,run_hour,run_minute,enabled) SELECT 1000+n,400,10,n,true FROM generate_series(0,10) n");
            assertThatThrownBy(()->q.execute(migration())).hasMessageContaining("maximum is 10");
            assertThat(value(q,"SELECT count(*) FROM commodity_export_schedule")).isEqualTo(overLimitParent.equals("commodity_export_schedule")?"4":"3");
            assertThat(value(q,"SELECT count(*) FROM "+overLimitChild+" WHERE schedule_id=400")).isEqualTo("11");
            assertThat(value(q,"SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('commodity_export_schedule','exchange_rate_export_schedule') AND column_name='name'")).isEqualTo("0");
            assertThat(value(q,"SELECT count(*) FROM pg_constraint WHERE conname IN ('uq_commodity_export_schedule_owner','uq_exchange_rate_export_schedule_owner')")).isEqualTo("2");
            assertThat(value(q,"SELECT gdrive_last_status FROM commodity_export_schedule WHERE id=100")).isEqualTo("shared-upload");
        }
    }
    @Test void liquibaseParsesExactlyOneUnsplitPostgresDoStatement()throws Exception{
        var accessor=new liquibase.resource.ClassLoaderResourceAccessor();var parser=new liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser();
        var log=parser.parse(PATH,new liquibase.changelog.ChangeLogParameters(),accessor);
        assertThat(log.getChangeSets()).hasSize(1);var change=(liquibase.change.core.RawSQLChange)log.getChangeSets().getFirst().getChanges().getFirst();
        assertThat(change.isSplitStatements()).isFalse();assertThat(change.getSql()).contains("DO $$","DROP TABLE public.%I");
    }
}
