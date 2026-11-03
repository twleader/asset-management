package com.steven.assets.bff.publicsrpp;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/** Strict selectors only; no caller-supplied financial evidence or refresh operation. */
record SrppUsReportQuery(String email, String consumer, String market, String sessionDate, String reportSlot,
                         String policyBundleSha256, String swaggerSha256, String calculationRunId) {
 static final String PATH="/api/public/srpp/us-report-calculations";
 static final String INTERNAL="/internal/public-srpp/us-report-calculations";
 private static final Set<String> KEYS=Set.of("email","consumer","market","sessionDate","reportSlot","policyBundleSha256","swaggerSha256","calculationRunId");
 static SrppUsReportQuery parse(MultiValueMap<String,String> p,HttpHeaders h,Clock clock){
  if(p==null || h!=null && (h.getContentLength()>0 || h.containsHeader(HttpHeaders.TRANSFER_ENCODING)))throw invalid();
  p.forEach((k,v)->{if(!KEYS.contains(k)||v==null||v.size()!=1||v.getFirst()==null||v.getFirst().isEmpty()||!v.getFirst().equals(v.getFirst().strip()))throw invalid();});
  String email=p.getFirst("email");if(email!=null && (email.length()>254||!SrppOrchestratedQuery.EMAIL.matcher(email).matches()))throw invalid();
  String consumer=required(p,"consumer"),market=required(p,"market"),date=required(p,"sessionDate"),slot=required(p,"reportSlot");
  if(!Set.of("Codex","Claude").contains(consumer)||!"US".equals(market)||!"US_OPEN_0945".equals(slot)||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();
  try{if(!LocalDate.parse(date).equals(LocalDate.now(clock.withZone(ZoneId.of("America/New_York")))))throw invalid();}catch(RuntimeException e){throw invalid();}
  String policy=required(p,"policyBundleSha256"),swagger=required(p,"swaggerSha256"),run=p.getFirst("calculationRunId");
  if(!policy.matches("[0-9a-f]{64}")||!swagger.matches("[0-9a-f]{64}")||run!=null&&!run.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();
  return new SrppUsReportQuery(email,consumer,market,date,slot,policy,swagger,run);
 }
 String businessUri(){var b=UriComponentsBuilder.fromPath(INTERNAL).queryParam("consumer",consumer).queryParam("market",market).queryParam("sessionDate",sessionDate).queryParam("reportSlot",reportSlot).queryParam("policyBundleSha256",policyBundleSha256).queryParam("swaggerSha256",swaggerSha256);if(calculationRunId!=null)b.queryParam("calculationRunId",calculationRunId);return b.encode().build().toUriString();}
 private static String required(MultiValueMap<String,String> p,String k){String v=p.getFirst(k);if(v==null)throw invalid();return v;}
 private static SrppUsReportProblemException invalid(){return new SrppUsReportProblemException(SrppUsReportProblemCatalog.INVALID_REQUEST);}
}
