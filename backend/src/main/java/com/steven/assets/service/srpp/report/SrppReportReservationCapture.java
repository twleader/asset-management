package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.*;
import com.steven.assets.repository.SrppReportRepository.ReservationRef;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.steven.assets.srpp.SrppDecimal;
import java.util.*;

/** Saved recommendations remain reserved until an exact canonical leg/order/fill link exists. */
@Component
public class SrppReportReservationCapture {
    private static final Logger LOG=LoggerFactory.getLogger(SrppReportReservationCapture.class);
    static final Set<String> SATELLITE_STRATEGIES=Set.of(SrppReportModuleCalculator.SATELLITE_009826_STRATEGY,SrppReportModuleCalculator.SATELLITE_VT_STRATEGY);
    private final SrppDecisionRepository decisions;
    private final SrppReportRepository reports;
    private final SrppReportReservationProjectionCache projections;
    @Autowired
    public SrppReportReservationCapture(SrppDecisionRepository decisions,SrppReportRepository reports){this(decisions,reports,new SrppReportReservationProjectionCache());}
    SrppReportReservationCapture(SrppDecisionRepository decisions,SrppReportRepository reports,SrppReportReservationProjectionCache projections){this.decisions=decisions;this.reports=reports;this.projections=projections;}
    @Transactional(readOnly=true)
    public List<ObjectNode> read(long owner,LocalDate date,String slot){
        LocalDate from=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));List<ObjectNode> out=new ArrayList<>();
        for(var run:decisions.reservations(owner,from,date))if(run.date().isBefore(date)||run.slot().compareTo(slot)<0)out.add((ObjectNode)SrppJcs.parseStrict(run.content()));
        reports.visitPriorReportReservationMetadata(owner,from,date,slot,ref -> {
            ObjectNode summary=projection(owner,ref);ObjectNode legacy=JsonNodeFactory.instance.objectNode();legacy.set("identity",identity(ref));legacy.put("calculationRunId",ref.id().toString());legacy.putObject("decision").set("candidates",summary.path("bondCandidates"));
            out.add(legacy);
        });
        logCache(owner);
        return List.copyOf(out);
    }
    @Transactional(readOnly=true)
    public ObjectNode readReportSource(long owner,LocalDate date,String slot,String currentPolicy){
        ObjectNode source=JsonNodeFactory.instance.objectNode();source.put("ownerUserId",owner);source.put("tradingDate",date.toString());source.put("policyBundleSha256",currentPolicy);source.put("status","VERIFIED");source.put("verified",true);source.put("coverage","COMPLETE");ArrayNode rows=source.putArray("rows");Set<String> seen=new HashSet<>();
        Set<SrppReportReservationProjectionCache.Key> missingKeys=projections.ownerKeys(owner);
        reports.visitUnlinkedReportReservationMetadata(owner,date,slot,ref -> {
            var key=SrppReportReservationProjectionCache.key(ref);ObjectNode summary=projection(owner,ref);
            // This bounded initial snapshot only shrinks, even when admissions saturate
            // or another reader admits new entries during the metadata traversal.
            missingKeys.remove(key);
            for(JsonNode row:summary.path("reportRows"))if(seen.add(row.path("reservationId").asText()))rows.add(row);
        });
        ArrayNode missing=source.putArray("missing");
        for(var run:decisions.unlinkedReservations(owner,date,slot)){
            JsonNode content=SrppJcs.parseStrict(run.content()),input=SrppJcs.parseStrict(run.input());ObjectNode hashable=((ObjectNode)content).deepCopy();hashable.remove("decisionContentSha256");
            if(run.owner()!=owner||!"FINAL".equals(content.path("status").asText())||!run.id().toString().equals(content.path("decisionRunId").asText())||!run.contentHash().equals(SrppJcs.hash(hashable))||!run.inputHash().equals(SrppJcs.sha256Hex(run.input()))||!run.inputHash().equals(content.path("inputSnapshot").path("inputSnapshotSha256").asText())||!run.input().equals(content.path("inputSnapshot").path("inputBundleJcs").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
            JsonNode identity=content.path("identity");
            if(!run.contentHash().equals(content.path("decisionContentSha256").asText())||identity.path("ownerUserId").asLong()!=owner||!run.date().toString().equals(identity.path("tradingDate").asText())||!run.slot().equals(identity.path("slot").asText())||!run.policy().equals(identity.path("policyBundleSha256").asText())||!run.swagger().equals(identity.path("swaggerSha256").asText())||input.path("ownerUserId").asLong()!=owner||!run.date().toString().equals(input.path("tradingDate").asText())||!run.slot().equals(input.path("slot").asText())||!run.policy().equals(input.path("sources").path("policy").path("bundleHash").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
            JsonNode feeRate=input.path("sources").path("policy").path("typedPolicy").path("feeRate");int index=0;
            for(JsonNode leg:content.path("decision").path("candidates")){
                int legIndex=index++;if(!"ACTIONABLE_FOR_REPORT".equals(leg.path("status").asText())||leg.path("lots").asInt()<=0)continue;
                if(!Set.of("00865B","00719B","00697B").contains(leg.path("symbol").asText())||!SrppDecimal.isCanonical(leg.path("amountTwd").asText())||new BigDecimal(leg.path("amountTwd").asText()).signum()<=0)throw new SrppReportProblem("UPSTREAM_INVALID");
                ObjectNode row=rows.addObject();row.put("origin","DAILY_DECISION");row.put("decisionRunId",run.id().toString());row.put("reservationId",SrppJcs.sha256Hex("DAILY_DECISION/"+run.id()+"/"+legIndex));row.put("calculationRunId",run.id().toString());row.putNull("sharedCalculationId");row.put("tradingDate",run.date().toString());row.put("reportSlot",run.slot());row.put("policyBundleSha256",run.policy());row.put("moduleId","BOND_D130_D195");row.put("legIndex",legIndex);row.set("symbol",leg.path("symbol"));row.put("market","台股");row.set("strategy",leg.path("strategy"));row.set("action",leg.path("action"));row.set("lots",leg.path("lots"));row.putNull("units");row.set("amount",leg.path("amountTwd"));row.put("currency","TWD");row.put("status","ACTIVE");row.putNull("linkedOrderId");row.putNull("linkedFillId");
                if(feeRate.isTextual()&&SrppDecimal.isCanonical(feeRate.asText())&&new BigDecimal(feeRate.asText()).signum()>=0){BigDecimal fee=new BigDecimal(leg.path("amountTwd").asText()).multiply(new BigDecimal(feeRate.asText())).setScale(2,RoundingMode.CEILING);row.put("fees",fee.stripTrailingZeros().toPlainString());}
                else{row.putNull("fees");missing.add("LEGACY_RESERVATION_FEES_UNAVAILABLE:"+run.id());}
            }
        }
        // A failed later row or legacy source must not trigger partial-traversal pruning.
        projections.pruneMissing(owner,missingKeys);logCache(owner);
        if(!missing.isEmpty())source.put("status","UNAVAILABLE").put("verified",false).put("coverage","PARTIAL");
        return source;
    }
    private ObjectNode projection(long owner,ReservationRef ref){
        if(ref==null||ref.scope()==null||ref.scope().owner()!=owner)throw new SrppReportProblem("UPSTREAM_INVALID");
        return projections.get(SrppReportReservationProjectionCache.key(ref),()->loadProjection(ref));
    }
    private ObjectNode loadProjection(ReservationRef ref){
        String raw=reports.reservationBody(ref).orElseThrow(()->new SrppReportProblem("UPSTREAM_INVALID"));
        JsonNode report=SrppReportEnvelopeValidator.validate(raw),identity=report.path("identity"),snapshot=report.path("inputSnapshot");var scope=ref.scope();
        if(!ref.id().toString().equals(report.path("calculationRunId").asText())||!ref.sharedId().toString().equals(report.path("sharedCalculationId").asText())
                ||!"FINAL".equals(ref.status())||!ref.status().equals(report.path("status").asText())||scope.owner()!=identity.path("ownerUserId").asLong()
                ||!scope.date().toString().equals(identity.path("tradingDate").asText())||!scope.slot().equals(identity.path("reportSlot").asText())
                ||!scope.policy().equals(identity.path("policyBundleSha256").asText())||!scope.swagger().equals(identity.path("swaggerSha256").asText())
                ||!scope.calculator().equals(report.path("authorityRevision").path("calculatorVersion").asText())||!ref.consumer().equals(identity.path("consumer").asText())
                ||!ref.inputHash().equals(snapshot.path("inputSnapshotSha256").asText())||!ref.contentHash().equals(report.path("contentSha256").asText())
                ||!ref.stateHash().equals(snapshot.path("sourceVector").path("core-state").path("sha256").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
        ObjectNode summary=JsonNodeFactory.instance.objectNode();ArrayNode candidates=summary.putArray("bondCandidates"),rows=summary.putArray("reportRows");
        for(JsonNode module:report.path("modules")){int index=0;for(JsonNode leg:module.path("data").path("legs")){
            int legIndex=index++;
            if("BOND_D130_D195".equals(module.path("moduleId").asText())){
                ObjectNode c=candidates.addObject();c.put("symbol",leg.path("symbol").asText());c.put("strategy",leg.path("strategy").asText());c.put("lots",leg.path("lots").asInt());c.set("amountTwd",leg.has("amount")?leg.path("amount"):leg.path("amountTwd"));
            }
            if(!"ACTIONABLE_FOR_REPORT".equals(leg.path("status").asText()))continue;
            JsonNode quantity=leg.path("units").isNull()?leg.path("lots"):leg.path("units");
            if(quantity.isMissingNode()||new BigDecimal(quantity.asText()).signum()<=0||SATELLITE_STRATEGIES.contains(leg.path("strategy").asText()))continue;
            String key=report.path("sharedCalculationId").asText()+"/"+module.path("moduleId").asText()+"/"+legIndex;
            ObjectNode row=rows.addObject();row.put("origin","REPORT");row.putNull("decisionRunId");row.put("reservationId",SrppJcs.sha256Hex("REPORT/"+key));row.set("calculationRunId",report.path("calculationRunId"));row.set("sharedCalculationId",report.path("sharedCalculationId"));row.set("tradingDate",identity.path("tradingDate"));row.set("reportSlot",identity.path("reportSlot"));row.set("policyBundleSha256",identity.path("policyBundleSha256"));row.set("moduleId",module.path("moduleId"));row.put("legIndex",legIndex);
            for(String field:List.of("symbol","market","strategy","action","lots","units","amount","currency","fees"))row.set(field,leg.path(field));
            row.put("status","ACTIVE");row.putNull("linkedOrderId");row.putNull("linkedFillId");
        }}
        return summary;
    }
    private static ObjectNode identity(ReservationRef ref){
        var s=ref.scope();ObjectNode identity=JsonNodeFactory.instance.objectNode();identity.put("ownerUserId",s.owner()).put("consumer",ref.consumer()).put("tradingDate",s.date().toString()).put("reportSlot",s.slot()).put("policyBundleSha256",s.policy()).put("swaggerSha256",s.swagger());
        return (ObjectNode)SrppJcs.parseStrict(SrppJcs.canonicalize(identity));
    }
    private void logCache(long owner){var s=projections.stats();LOG.info("SRPP reservation projection cache owner={} cumulativeHits={} cumulativeMisses={} admissionRejected={} entries={} bytes={}",owner,s.hits(),s.misses(),s.admissionRejected(),s.entries(),s.bytes());}
}
