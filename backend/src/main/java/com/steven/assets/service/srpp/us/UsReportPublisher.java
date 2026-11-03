package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.UsReportRepository;
import com.steven.assets.service.srpp.report.*;
import com.steven.assets.srpp.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.us.UsReportReadPort.*;

/** One owner fence covers source capture, both isolated consumer observations and insert-only publication. */
@Service
public class UsReportPublisher {
    public static final String VERSION="US_REPORT_CALCULATOR_V1";
    private static final JsonNodeFactory J=JsonNodeFactory.instance;
    private final UsReportRepository repository;private final UsReportCapturePort capture;private final SrppReportAuthority authority;private final UsReportCalendar calendar;
    public UsReportPublisher(UsReportRepository repository,UsReportCapturePort capture,SrppReportAuthority authority,UsReportCalendar calendar){this.repository=repository;this.capture=capture;this.authority=authority;this.calendar=calendar;}
    @Transactional
    public List<Saved> publishOwner(long owner,LocalDate date,SupportedPolicy policy,String swagger){
        if(!authority.matches(policy)||!policy.bundleHash().equals(authority.decision().policy().bundleHash()))throw new UsReportProblem("POLICY_UNSUPPORTED");
        repository.lockOwner(owner);Map<String,UsReportRepository.State> states=new LinkedHashMap<>();Set<String> watched=new TreeSet<>();LocalDate earliest=null;
        for(String consumer:List.of("Codex","Claude")){var state=repository.state(owner,consumer);states.put(consumer,state);for(JsonNode n:state.wrapper().path("ledger").path("states")){watched.add(n.path("stock_code").asText());try{LocalDate basis=LocalDate.parse(n.path("basis_date").asText());if(earliest==null||basis.isBefore(earliest))earliest=basis;}catch(RuntimeException invalid){throw new UsReportProblem("UPSTREAM_INVALID");}}}
        var frozen=capture.capture(owner,date,watched,earliest);if(!date.equals(frozen.capturedAt().atZone(UsReportCalendar.NY).toLocalDate())||!calendar.producerWindow(frozen.capturedAt()))throw new UsReportProblem("OUTSIDE_REPORT_WINDOW");
        List<Saved> saved=new ArrayList<>();for(String consumer:List.of("Codex","Claude"))saved.add(publish(new Scope(owner,consumer,"US",date,UsReportRequest.SLOT,policy.bundleHash(),swagger,VERSION),frozen,states.get(consumer),Instant.now()));return List.copyOf(saved);
    }
    /** Package-private deterministic seam; production always enters through publishOwner's transaction/fence. */
    Saved publish(Scope scope,UsReportCapturePort.Frozen frozen,UsReportRepository.State state,Instant at){
        ObjectNode raw=frozen.sources().deepCopy(),sources=(ObjectNode)SrppReportModuleCalculator.normalize(raw),vector=frozen.sourceVector().deepCopy();
        ObjectNode stateSource=state.wrapper().deepCopy();JsonNode model=authority.model();String watch=UsCoreSellCalculator.watchPolicyFingerprint(model.path("execution_policy").path("core_sell_guard_d174"));
        if("VERIFIED".equals(stateSource.path("status").asText())&&!watch.equals(stateSource.path("watchPolicySha256").asText())){stateSource.put("status","UNAVAILABLE").put("reason","WATCH_POLICY_IDENTITY_MISMATCH");}
        UsReportCaptureAdapter.source(sources,vector,"core-state",stateSource,frozen.capturedAt(),"VERIFIED".equals(stateSource.path("status").asText())?"COMPLETE":"NONE","VERIFIED".equals(stateSource.path("status").asText())?"VERIFIED":"UNAVAILABLE");
        String fingerprint=UsReportFingerprint.hash(sources,vector);Optional<Saved> existing=repository.forSource(scope,fingerprint);if(existing.isPresent()){UsReportEnvelopeValidator.validate(existing.get().content());return existing.get();}
        ObjectNode input=J.objectNode().put("ownerUserId",scope.owner()).put("market","US").put("sessionDate",scope.date().toString()).put("reportSlot",scope.slot()).put("capturedAt",frozen.capturedAt().toString());input.set("sources",sources);input.set("stateBefore",stateSource);
        ObjectNode coreInput=coreInput(raw,scope,frozen.capturedAt(),calendar.displayTime(scope.date()));
        JsonNode ledger="VERIFIED".equals(stateSource.path("status").asText())?stateSource.path("ledger"):NullNode.instance;
        ObjectNode core=UsCoreSellCalculator.calculate(coreInput,model,ledger,scope.consumer());
        for(JsonNode reason:raw.path("universe").path("reasonCodes"))addGap(core,reason.asText());
        if(!"COMPLETE".equals(vector.path("assets").path("coverage").asText()))addGap(core,"ASSET_MARKET_VALUATION_DATE_UNVERIFIED");
        ObjectNode tactical=UsTacticalCalculator.calculate(raw.path("us-tactical"),model,raw.path("market"));
        ArrayNode modules=J.arrayNode();modules.add(module("CORE_SELL_GUARD",core,List.of("assets","policy","swagger","calendar","universe","market","core-state")));modules.add(module("US_TACTICAL",tactical,List.of("assets","policy","swagger","calendar","universe","market","us-tactical")));
        UUID id=UUID.randomUUID();ObjectNode out=J.objectNode().put("schemaVersion",1).put("apiScope","US_REPORT_CALCULATIONS").put("status","FINAL").put("calculationRunId",id.toString());ObjectNode identity=out.putObject("identity");identity.put("ownerUserId",scope.owner()).put("consumer",scope.consumer()).put("market",scope.market()).put("sessionDate",scope.date().toString()).put("reportSlot",scope.slot()).put("policyBundleSha256",scope.policy()).put("swaggerSha256",scope.swagger());
        out.put("calculatorVersion",VERSION).put("capturedAt",frozen.capturedAt().toString()).put("publishedAt",at.toString()).put("displayReportTime",calendar.displayTime(scope.date()));out.set("session",calendar.session(scope.date()).json());out.set("inputBundle",input);out.set("sourceVector",vector);out.put("inputSnapshotSha256",SrppJcs.hash(input));out.set("modules",SrppReportModuleCalculator.normalize(modules));out.put("tradeAuthorization",false).put("placesOrders",false);out.put("contentSha256",SrppJcs.hash(out));
        String content=SrppJcs.canonicalize(out);UsReportEnvelopeValidator.validate(content);Saved saved=new Saved(id,scope,fingerprint,out.path("inputSnapshotSha256").asText(),out.path("contentSha256").asText(),content,at);repository.insert(saved);
        repository.saveStates(scope,state,SrppReportModuleCalculator.normalize(core.path("stateAfter")),fingerprint,id,at);return saved;
    }
    public static ObjectNode coreInput(JsonNode sources,Scope scope,Instant at,String display){ObjectNode input=J.objectNode().put("market","美股").put("trading_date",scope.date().toString()).put("slot",display).put("asof",at.toString());input.set("calendar",sources.path("calendar").path("days"));input.set("session",sources.path("calendar").path("session"));ArrayNode items=input.putArray("items");for(JsonNode row:sources.path("market").path("symbols"))if(row.hasNonNull("coreDirection")){ObjectNode item=items.addObject().put("stock_code",row.path("stockCode").asText()).put("policy_direction",row.path("coreDirection").asText());item.set("quote",row.path("quote"));item.set("radar",row.path("radar"));}return input;}
    private static ObjectNode module(String id,ObjectNode data,List<String> refs){ObjectNode out=J.objectNode().put("moduleId",id).put("coverage",data.path("coverage").asText());out.set("data",data);out.set("reasonCodes",data.path("reasonCodes"));ArrayNode a=out.putArray("inputRefs");refs.forEach(a::add);return out;}
    private static void addGap(ObjectNode data,String reason){ArrayNode reasons=(ArrayNode)data.path("reasonCodes");if(reasons.isEmpty()&&"COMPLETE".equals(data.path("coverage").asText()))data.put("coverage",data.path("results").isEmpty()?"UNAVAILABLE":"PARTIAL");for(JsonNode n:reasons)if(reason.equals(n.asText()))return;reasons.add(reason);}
}
