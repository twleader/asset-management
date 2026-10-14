package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import java.time.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class SrppProjectionQueryTest {
 private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-14T05:00:00Z"),ZoneId.of("Asia/Taipei"));
 private static LinkedMultiValueMap<String,String> common(){var p=new LinkedMultiValueMap<String,String>();p.add("consumer","Codex");p.add("tradingDate","2026-10-14");p.add("reportSlot","12:00");p.add("policyBundleSha256","a".repeat(64));p.add("swaggerSha256","b".repeat(64));p.add("calculationRunId","11111111-1111-4111-8111-111111111111");return p;}
 @Test void orderAcceptsOneClosedDecision(){var p=common();p.add("decisions","BOND_D130_D195.0~WAIT~RISK_MODE");p.add("asOf","2026-10-14T12:00:00+08:00");var q=SrppProjectionQuery.order(p,new HttpHeaders(),CLOCK);assertThat(q.businessUri()).contains("/internal/public-srpp/order-plan").contains("decisions=BOND_D130_D195.0~WAIT~RISK_MODE");}
 @Test void orderRejectsUnknownAndDuplicateLeg(){var p=common();p.add("decisions","A.0~ADOPT,A.0~ADOPT");p.add("asOf","2026-10-14T12:00:00+08:00");assertThatThrownBy(()->SrppProjectionQuery.order(p,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);}
 @Test void factsRejectsNonTaiwanCode(){var p=common();p.add("stockCodes","US:VOO");assertThatThrownBy(()->SrppProjectionQuery.facts(p,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);}
 @Test void orderRejectsFutureAsOfAtBffBoundary(){var p=common();p.add("decisions","A.0~ADOPT");p.add("asOf","2026-10-14T13:01:00+08:00");assertThatThrownBy(()->SrppProjectionQuery.order(p,new HttpHeaders(),CLOCK)).isInstanceOf(SrppReportProblemException.class);}
 @Test void sealedValidatorAcceptsExactProjectionAndRejectsLeakedField(){
  var q=new SrppProjectionQuery(SrppProjectionQuery.ORDER,"/internal/public-srpp/order-plan",null,"Codex","2026-10-14","12:00","a".repeat(64),"b".repeat(64),"11111111-1111-4111-8111-111111111111","A.0~ADOPT","2026-10-14T04:00:00Z",null);
  ObjectNode valid=orderProjection();
  assertThatCode(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,valid.toString().getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
  ObjectNode leaked=orderProjection();((ObjectNode)leaked.path("legs").get(0)).put("broker","must-not-leak");rehash(leaked);
  assertThatThrownBy(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,leaked.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
  ObjectNode quoted=orderProjection();ObjectNode leg=(ObjectNode)quoted.path("legs").get(0);leg.put("limitPrice","1");leg.put("amount","1");ObjectNode quote=leg.putObject("quoteCapture");quote.put("sourceId","market:台股:0050");quote.put("sourceRevision","r1");quote.put("sourceSha256","e".repeat(64));quote.put("capturedAt","2026-10-14T03:00:00Z");ObjectNode receipt=quote.putObject("receiptRef");receipt.put("kind","QUOTE");receipt.put("sha256","e".repeat(64));receipt.put("capturedAt","2026-10-14T03:00:00Z");rehash(quoted);
  assertThatCode(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,quoted.toString().getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
  ((ObjectNode)quote.path("receiptRef")).put("sha256","f".repeat(64));rehash(quoted);
  assertThatThrownBy(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,quoted.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void validatorRejectsAsOfOrPlanAddressMismatch(){
  var q=new SrppProjectionQuery(SrppProjectionQuery.ORDER,"/internal/public-srpp/order-plan",null,"Codex","2026-10-14","12:00","a".repeat(64),"b".repeat(64),"11111111-1111-4111-8111-111111111111","A.0~ADOPT","2026-10-14T04:00:00Z",null);
  ObjectNode wrongAsOf=orderProjection();wrongAsOf.put("asOf","2026-10-14T03:59:59Z");rehash(wrongAsOf);
  assertThatThrownBy(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,wrongAsOf.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
  ObjectNode wrongPlan=orderProjection();wrongPlan.put("planId","f".repeat(64));rehashContentOnly(wrongPlan);
  assertThatThrownBy(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,wrongPlan.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void factsValidatorAcceptsSourceReceiptT2ButRejectsFactAsOfNotEqualToPackageT1(){
  var q=new SrppProjectionQuery(SrppProjectionQuery.FACTS,"/internal/public-srpp/symbol-rule-facts",null,"Codex","2026-10-14","12:00","a".repeat(64),"b".repeat(64),"11111111-1111-4111-8111-111111111111",null,null,"0050");
  ObjectNode valid=factsProjection();
  assertThatCode(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,valid.toString().getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
  ((ObjectNode)valid.path("symbols").get(0).path("facts").get(0)).put("asOf","2026-10-14T02:30:00Z");rehash(valid);
  assertThatThrownBy(()->PublicSrppProjectionService.validate(q,7,MediaType.APPLICATION_JSON,valid.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void translatesOnlySealedProblemBody(){
  var q=new SrppProjectionQuery(SrppProjectionQuery.ORDER,"/internal/public-srpp/order-plan",null,"Codex","2026-10-14","12:00","a".repeat(64),"b".repeat(64),"11111111-1111-4111-8111-111111111111","A.0~ADOPT","2026-10-14T04:00:00Z",null);
  String body="{\"type\":\"about:blank\",\"title\":\"Not a Taiwan trading day\",\"status\":409,\"detail\":\"指定日期不是台股交易日。\",\"instance\":\"/api/public/srpp/order-plan\",\"code\":\"NON_TRADING_DAY\",\"retryable\":false}";
  assertThat(PublicSrppProjectionService.translateProblem(q,409,MediaType.APPLICATION_PROBLEM_JSON,body.getBytes(StandardCharsets.UTF_8)).problem()).isEqualTo(SrppReportProblemCatalog.NON_TRADING_DAY);
 }
 static ObjectNode orderProjection(){
  var f=JsonNodeFactory.instance;ObjectNode r=f.objectNode();r.put("schemaVersion","1");r.put("planId","c".repeat(64));ObjectNode i=r.putObject("identity");i.put("ownerUserId",7);i.put("consumer","Codex");i.put("tradingDate","2026-10-14");i.put("reportSlot","12:00");i.put("policyBundleSha256","a".repeat(64));i.put("swaggerSha256","b".repeat(64));ObjectNode b=r.putObject("base");b.put("packageContentSha256","c".repeat(64));b.put("inputSnapshotSha256","d".repeat(64));b.put("inputSnapshotCapturedAt","2026-10-14T03:00:00Z");r.put("asOf","2026-10-14T04:00:00Z");var d=r.putArray("normalizedDecisions").addObject();d.put("legId","A.0");d.put("verdict","ADOPT");d.putNull("amount");d.putNull("reason");ObjectNode risk=r.putObject("derivedRisk");risk.put("mode","DEFENSIVE");risk.put("status","UNAVAILABLE");risk.putArray("reasonCodes").add("RISK_EVIDENCE_UNAVAILABLE");risk.putArray("receiptRefs");ObjectNode l=r.putArray("legs").addObject();l.put("legId","A.0");l.put("market","TW");l.put("symbol","0050");l.put("action","BUY");l.put("verdict","ADOPT");l.putArray("reasonCodes").add("RISK_EVIDENCE_UNAVAILABLE");l.put("baseLots",1);l.putNull("baseUnits");l.put("finalLots",0);l.putNull("finalUnits");l.putNull("limitPrice");l.putNull("amount");l.putNull("quoteCapture");l.putNull("freshness");l.putNull("settlement");l.put("executable",false);ObjectNode c=r.putObject("coverage");c.put("requestedCount",1);c.put("completeCount",0);c.put("partialCount",0);c.put("unavailableCount",0);c.put("notEvaluatedCount",1);r.put("executionScope","REPORT_RECOMMENDATION_ONLY");r.put("tradeAuthorization",false);r.put("placesOrders",false);r.putArray("receiptRefs");rehash(r);return r;
  }
 static ObjectNode factsProjection(){
  var f=JsonNodeFactory.instance;ObjectNode r=f.objectNode();r.put("schemaVersion","1");ObjectNode i=r.putObject("identity");i.put("ownerUserId",7);i.put("consumer","Codex");i.put("tradingDate","2026-10-14");i.put("reportSlot","12:00");i.put("policyBundleSha256","a".repeat(64));i.put("swaggerSha256","b".repeat(64));ObjectNode b=r.putObject("base");b.put("packageContentSha256","c".repeat(64));b.put("inputSnapshotSha256","d".repeat(64));b.put("inputSnapshotCapturedAt","2026-10-14T03:00:00Z");ObjectNode s=r.putArray("symbols").addObject();s.put("symbol","0050");s.put("market","TW");var facts=s.putArray("facts");for(String name:java.util.List.of("WEEK52_POSITION","K","D","RSI5","BIAS10","WILLIAMS_R9","PREMIUM_PERCENT"))facts.add(availableFact(f,name,name.equals("WEEK52_POSITION")?"0.5":name.equals("K")?"91":name.equals("D")?"81":name.equals("RSI5")?"80":name.equals("BIAS10")?"8":name.equals("WILLIAMS_R9")?"20":"1",name.equals("WEEK52_POSITION")?"RATIO":"PERCENT"));facts.add(availableBoolean(f,"OVERHEAT_HIT",true));for(String name:java.util.List.of("LOW_HIT","ENTRY_WATCH","BUY_OBSERVATION","SELL_OBSERVATION"))facts.add(unavailableFact(f,name));s.put("status","NOT_EVALUATED");s.putArray("reasonCodes");ObjectNode c=r.putObject("coverage");c.put("requestedCount",1);c.put("completeCount",0);c.put("partialCount",0);c.put("unavailableCount",0);c.put("notEvaluatedCount",1);r.put("executionScope","REPORT_RECOMMENDATION_ONLY");r.put("tradeAuthorization",false);r.put("placesOrders",false);r.putArray("receiptRefs");rehash(r);return r;
 }
 private static ObjectNode availableFact(JsonNodeFactory f,String name,String value,String unit){ObjectNode n=f.objectNode();n.put("name",name);n.put("value",value);n.put("unit",unit);n.put("status","AVAILABLE");n.put("asOf","2026-10-14T03:00:00Z");n.put("sourceRevision","e".repeat(64));ObjectNode ref=n.putArray("receiptRefs").addObject();ref.put("kind","SOURCE");ref.put("sha256","e".repeat(64));ref.put("capturedAt","2026-10-14T02:30:00Z");n.putArray("reasonCodes");return n;}
 private static ObjectNode availableBoolean(JsonNodeFactory f,String name,boolean value){ObjectNode n=availableFact(f,name,"0","NONE");n.put("value",value);return n;}
 private static ObjectNode unavailableFact(JsonNodeFactory f,String name){ObjectNode n=f.objectNode();n.put("name",name);n.putNull("value");n.put("unit","NONE");n.put("status","NOT_EVALUATED");n.putNull("asOf");n.putNull("sourceRevision");n.putArray("receiptRefs");n.putArray("reasonCodes").add("FROZEN_INPUT_UNAVAILABLE");return n;}
 static void rehash(ObjectNode root){if(root.has("planId")){ObjectNode address=JsonNodeFactory.instance.objectNode();address.set("identity",root.path("identity").deepCopy());address.set("base",root.path("base").deepCopy());address.put("asOf",root.path("asOf").asText());address.set("normalizedDecisions",root.path("normalizedDecisions").deepCopy());root.put("planId",SrppJcs.sha256Hex(SrppJcs.canonicalize(address)));}rehashContentOnly(root);}
 static void rehashContentOnly(ObjectNode root){ObjectNode content=root.deepCopy();content.remove("contentSha256");root.put("contentSha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(content)));}
}
