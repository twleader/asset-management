package com.steven.assets.bff.publicsrpp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
class SrppReportQueryTest {
 static final java.time.Clock CLOCK=java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T05:00:00Z"),java.time.ZoneId.of("Asia/Taipei"));
 static LinkedMultiValueMap<String,String> valid(){
  var p=new LinkedMultiValueMap<String,String>();p.add("consumer","Codex");p.add("tradingDate","2026-10-12");
  p.add("reportSlot","12:00");p.add("policyBundleSha256","a".repeat(64));p.add("swaggerSha256","b".repeat(64));return p;
 }
 @Test void acceptsOnlyCurrentWireAndDoesNotForwardEmailAsTenant(){
  var p=valid();p.add("email","selected@example.invalid");var q=SrppReportQuery.parse(p,new HttpHeaders(),CLOCK);
  assertThat(q.businessUri()).contains("reportSlot=12:00","consumer=Codex").doesNotContain("email");
 }
 @Test void rejectsUnknownDuplicatedMissingEmptyAndWrongTypes(){
  for(String key:java.util.List.of("refresh","stockCodes","formula","rawInput","limitPrice","lots","score")){
   var p=valid();p.add(key,"1");assertThatThrownBy(()->SrppReportQuery.parse(p,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);
  }
  for(String key:java.util.List.of("consumer","tradingDate","reportSlot","policyBundleSha256","swaggerSha256")){
   var duplicate=valid();duplicate.add(key,duplicate.getFirst(key));assertThatThrownBy(()->SrppReportQuery.parse(duplicate,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);
   var missing=valid();missing.remove(key);assertThatThrownBy(()->SrppReportQuery.parse(missing,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);
  }
  for(var pair:java.util.Map.of("email"," ","consumer","Other","tradingDate","2026-02-30","reportSlot","11:40","policyBundleSha256","A".repeat(64),"swaggerSha256","short","calculationRunId","bad").entrySet()){
   var p=valid();p.set(pair.getKey(),pair.getValue());assertThatThrownBy(()->SrppReportQuery.parse(p,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);
  }
 }
 @Test void rejectsTaipeiOtherDayAndBeforeSlot() {
  var p=valid();p.set("tradingDate","2026-10-11");assertThatThrownBy(()->SrppReportQuery.parse(p,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);
  var before=java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T03:59:59Z"),java.time.ZoneId.of("Asia/Taipei"));
  assertThatThrownBy(()->SrppReportQuery.parse(valid(),new HttpHeaders(),before)).isInstanceOf(SrppReportProblemException.class);
  var crossDay=java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T16:00:00Z"),java.time.ZoneId.of("Asia/Taipei"));
  assertThatThrownBy(()->SrppReportQuery.parse(valid(),new HttpHeaders(),crossDay)).isInstanceOf(SrppReportProblemException.class);
 }
 @Test void rejectsBody(){var h=new HttpHeaders();h.setContentLength(1);assertThatThrownBy(()->SrppReportQuery.parse(valid(),h,CLOCK)).isInstanceOf(SrppReportProblemException.class);}
}
