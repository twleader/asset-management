package com.steven.assets.bff.publicsrpp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class SrppUsReportQueryTest {
 static Clock clock(String t){return Clock.fixed(Instant.parse(t),ZoneId.of("UTC"));}
 static LinkedMultiValueMap<String,String> query(){var p=new LinkedMultiValueMap<String,String>();p.add("consumer","Codex");p.add("market","US");p.add("sessionDate","2026-11-03");p.add("reportSlot","US_OPEN_0945");p.add("policyBundleSha256","a".repeat(64));p.add("swaggerSha256","b".repeat(64));return p;}
 @Test void nyDateSurvivesTaipeiMidnightAndEmailIsOnlyOwnerSelector(){var p=query();p.add("email","owner@example.invalid");assertThat(SrppUsReportQuery.parse(p,new HttpHeaders(),clock("2026-11-03T17:00:00Z")).businessUri()).contains("market=US","reportSlot=US_OPEN_0945").doesNotContain("email");}
 @Test void currentNyDateIsRequiredEvenForFixedId(){var p=query();p.add("calculationRunId","12345678-1234-1234-1234-123456789abc");assertThatThrownBy(()->SrppUsReportQuery.parse(p,new HttpHeaders(),clock("2026-11-04T17:00:00Z"))).isInstanceOf(SrppUsReportProblemException.class);}
 @Test void rejectsEveryUnknownDuplicateBlankAndBody(){for(String key:java.util.List.of("refresh","target","stateBefore","ownerUserId","costs")){var p=query();p.add(key,"1");assertThatThrownBy(()->SrppUsReportQuery.parse(p,new HttpHeaders(),clock("2026-11-03T15:00:00Z"))).isInstanceOf(SrppUsReportProblemException.class);}for(String key:query().keySet()){var p=query();p.add(key,p.getFirst(key));assertThatThrownBy(()->SrppUsReportQuery.parse(p,new HttpHeaders(),clock("2026-11-03T15:00:00Z"))).isInstanceOf(SrppUsReportProblemException.class);}for(String market:java.util.List.of("TW","美股"," US","")){var p=query();p.set("market",market);assertThatThrownBy(()->SrppUsReportQuery.parse(p,new HttpHeaders(),clock("2026-11-03T15:00:00Z"))).isInstanceOf(SrppUsReportProblemException.class);}var h=new HttpHeaders();h.setContentLength(1);assertThatThrownBy(()->SrppUsReportQuery.parse(query(),h,clock("2026-11-03T15:00:00Z"))).isInstanceOf(SrppUsReportProblemException.class);}
}
