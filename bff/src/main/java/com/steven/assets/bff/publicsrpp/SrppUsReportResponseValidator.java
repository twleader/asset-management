package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/** Independent transport/identity/source integrity checks; never calculates investment rules. */
final class SrppUsReportResponseValidator {
 private SrppUsReportResponseValidator() {}
 static void validate(SrppUsReportQuery q,Long owner,MediaType type,byte[] bytes){
  if(type==null||!"application".equalsIgnoreCase(type.getType())||!"json".equalsIgnoreCase(type.getSubtype()))fail();
  JsonNode r=SrppDailyContextResponseValidator.parseStrict(bytes);SrppUsSchemaValidator.validate(r);
  fields(r,"schemaVersion","apiScope","status","calculationRunId","identity","calculatorVersion","capturedAt","publishedAt","displayReportTime","session","inputBundle","sourceVector","inputSnapshotSha256","modules","tradeAuthorization","placesOrders","contentSha256");
  if(!r.path("schemaVersion").isIntegralNumber()||r.path("schemaVersion").intValue()!=1||!"US_REPORT_CALCULATIONS".equals(text(r,"apiScope"))||!"FINAL".equals(text(r,"status")))fail();
  falseValue(r,"tradeAuthorization");falseValue(r,"placesOrders");sha(r,"contentSha256");sha(r,"inputSnapshotSha256");
  if(!text(r,"calculationRunId").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||q.calculationRunId()!=null&&!q.calculationRunId().equals(text(r,"calculationRunId")))fail();
  if(!"US_REPORT_CALCULATOR_V1".equals(text(r,"calculatorVersion")))fail();
  JsonNode i=r.get("identity");fields(i,"ownerUserId","consumer","market","sessionDate","reportSlot","policyBundleSha256","swaggerSha256");
  if(owner==null||!i.path("ownerUserId").isIntegralNumber()||i.path("ownerUserId").longValue()!=owner||!q.consumer().equals(text(i,"consumer"))||!q.market().equals(text(i,"market"))||!q.sessionDate().equals(text(i,"sessionDate"))||!q.reportSlot().equals(text(i,"reportSlot"))||!q.policyBundleSha256().equals(text(i,"policyBundleSha256"))||!q.swaggerSha256().equals(text(i,"swaggerSha256")))fail();
  var captured=time(r,"capturedAt");var published=time(r,"publishedAt");
  if(published.isBefore(captured)||!captured.atZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate().toString().equals(q.sessionDate()))fail();
  String display=text(r,"displayReportTime");if(!Set.of("21:45","22:45").contains(display))fail();
  int offset=captured.atZoneSameInstant(ZoneId.of("America/New_York")).getOffset().getTotalSeconds();if(!(offset==-14400?"21:45":"22:45").equals(display))fail();
  JsonNode session=r.get("session");fields(session,"timezone","open","close","calendarVersion");if(!"America/New_York".equals(text(session,"timezone")))fail();text(session,"calendarVersion");
  var open=time(session,"open");var close=time(session,"close");
  var ny=ZoneId.of("America/New_York");var day=java.time.LocalDate.parse(q.sessionDate());var early=Set.of("2026-11-27","2026-12-24","2027-11-26","2028-07-03","2028-11-24").contains(q.sessionDate());if(day.getYear()<2026||day.getYear()>2028||!open.toInstant().equals(day.atTime(9,30).atZone(ny).toInstant())||!close.toInstant().equals(day.atTime(early?13:16,0).atZone(ny).toInstant())||!"NYSE_CASH_2026_2028_V1".equals(text(session,"calendarVersion")))fail();
  if(!close.isAfter(open)||captured.isBefore(open)||!captured.isBefore(close)||!open.atZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate().toString().equals(q.sessionDate()))fail();
  JsonNode b=r.get("inputBundle");fields(b,"ownerUserId","market","sessionDate","reportSlot","capturedAt","sources","stateBefore");
  if(!b.get("ownerUserId").equals(i.get("ownerUserId"))||!q.market().equals(text(b,"market"))||!q.sessionDate().equals(text(b,"sessionDate"))||!q.reportSlot().equals(text(b,"reportSlot"))||!text(r,"capturedAt").equals(text(b,"capturedAt")))fail();
  if(!text(r,"inputSnapshotSha256").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(b))))fail();
  JsonNode sources=b.get("sources"),v=r.get("sourceVector");if(!sources.isObject()||!v.isObject()||sources.size()!=v.size()||sources.isEmpty())fail();
  Set<String> ids=new HashSet<>(),verified=new HashSet<>();
  v.fields().forEachRemaining(e->{JsonNode receipt=e.getValue();fields(receipt,"sourceId","revision","capturedAt","sha256","coverage","validation");if(!e.getKey().equals(text(receipt,"sourceId"))||!ids.add(e.getKey())||!sources.has(e.getKey()))fail();text(receipt,"revision");sha(receipt,"sha256");if(time(receipt,"capturedAt").isAfter(captured)||!Set.of("COMPLETE","PARTIAL","NONE").contains(text(receipt,"coverage"))||!Set.of("VERIFIED","UNAVAILABLE").contains(text(receipt,"validation"))||!text(receipt,"sha256").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(sources.get(e.getKey())))))fail();if("VERIFIED".equals(text(receipt,"validation")))verified.add(e.getKey());});
  if(!ids.containsAll(Set.of("assets","policy","swagger","core-state")))fail();
  JsonNode policy=sources.get("policy");if(!q.policyBundleSha256().equals(text(policy,"bundleHash"))||!q.swaggerSha256().equals(text(sources.get("swagger"),"sha256")))fail();
  JsonNode state=b.get("stateBefore");if(!state.equals(sources.get("core-state"))||!state.path("ownerUserId").equals(i.path("ownerUserId"))||!q.consumer().equals(state.path("consumer").asText())||!"US".equals(state.path("market").asText()))fail();
  JsonNode modules=r.get("modules");if(!modules.isArray()||modules.size()!=2)fail();Set<String> seen=new HashSet<>();
  for(JsonNode m:modules){fields(m,"moduleId","coverage","data","reasonCodes","inputRefs");String id=text(m,"moduleId"),coverage=text(m,"coverage");if(!Set.of("CORE_SELL_GUARD","US_TACTICAL").contains(id)||!seen.add(id)||!Set.of("COMPLETE","PARTIAL","UNAVAILABLE").contains(coverage)||!m.get("data").isObject())fail();texts(m.get("reasonCodes"));texts(m.get("inputRefs"));for(JsonNode ref:m.get("inputRefs"))if(!ids.contains(ref.textValue())||"COMPLETE".equals(coverage)&&!verified.contains(ref.textValue()))fail();if(!"COMPLETE".equals(coverage)&&m.get("reasonCodes").isEmpty())fail();if(!coverage.equals(m.get("data").path("coverage").asText())||!m.get("reasonCodes").equals(m.get("data").path("reasonCodes")))fail();if("COMPLETE".equals(coverage)&&!m.get("reasonCodes").isEmpty())fail();if("CORE_SELL_GUARD".equals(id)&&"COMPLETE".equals(coverage)&&(!verified.contains("core-state")||!"VERIFIED".equals(state.path("status").asText())||!state.path("ledger").isObject()||!state.path("importLedgerSha256").isTextual()))fail();
   if("CORE_SELL_GUARD".equals(id))for(JsonNode row:m.get("data").path("results"))if(row.path("timing_pass").asBoolean()!= "CORE_SELL_READY".equals(row.path("status").asText()))fail();
   if("US_TACTICAL".equals(id))for(JsonNode row:m.get("data").path("results")){Set<String> blocking=new HashSet<>(),supported=new HashSet<>();row.path("blockingReasons").forEach(x->blocking.add(x.asText()));for(JsonNode gate:row.path("gateEvidence"))if("BLOCKED".equals(gate.path("status").asText())){if(gate.path("sourceRefs").isEmpty()||gate.path("reasons").isEmpty())fail();gate.path("reasons").forEach(x->supported.add(x.asText()));}if(!blocking.equals(supported))fail();if(!"PASS_FOR_REPORT_REVIEW".equals(row.path("status").asText()))for(String key:Set.of("units","amount_usd","fees_usd","settlement_twd"))if(!row.path(key).isNull())fail();}}
  ObjectNode projection=((ObjectNode)r).deepCopy();projection.remove("contentSha256");if(!text(r,"contentSha256").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(projection))))fail();
 }
 private static void fields(JsonNode n,String...keys){if(n==null||!n.isObject()||n.size()!=keys.length)fail();for(String k:keys)if(!n.has(k))fail();}
 private static String text(JsonNode n,String k){JsonNode v=n==null?null:n.get(k);if(v==null||!v.isTextual()||v.textValue().isEmpty())fail();return v.textValue();}
 private static void texts(JsonNode n){if(n==null||!n.isArray())fail();Set<String>s=new HashSet<>();for(JsonNode v:n)if(!v.isTextual()||v.textValue().isEmpty()||!s.add(v.textValue()))fail();}
 private static OffsetDateTime time(JsonNode n,String k){try{return OffsetDateTime.parse(text(n,k));}catch(RuntimeException e){throw new SrppPayloadException("US source timestamp invalid");}}
 private static void sha(JsonNode n,String k){if(!text(n,k).matches("[0-9a-f]{64}"))fail();}
 private static void falseValue(JsonNode n,String k){if(!n.path(k).isBoolean()||n.path(k).booleanValue())fail();}
 private static void fail(){throw new SrppPayloadException("SRPP US report response invalid");}
}
