package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.*;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.steven.assets.srpp.SrppDecimal;
import java.util.*;

/** Saved recommendations remain reserved until an exact canonical leg/order/fill link exists. */
@Component
public class SrppReportReservationCapture {
    private final SrppDecisionRepository decisions;
    private final SrppReportRepository reports;
    public SrppReportReservationCapture(SrppDecisionRepository decisions,SrppReportRepository reports){this.decisions=decisions;this.reports=reports;}
    @Transactional(readOnly=true)
    public List<ObjectNode> read(long owner,LocalDate date,String slot){
        LocalDate from=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));List<ObjectNode> out=new ArrayList<>();
        for(var run:decisions.reservations(owner,from,date))if(run.date().isBefore(date)||run.slot().compareTo(slot)<0)out.add((ObjectNode)SrppJcs.parseStrict(run.content()));
        for(String raw:reports.priorReportReservations(owner,from,date,slot)){
            JsonNode report=SrppJcs.parseStrict(raw);ObjectNode legacy=JsonNodeFactory.instance.objectNode();legacy.set("identity",report.path("identity"));legacy.put("calculationRunId",report.path("calculationRunId").asText());ArrayNode candidates=legacy.putObject("decision").putArray("candidates");
            for(JsonNode m:report.path("modules"))if("BOND_D130_D195".equals(m.path("moduleId").asText()))for(JsonNode leg:m.path("data").path("legs")){
                ObjectNode c=JsonNodeFactory.instance.objectNode();c.put("symbol",leg.path("symbol").asText());c.put("strategy",leg.path("strategy").asText());c.put("lots",leg.path("lots").asInt());c.set("amountTwd",leg.has("amount")?leg.path("amount"):leg.path("amountTwd"));candidates.add(c);
            }
            out.add(legacy);
        }
        return List.copyOf(out);
    }
    @Transactional(readOnly=true)
    public ObjectNode readReportSource(long owner,LocalDate date,String slot,String currentPolicy){
        ObjectNode source=JsonNodeFactory.instance.objectNode();source.put("ownerUserId",owner);source.put("tradingDate",date.toString());source.put("policyBundleSha256",currentPolicy);source.put("status","VERIFIED");source.put("verified",true);source.put("coverage","COMPLETE");ArrayNode rows=source.putArray("rows");Set<String> seen=new HashSet<>();
        for(String raw:reports.unlinkedReportReservations(owner,date,slot)){
            JsonNode report=SrppReportEnvelopeValidator.validate(raw),identity=report.path("identity");
            if(identity.path("ownerUserId").asLong()!=owner)throw new SrppReportProblem("UPSTREAM_INVALID");
            for(JsonNode module:report.path("modules")){int index=0;for(JsonNode leg:module.path("data").path("legs")){
                int legIndex=index++;if(!"ACTIONABLE_FOR_REPORT".equals(leg.path("status").asText()))continue;
                JsonNode quantity=leg.path("units").isNull()?leg.path("lots"):leg.path("units");
                if(quantity.isMissingNode()||new BigDecimal(quantity.asText()).signum()<=0)continue;
                String key=report.path("sharedCalculationId").asText()+"/"+module.path("moduleId").asText()+"/"+legIndex;if(!seen.add(key))continue;
                ObjectNode row=rows.addObject();row.put("origin","REPORT");row.putNull("decisionRunId");row.put("reservationId",SrppJcs.sha256Hex("REPORT/"+key));row.set("calculationRunId",report.path("calculationRunId"));row.set("sharedCalculationId",report.path("sharedCalculationId"));row.set("tradingDate",identity.path("tradingDate"));row.set("reportSlot",identity.path("reportSlot"));row.set("policyBundleSha256",identity.path("policyBundleSha256"));row.set("moduleId",module.path("moduleId"));row.put("legIndex",legIndex);
                for(String field:List.of("symbol","market","strategy","action","lots","units","amount","currency","fees"))row.set(field,leg.path(field));
                row.put("status","ACTIVE");
                row.putNull("linkedOrderId");row.putNull("linkedFillId");
            }}
        }
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
        if(!missing.isEmpty())source.put("status","UNAVAILABLE").put("verified",false).put("coverage","PARTIAL");
        return source;
    }
}
