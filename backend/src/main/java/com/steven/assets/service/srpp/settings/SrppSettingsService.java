package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.*;
import com.steven.assets.dto.SrppSettingsDto.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.LatestAssetsService;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.service.srpp.report.SrppReportAuthority;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owner-scoped settings workflow. No Git, shell, Docker, broker, or formal-report execution. */
@Service
public class SrppSettingsService {
    private static final JsonNodeFactory F=JsonNodeFactory.instance;
    private final CurrentUserContext user;
    private final SrppSettingsDraftRepository drafts;
    private final SrppSettingsRunRepository runs;
    private final SrppSettingsReleaseRepository releases;
    private final SrppSettingsWorkerPort worker;
    private final SrppSettingsAiPort ai;
    private final LatestAssetsService assets;
    private final DecisionPolicyAuthority authority;
    private final SrppReportAuthority report;
    private final SrppPolicyRegistryService registry;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ExecutorService executor;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"srpp-settings-deadline");t.setDaemon(true);return t;});
    private final ConcurrentMap<UUID,Future<?>> tasks=new ConcurrentHashMap<>();
    private final Clock clock;
    private final JsonNode receipt;

    @Autowired
    public SrppSettingsService(CurrentUserContext user,SrppSettingsDraftRepository drafts,SrppSettingsRunRepository runs,
            SrppSettingsReleaseRepository releases,SrppSettingsWorkerPort worker,SrppSettingsAiPort ai,LatestAssetsService assets,
            DecisionPolicyAuthority authority,SrppReportAuthority report,SrppPolicyRegistryService registry,ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this(user,drafts,runs,releases,worker,ai,assets,authority,report,registry,mapper,new TransactionTemplate(manager),
                new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"srpp-settings-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy()),Clock.systemUTC(),loadReceipt());
    }
    SrppSettingsService(CurrentUserContext user,SrppSettingsDraftRepository drafts,SrppSettingsRunRepository runs,
            SrppSettingsReleaseRepository releases,SrppSettingsWorkerPort worker,SrppSettingsAiPort ai,LatestAssetsService assets,
            DecisionPolicyAuthority authority,SrppReportAuthority report,SrppPolicyRegistryService registry,ObjectMapper mapper,
            TransactionTemplate tx,ExecutorService executor,Clock clock,JsonNode receipt) {
        this.user=user;this.drafts=drafts;this.runs=runs;this.releases=releases;this.worker=worker;this.ai=ai;this.assets=assets;
        this.authority=authority;this.report=report;this.registry=registry;this.mapper=mapper;this.tx=tx;this.executor=executor;this.clock=clock;this.receipt=receipt;
    }
    private static JsonNode loadReceipt(){try(InputStream in=SrppSettingsService.class.getResourceAsStream("/srpp/settings/release-receipt.json")){return in==null?NullNode.instance:SettingsCanonicalJson.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));}catch(Exception ignored){return NullNode.instance;}}

    public CenterResponse center() {
        long owner=owner();JsonNode catalog=catalog();ObjectNode out=F.objectNode();out.set("catalog",catalog);
        var p=authority.policy();out.putObject("current").put("baseBundleSha256",p.bundleHash()).put("modelInputsSha256",p.modelHash()).put("decision",p.decision()).put("policyVersion",p.policyVersion());
        ArrayNode ds=out.putArray("drafts");drafts.findTop20ByOwnerUserIdOrderByUpdatedAtDesc(owner).forEach(d->ds.add(draftTree(d)));
        ArrayNode rs=out.putArray("simulations");runs.findTop20ByOwnerUserIdOrderByCreatedAtDesc(owner).forEach(r->rs.add(runTree(r)));
        ArrayNode rel=out.putArray("releases");releases.findTop20ByOwnerUserIdOrderByCreatedAtDesc(owner).forEach(r->rel.add(releaseTree(r,catalog)));
        out.put("canPublish",user.isAdmin()).put("aiAvailable",ai.available());return SettingsCanonicalJson.bind(out,CenterResponse.class);
    }
    public DraftResponse create(String raw) {
        long owner=owner();JsonNode body=body(raw,Set.of("name","baseBundleSha256","values"));String name=name(body);String base=hash(body,"baseBundleSha256");ensureBase(base);
        JsonNode cat=catalog();Validated valid=validate(cat,body.path("values"));Instant now=clock.instant();
        SrppSettingsDraft d=new SrppSettingsDraft();d.setId(UUID.randomUUID());d.setOwnerUserId(owner);d.setName(name);d.setRevision(1);
        d.setBaseBundleSha256(base);d.setModelInputsSha256(authority.policy().modelHash());d.setCatalogVersion(cat.path("catalogVersion").asText());d.setCatalogSha256(cat.path("catalogSha256").asText());
        d.setConfigSha256(valid.hash());d.setValuesJson(SettingsCanonicalJson.canonical(valid.values()));d.setChangesJson(SettingsCanonicalJson.canonical(valid.changes()));d.setCreatedAt(now);d.setUpdatedAt(now);
        tx.executeWithoutResult(s->drafts.saveAndFlush(d));return SettingsCanonicalJson.bind(draftTree(d),DraftResponse.class);
    }
    public DraftResponse update(UUID id,String raw) {
        long owner=owner();JsonNode body=body(raw,Set.of("revision","name","values"));long revision=revision(body);String name=name(body);
        JsonNode cat=catalog();
        SrppSettingsDraft d=tx.execute(s->{SrppSettingsDraft existing=lockedDraft(id,owner);checkRevision(existing,revision);ensureDraftCurrent(existing,cat);Validated valid=validate(cat,body.path("values"));
            existing.setName(name);existing.setRevision(existing.getRevision()+1);existing.setValuesJson(SettingsCanonicalJson.canonical(valid.values()));existing.setChangesJson(SettingsCanonicalJson.canonical(valid.changes()));existing.setConfigSha256(valid.hash());existing.setUpdatedAt(clock.instant());return drafts.saveAndFlush(existing);});
        return SettingsCanonicalJson.bind(draftTree(Objects.requireNonNull(d)),DraftResponse.class);
    }
    public RunResponse simulate(UUID id,String raw) {
        long owner=owner();JsonNode body=body(raw,Set.of("revision","mode","seed","simulations"));long revision=revision(body);
        String mode=text(body,"mode");if(!Set.of("LOCAL","AI").contains(mode))throw bad("分析模式必須是 LOCAL 或 AI");
        int seed=integer(body,"seed",0,Integer.MAX_VALUE,20261114),count=integer(body,"simulations",100,100000,1000);JsonNode cat=catalog();
        ObjectNode input=F.objectNode();try{var latest=assets.getLatestForOwner(owner);if(latest==null)throw new IllegalStateException();input.set("latestAssets",mapper.valueToTree(latest));}catch(RuntimeException unavailable){throw new SrppSettingsProblem(503,"ASSETS_UNAVAILABLE","目前缺少可驗證的資產資料，無法模擬");}input.put("capturedAt",clock.instant().toString());
        String inputHash=SettingsCanonicalJson.hash(input);
        SrppSettingsRun run=tx.execute(s->{SrppSettingsDraft d=lockedDraft(id,owner);checkRevision(d,revision);ensureDraftCurrent(d,cat);
            ObjectNode request=F.objectNode();request.put("schemaVersion",1).put("baseBundleSha256",d.getBaseBundleSha256()).put("configSha256",d.getConfigSha256()).put("catalogVersion",d.getCatalogVersion()).put("seed",seed).put("simulations",count);request.set("values",SettingsCanonicalJson.parse(d.getValuesJson()));request.set("input",input);request.put("inputSha256",inputHash);
            SrppSettingsRun r=new SrppSettingsRun();r.setId(UUID.randomUUID());r.setOwnerUserId(owner);r.setDraftId(id);r.setDraftRevision(revision);r.setMode(mode);r.setStatus("PROCESSING");r.setAiStatus(mode.equals("AI")?"PENDING":"NOT_REQUESTED");r.setConfigSha256(d.getConfigSha256());r.setInputSha256(inputHash);r.setRequestJson(SettingsCanonicalJson.canonical(request));r.setChangesJson(d.getChangesJson());r.setCreatedAt(clock.instant());return runs.saveAndFlush(r);});
        Objects.requireNonNull(run);UUID runId=run.getId();
        FutureTask<Void> task=new FutureTask<>(()->{executeRun(runId,owner,run.getRequestJson(),mode);return null;});tasks.put(runId,task);
        ScheduledFuture<?> timeout=deadlines.schedule(()->{Future<?> active=tasks.remove(runId);if(active!=null)active.cancel(true);finish(owner,runId,r->{if(r.getResultJson()!=null){r.setStatus("SUCCEEDED");if("PENDING".equals(r.getAiStatus()))r.setAiStatus("FAILED");}else r.setStatus("FAILED");r.setErrorCode("SIMULATION_TIMEOUT");});},175,TimeUnit.SECONDS);
        try{executor.execute(()->{try{task.run();}finally{timeout.cancel(false);tasks.remove(runId);}});}
        catch(RejectedExecutionException full){timeout.cancel(false);tasks.remove(runId);finish(owner,runId,r->{r.setStatus("FAILED");r.setAiStatus(mode.equals("AI")?"FAILED":"NOT_REQUESTED");r.setErrorCode("QUEUE_FULL");});throw new SrppSettingsProblem(429,"QUEUE_FULL","目前模擬工作較多，請稍後重試");}
        return SettingsCanonicalJson.bind(runTree(run),RunResponse.class);
    }
    private void executeRun(UUID id,long owner,String raw,String mode) {
        try {
            JsonNode request=SettingsCanonicalJson.parse(raw),result=worker.simulate(request);
            for(String key:List.of("baseBundleSha256","configSha256","inputSha256"))if(!request.path(key).equals(result.path(key)))throw new SrppSettingsProblem(503,"SIMULATION_IDENTITY_MISMATCH","模擬結果身分不一致");
            validateResult(result);
            SimulationEnvelope typed=SettingsCanonicalJson.bind(result,SimulationEnvelope.class);
            JsonNode decimalResult=SettingsCanonicalJson.tree(typed);
            String encoded=SettingsCanonicalJson.canonical(decimalResult),digest=SettingsCanonicalJson.hash(decimalResult);
            if(!digest.equals(SettingsCanonicalJson.hash(result)))throw new SrppSettingsProblem(503,"SIMULATION_IDENTITY_MISMATCH","模擬金額編碼或欄位無法精確保存");
            if(!mutate(owner,id,r->{r.setResultJson(encoded);r.setResultSha256(digest);})||Thread.currentThread().isInterrupted())return;
            String aiStatus="NOT_REQUESTED",interpretation=null,error=null;
            if(mode.equals("AI")) {
                if(!ai.available())aiStatus="NOT_CONFIGURED";
                else try{interpretation=ai.interpret(decimalResult.deepCopy());aiStatus="SUCCEEDED";}catch(Exception failure){aiStatus="FAILED";error="AI_FAILED";}
            }
            final String finalAi=aiStatus,finalText=interpretation,finalError=error;
            finish(owner,id,r->{r.setStatus("SUCCEEDED");r.setAiStatus(finalAi);r.setAiInterpretation(finalText);r.setErrorCode(finalError);});
        } catch(Exception failure){String code=failure instanceof SrppSettingsProblem p?p.code():"SIMULATION_FAILED";finish(owner,id,r->{r.setStatus(r.getResultJson()==null?"FAILED":"SUCCEEDED");r.setAiStatus(mode.equals("AI")?"FAILED":"NOT_REQUESTED");r.setErrorCode(code);});}
    }
    static void validateResult(JsonNode result) {
        boolean valid=result.path("schemaVersion").isIntegralNumber()&&result.path("schemaVersion").intValue()==1
                &&"SRPP_SCENARIO_CASHFLOW_V1".equals(result.path("engineVersion").asText());
        JsonNode body=result.path("result");valid&=body.isObject()&&body.path("differences").isObject()&&body.path("changes").isArray()&&body.path("warnings").isArray();
        for(String key:List.of("baseline","scenario")) {
            JsonNode scenario=body.path(key),prob=scenario.path("successProbability");
            valid&=scenario.isObject()&&prob.isNumber()&&Double.isFinite(prob.doubleValue())&&prob.doubleValue()>=0&&prob.doubleValue()<=1
                    &&scenario.path("percentilePaths").isArray()&&!scenario.path("percentilePaths").isEmpty()
                    &&scenario.path("assumptions").isObject()&&scenario.path("retirementAssets").isObject()&&scenario.path("terminalAssets").isObject()
                    &&scenario.path("modeledFields").isArray()&&scenario.path("notModeledFields").isArray()
                    &&"MONTHLY_SCENARIO_NOT_FORMAL_MODEL".equals(scenario.path("scope").asText())
                    &&scenario.path("successProbabilityCi95").path("low").isNumber()&&scenario.path("successProbabilityCi95").path("high").isNumber()
                    &&scenario.path("successProbabilityCi95").path("low").doubleValue()>=0&&scenario.path("successProbabilityCi95").path("high").doubleValue()<=1
                    &&scenario.path("successProbabilityCi95").path("low").doubleValue()<=prob.doubleValue()
                    &&scenario.path("successProbabilityCi95").path("high").doubleValue()>=prob.doubleValue();
            for(JsonNode point:scenario.path("percentilePaths")){valid&=point.path("age").isNumber();for(String money:List.of("p10","p50","p90"))valid&=isMoney(point.path(money));}
            for(String asset:List.of("retirementAssets","terminalAssets"))for(String number:List.of("p10","p50","p90"))valid&=isMoney(scenario.path(asset).path(number));
            valid&=isMoney(scenario.path("assumptions").path("protectedReserveRealTwd"));
        }
        valid&=body.path("differences").path("successProbability").isNumber();for(String key:List.of("retirementAssetsP50","terminalAssetsP50"))valid&=isMoney(body.path("differences").path(key));
        if(!valid)throw new SrppSettingsProblem(503,"SIMULATION_INVALID","模擬結果格式無效");
    }
    private static boolean isMoney(JsonNode value){return value.isTextual()&&value.textValue().matches("-?(0|[1-9][0-9]*)\\.[0-9]{2}")&&!value.textValue().equals("-0.00");}
    public RunResponse simulation(UUID id){long owner=owner();return SettingsCanonicalJson.bind(runTree(runs.findByIdAndOwnerUserId(id,owner).orElseThrow(SrppSettingsService::notFound)),RunResponse.class);}
    public ReleaseResponse publish(UUID draftId,String raw) {
        long owner=owner();if(!user.isAdmin())throw new SrppSettingsProblem(403,"ADMIN_REQUIRED","正式發布需要管理者權限");
        JsonNode body=body(raw,Set.of("revision","simulationId"));long revision=revision(body);UUID runId=uuid(body,"simulationId");JsonNode cat=catalog();
        SrppSettingsRelease release=tx.execute(s->{SrppSettingsDraft d=lockedDraft(draftId,owner);checkRevision(d,revision);ensureDraftCurrent(d,cat);
            SrppSettingsRun r=runs.findByIdAndOwnerUserId(runId,owner).orElseThrow(SrppSettingsService::notFound);
            ArrayNode reasons=blocked(d,r,true);if(!reasons.isEmpty())throw new SrppSettingsProblem(409,reasons.get(0).path("code").asText(),reasons.get(0).path("message").asText());
            ObjectNode p=F.objectNode();p.put("schemaVersion",1).put("baseBundleSha256",d.getBaseBundleSha256()).put("modelInputsSha256",d.getModelInputsSha256()).put("catalogSha256",d.getCatalogSha256()).put("configSha256",d.getConfigSha256());p.set("values",SettingsCanonicalJson.parse(d.getValuesJson()));p.putObject("simulation").put("id",r.getId().toString()).put("resultSha256",r.getResultSha256());String digest=SettingsCanonicalJson.hash(p);p.put("requestSha256",digest);
            Optional<SrppSettingsRelease> existing=releases.findByRequestSha256AndOwnerUserId(digest,owner);if(existing.isPresent())return existing.get();
            SrppSettingsRelease rel=new SrppSettingsRelease();rel.setId(UUID.randomUUID());rel.setOwnerUserId(owner);rel.setDraftId(draftId);rel.setRunId(runId);rel.setRequestSha256(digest);rel.setPayloadJson(SettingsCanonicalJson.canonical(p));rel.setCreatedAt(clock.instant());return releases.saveAndFlush(rel);});
        return SettingsCanonicalJson.bind(releaseTree(Objects.requireNonNull(release),cat),ReleaseResponse.class);
    }
    public PayloadResponse payload(UUID id){long owner=owner();return SettingsCanonicalJson.bind(SettingsCanonicalJson.parse(releases.findByIdAndOwnerUserId(id,owner).orElseThrow(SrppSettingsService::notFound).getPayloadJson()),PayloadResponse.class);}

    private record CatalogAuthority(JsonNode document,String sha256) {}
    private CatalogAuthority catalogAuthority() {
        try(InputStream in=SrppSettingsService.class.getResourceAsStream("/srpp/settings/settings_catalog.json")) {
            if(in==null)throw new IllegalStateException();
            byte[] bytes=in.readAllBytes();String digest=SettingsCanonicalJson.bytesHash(bytes);
            JsonNode manifest=SettingsCanonicalJson.parse(authority.manifestBytes());
            if(!digest.equals(manifest.path("files").path("assumptions/settings_catalog.json").asText()))throw new IllegalStateException();
            return new CatalogAuthority(SettingsCanonicalJson.parse(new String(bytes,StandardCharsets.UTF_8)),digest);
        }catch(Exception invalid){throw new SrppSettingsProblem(503,"SIMULATOR_POLICY_MISMATCH","設定目錄尚未與正式規則包同步");}
    }
    private JsonNode authorityValues(JsonNode document) {
        JsonNode model=SettingsCanonicalJson.parse(authority.modelBytes());ObjectNode values=F.objectNode();
        for(JsonNode field:document.path("fields")) {
            JsonNode value=field.path("pointer").isTextual()?model.at(field.path("pointer").asText()):field.path("defaultAssumption");
            if(value.isMissingNode()||value.isNull())throw new SrppSettingsProblem(503,"SIMULATOR_POLICY_MISMATCH","正式規則缺少設定來源");
            values.set(field.path("id").asText(),value);
        }
        return values;
    }
    private JsonNode catalog() {
        CatalogAuthority proof=catalogAuthority();JsonNode cat=worker.catalog();
        if(!authority.policy().bundleHash().equals(cat.path("baseBundleSha256").asText())
                ||!authority.policy().modelHash().equals(cat.path("modelInputsSha256").asText())
                ||!proof.sha256().equals(cat.path("catalogSha256").asText())
                ||!proof.document().path("catalogVersion").equals(cat.path("catalogVersion"))
                ||!cat.path("currentValues").isObject()||!cat.path("fields").isArray())throw new SrppSettingsProblem(503,"SIMULATOR_POLICY_MISMATCH","模擬服務與正式規則版本尚未同步");
        ArrayNode metadata=F.arrayNode();for(JsonNode field:cat.path("fields")){ObjectNode clean=field.deepCopy();clean.remove("defaultValue");metadata.add(clean);}
        if(!SettingsCanonicalJson.canonical(metadata).equals(SettingsCanonicalJson.canonical(proof.document().path("fields")))
                ||!SettingsCanonicalJson.canonical(authorityValues(proof.document())).equals(SettingsCanonicalJson.canonical(cat.path("currentValues"))))throw new SrppSettingsProblem(503,"SIMULATOR_POLICY_MISMATCH","模擬設定與正式來源不一致");
        return cat;
    }
    private record Validated(JsonNode values,ArrayNode changes,String hash){}
    private Validated validate(JsonNode cat,JsonNode values) {
        if(!values.isObject())throw bad("values 必須是設定物件");ObjectNode full=((ObjectNode)cat.path("currentValues")).deepCopy();values.fields().forEachRemaining(e->full.set(e.getKey(),e.getValue()));ObjectNode req=F.objectNode();req.put("schemaVersion",1).put("baseBundleSha256",cat.path("baseBundleSha256").asText());req.set("values",full);req.put("configSha256",configHash(cat.path("baseBundleSha256").asText(),cat.path("catalogVersion").asText(),full));
        JsonNode checked=worker.validate(req);JsonNode normalized=checked.path("normalizedValues");
        if(!checked.path("valid").asBoolean()||!normalized.isObject())throw bad("設定無法驗證");
        Set<String> names=new HashSet<>();cat.path("fields").forEach(f->names.add(f.path("id").asText()));Set<String> normalizedNames=new HashSet<>();normalized.fieldNames().forEachRemaining(normalizedNames::add);
        if(!names.equals(normalizedNames))throw new SrppSettingsProblem(503,"SIMULATION_INVALID","模擬設定欄位不完整");
        String hash=configHash(cat.path("baseBundleSha256").asText(),cat.path("catalogVersion").asText(),normalized);
        if(!hash.equals(checked.path("configSha256").asText()))throw new SrppSettingsProblem(503,"SIMULATION_IDENTITY_MISMATCH","模擬設定身分不一致");
        ArrayNode changes=F.arrayNode();for(JsonNode field:cat.path("fields")){String key=field.path("id").asText();JsonNode before=cat.path("currentValues").path(key),after=normalized.path(key);if(!SettingsCanonicalJson.canonical(before).equals(SettingsCanonicalJson.canonical(after))){ObjectNode c=changes.addObject();c.put("fieldId",key).put("label",field.path("label").asText()).put("formalSupported",field.path("formalSupported").asBoolean()).put("retirementImpact",field.path("retirementImpact").asText());c.set("before",before);c.set("after",after);}}
        return new Validated(normalized,changes,hash);
    }
    static String configHash(String base,String catalogVersion,JsonNode values){ObjectNode n=F.objectNode();n.put("baseBundleSha256",base).put("catalogVersion",catalogVersion);n.set("values",values);return SettingsCanonicalJson.hash(n);}
    private ObjectNode draftTree(SrppSettingsDraft d){ObjectNode n=F.objectNode();n.put("id",d.getId().toString()).put("name",d.getName()).put("revision",d.getRevision()).put("baseBundleSha256",d.getBaseBundleSha256()).put("modelInputsSha256",d.getModelInputsSha256()).put("catalogSha256",d.getCatalogSha256()).put("catalogVersion",d.getCatalogVersion()).put("configSha256",d.getConfigSha256()).put("createdAt",d.getCreatedAt().toString()).put("updatedAt",d.getUpdatedAt().toString());n.set("values",SettingsCanonicalJson.parse(d.getValuesJson()));n.set("changes",SettingsCanonicalJson.parse(d.getChangesJson()));SrppSettingsRun r=runs.findFirstByDraftIdAndOwnerUserIdAndDraftRevisionAndConfigSha256AndStatusOrderByCreatedAtDesc(d.getId(),d.getOwnerUserId(),d.getRevision(),d.getConfigSha256(),"SUCCEEDED").orElse(null);eligibility(n,d,r);return n;}
    private ObjectNode runTree(SrppSettingsRun r){ObjectNode n=F.objectNode();n.put("id",r.getId().toString()).put("draftId",r.getDraftId().toString()).put("draftRevision",r.getDraftRevision()).put("mode",r.getMode()).put("status",r.getStatus()).put("configSha256",r.getConfigSha256()).put("inputSha256",r.getInputSha256()).put("resultSha256",r.getResultSha256()).put("aiStatus",r.getAiStatus()).put("aiInterpretation",r.getAiInterpretation()).put("errorCode",r.getErrorCode()).put("createdAt",r.getCreatedAt().toString());if(r.getCompletedAt()==null)n.putNull("completedAt");else n.put("completedAt",r.getCompletedAt().toString());n.set("deterministicResult",r.getResultJson()==null?NullNode.instance:SettingsCanonicalJson.parse(r.getResultJson()));n.set("changes",SettingsCanonicalJson.parse(r.getChangesJson()));SrppSettingsDraft d=drafts.findByIdAndOwnerUserId(r.getDraftId(),r.getOwnerUserId()).orElse(null);eligibility(n,d,r);return n;}
    private void eligibility(ObjectNode n,SrppSettingsDraft d,SrppSettingsRun r){ArrayNode reasons=blocked(d,r,user.isAdmin());n.put("publishEligible",reasons.isEmpty());n.set("blockedReasons",reasons);}
    private ArrayNode blocked(SrppSettingsDraft d,SrppSettingsRun r,boolean admin){ArrayNode reasons=F.arrayNode();if(!admin)reason(reasons,"ADMIN_REQUIRED","正式發布需要管理者權限");if(d==null){reason(reasons,"DRAFT_CHANGED","草稿已不存在");return reasons;}if(!authority.policy().bundleHash().equals(d.getBaseBundleSha256()))reason(reasons,"BASE_POLICY_CHANGED","正式規則已更新，請以新基準建立草稿");for(JsonNode change:SettingsCanonicalJson.parse(d.getChangesJson()))if(!change.path("formalSupported").asBoolean()){reason(reasons,"FORMAL_FIELD_UNSUPPORTED",change.path("label").asText()+"僅供情境模擬，不能發布為正式規則");}
        if(r==null)reason(reasons,"SIMULATION_REQUIRED","請先完成此版本草稿的模擬");else if(!r.getDraftId().equals(d.getId())||r.getDraftRevision()!=d.getRevision()||!r.getConfigSha256().equals(d.getConfigSha256()))reason(reasons,"DRAFT_CHANGED","模擬結果不屬於目前草稿版本");else if(!"SUCCEEDED".equals(r.getStatus())||r.getResultJson()==null||!isHash(r.getResultSha256()))reason(reasons,"SIMULATION_FAILED","目前模擬尚未成功完成");return reasons;}
    private static void reason(ArrayNode a,String code,String message){a.addObject().put("code",code).put("message",message);}
    private ObjectNode releaseTree(SrppSettingsRelease rel,JsonNode cat){JsonNode p=SettingsCanonicalJson.parse(rel.getPayloadJson());ObjectNode n=F.objectNode();n.put("id",rel.getId().toString()).put("draftId",rel.getDraftId().toString()).put("simulationId",rel.getRunId().toString()).put("requestSha256",rel.getRequestSha256()).put("configSha256",p.path("configSha256").asText()).put("baseBundleSha256",p.path("baseBundleSha256").asText()).put("createdAt",rel.getCreatedAt().toString());n.put("status",active(rel,p,cat)?"ACTIVE":superseded(rel,p,historicalReceipts())?"SUPERSEDED":"PENDING_RELEASE");return n;}
    private List<JsonNode> historicalReceipts() {
        List<JsonNode> result=new ArrayList<>();
        try {
            var resolver=new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            for(var resource:resolver.getResources("classpath*:srpp/settings/release-receipts/*.json"))try(InputStream in=resource.getInputStream()){
                JsonNode value=SettingsCanonicalJson.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));
                if((value.path("requestSha256").asText()+".json").equals(resource.getFilename()))result.add(value);
            }catch(Exception ignored){}
        }catch(Exception ignored){}
        return result;
    }
    boolean superseded(SrppSettingsRelease rel,JsonNode p,Collection<JsonNode> history) {
        try {
            ObjectNode unsigned=((ObjectNode)p).deepCopy();unsigned.remove("requestSha256");
            if(!p.path("requestSha256").asText().equals(rel.getRequestSha256())||!SettingsCanonicalJson.hash(unsigned).equals(rel.getRequestSha256()))return false;
            CatalogAuthority catalog=catalogAuthority();
            if(!p.path("catalogSha256").asText().equals(catalog.sha256())||!configHash(p.path("baseBundleSha256").asText(),catalog.document().path("catalogVersion").asText(),p.path("values")).equals(p.path("configSha256").asText()))return false;
            for(JsonNode proof:history)if(proof.path("schemaVersion").isIntegralNumber()&&proof.path("schemaVersion").intValue()==1
                    &&proof.path("requestSha256").equals(p.path("requestSha256"))
                    &&proof.path("configSha256").equals(p.path("configSha256"))
                    &&proof.path("baselineBundleSha256").equals(p.path("baseBundleSha256"))
                    &&isHash(proof.path("modelInputsSha256").asText())
                    &&!proof.path("publishedBundleSha256").asText().equals(authority.policy().bundleHash())
                    &&registry.find(proof.path("publishedBundleSha256").asText()).isPresent())return true;
            return false;
        }catch(RuntimeException invalid){return false;}
    }
    boolean active(SrppSettingsRelease rel,JsonNode p,JsonNode cat) {
        try {
            if(!receipt.isObject()||!receipt.path("schemaVersion").isIntegralNumber()||receipt.path("schemaVersion").intValue()!=1
                    ||!receipt.path("requestSha256").asText().equals(rel.getRequestSha256())
                    ||!p.path("requestSha256").asText().equals(rel.getRequestSha256())
                    ||!receipt.path("configSha256").asText().equals(p.path("configSha256").asText())
                    ||!receipt.path("baselineBundleSha256").asText().equals(p.path("baseBundleSha256").asText())
                    ||!receipt.path("publishedBundleSha256").asText().equals(authority.policy().bundleHash())
                    ||!receipt.path("modelInputsSha256").asText().equals(authority.policy().modelHash()))return false;
            CatalogAuthority catalog=catalogAuthority();
            if(!p.path("catalogSha256").asText().equals(catalog.sha256()))return false;
            ObjectNode unsigned=((ObjectNode)p).deepCopy();unsigned.remove("requestSha256");
            if(!SettingsCanonicalJson.hash(unsigned).equals(rel.getRequestSha256()))return false;
            String originalVersion=catalog.document().path("catalogVersion").asText();
            if(!configHash(p.path("baseBundleSha256").asText(),originalVersion,p.path("values")).equals(p.path("configSha256").asText()))return false;
            if(!SettingsCanonicalJson.canonical(authorityValues(catalog.document())).equals(SettingsCanonicalJson.canonical(p.path("values"))))return false;
            return registry.find(authority.policy().bundleHash()).filter(report::matches).isPresent();
        }catch(RuntimeException invalid){return false;}
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted(){for(SrppSettingsRun r:runs.findByStatus("PROCESSING"))finish(r.getOwnerUserId(),r.getId(),row->{row.setStatus(row.getResultJson()==null?"FAILED":"SUCCEEDED");if("PENDING".equals(row.getAiStatus()))row.setAiStatus("FAILED");row.setErrorCode("SIMULATION_INTERRUPTED");});}
    private boolean mutate(long owner,UUID id,Consumer<SrppSettingsRun> change){return Boolean.TRUE.equals(tx.execute(s->{SrppSettingsRun r=runs.lockOwned(id,owner).orElse(null);if(r==null||!"PROCESSING".equals(r.getStatus()))return false;change.accept(r);runs.saveAndFlush(r);return true;}));}
    private void finish(long owner,UUID id,Consumer<SrppSettingsRun> change){mutate(owner,id,r->{change.accept(r);r.setCompletedAt(clock.instant());});}
    private long owner(){if(!user.hasUser()||!"ACTIVE".equals(user.getStatus()))throw new SrppSettingsProblem(401,"UNAUTHENTICATED","請先登入有效帳號");return user.getEffectiveUserId();}
    private SrppSettingsDraft lockedDraft(UUID id,long owner){return drafts.lockOwned(id,owner).orElseThrow(SrppSettingsService::notFound);}
    private void ensureDraftCurrent(SrppSettingsDraft d,JsonNode cat){ensureBase(d.getBaseBundleSha256());if(!d.getCatalogSha256().equals(cat.path("catalogSha256").asText())||!d.getCatalogVersion().equals(cat.path("catalogVersion").asText())||!d.getModelInputsSha256().equals(authority.policy().modelHash()))throw new SrppSettingsProblem(409,"BASE_POLICY_CHANGED","規則或設定目錄已更新，請以新基準建立草稿");}
    private void ensureBase(String base){if(!authority.policy().bundleHash().equals(base))throw new SrppSettingsProblem(409,"BASE_POLICY_CHANGED","正式規則已更新，請以新基準建立草稿");}
    private static void checkRevision(SrppSettingsDraft d,long revision){if(d.getRevision()!=revision)throw new SrppSettingsProblem(409,"REVISION_CONFLICT","草稿已更新，請重新載入後再修改");}
    private static JsonNode body(String raw,Set<String> allowed){JsonNode n;try{n=SettingsCanonicalJson.parse(raw);}catch(RuntimeException e){throw bad("JSON 格式無效或包含重複欄位");}if(!n.isObject())throw bad("請提供 JSON 物件");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw bad("不支援的欄位："+k);});return n;}
    private static String text(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isTextual()||v.asText().isBlank())throw bad("缺少或無效欄位："+key);return v.asText();}
    private static String name(JsonNode n){String s=text(n,"name").strip();if(s.length()>100)throw bad("草稿名稱最多 100 字");return s;}
    private static String hash(JsonNode n,String key){String s=text(n,key);if(!isHash(s))throw bad("雜湊格式無效："+key);return s;}
    private static boolean isHash(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    private static long revision(JsonNode n){JsonNode v=n.get("revision");if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw bad("草稿版本無效");return v.longValue();}
    private static int integer(JsonNode n,String key,int min,int max,int fallback){JsonNode v=n.get(key);if(v==null)return fallback;if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<min||v.intValue()>max)throw bad(key+" 超出允許範圍");return v.intValue();}
    private static UUID uuid(JsonNode n,String key){try{return UUID.fromString(text(n,key));}catch(RuntimeException e){throw bad("識別碼無效："+key);}}
    private static SrppSettingsProblem bad(String message){return new SrppSettingsProblem(400,"CONFIG_INVALID",message);}
    private static SrppSettingsProblem notFound(){return new SrppSettingsProblem(404,"NOT_FOUND","找不到指定資料");}
    @PreDestroy public void shutdown(){tasks.values().forEach(f->f.cancel(true));executor.shutdownNow();deadlines.shutdownNow();}
}
