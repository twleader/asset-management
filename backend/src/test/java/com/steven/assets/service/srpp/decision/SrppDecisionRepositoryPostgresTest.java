package com.steven.assets.service.srpp.decision;

import com.steven.assets.repository.SrppDecisionRepository;
import com.steven.assets.repository.SrppDecisionRepository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.srpp.*;
import com.steven.assets.service.srpp.SrppCaptureProblem;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.node.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static com.steven.assets.service.srpp.decision.DailyDecisionCalculatorTest.*;

/** Real PostgreSQL isolation, immutable FINAL, claim fencing and twenty simultaneous callers. */
@Testcontainers
class SrppDecisionRepositoryPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("task488_test").withUsername("assets").withPassword("test-only");
    JdbcTemplate jdbc;DataSourceTransactionManager manager;SrppDecisionRepository repository;DailyDecisionCaptureService service;AtomicInteger captures;CurrentUserContext owner;MarketDataService market;SrppPolicyRegistryService registry;PublishedSwaggerIdentity swagger;DecisionInputCapturePort capture;
    @BeforeEach void setup()throws Exception{DriverManagerDataSource ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; CREATE TABLE app_user(id bigint PRIMARY KEY); INSERT INTO app_user VALUES(7),(8); CREATE TABLE srpp_policy_registry(policy_bundle_sha256 char(64) PRIMARY KEY); CREATE FUNCTION reject_srpp_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable'; END $$;");jdbc.update("INSERT INTO srpp_policy_registry VALUES(?)",POLICY.bundleHash());jdbc.execute(new ClassPathResource("db/changelog/changes/v1.149.0-srpp-decision-engine.sql").getContentAsString(StandardCharsets.UTF_8));repository=new SrppDecisionRepository(jdbc);owner=mock(CurrentUserContext.class);when(owner.hasUser()).thenReturn(true);when(owner.getStatus()).thenReturn("ACTIVE");when(owner.getEffectiveUserId()).thenReturn(7L);market=mock(MarketDataService.class);when(market.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true));registry=mock(SrppPolicyRegistryService.class);when(registry.find(POLICY.bundleHash())).thenReturn(Optional.of(mock(SupportedPolicy.class)));swagger=mock(PublishedSwaggerIdentity.class);when(swagger.sha256()).thenReturn(Optional.of(DailyDecisionCaptureServiceTest.SWAGGER));captures=new AtomicInteger();capture=(id,request,prior)->{captures.incrementAndGet();return DailyDecisionCaptureServiceTest.frozen();};service=new DailyDecisionCaptureService(owner,market,registry,swagger,new DecisionPolicyAuthority(),repository,capture,manager,Clock.fixed(NOW.plusSeconds(1),ZoneOffset.UTC),"postgres-test");}
    @Test void twentyConcurrentRequestsProduceOneFinalOneCaptureAndIdenticalFinalHashes()throws Exception{ExecutorService pool=Executors.newFixedThreadPool(20);CountDownLatch ready=new CountDownLatch(20),go=new CountDownLatch(1);List<Future<String>>futures=new ArrayList<>();for(int i=0;i<20;i++)futures.add(pool.submit(()->{ready.countDown();go.await();for(int attempt=0;attempt<100;attempt++){var r=service.evaluate(DailyDecisionCaptureServiceTest.body());if(r.status().value()!=202)return SrppJcs.parseStrict(r.body()).path("decisionContentSha256").asText();Thread.sleep(10);}throw new IllegalStateException("bounded capture did not finalize");}));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();Set<String>hashes=new HashSet<>();for(var f:futures)hashes.add(f.get(20,TimeUnit.SECONDS));pool.shutdownNow();assertThat(hashes).hasSize(1);assertThat(captures.get()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_decision_run",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_decision_capture_claim",Integer.class)).isZero();assertThatThrownBy(()->jdbc.update("UPDATE srpp_decision_run SET slot='11:40'")).isInstanceOf(RuntimeException.class);}
    @Test void expiredLeaseRecoversSameRunUuidAndOldGenerationCannotReleaseNewLease(){Claim c=new Claim(UUID.randomUUID(),7,DATE,"09:05",POLICY.bundleHash(),DailyDecisionCaptureServiceTest.SWAGGER,UUID.randomUUID(),NOW.minusSeconds(60),NOW.minusSeconds(1));new TransactionTemplate(manager).executeWithoutResult(t->repository.insertClaim(c));var result=service.evaluate(DailyDecisionCaptureServiceTest.body());assertThat(result.status().value()).isEqualTo(201);assertThat(SrppJcs.parseStrict(result.body()).path("decisionRunId").asText()).isEqualTo(c.id().toString());assertThat(captures.get()).isEqualTo(1);Claim pending=new Claim(UUID.randomUUID(),7,DATE,"11:40",POLICY.bundleHash(),DailyDecisionCaptureServiceTest.SWAGGER,UUID.randomUUID(),NOW,NOW.plusSeconds(30));new TransactionTemplate(manager).executeWithoutResult(t->{repository.insertClaim(pending);Claim renewed=repository.renew(pending,NOW.plusSeconds(1));repository.release(pending);assertThat(repository.claim(7,DATE,"11:40",true).orElseThrow().generation()).isEqualTo(renewed.generation());});}
    @Test void sameClockCrossSlotClaimsHaveStableTieBreak(){Claim a=new Claim(UUID.fromString("00000000-0000-0000-0000-000000000001"),7,DATE,"09:05",POLICY.bundleHash(),DailyDecisionCaptureServiceTest.SWAGGER,UUID.randomUUID(),NOW,NOW.plusSeconds(30));Claim b=new Claim(UUID.fromString("00000000-0000-0000-0000-000000000002"),7,DATE,"11:40",POLICY.bundleHash(),DailyDecisionCaptureServiceTest.SWAGGER,UUID.randomUUID(),NOW,NOW.plusSeconds(30));new TransactionTemplate(manager).executeWithoutResult(t->{repository.insertClaim(a);repository.insertClaim(b);assertThat(repository.otherActive(a,NOW)).isFalse();assertThat(repository.otherActive(b,NOW)).isTrue();});}
    @Test void nonemptyPlaceholderMigrationRefusesToDropExistingData()throws Exception{jdbc.execute("DROP TABLE srpp_decision_capture_claim; DROP TABLE srpp_decision_run; CREATE TABLE srpp_decision_run(id int); INSERT INTO srpp_decision_run VALUES(1)");String sql=new ClassPathResource("db/changelog/changes/v1.149.0-srpp-decision-engine.sql").getContentAsString(StandardCharsets.UTF_8);assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_decision_run",Integer.class)).isEqualTo(1);}
    @Test void controlledFinalCommitBetweenLastLookupAndClaimInsertReplaysWithoutRecapture(){service.evaluate(DailyDecisionCaptureServiceTest.body());Run finished=repository.find(7,DATE,"09:05").orElseThrow();jdbc.execute("DELETE FROM srpp_decision_run");SrppDecisionRepository delayed=spy(repository);AtomicBoolean inserted=new AtomicBoolean();doAnswer(call->{if(inserted.compareAndSet(false,true)){TransactionTemplate finalCommit=new TransactionTemplate(manager);finalCommit.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);finalCommit.executeWithoutResult(t->repository.insert(finished));}call.callRealMethod();return null;}).when(delayed).insertClaim(any());DailyDecisionCaptureService contender=new DailyDecisionCaptureService(owner,market,registry,swagger,new DecisionPolicyAuthority(),delayed,capture,manager,Clock.fixed(NOW.plusSeconds(1),ZoneOffset.UTC),"postgres-test");int before=captures.get();var replay=contender.evaluate(DailyDecisionCaptureServiceTest.body());assertThat(replay.status().value()).isEqualTo(200);assertThat(captures.get()).isEqualTo(before);assertThat(SrppJcs.parseStrict(replay.body()).path("decisionRunId").asText()).isEqualTo(finished.id().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_decision_capture_claim",Integer.class)).isZero();}

    @Test void twoRealFinalSlotsReserveQuotaFundsAndSymbolExposure(){
        AtomicReference<Budget> secondBudget=new AtomicReference<>();
        DecisionInputCapturePort slotCapture=(id,request,prior)->{
            captures.incrementAndGet();
            ArrayNode rows=JsonNodeFactory.instance.arrayNode();prior.forEach(rows::add);
            Budget budget=DecisionInputCaptureAdapter.budget(POLICY,DATE,JsonNodeFactory.instance.arrayNode(),rows);
            if(request.slot().equals("11:40"))secondBudget.set(budget);
            Input base=input(DATE,input().sessions(),Map.of("00865B",new BigDecimal("9.8"),"00719B",new BigDecimal("9.85"),"00697B",new BigDecimal("9.9")),Map.of(),request.slot());
            Symbol chosen=base.symbols().get("00865B");BigDecimal target=POLICY.targets().get(chosen.code()).multiply(base.totalAssets());
            base=symbol(base,chosen.code(),replace(chosen,chosen.quote(),target.subtract(new BigDecimal("12000")),PASS));
            Input facts=new Input(base.date(),base.slot(),base.capturedAt(),base.sessions(),base.totalAssets(),base.totalDeposits(),base.termDeposits(),base.bondValue(),base.fx(),base.fxEvidence(),base.bearWindow(),base.symbols(),budget,base.subaccounts(),base.emergencyValue());
            var fixture=DailyDecisionCaptureServiceTest.frozen();ObjectNode json=fixture.input();json.put("slot",request.slot());json.put("capturedAt",facts.capturedAt().toString());json.withObject("sources").set("reservations",rows);ObjectNode vector=fixture.sourceVector();vector.withObject("reservations").put("sha256",SrppJcs.hash(rows));
            return new DecisionInputCapturePort.Frozen(facts,json,vector,9,NOW.toString());
        };
        Instant later=DATE.atTime(11,40).atZone(ZoneId.of("Asia/Taipei")).toInstant().plusSeconds(1);
        DailyDecisionCaptureService slots=new DailyDecisionCaptureService(owner,market,registry,swagger,new DecisionPolicyAuthority(),repository,slotCapture,manager,Clock.fixed(later,ZoneOffset.UTC),"postgres-test");
        var first=slots.evaluate(DailyDecisionCaptureServiceTest.body());assertThat(first.status().value()).isEqualTo(201);
        assertThat(SrppJcs.parseStrict(first.body()).path("decision").path("candidates").get(0).path("lots").asInt()).isEqualTo(1);
        var second=slots.evaluate(DailyDecisionCaptureServiceTest.body().replace("09:05","11:40"));assertThat(second.status().value()).isEqualTo(201);
        assertThat(secondBudget.get().strategyToday()).isEqualTo(1);assertThat(secondBudget.get().strategicReservedValues().get("00865B")).isEqualByComparingTo("9810");assertThat(secondBudget.get().reservedFees()).isPositive();assertThat(secondBudget.get().reservedBondValue()).isGreaterThan(new BigDecimal("9810"));
        assertThat(SrppJcs.parseStrict(second.body()).path("decision").path("candidates").get(0).path("lots").asInt()).isZero();assertThat(SrppJcs.parseStrict(second.body()).path("decision").path("candidates").get(3).path("lots").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM srpp_decision_run",Integer.class)).isEqualTo(2);assertThat(captures.get()).isEqualTo(2);
        assertThat(slots.evaluate(DailyDecisionCaptureServiceTest.body()).status().value()).isEqualTo(200);assertThat(slots.evaluate(DailyDecisionCaptureServiceTest.body().replace("09:05","11:40")).status().value()).isEqualTo(200);assertThat(captures.get()).isEqualTo(2);
    }

}
