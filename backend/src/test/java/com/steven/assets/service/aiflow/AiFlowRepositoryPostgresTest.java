package com.steven.assets.service.aiflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.aiflow.*;
import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.srpp.SrppJcs;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static com.steven.assets.service.aiflow.AiFlowFixtures.*;
import static com.steven.assets.service.aiflow.AiFlowServiceTest.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真 PostgreSQL 原子追加、角色/tenant隔離、DB不可變及90日guard；不接觸production。 */
@Testcontainers
class AiFlowRepositoryPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("ai_flow_test").withUsername("assets").withPassword("test-only");
    JdbcTemplate jdbc;DataSourceTransactionManager manager;JdbcAiFlowRepository repository;AiFlowService service;String migration;
    @BeforeEach void setup()throws Exception{
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);repository=new JdbcAiFlowRepository(jdbc);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; CREATE TABLE app_user(id bigint PRIMARY KEY); INSERT INTO app_user VALUES(7),(8); CREATE TABLE api_error_log_operation(source varchar(16),operation_key varchar(96),operation_label varchar(96),display_order int,PRIMARY KEY(source,operation_key));");
        migration=new ClassPathResource("db/changelog/changes/v1.153.0-ai-flow-event-log.sql").getContentAsString(StandardCharsets.UTF_8);jdbc.execute(migration);
        service=service(7);
    }
    AiFlowService service(long owner){return new AiFlowService(repository,new AiFlowOwnerPort(){public long configuredOwner(){return owner;}public long readOwner(){return owner;}},new AiFlowCredentialService("c","h","r"),manager,Clock.systemUTC());}
    AppendResult append(List<Event> events){return service.ingest(List.of("r"),"application/json",raw(events).toString());}
    @Test void legacyOffsetSnapshotsReadAsInstantsAndOriginalReplayNeverChangesStoredHashes(){
        ObjectNode legacy=raw(complete());((ObjectNode)legacy.path("run")).put("startedAt",AT);
        for(var event:legacy.path("events"))if(!event.path("occurredAt").isNull())
            ((ObjectNode)event).put("occurredAt",OffsetDateTime.parse(event.path("occurredAt").asText()).withOffsetSameInstant(ZoneOffset.ofHours(8)).toString());
        String metadata=SrppJcs.canonicalize(JSON.createObjectNode().put("schemaVersion",1).set("run",legacy.path("run")));
        // 模擬升級前已入庫的合法 offset 字面快照，不透過新 typed DTO 再序列化。
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            repository.insertRun(new AiFlowRepository.StoredRun(7,RUN,"Codex",DATE,"09:05","CODEX_ROLLOUT","thread:turn",metadata,SrppJcs.sha256Hex(metadata)));
            for(var event:legacy.path("events")){
                String payload=SrppJcs.canonicalize(event);
                repository.insertEvent(7,RUN,new AiFlowRepository.StoredEvent(UUID.fromString(event.path("eventId").asText()),event.path("sequence").asInt(),payload,SrppJcs.sha256Hex(payload)));
            }
        });
        var beforeRuns=jdbc.queryForList("SELECT metadata_json,metadata_sha256 FROM ai_flow_run");
        var beforeEvents=jdbc.queryForList("SELECT event_id,payload_json,payload_sha256 FROM ai_flow_event ORDER BY event_id");
        var detail=service.detail(RUN.toString(),query(DATE.toString()));
        assertThat(detail.run().reportDate()).isEqualTo(DATE);assertThat(detail.run().startedAt()).isEqualTo(instant(AT));
        assertThat(detail.run().traceCoverage()).isEqualTo("FULL_OBSERVED");assertThat(detail.slowSteps().getFirst().durationMillis()).isEqualTo(3000);
        var replay=service.ingest(List.of("r"),"application/json",legacy.toString());
        assertThat(replay.status()).isEqualTo(200);assertThat(replay.ack().inserted()).isZero();assertThat(replay.ack().replayed()).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT metadata_json,metadata_sha256 FROM ai_flow_run")).isEqualTo(beforeRuns);
        assertThat(jdbc.queryForList("SELECT event_id,payload_json,payload_sha256 FROM ai_flow_event ORDER BY event_id")).isEqualTo(beforeEvents);
        assertThat(beforeRuns.getFirst().get("metadata_json").toString()).contains(AT);
        assertThat(beforeEvents).anySatisfy(row->assertThat(row.get("payload_json").toString()).contains("+08:00"));
    }
    @Test void durableAtomicAppendPureReplayAndMixedReplayStatuses(){
        var first=append(complete());assertThat(first.status()).isEqualTo(201);assertThat(first.ack().inserted()).isEqualTo(6);assertThat(count("ai_flow_event")).isEqualTo(6);
        var again=append(complete());assertThat(again.status()).isEqualTo(200);assertThat(again.ack().replayed()).isEqualTo(6);
        List<Event> mixed=new ArrayList<>(List.of(complete().getFirst()));mixed.add(event(7,"extra","OBSERVATION",null,List.of(),null));
        var result=append(mixed);assertThat(result.status()).isEqualTo(201);assertThat(result.ack().inserted()).isEqualTo(1);assertThat(result.ack().replayed()).isEqualTo(1);assertThat(count("ai_flow_event")).isEqualTo(7);
        assertThat(service.detail(RUN.toString(),query(DATE.toString())).run().traceCoverage()).isEqualTo("FULL_OBSERVED");
    }
    @Test void metadataAndEventConflictRollBackEveryNewEventAndNoOverwrite(){
        append(complete());ObjectNode altered=raw(List.of(event(7,"extra","OBSERVATION",null,List.of(),null),complete().getFirst()));
        ((ObjectNode)altered.path("events").get(1)).put("title","不同內容");
        assertThatThrownBy(()->service.ingest(List.of("r"),"application/json",altered.toString())).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("EVENT_CONFLICT"));assertThat(count("ai_flow_event")).isEqualTo(6);
        ObjectNode changed=raw(complete());((ObjectNode)changed.path("run")).put("workflowVersion","AI_FLOW_V2");
        final String changedVersion=changed.toString();assertThatThrownBy(()->service.ingest(List.of("r"),"application/json",changedVersion)).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RUN_METADATA_MISMATCH"));
        changed=raw(complete());((ObjectNode)changed.path("run")).put("runId",id(99));final String differentId=changed.toString();assertThatThrownBy(()->service.ingest(List.of("r"),"application/json",differentId)).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RUN_METADATA_MISMATCH"));assertThat(count("ai_flow_run")).isEqualTo(1);
    }
    @Test void missingParentAndCyclesRollBackRunAsWell(){
        for(List<Event> events:List.of(List.of(event(1,"x","START",null,List.of(id(9)),"DEPENDS_ON")),List.of(event(1,"x","START",null,List.of(id(2)),"BRANCH"),event(2,"y","END",null,List.of(id(1)),"BRANCH"))))
            assertThatThrownBy(()->append(events)).isInstanceOf(AiFlowProblem.class);
        assertThat(count("ai_flow_run")).isZero();assertThat(count("ai_flow_event")).isZero();
    }
    @Test void sixteenConcurrentUploadsSerializeReplayWithoutLostEvents()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(16);CountDownLatch ready=new CountDownLatch(16),go=new CountDownLatch(1);List<Future<AppendResult>> futures=new ArrayList<>();
        for(int i=0;i<16;i++)futures.add(pool.submit(()->{ready.countDown();go.await();return append(complete());}));assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
        int newCount=0;try{for(var future:futures)newCount+=future.get(30,TimeUnit.SECONDS).ack().inserted();}finally{pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
        assertThat(newCount).isEqualTo(6);assertThat(count("ai_flow_run")).isEqualTo(1);assertThat(count("ai_flow_event")).isEqualTo(6);
    }
    @Test void tenantAndConsumerAndDateAreMandatoryReadKeysAndReadTransactionCannotWrite(){
        append(complete());assertThat(service(8).list(query(DATE.toString())).runs()).isEmpty();
        assertThatThrownBy(()->service(8).detail(RUN.toString(),query(DATE.toString()))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("NOT_FOUND"));
        var other=query(DATE.toString());other.set("consumer","Claude");assertThat(service.list(other).runs()).isEmpty();assertThatThrownBy(()->service.detail(RUN.toString(),other)).isInstanceOf(AiFlowProblem.class);
        var yesterday=query(DATE.minusDays(1).toString());assertThatThrownBy(()->service.detail(RUN.toString(),yesterday)).isInstanceOf(AiFlowProblem.class);
        var read=new TransactionTemplate(manager);read.setReadOnly(true);List<RunView> readRuns=read.execute(tx->service.list(query(DATE.toString())).runs());assertThat(readRuns).hasSize(1);
        assertThat(count("ai_flow_run")).isEqualTo(1);assertThat(count("ai_flow_event")).isEqualTo(6);
        service(8).ingest(List.of("r"),"application/json",raw(complete()).toString());assertThat(count("ai_flow_run")).isEqualTo(2);
    }
    @Test void dbGuardsRejectUpdatesUnexpiredDeletesExpiredReplaysFutureAndForeignParent(){
        append(complete());
        for(String sql:List.of("UPDATE ai_flow_run SET report_date=report_date","UPDATE ai_flow_event SET sequence=sequence","DELETE FROM ai_flow_run","DELETE FROM ai_flow_event"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(RuntimeException.class);
        for(LocalDate date:List.of(DATE.minusDays(90),DATE.plusDays(1))){
            assertThatThrownBy(()->jdbc.update("INSERT INTO ai_flow_run SELECT owner_user_id,gen_random_uuid(),consumer,?,report_slot,source_kind,source_run_id||'x',metadata_json,metadata_sha256,received_at FROM ai_flow_run LIMIT 1",date)).isInstanceOf(RuntimeException.class);
            ObjectNode raw=raw(complete());((ObjectNode)raw.path("run")).put("reportDate",date.toString());assertThatThrownBy(()->service.ingest(List.of("r"),"application/json",raw.toString())).isInstanceOf(AiFlowProblem.class);
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO ai_flow_event SELECT 8,run_id,gen_random_uuid(),sequence,payload_json,payload_sha256,received_at FROM ai_flow_event LIMIT 1")).isInstanceOf(RuntimeException.class);
        assertThat(count("ai_flow_event")).isEqualTo(6);
    }
    @Test void startupPurgeCascadesExpiredOnlyAndPostPurgeDetailStill410()throws Exception{
        // 隔離fixture在guard安裝前保存停機留下的過期資料，不停用任何已安裝trigger。
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; CREATE TABLE app_user(id bigint PRIMARY KEY); INSERT INTO app_user VALUES(7); CREATE TABLE api_error_log_operation(source varchar(16),operation_key varchar(96),operation_label varchar(96),display_order int,PRIMARY KEY(source,operation_key));");
        jdbc.execute(migration.substring(0,migration.indexOf("CREATE OR REPLACE FUNCTION")));
        jdbc.update("INSERT INTO ai_flow_run(owner_user_id,run_id,consumer,report_date,report_slot,source_kind,source_run_id,metadata_json,metadata_sha256) VALUES(7,?,'Codex',?,'09:05','CODEX_ROLLOUT','old','{}',?)",RUN,DATE.minusDays(90),"a".repeat(64));
        jdbc.update("INSERT INTO ai_flow_event(owner_user_id,run_id,event_id,sequence,payload_json,payload_sha256) VALUES(7,?,?,0,'{}',?)",RUN,UUID.fromString(id(1)),"a".repeat(64));jdbc.execute(migration);
        new AiFlowRetentionService(repository,manager).startup();assertThat(count("ai_flow_run")).isZero();assertThat(count("ai_flow_event")).isZero();
        assertThatThrownBy(()->service.detail(RUN.toString(),query(DATE.minusDays(90).toString()))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RETENTION_EXPIRED"));
        assertThatThrownBy(()->service.detail(RUN.toString(),query(DATE.toString()))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("NOT_FOUND"));
    }
    @Test void dbAuthorityRejectsMidnightRaceWhenApplicationClockIsOneDateBehind(){
        var behind=new AiFlowService(repository,new AiFlowOwnerPort(){public long configuredOwner(){return 7;}public long readOwner(){return 7;}},
                new AiFlowCredentialService("c","h","r"),manager,Clock.fixed(DATE.minusDays(1).atTime(23,59).atZone(ZoneId.of("Asia/Taipei")).toInstant(),ZoneOffset.UTC));
        ObjectNode batch=raw(complete());((ObjectNode)batch.path("run")).put("reportDate",DATE.minusDays(90).toString());
        assertThatThrownBy(()->behind.ingest(List.of("r"),"application/json",batch.toString())).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RETENTION_EXPIRED"));
        assertThatThrownBy(()->behind.detail(RUN.toString(),query(DATE.minusDays(90).toString()))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RETENTION_EXPIRED"));
        assertThat(count("ai_flow_run")).isZero();assertThat(count("ai_flow_event")).isZero();
    }
    @Test void appendCoverageCanRecoverButSourceChangedNeverReplacesOldEvents(){
        List<Event> partial=new ArrayList<>(complete().subList(0,2));partial.add(coverage(7,40,40,"b".repeat(64),"TRACE_PARTIAL"));append(partial);
        assertThat(service.detail(RUN.toString(),query(DATE.toString())).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");
        append(complete().subList(2,complete().size()));assertThat(service.detail(RUN.toString(),query(DATE.toString())).run().traceCoverage()).isEqualTo("FULL_OBSERVED");
        var changed=List.of(coverage(8,20,20,"c".repeat(64),"SOURCE_TRACE_CHANGED"));assertThat(append(changed).ack().inserted()).isEqualTo(1);assertThat(append(changed).ack().replayed()).isEqualTo(1);
        append(List.of(coverage(9,200,200,"d".repeat(64),"TRACE_COMPLETE")));
        assertThat(service.detail(RUN.toString(),query(DATE.toString())).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");assertThat(count("ai_flow_event")).isEqualTo(9);
    }
    @Test void migrationReplayPreservesImmutableRowsAndBackupExcludesOnlyTheirData()throws Exception{
        append(complete());jdbc.execute(migration);assertThat(count("ai_flow_run")).isEqualTo(1);assertThat(count("api_error_log_operation")).isEqualTo(1);
        String restore="ai_flow_restore_"+UUID.randomUUID().toString().replace("-","");
        assertThat(DB.execInContainer("createdb","-U",DB.getUsername(),restore).getExitCode()).isZero();
        assertThat(DB.execInContainer("pg_dump","-U",DB.getUsername(),"-d",DB.getDatabaseName(),"--format=custom","--no-owner","--no-acl","--exclude-table-data=public.ai_flow_run","--exclude-table-data=public.ai_flow_event","-f","/tmp/ai-flow-test.dump").getExitCode()).isZero();
        assertThat(DB.execInContainer("pg_restore","-U",DB.getUsername(),"-d",restore,"--no-owner","--no-acl","/tmp/ai-flow-test.dump").getExitCode()).isZero();
        JdbcTemplate restored=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl().replace("/"+DB.getDatabaseName(),"/"+restore),DB.getUsername(),DB.getPassword()));
        assertThat(restored.queryForObject("SELECT count(*) FROM ai_flow_run",Integer.class)).isZero();assertThat(restored.queryForObject("SELECT count(*) FROM ai_flow_event",Integer.class)).isZero();
        assertThat(restored.queryForObject("SELECT count(*) FROM app_user",Integer.class)).isEqualTo(2);assertThat(restored.queryForObject("SELECT count(*) FROM api_error_log_operation",Integer.class)).isEqualTo(1);
    }
    int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
}
