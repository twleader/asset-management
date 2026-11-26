package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure D212 evidence port; unknown evidence retains the TSMC waiver. */
public final class SrppZeroTargetProtection {
    private SrppZeroTargetProtection(){}
    private static LocalDate day(JsonNode n){try{return LocalDate.parse(n.asText().substring(0,10));}catch(RuntimeException e){return null;}}
    private static boolean recent(JsonNode n,LocalDate today,int days){LocalDate d=day(n);return d!=null&&!d.isAfter(today)&&ChronoUnit.DAYS.between(d,today)<=days;}
    private static boolean checked(JsonNode item,LocalDate today,int age){return item.path("checked").isBoolean()&&item.path("checked").asBoolean()&&item.path("source").isTextual()&&!item.path("source").asText().isBlank()&&recent(item.path("source_date"),today,age);}
    private static boolean active(JsonNode item,LocalDate today,int age){return item.path("present").isBoolean()&&item.path("present").asBoolean()&&recent(item.hasNonNull("event_date")?item.path("event_date"):item.path("source_date"),today,age);}
    public static boolean waiver(JsonNode model,JsonNode proof,LocalDate today){
        JsonNode policy=model.path("execution_policy").path("bull_correction_dip_d212");
        if(!"D-212".equals(policy.path("decision").asText())||!policy.path("enabled").isBoolean()||!policy.path("enabled").asBoolean())throw new IllegalArgumentException("D236_D212_POLICY_UNVERIFIED");
        JsonNode ai=policy.path("ai_cycle_break");int active=0;
        for(var entry:ai.path("signals").properties()){
            JsonNode spec=entry.getValue(),item=proof.path("aiEvidence").path("signals").path(entry.getKey());boolean present;
            if(spec.has("companies")){int cuts=0;for(JsonNode company:spec.path("companies"))if(active(item.path("companies").path(company.asText()),today,ai.path("signal_validity_days").asInt(-1)))cuts++;present=cuts>=spec.path("minimum_companies_cut").asInt(Integer.MAX_VALUE);}
            else present=active(item,today,ai.path("signal_validity_days").asInt(-1));
            if(present)active++;
        }
        boolean aiPresent=active>=ai.path("minimum_signals_present").asInt(Integer.MAX_VALUE);
        JsonNode rule=policy.path("tsmc_exposure_waiver").path("catch_up"),signals=proof.path("catchUpEvidence").path("signals");boolean event=false;
        for(JsonNode name:rule.path("competitive_events"))event|=active(signals.path(name.asText()),today,rule.path("signal_validity_days").asInt(-1));
        JsonNode price=signals.path(rule.path("price_confirmation").asText());boolean drop=false;
        try{BigDecimal pre=new BigDecimal(price.path("pre_event_close").asText()),last=new BigDecimal(price.path("latest_close").asText()),threshold=new BigDecimal(rule.path("minimum_price_drop").asText()).add(new BigDecimal("0.000000000001"));
            drop=pre.signum()>0&&last.signum()>0&&last.compareTo(pre.multiply(BigDecimal.ONE.subtract(threshold)))<0&&price.path("attributed_to_event").isBoolean()&&price.path("attributed_to_event").asBoolean()&&checked(price,today,rule.path("maximum_source_age_days").asInt(-1));
        }catch(RuntimeException missing){/* Original unknown evidence retains the waiver. */}
        return !aiPresent&&!(event&&drop);
    }
}
