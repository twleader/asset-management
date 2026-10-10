package com.steven.assets.service.srpp.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.service.srpp.SrppCaptureProblem;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

/** Strict transport parsing; caller supplied facts never enter the calculator. */
public record DecisionRequest(String ownerEmail, LocalDate date, String slot, String policyHash, String swaggerHash) {
    public static DecisionRequest parse(String raw) {
        try {
            JsonNode n=SrppJcs.parseStrict(raw);
            Set<String> required=Set.of("tradingDate","slot","policyBundleSha256","swaggerSha256");
            Set<String> allowed=new HashSet<>(required); allowed.add("ownerEmail");
            if(!n.isObject()) throw new IllegalArgumentException();
            Set<String> actual=new HashSet<>();n.fieldNames().forEachRemaining(actual::add);
            if(!actual.containsAll(required)||!allowed.containsAll(actual)) throw new IllegalArgumentException();
            String email=n.has("ownerEmail")?text(n,"ownerEmail"):null;
            if(email!=null&&(email.length()>254||!email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"))) throw new IllegalArgumentException();
            String date=text(n,"tradingDate"),slot=text(n,"slot"),policy=text(n,"policyBundleSha256"),swagger=text(n,"swaggerSha256");
            if(!date.matches("\\d{4}-\\d{2}-\\d{2}")||!Set.of("09:05","11:40").contains(slot)||!policy.matches("[a-f0-9]{64}")||!swagger.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            return new DecisionRequest(email,LocalDate.parse(date),slot,policy,swagger);
        }catch(RuntimeException invalid){throw new SrppCaptureProblem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");}
    }
    public void checkClock(Instant now) {
        ZonedDateTime tw=now.atZone(ZoneId.of("Asia/Taipei"));
        if(!date.equals(tw.toLocalDate())||tw.toLocalTime().isBefore(LocalTime.parse(slot)))throw new SrppCaptureProblem(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");
    }
    private static String text(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isTextual())throw new IllegalArgumentException();return v.textValue();}
}
