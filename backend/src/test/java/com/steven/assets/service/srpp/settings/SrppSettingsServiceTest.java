package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.controller.*;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.LatestAssetsService;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.service.srpp.report.SrppReportAuthority;
import com.steven.assets.srpp.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SrppSettingsServiceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CurrentUserContext user=new CurrentUserContext();
    final SrppSettingsDraftRepository drafts=mock(SrppSettingsDraftRepository.class);
    final SrppSettingsRunRepository runs=mock(SrppSettingsRunRepository.class);
    final SrppSettingsReleaseRepository releases=mock(SrppSettingsReleaseRepository.class);
    final SrppSettingsWorkerPort worker=mock(SrppSettingsWorkerPort.class);
    final SrppSettingsAiPort ai=mock(SrppSettingsAiPort.class);
    final LatestAssetsService assets=mock(LatestAssetsService.class);
    final DecisionPolicyAuthority authority=mock(DecisionPolicyAuthority.class);
    final SrppReportAuthority report=mock(SrppReportAuthority.class);
    final SrppPolicyRegistryService registry=mock(SrppPolicyRegistryService.class);
    final Map<UUID,SrppSettingsDraft> ds=new ConcurrentHashMap<>();
    final Map<UUID,SrppSettingsRun> rs=new ConcurrentHashMap<>();
    final Map<UUID,SrppSettingsRelease> rels=new ConcurrentHashMap<>();
    SrppSettingsService service;JsonNode catalog;String base;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-14T05:00:00Z"),ZoneOffset.UTC);
    final TransactionTemplate tx=new TransactionTemplate(){@Override public <T>T execute(TransactionCallback<T> callback){return callback.doInTransaction(mock(TransactionStatus.class));}};
    @BeforeEach void setup()throws Exception {
        user.setEffectiveUserId(7L);user.setRole("ADMIN");user.setStatus("ACTIVE");
        DecisionPolicyAuthority real=new DecisionPolicyAuthority();base=real.policy().bundleHash();when(authority.policy()).thenReturn(real.policy());when(authority.modelBytes()).thenReturn(real.modelBytes());
        byte[] bytes=getClass().getResourceAsStream("/srpp/settings/settings_catalog.json").readAllBytes();JsonNode meta=SettingsCanonicalJson.parse(new String(bytes,StandardCharsets.UTF_8));String catHash=SettingsCanonicalJson.bytesHash(bytes);
        ObjectNode manifest=(ObjectNode)SettingsCanonicalJson.parse(real.manifestBytes());((ObjectNode)manifest.path("files")).put("assumptions/settings_catalog.json",catHash);when(authority.manifestBytes()).thenReturn(SettingsCanonicalJson.canonical(manifest));
        ObjectNode cat=((ObjectNode)meta).deepCopy();cat.put("baseBundleSha256",base).put("modelInputsSha256",real.policy().modelHash()).put("catalogSha256",catHash);ObjectNode values=cat.putObject("currentValues");JsonNode model=SettingsCanonicalJson.parse(real.modelBytes());
        byte[] payrollBytes;try(var in=getClass().getResourceAsStream("/srpp/settings/pre_retirement_payroll.json")){payrollBytes=in==null?null:in.readAllBytes();}
        ObjectNode extracted=SettingsAuthorityValues.extract(meta,model,manifest,payrollBytes);values.setAll(extracted);
        for(JsonNode f:cat.path("fields"))((ObjectNode)f).set("defaultValue",extracted.path(f.path("id").asText()));
        catalog=cat;when(worker.catalog()).thenAnswer(i->catalog.deepCopy());
        when(worker.validate(any())).thenAnswer(i->{JsonNode request=i.getArgument(0);JsonNode v=request.path("values");for(var names=v.fieldNames();names.hasNext();)if(!values.has(names.next()))throw new SrppSettingsProblem(400,"CONFIG_INVALID","未知欄位");ObjectNode out=json.createObjectNode().put("valid",true).put("configSha256",SrppSettingsService.configHash(base,meta.path("catalogVersion").asText(),v));out.set("normalizedValues",v);return out;});
        when(worker.simulate(any())).thenAnswer(i->result(i.getArgument(0)));
        when(assets.getLatestForOwner(7)).thenReturn(new LatestAssetsDto.Response(clock.instant(),"TEST_FROZEN",false,Map.of(),null,null));
        when(drafts.saveAndFlush(any())).thenAnswer(i->{SrppSettingsDraft d=i.getArgument(0);ds.put(d.getId(),d);return d;});
        when(drafts.lockOwned(any(),anyLong())).thenAnswer(i->Optional.ofNullable(ds.get(i.getArgument(0))).filter(d->d.getOwnerUserId().equals(i.getArgument(1))));
        when(drafts.findByIdAndOwnerUserId(any(),anyLong())).thenAnswer(i->Optional.ofNullable(ds.get(i.getArgument(0))).filter(d->d.getOwnerUserId().equals(i.getArgument(1))));
        when(runs.saveAndFlush(any())).thenAnswer(i->{SrppSettingsRun r=i.getArgument(0);rs.put(r.getId(),r);return r;});
        when(runs.lockOwned(any(),anyLong())).thenAnswer(i->Optional.ofNullable(rs.get(i.getArgument(0))).filter(r->r.getOwnerUserId().equals(i.getArgument(1))));
        when(runs.findByIdAndOwnerUserId(any(),anyLong())).thenAnswer(i->Optional.ofNullable(rs.get(i.getArgument(0))).filter(r->r.getOwnerUserId().equals(i.getArgument(1))));
        when(runs.findFirstByDraftIdAndOwnerUserIdAndDraftRevisionAndConfigSha256AndStatusOrderByCreatedAtDesc(any(),anyLong(),anyLong(),anyString(),anyString())).thenAnswer(i->rs.values().stream().filter(r->r.getDraftId().equals(i.getArgument(0))&&r.getOwnerUserId().equals(i.getArgument(1))&&r.getDraftRevision()==(Long)i.getArgument(2)&&r.getConfigSha256().equals(i.getArgument(3))&&r.getStatus().equals(i.getArgument(4))).findFirst());
        when(releases.saveAndFlush(any())).thenAnswer(i->{SrppSettingsRelease r=i.getArgument(0);rels.put(r.getId(),r);return r;});
        when(releases.findByIdAndOwnerUserId(any(),anyLong())).thenAnswer(i->Optional.ofNullable(rels.get(i.getArgument(0))).filter(r->r.getOwnerUserId().equals(i.getArgument(1))));
        when(releases.findByRequestSha256AndOwnerUserId(anyString(),anyLong())).thenAnswer(i->rels.values().stream().filter(r->r.getRequestSha256().equals(i.getArgument(0))&&r.getOwnerUserId().equals(i.getArgument(1))).findFirst());
        service=build(new ImmediateExecutor(),NullNode.instance);
    }
    SrppSettingsService build(ExecutorService executor,JsonNode receipt){return new SrppSettingsService(user,drafts,runs,releases,worker,ai,assets,authority,report,registry,json,tx,executor,clock,receipt);}
    @AfterEach void close(){service.shutdown();}
    ObjectNode create(){ObjectNode b=json.createObjectNode().put("name","退休情境").put("baseBundleSha256",base);b.set("values",catalog.path("currentValues"));return (ObjectNode)SettingsCanonicalJson.tree(service.create(b.toString()));}
    ObjectNode simulate(ObjectNode d,String mode){return (ObjectNode)SettingsCanonicalJson.tree(service.simulate(UUID.fromString(d.path("id").asText()),"{\"revision\":"+d.path("revision")+",\"mode\":\""+mode+"\",\"seed\":7,\"simulations\":100}"));}
    ObjectNode publish(ObjectNode d,ObjectNode r){return (ObjectNode)SettingsCanonicalJson.tree(service.publish(UUID.fromString(d.path("id").asText()),"{\"revision\":"+d.path("revision")+",\"simulationId\":\""+r.path("id").asText()+"\"}"));}
    ObjectNode update(ObjectNode d,String key,JsonNode value){ObjectNode values=d.path("values").deepCopy();values.set(key,value);ObjectNode b=json.createObjectNode().put("revision",d.path("revision").asLong()).put("name","修改情境");b.set("values",values);return (ObjectNode)SettingsCanonicalJson.tree(service.update(UUID.fromString(d.path("id").asText()),b.toString()));}
    /** Real Python V2 shape, with simple synthetic amounts for workflow assertions. */
    static ObjectNode result(JsonNode request) {
        try {
            ObjectNode out=(ObjectNode)SettingsCanonicalJson.parse(new String(SrppSettingsServiceTest.class
                    .getResourceAsStream("/srpp/settings-result-wire-v2.json").readAllBytes(),StandardCharsets.UTF_8));
            for(String key:List.of("baseBundleSha256","configSha256","inputSha256"))out.set(key,request.path(key));
            for(String side:List.of("baseline","scenario")) {
                ObjectNode scenario=(ObjectNode)out.path("result").path(side);
                for(String field:List.of("retirementAssets","terminalAssets"))scenario.putObject(field).put("p10","100.00").put("p50","200.00").put("p90","300.00");
                scenario.putNull("depletionAge");
            }
            return out;
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }

    @Test void localNeverChecksOrCreatesAiAndFreezesOwnerInput() {
        ObjectNode d=create(),r=simulate(d,"LOCAL");assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");verifyNoInteractions(ai);verify(assets).getLatestForOwner(7);
        SrppSettingsRun stored=rs.get(UUID.fromString(r.path("id").asText()));JsonNode frozen=SettingsCanonicalJson.parse(stored.getRequestJson());assertThat(SettingsCanonicalJson.hash(frozen.path("input"))).isEqualTo(stored.getInputSha256());
        assertThat(stored.getResultSha256()).isEqualTo(SettingsCanonicalJson.hash(r.path("deterministicResult")));assertThat(r.path("publishEligible").asBoolean()).isTrue();
    }
    @Test void aiFailureAndMissingKeyRetainIdenticalDeterministicNumbers() {
        ObjectNode d=create(),local=simulate(d,"LOCAL");when(ai.available()).thenReturn(true);when(ai.interpret(any())).thenThrow(new IllegalStateException("test"));ObjectNode failed=simulate(d,"AI");assertThat(failed.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(failed.path("aiStatus").asText()).isEqualTo("FAILED");assertThat(failed.path("deterministicResult")).isEqualTo(local.path("deterministicResult"));
        when(ai.available()).thenReturn(false);ObjectNode noKey=simulate(d,"AI");assertThat(noKey.path("aiStatus").asText()).isEqualTo("NOT_CONFIGURED");assertThat(noKey.path("deterministicResult")).isEqualTo(local.path("deterministicResult"));
    }
    @Test void successfulAiAddsTextWithoutChangingCalculatedEvidence() {
        ObjectNode d=create(),local=simulate(d,"LOCAL");when(ai.available()).thenReturn(true);when(ai.interpret(any())).thenReturn("請依給定假設比較情境差異。");ObjectNode interpreted=simulate(d,"AI");
        assertThat(interpreted.path("aiStatus").asText()).isEqualTo("SUCCEEDED");assertThat(interpreted.path("aiInterpretation").asText()).contains("給定假設");assertThat(interpreted.path("deterministicResult")).isEqualTo(local.path("deterministicResult"));assertThat(interpreted.path("resultSha256")).isEqualTo(local.path("resultSha256"));
    }
    @Test void staleRevisionOtherOwnerUnknownValuesAndNonAdminFailClosed() {
        ObjectNode d=create();UUID id=UUID.fromString(d.path("id").asText());ObjectNode changed=update(d,"riskCautiousTotal",IntNode.valueOf(4));assertThat(changed.path("revision").asLong()).isEqualTo(2);assertThat(changed.path("changes").get(0).path("formalSupported").asBoolean()).isTrue();
        assertThatThrownBy(()->update(d,"riskCautiousTotal",IntNode.valueOf(4))).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("草稿已更新");
        user.setEffectiveUserId(8L);assertThatThrownBy(()->service.update(id,"{\"revision\":2,\"name\":\"x\",\"values\":{}}" )).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("找不到");
        user.setEffectiveUserId(7L);user.setRole("USER");assertThatThrownBy(()->service.publish(id,"{}" )).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("管理者");
        user.setRole("ADMIN");assertThatThrownBy(()->update(changed,"unknown",IntNode.valueOf(1))).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("未知");
    }
    @Test void oldCatalogVersionOrHashCanBeReadButCannotBeUpdatedSimulatedOrPublished() {
        ObjectNode draft=create(),run=simulate(draft,"LOCAL");UUID id=UUID.fromString(draft.path("id").asText());
        SrppSettingsDraft stored=ds.get(id);stored.setCatalogVersion("SRPP_SETTINGS_CATALOG_V1_OLD");stored.setCatalogSha256("0".repeat(64));
        var historical=service.simulation(UUID.fromString(run.path("id").asText()));
        assertThat(historical.deterministicResult()).isNotNull();assertThat(historical.publishEligible()).isFalse();
        assertThat(historical.blockedReasons()).anyMatch(reason->reason.code().equals("BASE_POLICY_CHANGED"));
        assertThatThrownBy(()->update(draft,"riskCautiousTotal",IntNode.valueOf(4))).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("新基準");
        assertThatThrownBy(()->simulate(draft,"LOCAL")).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("新基準");
        assertThatThrownBy(()->publish(draft,run)).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("新基準");
    }
    @Test void changedDraftAndSimulationOnlyValuesCannotBeReleased() {
        ObjectNode d=create(),r=simulate(d,"LOCAL");ObjectNode changed=update(d,"riskCautiousTotal",IntNode.valueOf(4));assertThatThrownBy(()->publish(changed,r)).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("目前草稿");
        ObjectNode simulationOnly=update(changed,"livingExpense",IntNode.valueOf(600000));ObjectNode run=simulate(simulationOnly,"LOCAL");assertThat(run.path("publishEligible").asBoolean()).isFalse();assertThatThrownBy(()->publish(simulationOnly,run)).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("僅供情境");
    }
    @Test void releaseIsIdempotentPendingAndOtherOwnerCannotDownload() {
        ObjectNode d=create(),r=simulate(d,"LOCAL"),release=publish(d,r);assertThat(release.path("status").asText()).isEqualTo("PENDING_RELEASE");assertThat(publish(d,r).path("id")).isEqualTo(release.path("id"));UUID id=UUID.fromString(release.path("id").asText());JsonNode p=SettingsCanonicalJson.tree(service.payload(id));ObjectNode unsigned=((ObjectNode)p).deepCopy();unsigned.remove("requestSha256");assertThat(SettingsCanonicalJson.hash(unsigned)).isEqualTo(p.path("requestSha256").asText());user.setEffectiveUserId(8L);assertThatThrownBy(()->service.payload(id)).isInstanceOf(SrppSettingsProblem.class);
    }
    @Test void centerKeepsTwentyRunsMetadataOnlyWhileSingleGetPreservesCompleteEvidenceAndHash() {
        ObjectNode d=create();for(int i=0;i<20;i++)simulate(d,"LOCAL");
        when(drafts.findTop20ByOwnerUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(ds.get(UUID.fromString(d.path("id").asText()))));
        when(runs.findTop20ByOwnerUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.copyOf(rs.values()));
        var center=service.center();assertThat(center.simulations()).hasSize(20);
        assertThat(center.simulations()).allSatisfy(r->{assertThat(r.deterministicResult()).isNull();assertThat(r.presentation()).isNull();assertThat(r.resultSha256()).isNotBlank();});
        var full=service.simulation(rs.keySet().iterator().next());assertThat(full.deterministicResult().result().scenario().reserveStrategyAudit()).isNotNull();
        assertThat(SettingsCanonicalJson.hash(SettingsCanonicalJson.tree(full.deterministicResult()))).isEqualTo(full.resultSha256());
    }
    @Test void identityMatchedButEmptyResultCannotSucceedOrPublish() {
        doAnswer(i->{ObjectNode n=result(i.getArgument(0));n.set("result",json.createObjectNode());return n;}).when(worker).simulate(any());ObjectNode d=create(),r=simulate(d,"LOCAL");assertThat(r.path("status").asText()).isEqualTo("FAILED");assertThat(r.path("errorCode").asText()).isEqualTo("SIMULATION_INVALID");assertThatThrownBy(()->publish(d,r)).isInstanceOf(SrppSettingsProblem.class);
    }
    @Test void queueTimeoutAndRestartDoNotLeaveProcessingForever() {
        service.shutdown();service=build(new RejectExecutor(),NullNode.instance);ObjectNode d=create();assertThatThrownBy(()->simulate(d,"LOCAL")).isInstanceOf(SrppSettingsProblem.class);assertThat(rs.values()).allMatch(r->r.getStatus().equals("FAILED"));
        service.shutdown();service=build(new ImmediateExecutor(),NullNode.instance);doThrow(new SrppSettingsProblem(503,"SIMULATION_TIMEOUT","模擬逾時")).when(worker).simulate(any());ObjectNode r=simulate(d,"LOCAL");assertThat(r.path("errorCode").asText()).isEqualTo("SIMULATION_TIMEOUT");
        SrppSettingsRun orphan=rs.values().iterator().next();orphan.setStatus("PROCESSING");when(runs.findByStatus("PROCESSING")).thenReturn(List.of(orphan));service.recoverInterrupted();assertThat(orphan.getStatus()).isEqualTo("FAILED");assertThat(orphan.getErrorCode()).isEqualTo("SIMULATION_INTERRUPTED");
    }
    @Test void actualMvcBodyPreservesDuplicateKeysAndTrailingTokensForStrictRejection()throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new SrppSettingsController(service)).setControllerAdvice(new SrppSettingsExceptionAdvice()).build();
        for(String raw:new String[]{"{\"name\":\"a\",\"name\":\"b\",\"values\":{}}","{} {}","{\"values\":{\"x\":NaN}}"})mvc.perform(post("/api/portfolio-advice/srpp/drafts").contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIG_INVALID"));
        verifyNoInteractions(worker,assets);
    }
    @Test void activationUsesOriginalBaselineAndCurrentJarValuesNotWorkerClaims() {
        ObjectNode d=create(),r=simulate(d,"LOCAL"),release=publish(d,r);SrppSettingsRelease row=rels.get(UUID.fromString(release.path("id").asText()));ObjectNode p=(ObjectNode)SettingsCanonicalJson.parse(row.getPayloadJson());
        String old="a".repeat(64);p.put("baseBundleSha256",old).put("configSha256",SrppSettingsService.configHash(old,catalog.path("catalogVersion").asText(),p.path("values")));p.remove("requestSha256");String digest=SettingsCanonicalJson.hash(p);p.put("requestSha256",digest);row.setRequestSha256(digest);row.setPayloadJson(SettingsCanonicalJson.canonical(p));
        ObjectNode receipt=json.createObjectNode().put("schemaVersion",1).put("requestSha256",digest).put("configSha256",p.path("configSha256").asText()).put("baselineBundleSha256",old).put("publishedBundleSha256",base).put("modelInputsSha256",authority.policy().modelHash());
        when(registry.find(base)).thenReturn(Optional.of(mock(SupportedPolicy.class)));when(report.matches(any())).thenReturn(true);service.shutdown();service=build(new ImmediateExecutor(),receipt);assertThat(service.active(row,p,catalog)).isTrue();
        when(report.matches(any())).thenReturn(false);assertThat(service.active(row,p,catalog)).isFalse();when(report.matches(any())).thenReturn(true);
        ObjectNode fake=p.deepCopy();((ObjectNode)fake.path("values")).put("riskCautiousTotal",4);assertThat(service.active(row,fake,catalog)).isFalse();
        receipt.put("baselineBundleSha256","b".repeat(64));assertThat(service.active(row,p,catalog)).isFalse();
    }
    @Test void simultaneousRequestsQueueAndNeverOverlapWorkerCalls()throws Exception {
        service.shutdown();ExecutorService pool=Executors.newSingleThreadExecutor();service=build(pool,NullNode.instance);
        CountDownLatch firstStarted=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(),max=new java.util.concurrent.atomic.AtomicInteger(),calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i->{int a=active.incrementAndGet();max.accumulateAndGet(a,Math::max);int call=calls.incrementAndGet();try{if(call==1){firstStarted.countDown();if(!releaseFirst.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}return result(i.getArgument(0));}finally{active.decrementAndGet();}}).when(worker).simulate(any());
        ObjectNode d=create();simulate(d,"LOCAL");assertThat(firstStarted.await(5,TimeUnit.SECONDS)).isTrue();simulate(d,"LOCAL");
        assertThat(calls.get()).isEqualTo(1);releaseFirst.countDown();pool.shutdown();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(2);assertThat(max.get()).isEqualTo(1);assertThat(rs.values()).allMatch(r->r.getStatus().equals("SUCCEEDED"));
    }
    @Test void supersededRequiresTrustedHistoricalReceiptAndVerifiedRegistry() {
        ObjectNode d=create(),r=simulate(d,"LOCAL"),release=publish(d,r);SrppSettingsRelease row=rels.get(UUID.fromString(release.path("id").asText()));JsonNode p=SettingsCanonicalJson.parse(row.getPayloadJson());
        String old="e".repeat(64);ObjectNode proof=json.createObjectNode().put("schemaVersion",1).put("requestSha256",row.getRequestSha256()).put("configSha256",p.path("configSha256").asText()).put("baselineBundleSha256",p.path("baseBundleSha256").asText()).put("publishedBundleSha256",old).put("modelInputsSha256","d".repeat(64));
        assertThat(service.superseded(row,p,List.of(proof))).isFalse();when(registry.find(old)).thenReturn(Optional.of(mock(SupportedPolicy.class)));assertThat(service.superseded(row,p,List.of(proof))).isTrue();
        proof.put("configSha256","f".repeat(64));assertThat(service.superseded(row,p,List.of(proof))).isFalse();
    }
    @Test void workerCannotClaimDifferentCurrentValuesOrActivateSelfConsistentWrongPayload() {
        ObjectNode d=create(),r=simulate(d,"LOCAL"),release=publish(d,r);SrppSettingsRelease row=rels.get(UUID.fromString(release.path("id").asText()));ObjectNode p=(ObjectNode)SettingsCanonicalJson.parse(row.getPayloadJson());
        ((ObjectNode)p.path("values")).put("riskCautiousTotal",4);p.put("configSha256",SrppSettingsService.configHash(base,catalog.path("catalogVersion").asText(),p.path("values")));p.remove("requestSha256");String digest=SettingsCanonicalJson.hash(p);p.put("requestSha256",digest);row.setRequestSha256(digest);row.setPayloadJson(SettingsCanonicalJson.canonical(p));
        ObjectNode receipt=json.createObjectNode().put("schemaVersion",1).put("requestSha256",digest).put("configSha256",p.path("configSha256").asText()).put("baselineBundleSha256",base).put("publishedBundleSha256",base).put("modelInputsSha256",authority.policy().modelHash());
        when(registry.find(base)).thenReturn(Optional.of(mock(SupportedPolicy.class)));when(report.matches(any())).thenReturn(true);service.shutdown();service=build(new ImmediateExecutor(),receipt);
        assertThat(service.active(row,p,catalog)).isFalse();
        ObjectNode claimed=catalog.deepCopy();((ObjectNode)claimed.path("currentValues")).put("riskCautiousTotal",4);doReturn(claimed).when(worker).catalog();
        assertThatThrownBy(()->create()).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("正式來源不一致");
    }
    @Test void fixedRecordsAreDefensivelyImmutableAndMoneyIsBigDecimal() throws Exception {
        ObjectNode body=json.createObjectNode().put("name","不可變草稿").put("baseBundleSha256",base);body.set("values",catalog.path("currentValues"));
        var d=service.create(body.toString());String original=d.values().path("retirementDate").asText();((ObjectNode)d.values()).put("retirementDate","2099-01-01");assertThat(d.values().path("retirementDate").asText()).isEqualTo(original);
        assertThatThrownBy(()->d.blockedReasons().clear()).isInstanceOf(UnsupportedOperationException.class);
        ObjectNode run=simulate((ObjectNode)SettingsCanonicalJson.tree(d),"LOCAL");var typed=service.simulation(UUID.fromString(run.path("id").asText()));
        assertThat(typed.deterministicResult().result().scenario().retirementAssets().p50()).isEqualByComparingTo(new java.math.BigDecimal("200.00"));
        assertThat(typed.deterministicResult().result().scenario().retirementAssets().p50().scale()).isEqualTo(2);
        assertThatThrownBy(()->typed.deterministicResult().result().scenario().percentilePaths().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SettingsCanonicalJson.parse("{\"livingExpense\":12345.67}").path("livingExpense").isBigDecimal()).isTrue();
    }
    @Test void actualHttpEvenWithGlobalNonNullRetainsWorkerMoneyNullsAndCanonicalHash()throws Exception {
        ObjectNode d=create(),run=simulate(d,"LOCAL");String id=run.path("id").asText();
        ObjectMapper httpJson=new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        var mvc=MockMvcBuilders.standaloneSetup(new SrppSettingsController(service)).setControllerAdvice(new SrppSettingsExceptionAdvice())
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(httpJson)).build();
        String wire=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/portfolio-advice/srpp/simulations/"+id))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode response=SettingsCanonicalJson.parse(wire),result=response.path("deterministicResult");
        assertThat(result.path("result").path("scenario").has("depletionAge")).isTrue();assertThat(result.path("result").path("scenario").path("depletionAge").isNull()).isTrue();
        assertThat(result.path("result").path("scenario").path("retirementAssets").path("p50").textValue()).isEqualTo("200.00");
        assertThat(SettingsCanonicalJson.hash(result)).isEqualTo(response.path("resultSha256").asText());
    }
    static class ImmediateExecutor extends AbstractExecutorService {boolean down;public void shutdown(){down=true;}public List<Runnable> shutdownNow(){down=true;return List.of();}public boolean isShutdown(){return down;}public boolean isTerminated(){return down;}public boolean awaitTermination(long n,TimeUnit u){return down;}public void execute(Runnable r){r.run();}}
    static class RejectExecutor extends ImmediateExecutor {@Override public void execute(Runnable r){throw new RejectedExecutionException();}}
}
