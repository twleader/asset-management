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
 @Test
 @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="srpp.blockingReasonWire.dir",matches=".+")
 void acceptsPublishedCommonSourceGapsAndStillRejectsDuplicateReasonAttack() throws Exception {
  for(String consumer:java.util.List.of("codex","claude")) {
   byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("srpp.blockingReasonWire.dir"),consumer+".json"));
   ObjectNode root=(ObjectNode)SrppDailyContextResponseValidator.parseStrict(bytes);var identity=root.path("identity");
   var query=new SrppReportQuery(null,identity.path("consumer").asText(),identity.path("tradingDate").asText(),identity.path("reportSlot").asText(),identity.path("policyBundleSha256").asText(),identity.path("swaggerSha256").asText(),root.path("calculationRunId").asText());
   SrppReportResponseValidator.validate(query,identity.path("ownerUserId").asLong(),MediaType.APPLICATION_JSON,bytes);
   JsonNode attacked=null;
   for(JsonNode module:root.path("modules"))for(JsonNode leg:module.path("data").path("legs"))
    if("NAMED_CASH_BUY_D226".equals(leg.path("strategy").asText()))attacked=leg;
   assertThat(attacked).isNotNull();assertThat(attacked.path("status").asText()).isEqualTo("BLOCKED");
   var reasons=(com.fasterxml.jackson.databind.node.ArrayNode)attacked.path("blockingReasons");assertThat(reasons).isNotEmpty();reasons.add(reasons.get(0));
   assertThatThrownBy(()->SrppReportResponseValidator.validate(query,identity.path("ownerUserId").asLong(),MediaType.APPLICATION_JSON,signed(root))).isInstanceOf(SrppPayloadException.class);
  }
 }
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
 @Test void d218ClosedFactsAndSealedPositiveBudgetValidateWithoutChangingHistoricalPackages() throws Exception {
  ObjectNode n=d218Producer();validateProducer(n);
  var facts=(com.fasterxml.jackson.databind.node.ArrayNode)n.path("modules").get(6).path("data").path("facts");
  for(String name:java.util.List.of("d218ObservedNoNewLowSessions","d218Stabilized","d218AnchorLow","d218UsedBudget","d218CumulativeFraction","d218BudgetCeiling"))
   facts.add(fact(name,"0056",name.equals("d218BudgetCeiling")?"1000000":name.equals("d218Stabilized")?"1":name.equals("d218CumulativeFraction")?"0.1":"2",name.equals("d218ObservedNoNewLowSessions")?"COUNT":name.equals("d218Stabilized")?"BOOLEAN":name.equals("d218CumulativeFraction")?"RATIO":"TWD"));
  validateProducer(n);
  var bad=n.deepCopy();((ObjectNode)bad.path("modules").get(6).path("data").path("facts").get(facts.size()-1)).put("name","d218UnknownField");
  assertThatThrownBy(()->validateProducer(bad)).isInstanceOf(RuntimeException.class);
  validateProducer(producer("full-codex"));
 }
 @Test void d218PositiveLegRejectsMissingWrongSymbolAndInsufficientFeeInclusiveBudget() throws Exception {
  for(String mutation:java.util.List.of("missing","symbol","budget")){
   ObjectNode n=d218Producer();var receipts=(com.fasterxml.jackson.databind.node.ArrayNode)activeLeg(n,6).path("receipts");
   for(int i=receipts.size()-1;i>=0;i--)if(receipts.get(i).path("ruleId").asText().equals("D218_STABILIZATION")){
    if(mutation.equals("missing"))receipts.remove(i);
    else ((ObjectNode)receipts.get(i).path("values").get(0)).put(mutation.equals("symbol")?"subject":"value",mutation.equals("symbol")?"00878":"1");
   }
   assertThatThrownBy(()->validateProducer(n)).as(mutation).isInstanceOf(RuntimeException.class);
  }
 }
 private static ObjectNode d218Producer() throws Exception {
  ObjectNode root=producer("full-codex"),snapshot=(ObjectNode)root.path("inputSnapshot");
  ObjectNode bundle=(ObjectNode)SrppDailyContextResponseValidator.parseStrict(snapshot.path("inputBundleJcs").asText().getBytes(StandardCharsets.UTF_8));
  ObjectNode policy=(ObjectNode)bundle.path("sources").path("policy");
  ObjectNode model=(ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(policy.path("rawModelBytes").asText());
  model.withObject("execution_policy").putObject("high_dividend_stabilization_d218").put("enabled",true).putArray("symbols").add("0056").add("00713").add("00878");
  String raw=model.toString();policy.put("rawModelBytes",raw);
  ((ObjectNode)root.path("authorityRevision")).put("modelInputsSha256",SrppJcs.sha256Hex(raw));
  ((ObjectNode)bundle.path("authority")).put("modelInputsSha256",SrppJcs.sha256Hex(raw));
  String input=SrppJcs.canonicalize(bundle);snapshot.put("inputBundleJcs",input).put("inputSnapshotSha256",SrppJcs.sha256Hex(input));
  ((ObjectNode)snapshot.path("sourceVector").path("policy")).put("sha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(policy)));
  ObjectNode leg=(ObjectNode)activeLeg(root,6);assertThat(leg.path("symbol").asText()).isEqualTo("0056");
  ObjectNode receipt=leg.path("receipts").get(0).deepCopy();receipt.put("ruleId","D218_STABILIZATION");
  receipt.putArray("values").add(fact("d218BudgetCeiling","0056","1000000","TWD"));
  ((com.fasterxml.jackson.databind.node.ArrayNode)leg.path("receipts")).add(receipt);return root;
 }
 private static JsonNode activeLeg(ObjectNode root,int index){for(JsonNode leg:root.get("modules").get(index).get("data").get("legs"))if("ACTIONABLE_FOR_REPORT".equals(leg.get("status").asText()))return leg;throw new AssertionError("positive leg required");}
 private static ObjectNode producer(String name)throws Exception{return (ObjectNode)SrppDailyContextResponseValidator.parseStrict(SrppReportResponseValidatorTest.class.getResourceAsStream("/srpp-report/report-producer-"+name+"-pg.json").readAllBytes());}
 private static void validateProducer(ObjectNode root){var identity=root.get("identity");var query=new SrppReportQuery(null,identity.get("consumer").asText(),identity.get("tradingDate").asText(),identity.get("reportSlot").asText(),identity.get("policyBundleSha256").asText(),identity.get("swaggerSha256").asText(),root.get("calculationRunId").asText());SrppReportResponseValidator.validate(query,identity.get("ownerUserId").asLong(),MediaType.APPLICATION_JSON,signed(root));}
 private static ObjectNode fact(String name,String subject,String value,String unit){var n=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();n.put("name",name).put("subject",subject).put("value",value).put("unit",unit).put("quality","EXACT").putNull("reason");return n;}
 private static JsonNode leg(String status,int lots){String json="{\"symbol\":\"00865B\",\"market\":\"台股\",\"broker\":null,\"subaccount\":null,\"strategy\":\"D130\",\"status\":\""+status+"\",\"action\":\"BUY\",\"lots\":"+lots+",\"units\":null,\"limitPrice\":\"40\",\"amount\":\"40000\",\"currency\":\"TWD\",\"fees\":\"20\",\"netProfitSpace\":null,\"rankingReason\":null,\"blockingReasons\":[\"PENDING_UNVERIFIED\"],\"receipts\":[]}";return SrppDailyContextResponseValidator.parseStrict(json.getBytes(StandardCharsets.UTF_8));}

 private static ObjectNode withPooledHeadroom(String name)throws Exception{
  ObjectNode root=producer("full-codex");ObjectNode funding=null;for(JsonNode m:root.get("modules"))if("FUNDING_CAPACITY".equals(m.get("moduleId").asText()))funding=(ObjectNode)m;assertThat(funding).isNotNull();
  var facts=(com.fasterxml.jackson.databind.node.ArrayNode)funding.get("data").get("facts");for(String currency:java.util.List.of("TWD","USD")){var f=facts.addObject();f.put("name",name);f.put("subject",currency);f.put("value","TWD".equals(currency)?"3442122.0773":"0");f.put("unit",currency);f.put("quality","EXACT");f.putNull("reason");}return root;
 }
 @Test void fundingCapacityAcceptsPooledPostBuyHeadroomAndStillRejectsUnknownNames()throws Exception{
  validateProducer(withPooledHeadroom("pooledPostBuyHeadroom"));
  assertThatThrownBy(()->validateProducer(withPooledHeadroom("pooledPostBuyHeadroomUnknown"))).isInstanceOf(RuntimeException.class);
 }
 private static ObjectNode d222Fact(String name,String subject,String unit,String value){
  var f=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();f.put("name",name);if(subject==null)f.putNull("subject");else f.put("subject",subject);f.put("unit",unit);if(value==null){f.putNull("value");f.put("quality","UNAVAILABLE");f.put("reason","FIELD_SOURCE_UNVERIFIED");}else{f.put("value",value);f.put("quality","EXACT");f.putNull("reason");}return f;
 }
 private static ObjectNode withD222Facts(ObjectNode... facts){
  var n=fixture();var module=(ObjectNode)n.get("modules").get(1);module.put("status","PARTIAL");module.withArray("missing").add("D222_FX_DIRECTION_AND_CAPACITY_RECEIPTS_UNAVAILABLE");var list=(com.fasterxml.jackson.databind.node.ArrayNode)module.get("data").get("facts");for(var f:facts)list.add(f);((ObjectNode)n.get("coverage")).put("partialCount",1).put("notEvaluatedCount",11);return n;
 }
 @Test void d222ReserveFactsAcceptPositiveNegativeAndUnknownDelta(){
  for(String delta:new String[]{"100","-100",null})validate(withD222Facts(d222Fact("protectedReserveTarget",null,"TWD","3850000"),d222Fact("referenceCashDelta",null,"TWD",delta),d222Fact("equityTarget",null,"RATIO","0.715")));
 }
 @Test void d222PartialSingleSymbolRangeDoesNotRequireOtherCandidatesOrSelectorAcceptance(){
  for(String symbol:new String[]{"VOO","0050","00881"})validate(withD222Facts(d222Fact("reserveSelectionLow52",symbol,symbol.equals("VOO")?"USD":"TWD","10"),d222Fact("reserveSelectionHigh52",symbol,symbol.equals("VOO")?"USD":"TWD","20"),d222Fact("reserveSelectionPosition52",symbol,"RATIO","0.5")));
  validate(withD222Facts(d222Fact("marketAdjustmentReviewAmount","VOO","TWD","100"),d222Fact("marketAdjustmentUnallocated",null,"TWD","0")));
 }
 @Test void d222FactsRejectWrongSubjectsUnitsAndDomains(){
  for(ObjectNode fact:java.util.List.of(d222Fact("protectedReserveTarget","VOO","TWD","1"),d222Fact("protectedReserveTarget",null,"TWD","-1"),d222Fact("referenceCashDelta",null,"RATIO","1"),d222Fact("equityTarget",null,"RATIO","1.01"),d222Fact("equityTarget",null,"RATIO","-0.01"),d222Fact("reserveSelectionLow52","VOO","TWD","1"),d222Fact("reserveSelectionHigh52","0050","USD","1"),d222Fact("reserveSelectionPosition52","2330","RATIO","0.5"),d222Fact("reserveSelectionLow52","VOO","USD","0"),d222Fact("reserveSelectionPosition52","VOO","RATIO","1.01"),d222Fact("marketAdjustmentReviewAmount","VOO","TWD","-1"),d222Fact("marketAdjustmentUnallocated","VOO","TWD","1"),d222Fact("unknownD222Fact",null,"TWD","1")))assertThatThrownBy(()->validate(withD222Facts(fact))).isInstanceOf(RuntimeException.class);
 }

 @Test void d222UnknownFactValuePreservesUnavailableQualityContract(){
  validate(withD222Facts(d222Fact("marketAdjustmentUnallocated",null,"TWD",null)));
  var f=d222Fact("equityTarget",null,"RATIO",null);f.putNull("quality");assertThatThrownBy(()->validate(withD222Facts(f))).isInstanceOf(RuntimeException.class);
 }
 private static ObjectNode mediumFixture(){
  ObjectNode root=fixture(),snapshot=(ObjectNode)root.path("inputSnapshot"),bundle=(ObjectNode)SrppDailyContextResponseValidator.parseStrict(snapshot.path("inputBundleJcs").asText().getBytes(StandardCharsets.UTF_8));
  ObjectNode sources=(ObjectNode)bundle.path("sources"),vector=(ObjectNode)snapshot.path("sourceVector");
  java.util.Set<String> refs=java.util.Set.of("assets","policy","classifications","fx","market:美股:VOO","market:台股:0050","market:台股:00881","topup-state","report-reservations");
  for(String id:refs)if(!sources.has(id)){ObjectNode raw=sources.putObject(id);vector.putObject(id).put("sourceId",id).put("revision","SYNTHETIC_MEDIUM_ONLY").put("capturedAt",snapshot.path("capturedAt").asText()).put("sha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(raw))).put("coverage","COMPLETE").put("validation","VERIFIED");}
  for(String id:refs)((ObjectNode)vector.path(id)).put("coverage","COMPLETE").put("validation","VERIFIED");
  String input=SrppJcs.canonicalize(bundle);snapshot.put("inputBundleJcs",input).put("inputSnapshotSha256",SrppJcs.sha256Hex(input));
  ObjectNode module=(ObjectNode)root.path("modules").get(8);module.put("status","COMPLETE");module.putArray("missing");module.putArray("reasonCodes");module.putArray("scope").add("D215_SYNTHETIC_ONLY");var inputRefs=module.putArray("inputRefs");new java.util.TreeSet<>(refs).forEach(inputRefs::add);
  ObjectNode l=module.withObject("data").putArray("legs").addObject().put("symbol","00881").put("market","台股").put("strategy","NAMED_DEEP_DRAWDOWN_D215").put("status","ACTIONABLE_FOR_REPORT").put("action","BUY").put("broker","國泰證券").put("subaccount","CORE_D215").put("lots",1).putNull("units").put("limitPrice","40").put("amount","40000").put("fees","60").put("currency","TWD").putNull("netProfitSpace").put("rankingReason","SYNTHETIC_CEILING_ONLY");l.putArray("blockingReasons");var receipts=l.putArray("receipts");
  for(String rule:java.util.List.of("D215_NAMED_PATH","QUOTE","RADAR","STRATEGIC_PRICE","ALLOCATION","FUNDING","PENDING","RESERVATIONS","DEDUPLICATION","DEPTH","EXECUTION_ACCOUNT","THESIS","TRADABILITY","SHARED_CURRENCY_CAPACITY")){
   ObjectNode receipt=receipts.addObject().put("ruleId",rule).put("calculatorVersion","SRPP_REPORT_CALCULATIONS_V1").put("status","PASS").put("reason","SYNTHETIC_ONLY").putNull("scope");receipt.putArray("values");var list=receipt.putArray("inputRefs");java.util.Set<String> expected;
   if(rule.equals("ALLOCATION"))expected=java.util.Set.of("assets","policy","classifications","fx","market:美股:VOO","market:台股:0050","market:台股:00881");
   else if(rule.equals("SHARED_CURRENCY_CAPACITY"))expected=java.util.Set.of("assets","policy","topup-state","report-reservations");
   else if(rule.equals("THESIS"))expected=java.util.Set.of("policy","market:台股:00881","classifications");
   else if(rule.equals("TRADABILITY"))expected=java.util.Set.of("policy","market:台股:00881","topup-state");
   else if(java.util.Set.of("D215_NAMED_PATH","QUOTE","RADAR","STRATEGIC_PRICE","DEPTH").contains(rule))expected=java.util.Set.of("policy","market:台股:00881");
   else expected=java.util.Set.of("assets","policy","topup-state","report-reservations","classifications","market:台股:00881");new java.util.TreeSet<>(expected).forEach(list::add);
  }
  ((ObjectNode)root.path("coverage")).put("completeCount",1).put("notEvaluatedCount",11);return root;
 }
 @Test void mediumNamedLegAbove38IsAcceptedOnlyWithCompleteBoundGateSet(){ObjectNode n=mediumFixture();validate(n);((ObjectNode)n.path("modules").get(8).path("data").path("legs").get(0)).put("symbol","009826");assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);}
 @Test void mediumMissingOrWrongSourceReceiptAndUnknownStrategyFailClosed(){for(String mutation:java.util.List.of("missing","refs","strategy")){ObjectNode n=mediumFixture(),l=(ObjectNode)n.path("modules").get(8).path("data").path("legs").get(0);if(mutation.equals("missing"))((com.fasterxml.jackson.databind.node.ArrayNode)l.path("receipts")).remove(0);else if(mutation.equals("refs"))((ObjectNode)l.path("receipts").get(0)).putArray("inputRefs").add("assets");else l.put("strategy","CLIENT_PURPOSE_D215");assertThatThrownBy(()->validate(n)).as(mutation).isInstanceOf(RuntimeException.class);}}
 @Test void mediumD144FallbackIsNarrowAndDoesNotAcceptArbitraryUnavailable(){ObjectNode n=mediumFixture();for(JsonNode r:n.path("modules").get(8).path("data").path("legs").get(0).path("receipts"))if(r.path("ruleId").asText().equals("TRADABILITY"))((ObjectNode)r).put("status","UNAVAILABLE").put("reason","D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY");validate(n);for(JsonNode r:n.path("modules").get(8).path("data").path("legs").get(0).path("receipts"))if(r.path("ruleId").asText().equals("TRADABILITY"))((ObjectNode)r).put("reason","UNKNOWN");assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);}

 private static ObjectNode namedPlanFixture(){ObjectNode n=mediumFixture(),l=(ObjectNode)n.path("modules").get(8).path("data").path("legs").get(0);l.put("strategy","NAMED_CASH_BUY_D226").put("subaccount","CORE_D226_BUY");for(JsonNode receipt:l.path("receipts"))if("D215_NAMED_PATH".equals(receipt.path("ruleId").asText()))((ObjectNode)receipt).put("ruleId","D226_BUY_PATH");ObjectNode budget=((com.fasterxml.jackson.databind.node.ArrayNode)l.path("receipts")).addObject().put("ruleId","D223_PLAN_BUDGET").put("calculatorVersion","SRPP_REPORT_CALCULATIONS_V1").put("status","PASS").put("reason","D223_PLAN_BUDGET_COMPLETE").putNull("scope");budget.putArray("inputRefs").add("assets").add("policy").add("topup-state").add("report-reservations").add("classifications").add("market:台股:00881");budget.putArray("values");return n;}
 @Test void independentPlanBuyRequiresExactBudgetGateAndOwnAccount(){validate(namedPlanFixture());for(String mutation:java.util.List.of("budget","account")){ObjectNode n=namedPlanFixture(),l=(ObjectNode)n.path("modules").get(8).path("data").path("legs").get(0);if(mutation.equals("account"))l.put("subaccount","CORE_D215");else ((com.fasterxml.jackson.databind.node.ArrayNode)l.path("receipts")).remove(l.path("receipts").size()-1);assertThatThrownBy(()->validate(n)).as(mutation).isInstanceOf(RuntimeException.class);}}

 private static ObjectNode namedSellerFixture(){
  ObjectNode root=mediumFixture(),snapshot=(ObjectNode)root.path("inputSnapshot"),bundle=(ObjectNode)SrppDailyContextResponseValidator.parseStrict(snapshot.path("inputBundleJcs").asText().getBytes(StandardCharsets.UTF_8));ObjectNode source=bundle.withObject("sources").putObject("market:台股:2881");snapshot.withObject("sourceVector").putObject("market:台股:2881").put("sourceId","market:台股:2881").put("revision","SYNTHETIC_NAMED_SELL_ONLY").put("sha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(source))).put("capturedAt",snapshot.path("capturedAt").asText()).put("coverage","COMPLETE").put("validation","VERIFIED");String input=SrppJcs.canonicalize(bundle);snapshot.put("inputBundleJcs",input).put("inputSnapshotSha256",SrppJcs.sha256Hex(input));ObjectNode module=(ObjectNode)root.path("modules").get(8);module.withArray("inputRefs").add("market:台股:2881");ObjectNode leg=(ObjectNode)module.path("data").path("legs").get(0);leg.put("symbol","2881").put("strategy","NAMED_RADAR_ROTATION_D226").put("action","SELL").putNull("lots").put("units","3000").put("limitPrice","140").put("amount","420000").put("fees","1858.5").put("broker","富邦證券").put("subaccount","CORE_D226");var receipts=leg.putArray("receipts");
  for(String rule:java.util.List.of("D226_SELL_PATH","QUOTE","RADAR","POSITION","PENDING","RESERVATIONS","DEDUPLICATION","CORE_RETENTION","D152_BATCH","COOLDOWN","DEPTH","EXECUTION_ACCOUNT","COSTS","TRADABILITY")){ObjectNode r=receipts.addObject().put("ruleId",rule).put("calculatorVersion","SRPP_REPORT_CALCULATIONS_V1").put("status","PASS").put("reason","SYNTHETIC_ONLY").putNull("scope");r.putArray("values");java.util.Set<String> refs=java.util.Set.of("QUOTE","RADAR","DEPTH").contains(rule)?java.util.Set.of("policy","market:台股:2881"):rule.equals("TRADABILITY")?java.util.Set.of("policy","market:台股:2881","topup-state"):java.util.Set.of("assets","policy","topup-state","report-reservations","classifications","market:台股:2881");var a=r.putArray("inputRefs");new java.util.TreeSet<>(refs).forEach(a::add);}return root;
 }
 @Test void namedSellerUnitsBelow152ValidateWithExactAccountAndFourteenGates(){validate(namedSellerFixture());}
 @Test void namedSellerRejectsMissingBatchOrCooldownAndWrongQuantityType(){for(String mutation:java.util.List.of("D152_BATCH","COOLDOWN","lots","scope")){ObjectNode n=namedSellerFixture(),l=(ObjectNode)n.path("modules").get(8).path("data").path("legs").get(0);if(mutation.equals("lots"))l.put("lots",3);else if(mutation.equals("scope"))l.put("symbol","0050");else {var a=(com.fasterxml.jackson.databind.node.ArrayNode)l.path("receipts");for(int i=a.size()-1;i>=0;i--)if(a.get(i).path("ruleId").asText().equals(mutation))a.remove(i);}assertThatThrownBy(()->validate(n)).as(mutation).isInstanceOf(RuntimeException.class);}}

 // ---- Task 623 satellite build legs (actual business-services producer wire) ----
 private static ObjectNode paceLeg(ObjectNode root,String symbol){for(JsonNode leg:root.path("modules").get(9).path("data").path("legs"))if(symbol.equals(leg.path("symbol").asText()))return (ObjectNode)leg;throw new AssertionError(symbol);}
 private static ObjectNode receipt(ObjectNode leg,String rule){for(JsonNode r:leg.path("receipts"))if(rule.equals(r.path("ruleId").asText()))return (ObjectNode)r;throw new AssertionError(rule);}
 private static void rejectsProducer(String name,Consumer<ObjectNode> mutation)throws Exception{ObjectNode root=producer(name);mutation.accept(root);assertThatThrownBy(()->validateProducer(root)).isInstanceOf(RuntimeException.class);}
 @Test void actualProducerSatelliteLegsValidateIncludingHalvedUnverifiedMids() throws Exception {
  for(String name:java.util.List.of("satellite-enabled","satellite-mids-unavailable")){
   ObjectNode root=producer(name);assertThat(root.path("modules").get(9).path("moduleId").asText()).isEqualTo("GLOBAL_EQUITY_PACE");
   assertThat(paceLeg(root,"009826").path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(paceLeg(root,"VT").path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(paceLeg(root,"VT").path("market").asText()).isEqualTo("美股");
   boolean mids=name.equals("satellite-enabled");assertThat(receipt(paceLeg(root,"VT"),"SATELLITE_BUILD").path("inputRefs").toString().contains("usd-twd-month-mids")).isEqualTo(mids);
   validateProducer(root);
  }
 }
 @Test void vtUsLegIsAcceptedOnlyInGlobalEquityPaceWithItsExactIdentity() throws Exception {
  rejectsProducer("satellite-enabled",root->((com.fasterxml.jackson.databind.node.ArrayNode)root.path("modules").get(8).path("data").path("legs")).add(paceLeg(root,"VT").deepCopy()));
  rejectsProducer("satellite-enabled",root->((ObjectNode)root.path("modules").get(5).path("data").path("legs").get(0)).put("market","美股"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"VT").put("currency","TWD"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"VT").put("symbol","VOO"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"009826").put("market","美股").put("currency","USD"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"009826").put("subaccount","CORE_D215"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"VT").put("broker","富邦證券"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"VT").put("strategy","SATELLITE_BUILD_D143"));
 }
 @Test void satelliteReceiptSetFxIdentityAndWholeUnitsAreExact() throws Exception {
  rejectsProducer("satellite-enabled",root->{var receipts=(com.fasterxml.jackson.databind.node.ArrayNode)paceLeg(root,"VT").path("receipts");for(int i=receipts.size()-1;i>=0;i--)if("FX_CONVERSION".equals(receipts.get(i).path("ruleId").asText()))receipts.remove(i);});
  rejectsProducer("satellite-enabled",root->((com.fasterxml.jackson.databind.node.ArrayNode)paceLeg(root,"009826").path("receipts")).add(receipt(paceLeg(root,"VT"),"FX_CONVERSION").deepCopy()));
  rejectsProducer("satellite-enabled",root->{for(JsonNode v:receipt(paceLeg(root,"VT"),"FX_CONVERSION").path("values"))if("twdAmount".equals(v.path("name").asText()))((ObjectNode)v).put("value","24376");});
  rejectsProducer("satellite-enabled",root->{for(JsonNode v:receipt(paceLeg(root,"VT"),"FX_CONVERSION").path("values"))if("twdFee".equals(v.path("name").asText()))((ObjectNode)v).put("value","97");});
  rejectsProducer("satellite-enabled",root->receipt(paceLeg(root,"VT"),"FX_CONVERSION").putArray("inputRefs").add("fx"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"VT").put("units","5.5").put("amount","825"));
  rejectsProducer("satellite-enabled",root->paceLeg(root,"VT").put("fees","2.5"));
  rejectsProducer("satellite-enabled",root->{var refs=receipt(paceLeg(root,"VT"),"SATELLITE_BUILD").putArray("inputRefs");for(String r:java.util.List.of("assets","fx","market:美股:VT","policy","satellite-build-fills"))refs.add(r);});
  rejectsProducer("satellite-enabled",root->{var refs=receipt(paceLeg(root,"009826"),"SATELLITE_BUILD").putArray("inputRefs");for(String r:java.util.List.of("assets","fx","policy","satellite-build-fills","usd-twd-month-mids"))refs.add(r);});
  rejectsProducer("satellite-enabled",root->{var values=(com.fasterxml.jackson.databind.node.ArrayNode)receipt(paceLeg(root,"009826"),"SATELLITE_BUILD").path("values");for(int i=values.size()-1;i>=0;i--)if("fxStatusVerified".equals(values.get(i).path("name").asText()))values.remove(i);});
 }
 @Test void blockedSatelliteLegKeepsFreeNamedAfterCloseReason() throws Exception {
  ObjectNode root=producer("satellite-enabled");
  for(String code:java.util.List.of("009826","VT")){ObjectNode leg=paceLeg(root,code);leg.put("status","BLOCKED");if(code.equals("VT"))leg.put("units","0");else leg.put("lots",0);for(String k:java.util.List.of("limitPrice","amount","fees","netProfitSpace","rankingReason"))leg.putNull(k);leg.putArray("blockingReasons").add("AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE");}
  validateProducer(root);
 }
 // ---- Task 625 D215_FX on BOND_D130_D195 legs and the VT build leg (actual business-services producer wire) ----
 private static ObjectNode bondLeg(ObjectNode root,String symbol,String strategy){for(JsonNode leg:root.path("modules").get(5).path("data").path("legs"))if(symbol.equals(leg.path("symbol").asText())&&strategy.equals(leg.path("strategy").asText()))return (ObjectNode)leg;throw new AssertionError(symbol);}
 @Test void d215NormalWaitAndSourceUnverifiedProducerWiresValidate() throws Exception {
  ObjectNode normal=producer("satellite-enabled");validateProducer(normal);
  ObjectNode active=bondLeg(normal,"00865B","STRATEGIC_FUBON");assertThat(active.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(receipt(active,"D215_FX").path("status").asText()).isEqualTo("PASS");
  assertThat(receipt(paceLeg(normal,"VT"),"D215_FX").path("reason").asText()).isEqualTo("D215_NO_FX_PREFERENCE_VETO");
  ObjectNode wait=producer("satellite-d215-wait");validateProducer(wait);
  ObjectNode waited=bondLeg(wait,"00865B","EMERGENCY_CATHAY");assertThat(waited.path("status").asText()).isEqualTo("BLOCKED");assertThat(receipt(waited,"D215_FX").path("status").asText()).isEqualTo("BLOCK");assertThat(receipt(waited,"D215_FX").toString()).contains("d215WaitCeilingLots","d215WaitCeilingAmount");
  assertThat(receipt(paceLeg(wait,"VT"),"D215_FX").toString()).contains("d215WaitCeilingUnits");
  ObjectNode unverified=producer("satellite-d215-bond-source-unverified");validateProducer(unverified);
  ObjectNode blocked=bondLeg(unverified,"00865B","STRATEGIC_FUBON");assertThat(blocked.path("status").asText()).isEqualTo("BLOCKED");assertThat(receipt(blocked,"D215_FX").path("reason").asText()).isEqualTo("SOURCE_NOT_VERIFIED:market:台股:00865B");
 }
 @Test void d215UnknownBlockedLegsAreAcceptedButNeverActionable() throws Exception {
  ObjectNode root=producer("satellite-d215-wait");ObjectNode leg=bondLeg(root,"00865B","STRATEGIC_FUBON");receipt(leg,"D215_FX").put("status","UNAVAILABLE").put("reason","D215_FX_EVIDENCE_UNKNOWN");validateProducer(root);
  // An ACTIONABLE bond or VT leg needs a PASS D215_FX; WAIT, UNKNOWN and SOURCE_NOT_VERIFIED are rejected there.
  for(String[] c:java.util.List.of(new String[]{"BLOCK","D215_FX_WAIT_PREFERRED"},new String[]{"UNAVAILABLE","D215_FX_EVIDENCE_UNKNOWN"},new String[]{"UNAVAILABLE","SOURCE_NOT_VERIFIED:market:台股:00865B"})){
   rejectsProducer("satellite-enabled",r->receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").put("status",c[0]).put("reason",c[1]));
   rejectsProducer("satellite-enabled",r->receipt(paceLeg(r,"VT"),"D215_FX").put("status",c[0]).put("reason",c[1].replace("台股:00865B","美股:VT")));
  }
  rejectsProducer("satellite-enabled",r->{var receipts=(com.fasterxml.jackson.databind.node.ArrayNode)bondLeg(r,"00865B","STRATEGIC_FUBON").path("receipts");for(int i=receipts.size()-1;i>=0;i--)if("D215_FX".equals(receipts.get(i).path("ruleId").asText()))receipts.remove(i);});
  rejectsProducer("satellite-enabled",r->{var receipts=(com.fasterxml.jackson.databind.node.ArrayNode)paceLeg(r,"VT").path("receipts");for(int i=receipts.size()-1;i>=0;i--)if("D215_FX".equals(receipts.get(i).path("ruleId").asText()))receipts.remove(i);});
  // ACTIONABLE leg referencing an unverified source is refused even with PASS.
  rejectsProducer("satellite-enabled",r->((ObjectNode)r.path("inputSnapshot").path("sourceVector").path("market:台股:00865B")).put("validation","UNAVAILABLE").put("coverage","NONE"));
  rejectsProducer("satellite-enabled",r->receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").put("reason","D215_FX_SOMETHING_ELSE"));
  rejectsProducer("satellite-enabled",r->((com.fasterxml.jackson.databind.node.ArrayNode)paceLeg(r,"009826").path("receipts")).add(receipt(paceLeg(r,"VT"),"D215_FX").deepCopy()));
 }
 @Test void d215RefsAndValueNamesAndUnitsAreExact() throws Exception {
  rejectsProducer("satellite-enabled",r->receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").putArray("inputRefs").add("fx").add("policy"));
  rejectsProducer("satellite-enabled",r->receipt(bondLeg(r,"00719B","STRATEGIC_FUBON"),"D215_FX").putArray("inputRefs").add("fx").add("market:台股:00865B").add("policy"));
  rejectsProducer("satellite-enabled",r->receipt(paceLeg(r,"VT"),"D215_FX").putArray("inputRefs").add("assets").add("fx").add("market:美股:VT").add("policy"));
  rejectsProducer("satellite-enabled",r->((com.fasterxml.jackson.databind.node.ArrayNode)receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").path("values")).add(fact("fxPercentile","00865B","0.5","RATIO")));
  rejectsProducer("satellite-enabled",r->{for(JsonNode v:receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").path("values"))if("fxRangePosition".equals(v.path("name").asText()))((ObjectNode)v).put("unit","PERCENT");});
  rejectsProducer("satellite-enabled",r->{for(JsonNode v:receipt(paceLeg(r,"VT"),"D215_FX").path("values"))if("assetHigh52w".equals(v.path("name").asText()))((ObjectNode)v).put("unit","TWD");});
  rejectsProducer("satellite-enabled",r->{for(JsonNode v:receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").path("values"))if("fxHigh".equals(v.path("name").asText()))((ObjectNode)v).put("value","2");});
  rejectsProducer("satellite-d215-wait",r->{for(JsonNode v:receipt(bondLeg(r,"00865B","EMERGENCY_CATHAY"),"D215_FX").path("values"))if("d215WaitCeilingLots".equals(v.path("name").asText()))((ObjectNode)v).put("name","d215WaitCeilingUnits").put("unit","SHARES");});
  rejectsProducer("satellite-enabled",r->((com.fasterxml.jackson.databind.node.ArrayNode)receipt(bondLeg(r,"00865B","STRATEGIC_FUBON"),"D215_FX").path("values")).add(fact("d215WaitCeilingLots","00865B","2","LOTS")));
 }
}
