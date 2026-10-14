package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.srpp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Task 497 immutable package projection.  It has no writer, vendor, broker or producer dependency. */
@Service
public class SrppProjectionService {
    private static final JsonNodeFactory F=JsonNodeFactory.instance;
    private static final List<String> FACTS=List.of("WEEK52_POSITION","K","D","RSI5","BIAS10","WILLIAMS_R9","PREMIUM_PERCENT","OVERHEAT_HIT","LOW_HIT","ENTRY_WATCH","BUY_OBSERVATION","SELL_OBSERVATION");
    private final CurrentUserContext owner; private final MarketDataService calendar; private final SrppPolicyRegistryService registry; private final PublishedSwaggerIdentity swagger; private final SrppReportAuthority authority; private final SrppReportRepository repository; private final Clock clock;
    @Autowired public SrppProjectionService(CurrentUserContext owner,MarketDataService calendar,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,SrppReportAuthority authority,SrppReportRepository repository){this(owner,calendar,registry,swagger,authority,repository,Clock.system(SrppTime.TW_ZONE));}
    SrppProjectionService(CurrentUserContext owner,MarketDataService calendar,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,SrppReportAuthority authority,SrppReportRepository repository,Clock clock){this.owner=owner;this.calendar=calendar;this.registry=registry;this.swagger=swagger;this.authority=authority;this.repository=repository;this.clock=clock;}
    @Transactional(readOnly=true) public String order(MultiValueMap<String,String> values){ SrppProjectionRequest.Order q=SrppProjectionRequest.order(values); Loaded l=load(q.common()); Instant now=clock.instant(); if(q.asOf().isBefore(l.capturedAt)||q.asOf().isAfter(now))throw new SrppReportProblem("INVALID_REQUEST"); return SrppJcs.canonicalize(plan(l,q)); }
    @Transactional(readOnly=true) public String facts(MultiValueMap<String,String> values){ SrppProjectionRequest.Symbols q=SrppProjectionRequest.symbols(values); Loaded l=load(q.common()); return SrppJcs.canonicalize(symbolFacts(l,q)); }
    private Loaded load(SrppProjectionRequest.Common q){
        ZonedDateTime now=ZonedDateTime.now(clock.withZone(SrppTime.TW_ZONE)); if(!q.date().equals(now.toLocalDate())||now.toLocalTime().isBefore(LocalTime.parse(q.slot())))throw new SrppReportProblem("INVALID_REQUEST");
        Optional<Boolean> day;try{day=calendar.isTwTradingDayCachedOnly(q.date());}catch(RuntimeException e){day=Optional.empty();} if(day==null||day.isEmpty())throw new SrppReportProblem("CALENDAR_UNAVAILABLE");if(!day.get())throw new SrppReportProblem("NON_TRADING_DAY");
        if(!owner.hasUser()||!AppUser.STATUS_ACTIVE.equals(owner.getStatus()))throw new SrppReportProblem("OWNER_UNAVAILABLE"); long ownerId=owner.getEffectiveUserId();
        Optional<SupportedPolicy> policy=registry.find(q.policy());if(policy.isEmpty()||!authority.matches(policy.get()))throw new SrppReportProblem("POLICY_UNSUPPORTED"); Optional<String> published=swagger.sha256();if(published.isEmpty())throw new SrppReportProblem("CONTEXT_NOT_READY");if(!published.get().equals(q.swagger()))throw new SrppReportProblem("SWAGGER_MISMATCH");
        SrppReportRepository.Scope scope=new SrppReportRepository.Scope(ownerId,q.date(),q.slot(),q.policy(),q.swagger(),SrppReportModuleCalculator.VERSION);
        SrppReportRepository.Package saved=repository.byId(ownerId,q.run()).orElseThrow(()->new SrppReportProblem("CALCULATION_NOT_FOUND"));if(!saved.scope().equals(scope)||!q.consumer().equals(saved.consumer()))throw new SrppReportProblem("CONTEXT_IDENTITY_MISMATCH");
        JsonNode root=SrppReportEnvelopeValidator.validate(saved.content()); JsonNode identity=root.path("identity");
        if(!saved.scope().date().toString().equals(identity.path("tradingDate").asText())||!saved.scope().slot().equals(identity.path("reportSlot").asText())||!saved.scope().policy().equals(identity.path("policyBundleSha256").asText())||!saved.scope().swagger().equals(identity.path("swaggerSha256").asText())||!saved.scope().calculator().equals(root.path("authorityRevision").path("calculatorVersion").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
        if(!saved.id().toString().equals(root.path("calculationRunId").asText())||!saved.contentHash().equals(root.path("contentSha256").asText())||!saved.inputHash().equals(root.path("inputSnapshot").path("inputSnapshotSha256").asText())||identity.path("ownerUserId").asLong()!=ownerId||!q.consumer().equals(identity.path("consumer").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
        Instant captured;try{captured=Instant.parse(root.path("inputSnapshot").path("capturedAt").asText());}catch(RuntimeException e){throw new SrppReportProblem("UPSTREAM_INVALID");}
        JsonNode input;try{input=SrppJcs.parseStrict(root.path("inputSnapshot").path("inputBundleJcs").asText());}catch(RuntimeException e){throw new SrppReportProblem("UPSTREAM_INVALID");}
        return new Loaded(root,input,captured);
    }
    private ObjectNode plan(Loaded l,SrppProjectionRequest.Order q){
        List<Leg> legs=legs(l.root);Map<String,SrppProjectionRequest.Decision> decisions=new LinkedHashMap<>();int previous=-1;for(var d:q.decisions()){int position=-1;for(int i=0;i<legs.size();i++)if(legs.get(i).id.equals(d.legId())){position=i;break;}if(position<0||position<=previous||decisions.put(d.legId(),d)!=null)throw new SrppReportProblem("INVALID_REQUEST");previous=position;}
        ObjectNode out=F.objectNode();out.put("schemaVersion","1");out.set("identity",l.root.path("identity").deepCopy());putBase(out,l);out.put("asOf",q.asOf().toString());ArrayNode normalized=out.putArray("normalizedDecisions");for(var d:q.decisions())normalized.add(decision(d));
        /* planId is a deterministic content address, never a UUID-looking run identifier. */
        ObjectNode address=F.objectNode();address.set("identity",out.path("identity").deepCopy());address.set("base",out.path("base").deepCopy());address.put("asOf",q.asOf().toString());address.set("normalizedDecisions",normalized.deepCopy());out.put("planId",SrppJcs.sha256Hex(SrppJcs.canonicalize(address)));
        ObjectNode risk=out.putObject("derivedRisk");risk.put("mode","DEFENSIVE");risk.put("status","UNAVAILABLE");risk.putArray("reasonCodes").add("RISK_EVIDENCE_UNAVAILABLE");risk.putArray("receiptRefs");
        ArrayNode projected=out.putArray("legs");int complete=0,unavailable=0,notEvaluated=0;for(Leg leg:legs){LegProjection p=projectLeg(l,leg,decisions.get(leg.id));projected.add(p.node());switch(p.coverage()){case COMPLETE->complete++;case UNAVAILABLE->unavailable++;case NOT_EVALUATED->notEvaluated++;}}
        coverage(out,legs.size(),complete,0,unavailable,notEvaluated);out.put("executionScope","REPORT_RECOMMENDATION_ONLY");out.put("tradeAuthorization",false);out.put("placesOrders",false);out.putArray("receiptRefs");hash(out);return out;
    }
    private ObjectNode decision(SrppProjectionRequest.Decision d){ObjectNode o=F.objectNode();o.put("legId",d.legId());o.put("verdict",d.verdict());if(d.amount()==null)o.putNull("amount");else o.put("amount",d.amount());if(d.reason()==null)o.putNull("reason");else o.put("reason",d.reason());return o;}
    private LegProjection projectLeg(Loaded loaded,Leg leg,SrppProjectionRequest.Decision decision){
        JsonNode b=leg.node;ObjectNode o=F.objectNode();String action=text(b,"action");
        o.put("legId",leg.id);o.put("market",market(text(b,"market")));o.put("symbol",text(b,"symbol"));o.put("action",action);
        o.put("verdict",decision==null?"NOT_DECIDED":decision.verdict());ArrayNode reasons=o.putArray("reasonCodes");
        JsonNode baseLots=baseLots(b),baseUnits=baseUnits(b);o.set("baseLots",baseLots);o.set("baseUnits",baseUnits);o.putNull("finalLots");o.putNull("finalUnits");
        QuoteProof quote=quoteProof(loaded,leg);if(quote==null){o.putNull("limitPrice");o.putNull("amount");o.putNull("quoteCapture");}else{ o.put("limitPrice",quote.price());o.put("amount",quote.amount());ObjectNode capture=o.putObject("quoteCapture");capture.put("sourceId",quote.sourceId());capture.put("sourceRevision",quote.revision());capture.put("sourceSha256",quote.sha256());capture.put("capturedAt",quote.capturedAt());ObjectNode receipt=capture.putObject("receiptRef");receipt.put("kind","QUOTE");receipt.put("sha256",quote.sha256());receipt.put("capturedAt",quote.capturedAt()); }o.putNull("freshness");o.putNull("settlement");
        Coverage outcome=Coverage.NOT_EVALUATED;
        if(decision==null){reasons.add("LLM_DECISION_MISSING");}
        else if("BUY".equals(action)){
            reasons.add("RISK_EVIDENCE_UNAVAILABLE");
            if(!baseLots.isNull())o.put("finalLots",0); else if(!baseUnits.isNull())o.put("finalUnits","0");
        } else if("REDUCE".equals(decision.verdict()) && (baseLots.isNull()==baseUnits.isNull())) {
            reasons.add("REDUCE_BASE_UNIT_UNAVAILABLE");
        } else if("SELL".equals(action) && "ADOPT".equals(decision.verdict()) && (baseLots.isNull()!=baseUnits.isNull())) {
            if(!baseLots.isNull())o.set("finalLots",baseLots.deepCopy()); else o.set("finalUnits",baseUnits.deepCopy());
            outcome=Coverage.COMPLETE;
        } else if("VETO".equals(decision.verdict()) || "WAIT".equals(decision.verdict())) {
            if(decision.reason()!=null)reasons.add(decision.reason());
        } else {
            reasons.add("FROZEN_QUOTE_EVIDENCE_UNAVAILABLE");
            outcome=Coverage.UNAVAILABLE;
        }
        o.put("executable",false);return new LegProjection(o,outcome);
    }
    /**
     * Reify a QUOTE receipt only from one immutable source-vector entry and the immutable package leg.
     * The source object itself has no self-referential receipt hash: the matching vector is the sealed
     * receipt (hash and capture time), so a changed quote necessarily invalidates the vector hash before
     * any price can be returned.  The current {@code PriceQueryService.LivePrice} source has no amount;
     * that normal source shape therefore remains the deliberate all-null fallback until a complete sealed
     * source exists, rather than inventing an amount at projection time.
     */
    private QuoteProof quoteProof(Loaded loaded,Leg leg){
        try{
            String wireMarket=market(text(leg.node,"market")),sourceMarket="TW".equals(wireMarket)?"台股":"美股",symbol=text(leg.node,"symbol"),sourceId="market:"+sourceMarket+":"+symbol;
            JsonNode source=loaded.input.path("sources").path(sourceId),vector=loaded.root.path("inputSnapshot").path("sourceVector").path(sourceId),quote=source.path("quote");
            if(!source.isObject()||!sourceMarket.equals(text(source,"market"))||!symbol.equals(text(source,"symbol"))||!quote.isObject()||!sourceMarket.equals(text(quote,"market"))||!symbol.equals(text(quote,"stockCode")))return null;
            String price=text(quote,"price"),amount=text(quote,"amount"),legPrice=text(leg.node,"limitPrice"),legAmount=text(leg.node,"amount");
            if(!positivePublicDecimal(price)||!positivePublicDecimal(amount)||!positivePublicDecimal(legPrice)||!positivePublicDecimal(legAmount)||!legPrice.equals(price)||!legAmount.equals(amount))return null;
            String revision=text(vector,"revision"),sha=text(vector,"sha256"),captured=text(vector,"capturedAt");
            if(revision.length()>128||!sha.matches("[a-f0-9]{64}")||!"VERIFIED".equals(text(vector,"validation"))||!sourceId.equals(text(vector,"sourceId"))||!sha.equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(source))))return null;
            Instant capture=Instant.parse(captured);if(capture.isAfter(loaded.capturedAt))return null;
            return new QuoteProof(sourceId,revision,sha,captured,price,amount);
        }catch(RuntimeException invalid){return null;}
    }
    private ObjectNode symbolFacts(Loaded l,SrppProjectionRequest.Symbols q){
        SortedSet<String> universe=new TreeSet<>();for(Leg leg:legs(l.root))if("TW".equals(market(text(leg.node,"market"))))universe.add(text(leg.node,"symbol"));List<String> requested=q.stockCodes().isEmpty()?List.copyOf(universe):q.stockCodes();
        ObjectNode out=F.objectNode();out.put("schemaVersion","1");out.set("identity",l.root.path("identity").deepCopy());putBase(out,l);ArrayNode symbols=out.putArray("symbols");int available=0,unavailable=0,notEvaluated=0;
        for(String code:requested){ObjectNode row=buildSymbol(l,code,universe.contains(code));symbols.add(row);String s=row.path("status").asText();if("AVAILABLE".equals(s))available++;else if("UNAVAILABLE".equals(s))unavailable++;else notEvaluated++;}
        coverage(out,requested.size(),available,0,unavailable,notEvaluated);out.put("executionScope","REPORT_RECOMMENDATION_ONLY");out.put("tradeAuthorization",false);out.put("placesOrders",false);out.putArray("receiptRefs");hash(out);return out;
    }
    private ObjectNode buildSymbol(Loaded l,String code,boolean included){ObjectNode row=F.objectNode();row.put("symbol",code);row.put("market","TW");ArrayNode facts=row.putArray("facts");if(!included){row.put("status","UNAVAILABLE");row.putArray("reasonCodes").add("NOT_IN_PACKAGE_CAPTURE");for(String n:FACTS)facts.add(outsideFact(n));return row;}
        JsonNode source=l.input.path("sources").path("market:台股:"+code), vector=l.root.path("inputSnapshot").path("sourceVector").path("market:台股:"+code);boolean trusted=trusted(source,vector,"market:台股:"+code);Map<String,ObjectNode> mapped=new LinkedHashMap<>();
        mapped.put("WEEK52_POSITION",mappedFact("WEEK52_POSITION",source.at("/radar/stock/week52Position"),"RATIO",BigDecimal.ZERO,BigDecimal.ONE,l,vector,trusted));mapped.put("K",mappedFact("K",source.at("/radar/stock/kValue"),"PERCENT",BigDecimal.ZERO,new BigDecimal("100"),l,vector,trusted));mapped.put("D",mappedFact("D",source.at("/radar/stock/dValue"),"PERCENT",BigDecimal.ZERO,new BigDecimal("100"),l,vector,trusted));mapped.put("RSI5",mappedFact("RSI5",source.at("/radar/stock/extendedIndicators/rsi5"),"PERCENT",BigDecimal.ZERO,new BigDecimal("100"),l,vector,trusted));mapped.put("BIAS10",mappedFact("BIAS10",source.at("/radar/stock/extendedIndicators/bias10"),"PERCENT",null,null,l,vector,trusted));mapped.put("WILLIAMS_R9",mappedFact("WILLIAMS_R9",source.at("/radar/stock/extendedIndicators/wr9"),"PERCENT",BigDecimal.ZERO,new BigDecimal("100"),l,vector,trusted));mapped.put("PREMIUM_PERCENT",mappedFact("PREMIUM_PERCENT",source.at("/quote/premiumDiscountPct"),"PERCENT",null,null,l,vector,trusted));
        for(String n:FACTS){if(mapped.containsKey(n))facts.add(mapped.get(n));else if("OVERHEAT_HIT".equals(n))facts.add(overheat(mapped,l,vector,trusted));else facts.add(unavailableFact(n,"FROZEN_INPUT_UNAVAILABLE"));}
        boolean any=false,all=true;for(JsonNode f:facts){any|="AVAILABLE".equals(f.path("status").asText());all&="AVAILABLE".equals(f.path("status").asText());}row.put("status",all?"AVAILABLE":any?"NOT_EVALUATED":"NOT_EVALUATED");row.putArray("reasonCodes");return row;
    }
    private ObjectNode mappedFact(String name,JsonNode value,String unit,BigDecimal min,BigDecimal max,Loaded l,JsonNode v,boolean trusted){if(!trusted||!value.isTextual())return unavailableFact(name,"FROZEN_INPUT_UNAVAILABLE");try{BigDecimal d=new BigDecimal(value.textValue());if((min!=null&&d.compareTo(min)<0)||(max!=null&&d.compareTo(max)>0)||!publicDecimal(value.textValue()))throw new IllegalArgumentException();return availableFact(name,value.textValue(),unit,l,v);}catch(RuntimeException bad){return unavailableFact(name,"FROZEN_INPUT_UNAVAILABLE");}}
    private ObjectNode overheat(Map<String,ObjectNode> mapped,Loaded l,JsonNode v,boolean trusted){for(String n:List.of("K","D","RSI5","BIAS10","WILLIAMS_R9"))if(!"AVAILABLE".equals(mapped.get(n).path("status").asText()))return unavailableFact("OVERHEAT_HIT","FROZEN_INPUT_UNAVAILABLE");boolean hit=new BigDecimal(mapped.get("K").path("value").asText()).compareTo(new BigDecimal("90"))>0&&new BigDecimal(mapped.get("D").path("value").asText()).compareTo(new BigDecimal("80"))>0||new BigDecimal(mapped.get("RSI5").path("value").asText()).compareTo(new BigDecimal("80"))>=0||new BigDecimal(mapped.get("BIAS10").path("value").asText()).compareTo(new BigDecimal("8"))>=0||new BigDecimal(mapped.get("WILLIAMS_R9").path("value").asText()).compareTo(new BigDecimal("20"))<=0;return availableBooleanFact("OVERHEAT_HIT",hit,l,v);}
    private ObjectNode availableFact(String n,String value,String unit,Loaded l,JsonNode v){ObjectNode o=F.objectNode();o.put("name",n);o.put("value",value);o.put("unit",unit);o.put("status","AVAILABLE");o.put("asOf",l.capturedAt.toString());o.put("sourceRevision",v.path("sha256").asText());ArrayNode refs=o.putArray("receiptRefs");ObjectNode r=refs.addObject();r.put("kind","SOURCE");r.put("sha256",v.path("sha256").asText());r.put("capturedAt",v.path("capturedAt").asText());o.putArray("reasonCodes");return o;}
    private ObjectNode availableBooleanFact(String n,boolean value,Loaded l,JsonNode v){ObjectNode o=F.objectNode();o.put("name",n);o.put("value",value);o.put("unit","NONE");o.put("status","AVAILABLE");o.put("asOf",l.capturedAt.toString());o.put("sourceRevision",v.path("sha256").asText());ArrayNode refs=o.putArray("receiptRefs");ObjectNode r=refs.addObject();r.put("kind","SOURCE");r.put("sha256",v.path("sha256").asText());r.put("capturedAt",v.path("capturedAt").asText());o.putArray("reasonCodes");return o;}
    private ObjectNode unavailableFact(String n,String reason){ObjectNode o=F.objectNode();o.put("name",n);o.putNull("value");o.put("unit","NONE");o.put("status","NOT_EVALUATED");o.putNull("asOf");o.putNull("sourceRevision");o.putArray("receiptRefs");o.putArray("reasonCodes").add(reason);return o;}
    private ObjectNode outsideFact(String n){ObjectNode o=unavailableFact(n,"NOT_IN_PACKAGE_CAPTURE");o.put("status","UNAVAILABLE");return o;}
    private boolean trusted(JsonNode source,JsonNode vector,String id){try{String symbol=id.substring("market:台股:".length());return source.isObject()&&"台股".equals(source.path("market").asText())&&symbol.equals(source.path("symbol").asText())&&vector.isObject()&&id.equals(vector.path("sourceId").asText())&&"VERIFIED".equals(vector.path("validation").asText())&&vector.path("sha256").asText().matches("[a-f0-9]{64}")&&vector.path("sha256").asText().equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(source)))&&Instant.parse(vector.path("capturedAt").asText())!=null;}catch(RuntimeException e){return false;}}
    private List<Leg> legs(JsonNode root){List<Leg> out=new ArrayList<>();JsonNode modules=root.path("modules");if(!modules.isArray())throw new SrppReportProblem("UPSTREAM_INVALID");for(JsonNode m:modules){String module=text(m,"moduleId");JsonNode rows=m.path("data").path("legs");if(!rows.isArray())throw new SrppReportProblem("UPSTREAM_INVALID");for(int i=0;i<rows.size();i++){JsonNode leg=rows.get(i);String action=text(leg,"action"),market=text(leg,"market");if(!Set.of("BUY","SELL","NONE").contains(action)||!Set.of("台股","美股").contains(market))throw new SrppReportProblem("UPSTREAM_INVALID");if(!"NONE".equals(action))out.add(new Leg(module+"."+i,module,i,leg));}}return out;}
    private static String market(String market){return switch(market){case "台股"->"TW";case "美股"->"US";default->throw new SrppReportProblem("UPSTREAM_INVALID");};}
    private static String text(JsonNode n,String field){if(!n.path(field).isTextual()||n.path(field).asText().isBlank())throw new SrppReportProblem("UPSTREAM_INVALID");return n.path(field).asText();}
    private static JsonNode baseLots(JsonNode leg){JsonNode n=leg.path("lots");return n.isIntegralNumber()&&n.longValue()>=0?n.deepCopy():NullNode.instance;}
    private static JsonNode baseUnits(JsonNode leg){JsonNode n=leg.path("units");return n.isTextual()&&publicDecimal(n.textValue())&&new BigDecimal(n.textValue()).signum()>=0?n.deepCopy():NullNode.instance;}
    /** Public v1 grammar: at most 18 integer digits and eight fractional digits, with no exponent or trailing zero. */
    private static boolean publicDecimal(String value){return SrppDecimal.isCanonical(value)&&value.matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{0,7}[1-9])?");}
    private static boolean positivePublicDecimal(String value){try{return publicDecimal(value)&&new BigDecimal(value).signum()>0;}catch(RuntimeException invalid){return false;}}
    private static void putBase(ObjectNode out,Loaded l){ObjectNode base=out.putObject("base");base.put("packageContentSha256",l.root.path("contentSha256").asText());base.put("inputSnapshotSha256",l.root.path("inputSnapshot").path("inputSnapshotSha256").asText());base.put("inputSnapshotCapturedAt",l.capturedAt.toString());}
    private static void coverage(ObjectNode out,int requested,int complete,int partial,int unavailable,int notEvaluated){ObjectNode c=out.putObject("coverage");c.put("requestedCount",requested);c.put("completeCount",complete);c.put("partialCount",partial);c.put("unavailableCount",unavailable);c.put("notEvaluatedCount",notEvaluated);}
    private static void hash(ObjectNode out){out.put("contentSha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(out)));}
    private enum Coverage { COMPLETE, UNAVAILABLE, NOT_EVALUATED }
    private record Loaded(JsonNode root,JsonNode input,Instant capturedAt){} private record Leg(String id,String moduleId,int index,JsonNode node){} private record LegProjection(ObjectNode node,Coverage coverage){} private record QuoteProof(String sourceId,String revision,String sha256,String capturedAt,String price,String amount){}
}
