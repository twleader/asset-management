package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import java.time.*;

/** Explicit market clock and immutable source projection. No source lookup or trading side effects. */
public record SrppZeroTargetExitContext(JsonNode model, JsonNode sources, JsonNode sourceVector,
        long ownerUserId, LocalDate date, Instant capturedAt, String market, String slot, String policyHash) {
    public SrppZeroTargetExitContext {
        if(model==null||sources==null||sourceVector==null||ownerUserId<=0||date==null||capturedAt==null
                ||!java.util.Set.of("台股","美股").contains(market)||slot==null||policyHash==null)
            throw new IllegalArgumentException("D236_CONTEXT_UNVERIFIED");
    }
    public static SrppZeroTargetExitContext taiwan(Frozen f,JsonNode model,String policyHash){
        return new SrppZeroTargetExitContext(model,f.input().path("sources"),f.sourceVector(),
                f.input().path("ownerUserId").asLong(),f.facts().date(),f.facts().capturedAt(),"台股",f.facts().slot(),policyHash);
    }
    public boolean us(){return market.equals("美股");}
    public String marketRef(String symbol){return us()?"market":"market:台股:"+symbol;}
    public ZoneId zone(){return ZoneId.of(us()?"America/New_York":"Asia/Taipei");}
}
