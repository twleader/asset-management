package com.steven.assets.service.srpp.report;

import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Closed, pure parsing for Task 497.  No query key is silently ignored. */
final class SrppProjectionRequest {
    /** email is deliberately optional: the public BFF resolves it to an owner header and never forwards it. */
    private static final Set<String> REQUIRED_COMMON = Set.of("consumer", "tradingDate", "reportSlot", "policyBundleSha256", "swaggerSha256", "calculationRunId");
    private static final Set<String> COMMON = Set.of("email", "consumer", "tradingDate", "reportSlot", "policyBundleSha256", "swaggerSha256", "calculationRunId");
    static Common common(MultiValueMap<String,String> values, Set<String> allowed) {
        try {
            if (values == null || !allowed.containsAll(values.keySet()) || !values.keySet().containsAll(REQUIRED_COMMON)) throw new IllegalArgumentException();
            for (var e : values.entrySet()) if (e.getValue() == null || e.getValue().size() != 1 || e.getValue().getFirst() == null || e.getValue().getFirst().isBlank() || !e.getValue().getFirst().equals(e.getValue().getFirst().strip())) throw new IllegalArgumentException();
            String email=values.getFirst("email"), consumer=values.getFirst("consumer"), date=values.getFirst("tradingDate"), slot=values.getFirst("reportSlot"), policy=values.getFirst("policyBundleSha256"), swagger=values.getFirst("swaggerSha256"), run=values.getFirst("calculationRunId");
            if (email != null && (email.length()>254 || !email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))) throw new IllegalArgumentException();
            if (!Set.of("Codex","Claude").contains(consumer) || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !Set.of("09:05","12:00").contains(slot) || !policy.matches("[a-f0-9]{64}") || !swagger.matches("[a-f0-9]{64}") || !run.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException();
            return new Common(email,consumer,LocalDate.parse(date),slot,policy,swagger,UUID.fromString(run));
        } catch (RuntimeException bad) { throw new SrppReportProblem("INVALID_REQUEST"); }
    }
    static Order order(MultiValueMap<String,String> values) {
        Common common=common(values, union(COMMON,Set.of("decisions","asOf")));
        try {
            String raw=one(values,"decisions"), asOf=one(values,"asOf");
            if (!asOf.equals(asOf.strip())) throw new IllegalArgumentException();
            Instant at=Instant.parse(asOf);
            String[] parts=raw.split(",",-1); if(parts.length<1||parts.length>25)throw new IllegalArgumentException();
            List<Decision> decisions=new ArrayList<>(); Set<String> ids=new HashSet<>();
            for(String token:parts){ if(token.isEmpty()||!token.equals(token.strip()))throw new IllegalArgumentException(); String[] f=token.split("~",-1); if(f.length<2||f.length>4||f[0].isEmpty()||!f[0].matches("[A-Z][A-Z0-9_]{0,127}\\.[0-9]{1,6}")||!ids.add(f[0]))throw new IllegalArgumentException();
                switch(f[1]) {
                    case "ADOPT" -> { if(f.length!=2)throw new IllegalArgumentException(); decisions.add(new Decision(f[0],"ADOPT",null,null)); }
                    case "REDUCE" -> { if(f.length!=4||!f[2].matches("[1-9][0-9]{0,17}(?:\\.[0-9]{1,8})?")||!reason(f[3]))throw new IllegalArgumentException(); decisions.add(new Decision(f[0],"REDUCE",f[2],f[3])); }
                    case "WAIT", "VETO" -> { if(f.length!=3||!reason(f[2]))throw new IllegalArgumentException(); decisions.add(new Decision(f[0],f[1],null,f[2])); }
                    default -> throw new IllegalArgumentException();
                }
            }
            return new Order(common,at,List.copyOf(decisions));
        } catch(RuntimeException bad){ if(bad instanceof SrppReportProblem p)throw p;throw new SrppReportProblem("INVALID_REQUEST"); }
    }
    static Symbols symbols(MultiValueMap<String,String> values) {
        Common common=common(values, union(COMMON,Set.of("stockCodes"))); String raw=values.getFirst("stockCodes");
        if(raw==null)return new Symbols(common,List.of());
        try { String[] parts=raw.split(",",-1);if(parts.length<1||parts.length>60)throw new IllegalArgumentException(); Set<String> set=new TreeSet<>();for(String code:parts)if(!code.matches("[0-9]{4,5}[A-Z]?" )||!set.add(code))throw new IllegalArgumentException();return new Symbols(common,List.copyOf(set)); }catch(RuntimeException bad){throw new SrppReportProblem("INVALID_REQUEST");}
    }
    private static boolean reason(String value){return Set.of("EVENT_RISK","RISK_MODE","MARKET_CONTEXT","PRIORITY_ELSEWHERE","EVIDENCE_CONFLICT","OTHER").contains(value);}
    private static String one(MultiValueMap<String,String> values,String key){String v=values.getFirst(key);if(v==null||v.isEmpty())throw new IllegalArgumentException();return v;}
    private static Set<String> union(Set<String>a,Set<String>b){Set<String>x=new HashSet<>(a);x.addAll(b);return Set.copyOf(x);}
    record Common(String email,String consumer,LocalDate date,String slot,String policy,String swagger,UUID run) {}
    record Decision(String legId,String verdict,String amount,String reason) {}
    record Order(Common common, Instant asOf, List<Decision> decisions) {}
    record Symbols(Common common,List<String> stockCodes) {}
}
