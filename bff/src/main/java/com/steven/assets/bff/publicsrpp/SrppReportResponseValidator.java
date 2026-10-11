package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/** Structural and integrity validation only: investment formulas remain in business-services. */
final class SrppReportResponseValidator {
    static final Set<String> MODULES = Set.of("ASSET_RECONCILIATION","ALLOCATION_GAP","FUNDING_CAPACITY","CASH_INCOME",
            "PAYROLL_CASHFLOW","BOND_D130_D195","DIVIDEND_TOPUP","CORE_SELL_GUARD","STRATEGIC_BUY_GATES",
            "GLOBAL_EQUITY_PACE","ROTATION_AND_REGIME_CAPACITY","SHORT_TERM_AND_US_TACTICAL");
    static final Set<String> QUALITIES = Set.of("EXACT","UNAVAILABLE","LOWER_BOUND");
    private static final Set<String> UNITS = Set.of("PERCENT","RATIO","LOTS","SHARES","BOOLEAN","YEARS","COUNT");
    private static final java.util.Map<String, Set<String>> FACT_NAMES=java.util.Map.ofEntries(
            java.util.Map.entry("ASSET_RECONCILIATION", Set.of("snapshotDeposit","liveDeposit","snapshotFund","liveFund","snapshotStock","liveStock","snapshotTotal","liveTotal","holdingShares","holdingStaticValue","holdingLiveValue","depositAmount","depositOriginalAmount","depositInterest","fundValue","fundIncome","bankCurrencyBalanceOriginal","bankCurrencyBalanceTwd","bankCurrencyPayableOriginal","bankCurrencyReceivableOriginal")),
            java.util.Map.entry("ALLOCATION_GAP", Set.of("targetWeight","currentValue","currentWeight","targetValue","gapValue","normalizedGap","protectedCashWeight","bondTarget","bondFloor","bondCap","emergencyTarget","emergencyGap")),
            java.util.Map.entry("FUNDING_CAPACITY", Set.of("settledBalance","payable","excludedReceivable","termBalance","inflationFactor","termFloor","totalFloor","reservedFunds","reservedFees","deployableCapacity")),
            java.util.Map.entry("CASH_INCOME", Set.of("stockGross","fundGross","depositGross","totalGross","nonSpendableInterest","spendableGross","taiwanIncomeTax","usWithholding","additionalBasicTax","supplementaryNhi","afterTaxCash","annualSettlement","spendableSgovGross","domesticDividend","domesticInterest","savingsInterest","otherDomesticIncome","overseasIncome","creditableForeignTax","nhiChargeableIncome","taxCalculation","annualTaxPrepayments")),
            java.util.Map.entry("PAYROLL_CASHFLOW", Set.of("grossSalary","withholding","voluntaryPension","takeHome","investableContribution","annualSettlement","paymentCount","payrollDetail","calendarYearPayroll","pensionReceipt")),
            java.util.Map.entry("BOND_D130_D195", Set.of()),
            java.util.Map.entry("DIVIDEND_TOPUP", Set.of("referenceHigh","drawdownPct","tierLots","targetGap","normalizedGap","remainingDailyLots","remainingWeeklyLots")),
            java.util.Map.entry("CORE_SELL_GUARD", Set.of("highWater","pullbackPct","overheatHits","trendReversal","timingPass")),
            java.util.Map.entry("STRATEGIC_BUY_GATES", Set.of("position52w","high52w","low52w","overheatHits","averageExDividendClose","eligible","dcaExempt")),
            java.util.Map.entry("GLOBAL_EQUITY_PACE", Set.of("monthlyBaseline","monthlyCap","spent","remaining","previousShortfall","discretionaryBudget","remainingDayLots","remainingWeekLots","consecutiveShortfalls")),
            java.util.Map.entry("ROTATION_AND_REGIME_CAPACITY", Set.of("age","equityFloor","bondFloor","livingRefill","deployableLiving","rotationCapacity","bearBondCapacity","ladderAmount","gainAboveAnchor","pullback","drawdown","nextBatch","settledReserve","overdueReserve")),
            java.util.Map.entry("SHORT_TERM_AND_US_TACTICAL", Set.of("support","pressure","reboundPct","netUpsidePct","netDownsidePct","rewardRisk","positionCapacity","netProfitSpacePct","referencePrice","maximumNotional")));
    private static final Set<String> TOPUP_RECEIPTS=Set.of("QUOTE","RADAR","STRATEGIC_PRICE","TIER","STOP","REGIME","TRADABILITY","QUOTAS","PENDING","RESERVATIONS","SCHEDULED_DCA","FUNDING","DEPTH","ALLOCATION","CLASSIFICATION","EXECUTION_ACCOUNT","SHARED_CURRENCY_CAPACITY");
    private static final Set<String> SHORT_STRATEGIES=Set.of("SHORT_TERM_D173","BOND_SHORT_TERM_D185");
    private static final Set<String> SHORT_BUY_RECEIPTS=Set.of("SHORT_RECONCILE","SHORT_SIZING","SHORT_FUNDS","SHORT_PORTFOLIO","SHORT_GROUP","SHORT_PROTECTION","SHORT_DEDUP","SHORT_INTRADAY","SHORT_QUOTE","SHORT_BOOK","SHORT_ACCOUNT","SHARED_CURRENCY_CAPACITY");
    private static final Set<String> SHORT_SELL_RECEIPTS=Set.of("SHORT_RECONCILE","SHORT_EXIT","SHORT_FLOORS","SHORT_DEDUP","SHORT_QUOTE","SHORT_BOOK","SHORT_ACCOUNT");
    private SrppReportResponseValidator() {}
    static void validate(SrppReportQuery q, Long owner, MediaType type, byte[] bytes) {
        if(type==null || !"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())) fail();
        JsonNode root=SrppDailyContextResponseValidator.parseStrict(bytes);
        fields(root,"schemaVersion","calculationRunId","status","identity","authorityRevision","sharedCalculationId","inputSnapshot",
                "modules","coverage","executionScope","tradeAuthorization","placesOrders","contentSha256");
        if(integer(root,"schemaVersion")!=1 || !"FINAL".equals(text(root,"status"))
                || !"REPORT_RECOMMENDATION_ONLY".equals(text(root,"executionScope"))) fail();
        boolFalse(root,"tradeAuthorization");boolFalse(root,"placesOrders");
        uuid(root,"calculationRunId");uuid(root,"sharedCalculationId");
        if(q.calculationRunId()!=null && !q.calculationRunId().equals(text(root,"calculationRunId"))) fail();
        JsonNode identity=root.get("identity");
        fields(identity,"ownerUserId","consumer","tradingDate","reportSlot","policyBundleSha256","swaggerSha256");
        if(owner==null || integer(identity,"ownerUserId")!=owner || !q.consumer().equals(text(identity,"consumer"))
                || !q.tradingDate().equals(text(identity,"tradingDate")) || !q.reportSlot().equals(text(identity,"reportSlot"))
                || !q.policyBundleSha256().equals(text(identity,"policyBundleSha256"))
                || !q.swaggerSha256().equals(text(identity,"swaggerSha256"))) fail();
        date(identity,"tradingDate");sha(identity,"policyBundleSha256");sha(identity,"swaggerSha256");
        JsonNode authority=root.get("authorityRevision");
        fields(authority,"serviceBuild","databaseSchema","calculatorVersion","modelInputsSha256","policyManifestSha256","targetAllocationSha256");
        text(authority,"serviceBuild");text(authority,"databaseSchema");text(authority,"calculatorVersion");
        sha(authority,"modelInputsSha256");sha(authority,"policyManifestSha256");sha(authority,"targetAllocationSha256");
        JsonNode snapshot=root.get("inputSnapshot");
        fields(snapshot,"capturedAt","assetGeneratedAt","assetSnapshotId","valuationCaptureId","inputBundleJcs","inputSnapshotSha256","sourceVector");
        OffsetDateTime captured=time(snapshot,"capturedAt");
        if(time(snapshot,"assetGeneratedAt").isAfter(captured)
                || !captured.atZoneSameInstant(java.time.ZoneId.of("Asia/Taipei")).toLocalDate().toString().equals(q.tradingDate()))fail();
        if(integer(snapshot,"assetSnapshotId")<=0) fail();uuid(snapshot,"valuationCaptureId");sha(snapshot,"inputSnapshotSha256");
        String jcs=text(snapshot,"inputBundleJcs");
        JsonNode bundle=SrppDailyContextResponseValidator.parseStrict(jcs.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!bundle.isObject() || !jcs.equals(SrppJcs.canonicalize(bundle))
                || !text(snapshot,"inputSnapshotSha256").equals(SrppJcs.sha256Hex(jcs))) fail();
        if(integer(bundle,"ownerUserId")!=owner || !q.tradingDate().equals(text(bundle,"tradingDate"))
                || !q.reportSlot().equals(text(bundle,"slot"))) fail();
        JsonNode state=bundle.get("stateBefore");
        if(state==null || !state.isObject() || integer(state,"ownerUserId")!=owner
                || !q.consumer().equals(text(state,"consumer")) || !q.tradingDate().equals(text(state,"tradingDate"))
                || !q.reportSlot().equals(text(state,"reportSlot"))) fail();
        if (!authority.equals(bundle.get("authority"))) fail();
        JsonNode sources=bundle.get("sources"), vector=snapshot.get("sourceVector");
        if(sources==null || !sources.isObject() || vector==null || !vector.isObject() || vector.isEmpty()
                || sources.size()!=vector.size()) fail();
        if (!state.equals(sources.get("core-state")) || !Set.of("VERIFIED","UNAVAILABLE").contains(text(state,"status")) || !state.has("stateBefore")) fail();
        boolean verifiedState="VERIFIED".equals(text(state,"status"));
        if(verifiedState && !state.get("stateBefore").isObject())fail();
        JsonNode stateVector=vector.get("core-state");
        if(stateVector==null || !(verifiedState?"VERIFIED":"UNAVAILABLE").equals(text(stateVector,"validation"))
                || !(verifiedState?"COMPLETE":"NONE").equals(text(stateVector,"coverage")))fail();
        JsonNode policy=sources.get("policy"), swagger=sources.get("swagger");
        if(policy==null || !policy.isObject() || !q.policyBundleSha256().equals(text(policy,"bundleHash"))
                || !SrppJcs.sha256Hex(text(policy,"rawModelBytes")).equals(text(authority,"modelInputsSha256"))
                || !SrppJcs.sha256Hex(text(policy,"rawManifestBytes")).equals(text(authority,"policyManifestSha256"))
                || swagger==null || !q.swaggerSha256().equals(text(swagger,"sha256")))fail();
        JsonNode model=SrppDailyContextResponseValidator.parseStrict(text(policy,"rawModelBytes").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode targetWeights=model.get("detailed_target_allocation_2027");
        if(targetWeights==null || !targetWeights.isObject() || targetWeights.isEmpty())fail();
        ObjectNode targetIdentity=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        targetWeights.fields().forEachRemaining(e->{
            if(!e.getValue().isNumber())fail();
            targetIdentity.put(e.getKey(),e.getValue().decimalValue().stripTrailingZeros().toPlainString());
        });
        if(!SrppJcs.sha256Hex(SrppJcs.canonicalize(targetIdentity)).equals(text(authority,"targetAllocationSha256")))fail();
        Set<String> sourceIds=new HashSet<>(), verifiedSources=new HashSet<>();
        vector.fields().forEachRemaining(entry->{
            String id=entry.getKey();JsonNode source=entry.getValue();
            fields(source,"sourceId","revision","capturedAt","sha256","coverage","validation");
            if(!id.equals(text(source,"sourceId")) || !sourceIds.add(id) || !sources.has(id)) fail();
            text(source,"revision");sha(source,"sha256");
            if(time(source,"capturedAt").isAfter(captured)) fail();
            if(!Set.of("COMPLETE","PARTIAL","NONE").contains(text(source,"coverage"))
                    || !Set.of("VERIFIED","UNAVAILABLE").contains(text(source,"validation"))) fail();
            if(!text(source,"sha256").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(sources.get(id))))) fail();
            if("VERIFIED".equals(text(source,"validation"))) verifiedSources.add(id);
        });
        JsonNode modules=root.get("modules");array(modules,12);if(modules.size()!=12)fail();
        Set<String> seen=new HashSet<>();int[] counts=new int[4];
        for(JsonNode module:modules){
            fields(module,"moduleId","status","formulaVersion","scope","data","missing","reasonCodes","inputRefs","receipts");
            String id=text(module,"moduleId");if(!MODULES.contains(id)||!seen.add(id))fail();
            text(module,"formulaVersion");texts(module.get("scope"),100);texts(module.get("missing"),1000);texts(module.get("reasonCodes"),1000);
            refs(module.get("inputRefs"),sourceIds);receipts(module.get("receipts"),sourceIds);
            String status=text(module,"status");int index=switch(status){case "COMPLETE"->0;case "PARTIAL"->1;case "UNAVAILABLE"->2;case "NOT_EVALUATED"->3;default->throw invalid();};counts[index]++;
            if(index==0 && (!module.get("missing").isEmpty() || module.get("scope").isEmpty()))fail();
            if(index==0) {
                for(JsonNode ref:module.get("inputRefs")) if(!verifiedSources.contains(ref.textValue()))fail();
                if("CORE_SELL_GUARD".equals(id) && (!"VERIFIED".equals(text(state,"status")) || state.path("stateBefore").isNull()))fail();
            }
            if(index>0 && module.get("reasonCodes").isEmpty())fail();
            JsonNode data=module.get("data");fields(data,"facts","legs","ranking","eventEvidenceIntegrationStatus");
            if(!"NOT_BOUND".equals(text(data,"eventEvidenceIntegrationStatus")))fail();
            texts(data.get("ranking"),1000);array(data.get("facts"),5000);array(data.get("legs"),1000);
            Set<String> factIdentities=new HashSet<>();
            for(JsonNode fact:data.get("facts")){
                fact(fact);
                if(!FACT_NAMES.get(id).contains(text(fact,"name")) || !factIdentities.add(text(fact,"name")+"\u0000"+fact.get("subject").toString()))fail();
                if(index==0 && "UNAVAILABLE".equals(text(fact,"quality")))fail();
                if(index==2 && !fact.get("value").isNull())fail();
            }
            if("BOND_D130_D195".equals(id) && !data.get("legs").isEmpty()) {
                Set<String> bondLegs=new HashSet<>();
                for(JsonNode leg:data.get("legs")) {
                    String identityKey=text(leg,"symbol")+":"+text(leg,"strategy");
                    if(!bondLegs.add(identityKey))fail();
                }
                if(!bondLegs.equals(Set.of("00697B:STRATEGIC_FUBON","00719B:STRATEGIC_FUBON","00865B:STRATEGIC_FUBON","00865B:EMERGENCY_CATHAY")))fail();
            }
            for(JsonNode leg:data.get("legs")){leg(id,leg,sourceIds,verifiedSources);if(index>=2 && "ACTIONABLE_FOR_REPORT".equals(text(leg,"status")))fail();}
        }
        JsonNode coverage=root.get("coverage");fields(coverage,"requestedCount","completeCount","partialCount","unavailableCount","notEvaluatedCount");
        if(integer(coverage,"requestedCount")!=12 || integer(coverage,"completeCount")!=counts[0]
                ||integer(coverage,"partialCount")!=counts[1] ||integer(coverage,"unavailableCount")!=counts[2]
                ||integer(coverage,"notEvaluatedCount")!=counts[3])fail();
        sha(root,"contentSha256");ObjectNode projection=((ObjectNode)root).deepCopy();projection.remove("contentSha256");
        if(!text(root,"contentSha256").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(projection))))fail();
    }
    private static void leg(String moduleId,JsonNode leg,Set<String> sources,Set<String> verifiedSources){
        fields(leg,"symbol","market","broker","subaccount","strategy","status","action","lots","units","limitPrice","amount","currency","fees","netProfitSpace","rankingReason","blockingReasons","receipts");
        text(leg,"symbol");text(leg,"market");text(leg,"strategy");text(leg,"currency");
        for(String key:Set.of("broker","subaccount","rankingReason"))nullableText(leg,key);
        String status=text(leg,"status"),action=text(leg,"action"),strategy=text(leg,"strategy");
        // These 09:05/12:00 Taiwan report slots never expose individual US legs.
        if(!"台股".equals(text(leg,"market")))fail();
        boolean shortStrategy=SHORT_STRATEGIES.contains(strategy);
        boolean boundStrategy=switch(moduleId){
            case "BOND_D130_D195" -> Set.of("STRATEGIC_FUBON","EMERGENCY_CATHAY").contains(strategy);
            case "DIVIDEND_TOPUP" -> "DIVIDEND_TOPUP".equals(strategy);
            case "SHORT_TERM_AND_US_TACTICAL" -> shortStrategy;
            default -> false;
        };
        if(!boundStrategy)fail();
        if(!Set.of("ACTIONABLE_FOR_REPORT","BLOCKED","NOT_EVALUATED").contains(status)||!Set.of("BUY","SELL","NONE").contains(action))fail();
        JsonNode lots=leg.get("lots");if(!lots.isNull() && integer(leg,"lots")<0)fail();
        for(String key:Set.of("units","limitPrice","amount","fees","netProfitSpace"))decimal(leg.get(key));
        texts(leg.get("blockingReasons"),1000);receipts(leg.get("receipts"),sources);
        boolean active="ACTIONABLE_FOR_REPORT".equals(status);
        if(active){
            boolean positiveLots=!lots.isNull() && lots.longValue()>0;
            boolean positiveUnits=positive(leg.get("units"));
            if(positiveLots==positiveUnits || !positive(leg.get("limitPrice")) || !positive(leg.get("amount"))
                    || leg.get("fees").isNull() || new BigDecimal(leg.get("fees").textValue()).signum()<0
                    ||"NONE".equals(action) || !leg.get("blockingReasons").isEmpty() || leg.get("receipts").isEmpty())fail();
            Set<String> gates=new HashSet<>();
            for(JsonNode receipt:leg.get("receipts")){
                String rule=text(receipt,"ruleId"), gate=text(receipt,"status");
                if(!gates.add(rule))fail();
                boolean d130Optional=Set.of("STRATEGIC_FUBON","EMERGENCY_CATHAY").contains(text(leg,"strategy"))
                        && ("INTRADAY".equals(rule) && "NOT_EVALUATED".equals(gate)
                            && text(receipt,"reason").startsWith("HORIZON_NOT_APPLICABLE")
                            || Set.of("TRADABILITY","BEAR_WINDOW").contains(rule) && "UNAVAILABLE".equals(gate));
                if(!"PASS".equals(gate) && !d130Optional)fail();
                if(!d130Optional)for(JsonNode ref:receipt.get("inputRefs"))if(!verifiedSources.contains(ref.textValue()))fail();
                if("SHARED_CURRENCY_CAPACITY".equals(rule)){Set<String> sharedRefs=new HashSet<>();receipt.get("inputRefs").forEach(r->sharedRefs.add(r.textValue()));if(!sharedRefs.equals(Set.of("assets","report-reservations","topup-state","policy")))fail();}
            }
            if(Set.of("STRATEGIC_FUBON","EMERGENCY_CATHAY").contains(text(leg,"strategy"))) {
                Set<String> required=new HashSet<>(Set.of("POSITION","PENDING","RADAR","RADAR_DIRECTION","VALUATION","TRADABILITY","FX","FX_CAP","BEAR_WINDOW","DEDUP","QUOTE","BOOK","D-195","FUNDS_FLOOR","INTRADAY"));
                if("STRATEGIC_FUBON".equals(text(leg,"strategy")))required.addAll(Set.of("TARGET","D-130_CAPACITY"));
                else required.add("EMERGENCY_CAPACITY");
                if("00865B".equals(text(leg,"symbol")))required.add("SUBACCOUNTS");
                required.add("SHARED_CURRENCY_CAPACITY");
                if(!gates.containsAll(required))fail();
            }
            if("DIVIDEND_TOPUP".equals(strategy)){
                if(!"BUY".equals(action) || !"國泰證券".equals(text(leg,"broker"))
                        || !"HIGH_DIVIDEND_TOPUP".equals(text(leg,"subaccount")) || !gates.equals(TOPUP_RECEIPTS))fail();
                Set<String> expectedRefs=Set.of("market:台股:"+text(leg,"symbol"),"assets","policy","ledger","topup-state","report-reservations","classifications");
                for(JsonNode receipt:leg.get("receipts")){
                    Set<String> actualRefs=new HashSet<>();receipt.get("inputRefs").forEach(r->actualRefs.add(r.textValue()));
                    if(!actualRefs.equals("SHARED_CURRENCY_CAPACITY".equals(text(receipt,"ruleId"))?Set.of("assets","report-reservations","topup-state","policy"):expectedRefs))fail();
                }
            }
            if(shortStrategy){
                if(!lots.isNull() || !positiveUnits || !leg.get("units").textValue().matches("[1-9][0-9]*")
                        || new BigDecimal(leg.get("units").textValue()).compareTo(BigDecimal.valueOf(9007199254740991L))>0)fail();
                text(leg,"broker");text(leg,"subaccount");
                Set<String> required="BUY".equals(action)?SHORT_BUY_RECEIPTS:SHORT_SELL_RECEIPTS;
                if(!gates.equals(required))fail();
                Set<String> expectedRefs=Set.of("assets","policy","short-sleeve","market:台股:"+text(leg,"symbol"));
                for(JsonNode receipt:leg.get("receipts")){
                    Set<String> actualRefs=new HashSet<>();receipt.get("inputRefs").forEach(r->actualRefs.add(r.textValue()));
                    if(!actualRefs.equals("SHARED_CURRENCY_CAPACITY".equals(text(receipt,"ruleId"))?Set.of("assets","report-reservations","topup-state","policy"):expectedRefs))fail();
                }
            }
            BigDecimal quantity=positiveLots ? BigDecimal.valueOf(lots.longValue()).multiply(BigDecimal.valueOf(1000)) : new BigDecimal(leg.get("units").textValue());
            if(new BigDecimal(leg.get("limitPrice").textValue()).multiply(quantity).compareTo(new BigDecimal(leg.get("amount").textValue()))!=0)fail();
            if(!"TWD".equals(text(leg,"currency")) || (!shortStrategy && (!positiveLots || !leg.get("units").isNull())))fail();
        } else {
            if(!lots.isNull() && lots.longValue()!=0 || !leg.get("units").isNull() && new BigDecimal(leg.get("units").textValue()).signum()!=0
                    || !leg.get("limitPrice").isNull() || !leg.get("amount").isNull())fail();
            if("BLOCKED".equals(status) && leg.get("blockingReasons").isEmpty())fail();
        }
    }
    private static boolean positive(JsonNode n){return n!=null && !n.isNull() && new BigDecimal(n.textValue()).signum()>0;}
    private static void receipts(JsonNode list,Set<String> sources){array(list,5000);for(JsonNode r:list){
        fields(r,"ruleId","calculatorVersion","inputRefs","status","reason","values","scope");text(r,"ruleId");text(r,"calculatorVersion");text(r,"reason");
        if(!Set.of("PASS","BLOCK","UNAVAILABLE","NOT_EVALUATED").contains(text(r,"status")))fail();
        nullableText(r,"scope");
        if("INTRADAY".equals(text(r,"ruleId"))) {if(!"SHORT_ONLY".equals(text(r,"scope")))fail();}
        else if(!r.get("scope").isNull())fail();
        refs(r.get("inputRefs"),sources);array(r.get("values"),5000);for(JsonNode v:r.get("values"))fact(v);
    }}
    private static void fact(JsonNode n){fields(n,"name","subject","value","unit","quality","reason");text(n,"name");nullableText(n,"subject");nullableText(n,"reason");decimal(n.get("value"));
        String unit=text(n,"unit");if((!UNITS.contains(unit) && !unit.matches("[A-Z]{3}")) || !QUALITIES.contains(text(n,"quality")))fail();
        boolean unavailable="UNAVAILABLE".equals(text(n,"quality"));if(unavailable!=n.get("value").isNull())fail();
        if(unavailable && n.get("reason").isNull())fail();
    }
    private static void decimal(JsonNode n){if(n==null)fail();if(!n.isNull() && (!n.isTextual() || !n.textValue().matches("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?") || "-0".equals(n.textValue())))fail();}
    private static void refs(JsonNode list,Set<String> sources){texts(list,1000);for(JsonNode id:list)if(!sources.contains(id.textValue()))fail();}
    private static void texts(JsonNode n,int max){array(n,max);Set<String>s=new HashSet<>();for(JsonNode v:n)if(!v.isTextual()||v.textValue().isEmpty()||!s.add(v.textValue()))fail();}
    private static void array(JsonNode n,int max){if(n==null||!n.isArray()||n.size()>max)fail();}
    private static void fields(JsonNode n,String...keys){if(n==null||!n.isObject()||n.size()!=keys.length)fail();for(String k:keys)if(!n.has(k))fail();}
    private static String text(JsonNode n,String k){JsonNode v=n.get(k);if(v==null||!v.isTextual()||v.textValue().isEmpty())fail();return v.textValue();}
    private static void nullableText(JsonNode n,String k){if(!n.get(k).isNull())text(n,k);}
    private static long integer(JsonNode n,String k){JsonNode v=n.get(k);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0||v.longValue()>9007199254740991L)fail();return v.longValue();}
    private static void sha(JsonNode n,String k){if(!text(n,k).matches("[0-9a-f]{64}"))fail();}
    private static void uuid(JsonNode n,String k){if(!text(n,k).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))fail();}
    private static void date(JsonNode n,String k){try{LocalDate.parse(text(n,k));}catch(RuntimeException e){fail();}}
    private static OffsetDateTime time(JsonNode n,String k){try{return OffsetDateTime.parse(text(n,k));}catch(RuntimeException e){throw invalid();}}
    private static void boolFalse(JsonNode n,String k){if(!n.get(k).isBoolean()||n.get(k).booleanValue())fail();}
    private static SrppPayloadException invalid(){return new SrppPayloadException("SRPP report response invalid");}
    private static void fail(){throw invalid();}
}
