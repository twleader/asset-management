package com.steven.assets.bff.publicsrpp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
class SrppReportResponseValidatorTest {
 private static final SrppReportQuery QUERY=new SrppReportQuery(null,"Codex","2026-10-12","12:00","a".repeat(64),"b".repeat(64),null);
 private static ObjectNode fixture(){try{return (ObjectNode)SrppDailyContextResponseValidator.parseStrict(SrppReportResponseValidatorTest.class.getResourceAsStream("/srpp-report/report-synthetic.json").readAllBytes());}catch(Exception e){throw new AssertionError(e);}}
 private static byte[] signed(ObjectNode n){n.remove("contentSha256");n.put("contentSha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(n)));return SrppJcs.canonicalize(n).getBytes(StandardCharsets.UTF_8);}
 private static void validate(ObjectNode n){SrppReportResponseValidator.validate(QUERY,1L,MediaType.APPLICATION_JSON,signed(n));}
 private static void rejects(Consumer<ObjectNode> mutation){var n=fixture();mutation.accept(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);}
 @Test void completeReadbackOfSyntheticPackageValidatesWithUnknownScopesExplicit(){validate(fixture());}
 @Test void acceptsActualPostgresqlPublisherWireWithScopeGapsExplicit() throws Exception {
  byte[] bytes=getClass().getResourceAsStream("/srpp-report/report-producer-pg.json").readAllBytes();
  var root=SrppDailyContextResponseValidator.parseStrict(bytes);var identity=root.get("identity");
  var query=new SrppReportQuery(null,identity.get("consumer").asText(),identity.get("tradingDate").asText(),identity.get("reportSlot").asText(),
    identity.get("policyBundleSha256").asText(),identity.get("swaggerSha256").asText(),root.get("calculationRunId").asText());
  SrppReportResponseValidator.validate(query,identity.get("ownerUserId").asLong(),MediaType.APPLICATION_JSON,bytes);
  assertThat(root.get("modules").size()).isEqualTo(12);
 }
 @Test void acceptsActualPostgresqlPublisherActionableBondLegs() throws Exception {
  byte[] bytes=getClass().getResourceAsStream("/srpp-report/report-producer-actionable-pg.json").readAllBytes();
  var root=SrppDailyContextResponseValidator.parseStrict(bytes);var identity=root.get("identity");
  var query=new SrppReportQuery(null,identity.get("consumer").asText(),identity.get("tradingDate").asText(),identity.get("reportSlot").asText(),
    identity.get("policyBundleSha256").asText(),identity.get("swaggerSha256").asText(),root.get("calculationRunId").asText());
  SrppReportResponseValidator.validate(query,identity.get("ownerUserId").asLong(),MediaType.APPLICATION_JSON,bytes);
  var bonds=root.get("modules").get(5).get("data").get("legs");
  assertThat(bonds.get(0).get("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
  assertThat(bonds.get(0).get("lots").asInt()).isEqualTo(2);
  assertThat(bonds.get(3).get("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
 }
 @Test void optionalOriginalBondGatesPreserveQuantityAndTypedShortOnlyScope() throws Exception {
  byte[] bytes=getClass().getResourceAsStream("/srpp-report/report-producer-optional-pg.json").readAllBytes();
  var root=SrppDailyContextResponseValidator.parseStrict(bytes);var identity=root.get("identity");
  var query=new SrppReportQuery(null,identity.get("consumer").asText(),identity.get("tradingDate").asText(),identity.get("reportSlot").asText(),
    identity.get("policyBundleSha256").asText(),identity.get("swaggerSha256").asText(),root.get("calculationRunId").asText());
  SrppReportResponseValidator.validate(query,identity.get("ownerUserId").asLong(),MediaType.APPLICATION_JSON,bytes);
  var leg=root.get("modules").get(5).get("data").get("legs").get(0);
  assertThat(leg.get("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
  assertThat(leg.get("lots").asInt()).isEqualTo(2);
  assertThat(leg.get("limitPrice").asText()).isEqualTo("9.81");
  var gates=new java.util.HashMap<String,JsonNode>();leg.get("receipts").forEach(r->gates.put(r.get("ruleId").asText(),r));
  assertThat(gates.get("TRADABILITY").get("status").asText()).isEqualTo("UNAVAILABLE");
  assertThat(gates.get("BEAR_WINDOW").get("status").asText()).isEqualTo("UNAVAILABLE");
  assertThat(gates.get("INTRADAY").get("status").asText()).isEqualTo("NOT_EVALUATED");
  assertThat(gates.get("INTRADAY").get("scope").asText()).isEqualTo("SHORT_ONLY");
 }
 @Test void refusesOwnerConsumerDateSlotAndAuthorityMismatchEvenAfterResigning(){
  rejects(n->((ObjectNode)n.get("identity")).put("ownerUserId",2));
  rejects(n->((ObjectNode)n.get("identity")).put("consumer","Claude"));
  rejects(n->((ObjectNode)n.get("identity")).put("tradingDate","2026-10-11"));
  rejects(n->((ObjectNode)n.get("identity")).put("reportSlot","09:05"));
  rejects(n->((ObjectNode)n.get("identity")).put("policyBundleSha256","0".repeat(64)));
  rejects(n->((ObjectNode)n.get("authorityRevision")).put("calculatorVersion","different"));
 }
 @Test void rejectsSchemaDecimalCoverageDuplicateModuleAndUnknownSourceRefsAfterResigning(){
  rejects(n->n.put("unexpected",true));
  rejects(n->((ObjectNode)n.get("coverage")).put("notEvaluatedCount",11));
  rejects(n->((ObjectNode)n.get("modules").get(1)).put("moduleId","ASSET_RECONCILIATION"));
  rejects(n->((com.fasterxml.jackson.databind.node.ArrayNode)n.get("modules").get(0).get("inputRefs")).add("unknown"));
  rejects(n->((ObjectNode)n.get("modules").get(0).get("data")).put("payload","untyped"));
  rejects(n->n.put("tradeAuthorization",true));
 }
 @Test void rejectsRawSourceTamperingAndConsumerStateCrossLinkEvenWithValidOuterHash(){
  rejects(n->((ObjectNode)n.get("inputSnapshot").get("sourceVector").get("assets")).put("sha256","0".repeat(64)));
  rejects(n->((ObjectNode)n.get("inputSnapshot").get("sourceVector").get("core-state")).put("validation","VERIFIED"));
  rejects(n->((ObjectNode)n.get("inputSnapshot").get("sourceVector").get("core-state")).put("coverage","PARTIAL"));
  rejects(n->{ObjectNode snapshot=(ObjectNode)n.get("inputSnapshot");ObjectNode bundle=(ObjectNode)SrppDailyContextResponseValidator.parseStrict(snapshot.get("inputBundleJcs").asText().getBytes(StandardCharsets.UTF_8));
   ((ObjectNode)bundle.get("stateBefore")).put("consumer","Claude");String jcs=SrppJcs.canonicalize(bundle);snapshot.put("inputBundleJcs",jcs);snapshot.put("inputSnapshotSha256",SrppJcs.sha256Hex(jcs));});
 }
 @Test void rejectsPositiveBlockedLegAndActionableLegWithoutVerifiedReceipts(){
  rejects(n->((com.fasterxml.jackson.databind.node.ArrayNode)n.get("modules").get(0).get("data").get("legs")).add(leg("BLOCKED",1)));
  rejects(n->((com.fasterxml.jackson.databind.node.ArrayNode)n.get("modules").get(0).get("data").get("legs")).add(leg("ACTIONABLE_FOR_REPORT",1)));
 }
 @Test void isoMoneyUnitsAndNewIncomePayrollFactIdentitiesValidateWithoutInventingZero(){
  var n=fixture();var modules=n.get("modules");
  for(int i:java.util.List.of(0,2,3,4))((ObjectNode)modules.get(i)).put("status","PARTIAL");
  ((ObjectNode)n.get("coverage")).put("partialCount",4).put("notEvaluatedCount",8);
  var facts=(com.fasterxml.jackson.databind.node.ArrayNode)modules.get(0).get("data").get("facts");
  for(String name:java.util.List.of("bankCurrencyBalanceOriginal","bankCurrencyBalanceTwd","bankCurrencyPayableOriginal","bankCurrencyReceivableOriginal"))facts.add(fact(name,"bank-1:EUR","0",name.endsWith("Twd")?"TWD":"EUR"));
  ((com.fasterxml.jackson.databind.node.ArrayNode)modules.get(2).get("data").get("facts")).add(fact("settledBalance","JPY","12345","JPY"));
  for(String name:java.util.List.of("spendableSgovGross","domesticDividend","domesticInterest","savingsInterest","otherDomesticIncome","overseasIncome","creditableForeignTax","nhiChargeableIncome","taxCalculation","annualTaxPrepayments"))
   ((com.fasterxml.jackson.databind.node.ArrayNode)modules.get(3).get("data").get("facts")).add(fact(name,"2026","0","TWD"));
  for(String name:java.util.List.of("paymentCount","payrollDetail","calendarYearPayroll","pensionReceipt"))
   ((com.fasterxml.jackson.databind.node.ArrayNode)modules.get(4).get("data").get("facts")).add(fact(name,"2027-01-05","0",name.equals("paymentCount")?"COUNT":"TWD"));
  validate(n);
  for(String invalid:java.util.List.of("eur","EURO","TW","NATIVE_PRICE","shares")) {
   var bad=n.deepCopy();((ObjectNode)bad.get("modules").get(0).get("data").get("facts").get(0)).put("unit",invalid);
   assertThatThrownBy(()->validate(bad)).isInstanceOf(RuntimeException.class);
  }
 }
 @Test void fullTopupIncomePayrollPostgresPackagesShareBaseAndIsolateConsumerState() throws Exception {
  ObjectNode codex=producer("full-codex"),claude=producer("full-claude");validateProducer(codex);validateProducer(claude);
  assertThat(codex.get("sharedCalculationId")).isEqualTo(claude.get("sharedCalculationId"));
  assertThat(codex.get("calculationRunId")).isNotEqualTo(claude.get("calculationRunId"));
  assertThat(codex.get("inputSnapshot").get("inputSnapshotSha256")).isNotEqualTo(claude.get("inputSnapshot").get("inputSnapshotSha256"));
  var a=codex.get("inputSnapshot").get("sourceVector");var b=claude.get("inputSnapshot").get("sourceVector");
  a.fields().forEachRemaining(e->{if(!e.getKey().equals("core-state"))assertThat(e.getValue()).isEqualTo(b.get(e.getKey()));});
  assertThat(codex.get("modules").get(3).get("status").asText()).isEqualTo("COMPLETE");
  assertThat(codex.get("modules").get(4).get("status").asText()).isEqualTo("PARTIAL");
  assertThat(codex.get("modules").get(6).get("data").get("legs")).anySatisfy(l->{assertThat(l.get("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(l.get("lots").asInt()).isEqualTo(3);});
  var identity=codex.get("identity");var wrong=new SrppReportQuery(null,"Claude",identity.get("tradingDate").asText(),identity.get("reportSlot").asText(),identity.get("policyBundleSha256").asText(),identity.get("swaggerSha256").asText(),null);
  assertThatThrownBy(()->SrppReportResponseValidator.validate(wrong,identity.get("ownerUserId").asLong(),MediaType.APPLICATION_JSON,signed(codex))).isInstanceOf(RuntimeException.class);
 }
 @Test void fullShortPostgresBuySellUnitsValidateWithUsUnavailable() throws Exception {
  for(String side:java.util.List.of("buy","sell"))for(String consumer:java.util.List.of("codex","claude")){
   ObjectNode root=producer("short-"+side+"-"+consumer);validateProducer(root);var module=root.get("modules").get(11);
   assertThat(module.get("status").asText()).isEqualTo("COMPLETE");assertThat(module.get("inputRefs").toString()).doesNotContain("us-tactical");
   assertThat(root.get("inputSnapshot").get("sourceVector").get("us-tactical").get("validation").asText()).isEqualTo("UNAVAILABLE");
   assertThat(module.get("data").get("legs")).anySatisfy(l->{assertThat(l.get("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(l.get("action").asText()).isEqualTo(side.toUpperCase());assertThat(l.get("lots").isNull()).isTrue();assertThat(new java.math.BigDecimal(l.get("units").asText())).isPositive();});
  }
 }
 @Test void fullProducerRequiredSharedCashGateCannotBeRemovedOrReboundAfterResigning() throws Exception {
  for(String name:java.util.List.of("full-codex","short-buy-codex")){
   ObjectNode original=producer(name);int index=name.startsWith("full")?6:11;
   ObjectNode missing=original.deepCopy();var receipts=(com.fasterxml.jackson.databind.node.ArrayNode)activeLeg(missing,index).get("receipts");
   for(int i=receipts.size()-1;i>=0;i--)if(receipts.get(i).get("ruleId").asText().equals("SHARED_CURRENCY_CAPACITY"))receipts.remove(i);
   assertThatThrownBy(()->validateProducer(missing)).isInstanceOf(RuntimeException.class);
   ObjectNode rebound=original.deepCopy();for(JsonNode r:activeLeg(rebound,index).get("receipts"))if(r.get("ruleId").asText().equals("SHARED_CURRENCY_CAPACITY"))((ObjectNode)r).set("inputRefs",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add("assets").add("policy").add("ledger").add("topup-state"));
   assertThatThrownBy(()->validateProducer(rebound)).isInstanceOf(RuntimeException.class);
  }
 }
 @Test void actualShortProducerRejectsFractionalUnsafeQuantityAndWrongStrategyAfterResigning() throws Exception {
  for(String units:java.util.List.of("1.5","-1","0","9007199254740992")){
   ObjectNode packet=producer("short-buy-codex");((ObjectNode)activeLeg(packet,11)).put("units",units);
   assertThatThrownBy(()->validateProducer(packet)).isInstanceOf(RuntimeException.class);
  }
  ObjectNode strategy=producer("short-buy-codex");((ObjectNode)activeLeg(strategy,11)).put("strategy","US_TACTICAL");assertThatThrownBy(()->validateProducer(strategy)).isInstanceOf(RuntimeException.class);
  ObjectNode amount=producer("short-buy-codex");((ObjectNode)activeLeg(amount,11)).put("amount","1");assertThatThrownBy(()->validateProducer(amount)).isInstanceOf(RuntimeException.class);
  ObjectNode topup=producer("full-codex");((ObjectNode)activeLeg(topup,6)).put("units","1000");assertThatThrownBy(()->validateProducer(topup)).isInstanceOf(RuntimeException.class);
 }
 private static JsonNode activeLeg(ObjectNode root,int index){for(JsonNode leg:root.get("modules").get(index).get("data").get("legs"))if("ACTIONABLE_FOR_REPORT".equals(leg.get("status").asText()))return leg;throw new AssertionError("positive leg required");}
 private static ObjectNode producer(String name)throws Exception{return (ObjectNode)SrppDailyContextResponseValidator.parseStrict(SrppReportResponseValidatorTest.class.getResourceAsStream("/srpp-report/report-producer-"+name+"-pg.json").readAllBytes());}
 private static void validateProducer(ObjectNode root){var identity=root.get("identity");var query=new SrppReportQuery(null,identity.get("consumer").asText(),identity.get("tradingDate").asText(),identity.get("reportSlot").asText(),identity.get("policyBundleSha256").asText(),identity.get("swaggerSha256").asText(),root.get("calculationRunId").asText());SrppReportResponseValidator.validate(query,identity.get("ownerUserId").asLong(),MediaType.APPLICATION_JSON,signed(root));}
 private static ObjectNode fact(String name,String subject,String value,String unit){var n=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();n.put("name",name).put("subject",subject).put("value",value).put("unit",unit).put("quality","EXACT").putNull("reason");return n;}
 private static JsonNode leg(String status,int lots){String json="{\"symbol\":\"00865B\",\"market\":\"台股\",\"broker\":null,\"subaccount\":null,\"strategy\":\"D130\",\"status\":\""+status+"\",\"action\":\"BUY\",\"lots\":"+lots+",\"units\":null,\"limitPrice\":\"40\",\"amount\":\"40000\",\"currency\":\"TWD\",\"fees\":\"20\",\"netProfitSpace\":null,\"rankingReason\":null,\"blockingReasons\":[\"PENDING_UNVERIFIED\"],\"receipts\":[]}";return SrppDailyContextResponseValidator.parseStrict(json.getBytes(StandardCharsets.UTF_8));}
}
