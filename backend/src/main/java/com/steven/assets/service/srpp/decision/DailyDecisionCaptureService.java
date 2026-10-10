package com.steven.assets.service.srpp.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.SrppDecisionRepository;
import com.steven.assets.repository.SrppDecisionRepository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.*;
import com.steven.assets.srpp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.sql.SQLException;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;

/** Strict/calendar first, replay before current registries, durable claim then frozen canonical calculation. */
@Service
public class DailyDecisionCaptureService {
    private final CurrentUserContext owner;private final MarketDataService market;private final SrppPolicyRegistryService registry;
    private final PublishedSwaggerIdentity swagger;private final DecisionPolicyAuthority authority;private final SrppDecisionRepository repository;
    private final DecisionInputCapturePort capture;private final TransactionTemplate tx;private final Clock clock;private final String build;
    @Autowired public DailyDecisionCaptureService(CurrentUserContext owner,MarketDataService market,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,DecisionPolicyAuthority authority,SrppDecisionRepository repository,DecisionInputCapturePort capture,PlatformTransactionManager manager,@Value("${app.build-revision:LOCAL_BUILD}")String build){this(owner,market,registry,swagger,authority,repository,capture,manager,Clock.systemUTC(),build);}
    DailyDecisionCaptureService(CurrentUserContext owner,MarketDataService market,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,DecisionPolicyAuthority authority,SrppDecisionRepository repository,DecisionInputCapturePort capture,PlatformTransactionManager manager,Clock clock,String build){this.owner=owner;this.market=market;this.registry=registry;this.swagger=swagger;this.authority=authority;this.repository=repository;this.capture=capture;this.clock=clock;this.build=build;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(20);}
    public SrppCaptureService.Result evaluate(String raw){
        DecisionRequest r=DecisionRequest.parse(raw);r.checkClock(clock.instant());if(!owner.hasUser()||!"ACTIVE".equals(owner.getStatus()))throw problem(HttpStatus.SERVICE_UNAVAILABLE,"OWNER_UNAVAILABLE");long id=owner.getEffectiveUserId();
        Optional<Boolean>day=market.isTwTradingDayCachedOnly(r.date());if(day==null||day.isEmpty())throw problem(HttpStatus.SERVICE_UNAVAILABLE,"CALENDAR_UNAVAILABLE");if(!day.get())throw problem(HttpStatus.CONFLICT,"NON_TRADING_DAY");
        SrppCaptureService.Result existing=tx.execute(s->existing(id,r));if(existing!=null)return existing;
        if(!authority.policy().bundleHash().equals(r.policyHash())||registry.find(r.policyHash()).isEmpty())throw problem(HttpStatus.CONFLICT,"POLICY_UNSUPPORTED");
        Optional<String>published=swagger.sha256();if(published.isEmpty())throw problem(HttpStatus.SERVICE_UNAVAILABLE,"CONTEXT_NOT_READY");if(!r.swaggerHash().equals(published.get()))throw problem(HttpStatus.CONFLICT,"SWAGGER_MISMATCH");
        Claim claimed;
        try{claimed=tx.execute(s->{var run=repository.find(id,r.date(),r.slot());if(run.isPresent())return null;var old=repository.claim(id,r.date(),r.slot(),true);if(old.isPresent()){metadata(old.get().policy(),old.get().swagger(),r);if(old.get().leaseUntil().isAfter(clock.instant()))return null;return repository.renew(old.get(),clock.instant());}Instant now=clock.instant();Claim c=new Claim(UUID.randomUUID(),id,r.date(),r.slot(),r.policyHash(),r.swaggerHash(),UUID.randomUUID(),now,now.plusSeconds(30));if(repository.find(id,r.date(),r.slot()).isPresent())return null;repository.insertClaim(c);if(repository.find(id,r.date(),r.slot()).isPresent()){repository.release(c);return null;}return c;});}
        catch(RuntimeException conflict){if(!identityConflict(conflict))throw conflict;return tx.execute(s->{SrppCaptureService.Result e=existing(id,r);if(e==null)throw new IllegalStateException("Missing concurrently claimed identity");return e;});}
        if(claimed==null)return tx.execute(s->{SrppCaptureService.Result e=existing(id,r);if(e==null)throw new IllegalStateException("Missing decision identity");return e;});
        try{return tx.execute(s->{
            if(!repository.scopeLock(id,r.date()))return capturing(claimed);
            var alreadyFinal=repository.find(id,r.date(),r.slot());if(alreadyFinal.isPresent()){metadata(alreadyFinal.get().policy(),alreadyFinal.get().swagger(),r);repository.release(claimed);return replay(alreadyFinal.get());}
            if(repository.otherActive(claimed,clock.instant()))return capturing(claimed);
            var current=repository.claim(id,r.date(),r.slot(),true);if(current.isEmpty()||!current.get().generation().equals(claimed.generation())||!current.get().leaseUntil().isAfter(clock.instant()))return capturing(claimed);
            List<ObjectNode>prior=repository.reservations(id,r.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),r.date()).stream().map(priorRun->{replay(priorRun);return (ObjectNode)SrppJcs.parseStrict(priorRun.content());}).toList();
            DecisionInputCapturePort.Frozen frozen=capture.capture(id,r,prior);Result calculated=DailyDecisionCalculator.calculate(authority.policy(),frozen.facts());
            if(!claimed.leaseUntil().isAfter(clock.instant()))throw problem(HttpStatus.SERVICE_UNAVAILABLE,"CONTEXT_NOT_READY");
            String input=SrppJcs.canonicalize(frozen.input()),inputHash=SrppJcs.sha256Hex(input);ObjectNode content=content(claimed,r,frozen,calculated,input,inputHash);
            String contentHash=SrppJcs.hash(content);content.put("decisionContentSha256",contentHash);String canonical=SrppJcs.canonicalize(content);
            repository.insert(new Run(claimed.id(),id,r.date(),r.slot(),r.policyHash(),r.swaggerHash(),input,inputHash,canonical,contentHash,claimed.claimedAt(),clock.instant()));repository.release(claimed);content.put("created",true);return new SrppCaptureService.Result(HttpStatus.CREATED,SrppJcs.canonicalize(content),false);
        });}catch(RuntimeException failure){tx.executeWithoutResult(s->repository.release(claimed));throw failure;}
    }
    private SrppCaptureService.Result existing(long owner,DecisionRequest r){var run=repository.find(owner,r.date(),r.slot());if(run.isPresent()){metadata(run.get().policy(),run.get().swagger(),r);return replay(run.get());}var claim=repository.claim(owner,r.date(),r.slot(),false);if(claim.isPresent()){metadata(claim.get().policy(),claim.get().swagger(),r);if(claim.get().leaseUntil().isAfter(clock.instant()))return capturing(claim.get());}
        // READ COMMITTED: FINAL insertion and claim deletion may commit between the two reads.
        var finalized=repository.find(owner,r.date(),r.slot());if(finalized.isPresent()){metadata(finalized.get().policy(),finalized.get().swagger(),r);return replay(finalized.get());}return null;}
    private static void metadata(String policy,String swagger,DecisionRequest r){if(!policy.equals(r.policyHash())||!swagger.equals(r.swaggerHash()))throw problem(HttpStatus.CONFLICT,"RUN_METADATA_MISMATCH");}
    static SrppCaptureService.Result replay(Run r){try{ObjectNode content=(ObjectNode)SrppJcs.parseStrict(r.content());String hash=content.path("decisionContentSha256").asText();ObjectNode checked=content.deepCopy();checked.remove("decisionContentSha256");if(!SrppJcs.canonicalize(content).equals(r.content())||!hash.equals(r.contentHash())||!hash.equals(SrppJcs.hash(checked))||!r.inputHash().equals(SrppJcs.sha256Hex(r.input()))||!r.inputHash().equals(content.path("inputSnapshot").path("inputSnapshotSha256").asText())||!r.input().equals(content.path("inputSnapshot").path("inputBundleJcs").asText())||content.has("created")||!"FINAL".equals(content.path("status").asText())||!r.id().toString().equals(content.path("decisionRunId").asText())||r.owner()!=content.path("identity").path("ownerUserId").asLong()||!r.policy().equals(content.path("identity").path("policyBundleSha256").asText())||!r.swagger().equals(content.path("identity").path("swaggerSha256").asText())||!r.date().toString().equals(content.path("identity").path("tradingDate").asText())||!r.slot().equals(content.path("identity").path("slot").asText()))throw new IllegalArgumentException();content.put("created",false);return new SrppCaptureService.Result(HttpStatus.OK,SrppJcs.canonicalize(content),true);}catch(RuntimeException invalid){throw problem(HttpStatus.BAD_GATEWAY,"UPSTREAM_INVALID");}}
    private static SrppCaptureService.Result capturing(Claim c){ObjectNode n=JsonNodeFactory.instance.objectNode();n.put("schemaVersion",1);n.put("decisionRunId",c.id().toString());n.put("status","CAPTURING");n.put("code","DECISION_CAPTURE_IN_PROGRESS");ObjectNode i=n.putObject("identity");identity(i,c);return new SrppCaptureService.Result(HttpStatus.ACCEPTED,SrppJcs.canonicalize(n),false);}
    private ObjectNode content(Claim c,DecisionRequest r,DecisionInputCapturePort.Frozen f,Result result,String input,String hash){ObjectNode n=JsonNodeFactory.instance.objectNode();n.put("schemaVersion",1);n.put("decisionRunId",c.id().toString());n.put("status","FINAL");ObjectNode ar=n.putObject("authorityRevision");ar.put("serviceBuild",build);ar.put("databaseSchema","v1.149.0");ar.put("calculatorVersion",DailyDecisionCalculator.VERSION);ar.put("modelInputsSha256",authority.policy().modelHash());ar.put("policyManifestSha256",authority.policy().manifestHash());identity(n.putObject("identity"),c);ObjectNode snapshot=n.putObject("inputSnapshot");snapshot.put("inputSnapshotSha256",hash);snapshot.put("capturedAt",f.facts().capturedAt().toString());snapshot.put("assetSnapshotId",f.assetSnapshotId());snapshot.put("assetGeneratedAt",f.assetGeneratedAt());snapshot.set("sourceVector",f.sourceVector());snapshot.put("inputBundleJcs",input);
        ObjectNode d=n.putObject("decision");d.put("executionScope","REPORT_RECOMMENDATION_ONLY");d.put("tradeAuthorization",false);d.put("placesOrders",false);d.put("eventEvidenceIntegrationStatus","NOT_BOUND");d.put("eventEvidenceReason","PER_CONSUMER_EVIDENCE");ArrayNode ranking=d.putArray("ranking");result.ranking().forEach(ranking::add);ArrayNode cs=d.putArray("candidates");for(Candidate candidate:result.candidates()){ObjectNode a=cs.addObject();a.put("symbol",candidate.symbol());a.put("strategy",candidate.strategy());a.put("status",candidate.status());a.put("action",candidate.action());a.put("lots",candidate.lots());if(candidate.limitPrice()==null)a.putNull("limitPrice");else a.put("limitPrice",DailyDecisionCalculator.decimal(candidate.limitPrice()));if(candidate.amountTwd()==null)a.putNull("amountTwd");else a.put("amountTwd",DailyDecisionCalculator.decimal(candidate.amountTwd()));ArrayNode reasons=a.putArray("blockingReasons");candidate.blockingReasons().forEach(reasons::add);ArrayNode receipts=a.putArray("receipts");for(Receipt receipt:candidate.receipts()){ObjectNode rr=receipts.addObject();rr.put("ruleId",receipt.ruleId());rr.put("calculatorVersion",receipt.calculatorVersion());ArrayNode refs=rr.putArray("inputRefs");receipt.inputRefs().forEach(refs::add);rr.put("status",receipt.status());rr.put("reason",receipt.reason());ObjectNode values=rr.putObject("values");receipt.values().forEach(values::put);}}return n;}
    private static void identity(ObjectNode i,Claim c){i.put("ownerUserId",c.owner());i.put("tradingDate",c.date().toString());i.put("slot",c.slot());i.put("policyBundleSha256",c.policy());i.put("swaggerSha256",c.swagger());}
    private static boolean identityConflict(Throwable t){Set<Throwable>seen=Collections.newSetFromMap(new IdentityHashMap<>());for(Throwable c=t;c!=null&&seen.add(c);c=c.getCause())if(c instanceof SQLException sql&&"23505".equals(sql.getSQLState())&&Objects.toString(sql.getMessage(),"").contains("srpp_decision_claim_identity_uq"))return true;return false;}
    private static SrppCaptureProblem problem(HttpStatus status,String code){return new SrppCaptureProblem(status,code);}
}
