package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.repository.SrppReportRepository.*;
import com.steven.assets.repository.SrppReportRepository.Package;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DecisionFacts;
import com.steven.assets.srpp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Atomic insert-only publication. The shared calculation executes only after the PostgreSQL lock and lookup. */
@Service
public class SrppReportPublisher {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private final SrppReportRepository repository;
    private final SrppReportAuthority authority;
    private final SrppReportCalculationPort calculations;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final String build;
    @Autowired
    public SrppReportPublisher(SrppReportRepository repository, SrppReportAuthority authority,
                               SrppReportCalculationPort calculations, ObjectMapper mapper,
                               PlatformTransactionManager manager, SrppReportBuildIdentity identity) {
        this(repository,authority,calculations,mapper,manager,identity.value());
    }
    SrppReportPublisher(SrppReportRepository repository, SrppReportAuthority authority,
                               SrppReportCalculationPort calculations, ObjectMapper mapper,
                               PlatformTransactionManager manager, String build) {
        this.repository=repository;this.authority=authority;this.calculations=calculations;this.mapper=mapper;this.transaction=new TransactionTemplate(manager);this.build=build;
    }
    public List<Package> publish(Scope scope, Frozen frozen, SupportedPolicy supported, JsonNode extra,
                                 Map<String,ObjectNode> states, Instant publishedAt) {
        if (!authority.matches(supported) || !scope.policy().equals(authority.decision().policy().bundleHash()) || !scope.calculator().equals(SrppReportModuleCalculator.VERSION)) throw new SrppReportProblem("POLICY_UNSUPPORTED");
        if (scope.owner() <= 0 || scope.owner() > 9007199254740991L || frozen.assetSnapshotId() <= 0 || !Set.of("09:05","12:00").contains(scope.slot()) || !states.keySet().equals(Set.of("Codex","Claude"))) throw new SrppReportProblem("UPSTREAM_INVALID");
        ObjectNode input=frozen.input(),vector=frozen.sourceVector();
        if (input.path("ownerUserId").asLong()!=scope.owner() || !input.path("tradingDate").asText().equals(scope.date().toString()) || !input.path("slot").asText().equals(scope.slot())) throw new SrppReportProblem("UPSTREAM_INVALID");
        JsonNode rawPolicy=input.path("sources").path("policy");
        if(!authority.decision().modelBytes().equals(rawPolicy.path("rawModelBytes").asText())||!authority.decision().manifestBytes().equals(rawPolicy.path("rawManifestBytes").asText())||!scope.policy().equals(rawPolicy.path("bundleHash").asText())||!scope.swagger().equals(input.path("sources").path("swagger").path("sha256").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
        input.set("authority",authority.revision(build));
        String fingerprint=SrppReportFingerprint.hash(input,vector);
        return transaction.execute(tx -> {
            String scopeIdentity=scope.owner()+"/"+scope.date()+"/"+scope.slot()+"/"+scope.policy()+"/"+scope.swagger()+"/"+scope.calculator();
            repository.lock(scopeIdentity+"/"+fingerprint);
            Base base=repository.base(scope,fingerprint).orElseGet(() -> {
                ArrayNode shared=calculations.shared(frozen,authority.decision().policy(),authority.model(),supported,extra);
                String inputJcs=SrppJcs.canonicalize(input),vectorJcs=SrppJcs.canonicalize(vector),modulesJcs=SrppJcs.canonicalize(shared);
                Base candidate=new Base(UUID.randomUUID(),scope,fingerprint,frozen.facts().capturedAt(),Instant.parse(frozen.assetGeneratedAt()),frozen.assetSnapshotId(),UUID.randomUUID(),inputJcs,vectorJcs,modulesJcs,baseHash(inputJcs,vectorJcs,modulesJcs),publishedAt);
                repository.insert(candidate);return candidate;
            });
            if (!base.scope().equals(scope) || !base.hash().equals(baseHash(base.input(),base.vector(),base.modules())) || !base.fingerprint().equals(SrppReportFingerprint.hash((ObjectNode)SrppJcs.parseStrict(base.input()),(ObjectNode)SrppJcs.parseStrict(base.vector())))) throw new SrppReportProblem("UPSTREAM_INVALID");
            ArrayList<Package> result=new ArrayList<>();
            for(String consumer:List.of("Codex","Claude")) {
                ObjectNode state=states.get(consumer);
                if(state==null || state.path("ownerUserId").asLong()!=scope.owner() || !state.path("consumer").asText().equals(consumer) || !state.path("tradingDate").asText().equals(scope.date().toString()) || !state.path("reportSlot").asText().equals(scope.slot())) throw new SrppReportProblem("UPSTREAM_INVALID");
                if(!Set.of("VERIFIED","UNAVAILABLE").contains(state.path("status").asText())||"VERIFIED".equals(state.path("status").asText())&&(state.path("stateBefore").isNull()||state.path("stateBefore").isMissingNode()))throw new SrppReportProblem("UPSTREAM_INVALID");
                String stateHash=SrppJcs.hash(state);repository.lock(scopeIdentity+"/"+base.id()+"/"+consumer+"/"+stateHash);
                Optional<Package> existing=repository.packageForState(base,consumer,stateHash);
                if(existing.isPresent()) { SrppReportEnvelopeValidator.validate(existing.get().content());result.add(existing.get());continue; }
                ObjectNode original=(ObjectNode)SrppJcs.parseStrict(base.input()),sourceVector=(ObjectNode)SrppJcs.parseStrict(base.vector());
                Instant originalCaptured=Instant.parse(original.path("capturedAt").asText());
                Instant originalAssetGenerated=Instant.parse(original.path("sources").path("assets").path("generatedAt").asText());
                original.set("stateBefore",state);original.withObject("sources").set("core-state",state);
                ObjectNode stateReceipt=sourceVector.putObject("core-state");stateReceipt.put("sourceId","core-state");stateReceipt.put("revision",stateHash);stateReceipt.put("capturedAt",originalCaptured.toString());stateReceipt.put("sha256",stateHash);stateReceipt.put("coverage","UNAVAILABLE".equals(state.path("status").asText())?"NONE":"COMPLETE");stateReceipt.put("validation","UNAVAILABLE".equals(state.path("status").asText())?"UNAVAILABLE":"VERIFIED");
                DecisionFacts.Input facts=mapper.convertValue(original.path("facts"),DecisionFacts.Input.class);
                Frozen originalFrozen=new Frozen(facts,original,sourceVector,base.snapshotId(),originalAssetGenerated.toString());
                ObjectNode core=calculations.core(originalFrozen,authority.model(),state,consumer);
                ArrayNode modules=calculations.compose((ArrayNode)SrppJcs.parseStrict(base.modules()),core);
                UUID id=UUID.randomUUID();String inputJcs=SrppJcs.canonicalize(original),inputHash=SrppJcs.sha256Hex(inputJcs);
                ObjectNode envelope=F.objectNode();envelope.put("schemaVersion",1);envelope.put("calculationRunId",id.toString());envelope.put("status","FINAL");
                ObjectNode identity=envelope.putObject("identity");identity.put("ownerUserId",scope.owner());identity.put("consumer",consumer);identity.put("tradingDate",scope.date().toString());identity.put("reportSlot",scope.slot());identity.put("policyBundleSha256",scope.policy());identity.put("swaggerSha256",scope.swagger());
                envelope.set("authorityRevision",original.path("authority"));envelope.put("sharedCalculationId",base.id().toString());
                ObjectNode snapshot=envelope.putObject("inputSnapshot");snapshot.put("capturedAt",originalCaptured.toString());snapshot.put("assetGeneratedAt",originalAssetGenerated.toString());snapshot.put("assetSnapshotId",base.snapshotId());snapshot.put("valuationCaptureId",base.valuationId().toString());snapshot.put("inputBundleJcs",inputJcs);snapshot.put("inputSnapshotSha256",inputHash);snapshot.set("sourceVector",sourceVector);
                envelope.set("modules",modules);envelope.set("coverage",coverage(modules));envelope.put("executionScope","REPORT_RECOMMENDATION_ONLY");envelope.put("tradeAuthorization",false);envelope.put("placesOrders",false);envelope.put("contentSha256",SrppJcs.hash(envelope));
                String content=SrppJcs.canonicalize(envelope);SrppReportEnvelopeValidator.validate(content);
                Package saved=new Package(id,base.id(),scope,consumer,stateHash,inputHash,content,envelope.path("contentSha256").asText(),publishedAt);repository.insert(saved);result.add(saved);
            }
            return List.copyOf(result);
        });
    }
    private static String baseHash(String input,String vector,String modules) { ObjectNode n=F.objectNode();n.set("input",SrppJcs.parseStrict(input));n.set("sourceVector",SrppJcs.parseStrict(vector));n.set("modules",SrppJcs.parseStrict(modules));return SrppJcs.hash(n); }
    private static ObjectNode coverage(ArrayNode modules) {
        Map<String,Integer> counts=new HashMap<>();for(JsonNode module:modules)counts.merge(module.path("status").asText(),1,Integer::sum);
        ObjectNode n=F.objectNode();n.put("requestedCount",modules.size());n.put("completeCount",counts.getOrDefault("COMPLETE",0));n.put("partialCount",counts.getOrDefault("PARTIAL",0));n.put("unavailableCount",counts.getOrDefault("UNAVAILABLE",0));n.put("notEvaluatedCount",counts.getOrDefault("NOT_EVALUATED",0));return n;
    }
}
