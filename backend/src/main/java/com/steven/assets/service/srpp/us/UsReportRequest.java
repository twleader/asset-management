package com.steven.assets.service.srpp.us;

import org.springframework.util.MultiValueMap;
import java.time.*;
import java.util.*;

/** No facts, prices, target, owner id or clock can be supplied by a GET caller. */
public record UsReportRequest(String email,String consumer,String market,LocalDate sessionDate,String reportSlot,String policyHash,String swaggerHash,UUID runId){
    public static final String SLOT="US_OPEN_0945";
    private static final Set<String> REQUIRED=Set.of("consumer","market","sessionDate","reportSlot","policyBundleSha256","swaggerSha256");
    private static final Set<String> ALLOWED=Set.of("email","consumer","market","sessionDate","reportSlot","policyBundleSha256","swaggerSha256","calculationRunId");
    public static UsReportRequest parse(MultiValueMap<String,String> values){try{
        if(values==null||!ALLOWED.containsAll(values.keySet())||!values.keySet().containsAll(REQUIRED))throw new IllegalArgumentException();
        values.forEach((k,v)->{if(v==null||v.size()!=1||v.getFirst()==null||v.getFirst().isBlank())throw new IllegalArgumentException();});
        String email=values.getFirst("email"),consumer=values.getFirst("consumer"),market=values.getFirst("market"),date=values.getFirst("sessionDate"),slot=values.getFirst("reportSlot"),policy=values.getFirst("policyBundleSha256"),swagger=values.getFirst("swaggerSha256"),run=values.getFirst("calculationRunId");
        if(email!=null&&(email.length()>254||!email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")))throw new IllegalArgumentException();
        if(!Set.of("Codex","Claude").contains(consumer)||!"US".equals(market)||!SLOT.equals(slot)||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!policy.matches("[a-f0-9]{64}")||!swagger.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
        if(run!=null&&!run.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException();
        return new UsReportRequest(email,consumer,market,LocalDate.parse(date),slot,policy,swagger,run==null?null:UUID.fromString(run));
    }catch(RuntimeException invalid){throw new UsReportProblem("INVALID_REQUEST");}}
}
