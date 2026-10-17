package com.steven.assets.service.srpp.report;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.srpp.SrppJcs;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;
/** Current-policy report-only frame; preserves the capture object's immutable values and hash. */
public final class SrppReportFubonOverlay {
    private SrppReportFubonOverlay(){}
    public static DecisionInputCapturePort.Frozen apply(DecisionInputCapturePort.Frozen original,SrppReportFubonCapturePort.Capture capture){
        var old=original.facts();ObjectNode input=original.input(),vector=original.sourceVector(),sources=input.withObject("sources");ObjectNode raw=capture.source();
        if(input.path("ownerUserId").asLong()!=raw.path("ownerUserId").asLong()||!old.date().toString().equals(raw.path("tradingDate").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
        raw.put("policyBundleSha256",sources.path("policy").path("bundleHash").asText());
        sources.set("fubon-account",raw);receipt(vector,"fubon-account",raw,old.capturedAt().toString(),capture.verified());
        Map<String,Symbol> symbols=new TreeMap<>();
        for(var entry:old.symbols().entrySet()){
            Symbol s=entry.getValue();Evidence pending=capture.pendingFor(s.code(),"fubon");
            // 00865B's combined strategy/emergency typed gate needs both official broker accounts.
            // Its source cannot be upgraded by one Fubon account observation.
            if("00865B".equals(s.code()))pending=new Evidence("UNAVAILABLE","SUBACCOUNT_UNVERIFIED");
            Symbol replacement=new Symbol(s.code(),s.quote(),s.book(),s.closes(),s.distributions(),s.distributionVerified(),s.currentValue(),s.position(),pending,s.radar(),s.valuation(),s.tradability(),s.mediumAction(),s.mediumCandidateAction(),s.intraday());symbols.put(entry.getKey(),replacement);
            ObjectNode p=JsonNodeFactory.instance.objectNode();p.put("status",pending.status());p.put("reason",pending.reason());p.put("sourceId","fubon-account");p.put("broker","fubon");if(raw.hasNonNull("accountFingerprint"))p.set("accountFingerprint",raw.get("accountFingerprint"));p.put("symbol",s.code());sources.set("pending:"+s.code(),p);receipt(vector,"pending:"+s.code(),p,old.capturedAt().toString(),pending.verified());
        }
        Budget originalBudget=old.budget();
        // Any active unlinked order may consume shared cash or quotas. Until exact links
        // reconcile that observation, retain every prior reservation and block funding.
        Budget budget=raw.path("activeCount").asLong()>0?new Budget(originalBudget.strategyToday(),originalBudget.strategyWeek(),originalBudget.emergencyToday(),false,originalBudget.reservedFunds(),originalBudget.strategicReservedValues(),originalBudget.emergencyReservedValue(),originalBudget.reservedBondValue(),originalBudget.reservedFees()):originalBudget;
        Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),symbols,budget,old.subaccounts(),old.emergencyValue());
        input.set("facts",SrppReportModuleCalculator.normalize(new ObjectMapper().findAndRegisterModules().valueToTree(facts)));
        return new DecisionInputCapturePort.Frozen(facts,input,vector,original.assetSnapshotId(),original.assetGeneratedAt());
    }
    private static void receipt(ObjectNode vector,String id,ObjectNode source,String at,boolean verified){ObjectNode n=vector.putObject(id);n.put("sourceId",id);n.put("revision",SrppJcs.hash(source));n.put("sha256",SrppJcs.hash(source));n.put("capturedAt",at);n.put("coverage",verified?"COMPLETE":"NONE");n.put("validation",verified?"VERIFIED":"UNAVAILABLE");}
}
