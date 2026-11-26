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
            java.util.Map.entry("ALLOCATION_GAP", Set.of("targetWeight","currentValue","currentWeight","targetValue","gapValue","normalizedGap","protectedCashWeight","bondTarget","bondFloor","bondCap","emergencyTarget","emergencyGap","protectedReserveTarget","referenceCashDelta","equityTarget","reserveSelectionLow52","reserveSelectionHigh52","reserveSelectionPosition52","marketAdjustmentReviewAmount","marketAdjustmentUnallocated")),
            java.util.Map.entry("FUNDING_CAPACITY", Set.of("settledBalance","payable","excludedReceivable","termBalance","inflationFactor","termFloor","totalFloor","reservedFunds","reservedFees","deployableCapacity","pooledPostBuyHeadroom")),
            java.util.Map.entry("CASH_INCOME", Set.of("stockGross","fundGross","depositGross","totalGross","nonSpendableInterest","spendableGross","taiwanIncomeTax","usWithholding","additionalBasicTax","supplementaryNhi","afterTaxCash","annualSettlement","spendableSgovGross","domesticDividend","domesticInterest","savingsInterest","otherDomesticIncome","overseasIncome","creditableForeignTax","nhiChargeableIncome","taxCalculation","annualTaxPrepayments")),
            java.util.Map.entry("PAYROLL_CASHFLOW", Set.of("grossSalary","withholding","voluntaryPension","takeHome","investableContribution","annualSettlement","paymentCount","payrollDetail","calendarYearPayroll","pensionReceipt")),
            java.util.Map.entry("BOND_D130_D195", Set.of()),
            java.util.Map.entry("DIVIDEND_TOPUP", Set.of("referenceHigh","drawdownPct","tierLots","targetGap","normalizedGap","remainingDailyLots","remainingWeeklyLots","d218ObservedNoNewLowSessions","d218Stabilized","d218AnchorLow","d218UsedBudget","d218CumulativeFraction","d218BudgetCeiling")),
            java.util.Map.entry("CORE_SELL_GUARD", Set.of("highWater","pullbackPct","overheatHits","trendReversal","timingPass")),
            java.util.Map.entry("STRATEGIC_BUY_GATES", Set.of("position52w","high52w","low52w","overheatHits","averageExDividendClose","eligible","dcaExempt")),
            java.util.Map.entry("GLOBAL_EQUITY_PACE", Set.of("monthlyBaseline","monthlyCap","spent","remaining","previousShortfall","discretionaryBudget","remainingDayLots","remainingWeekLots","consecutiveShortfalls","ceilingLots","remainingDayShares","remainingWeekShares","minOrderShares","ceilingShares")),
            java.util.Map.entry("ROTATION_AND_REGIME_CAPACITY", Set.of("age","equityFloor","bondFloor","livingRefill","deployableLiving","rotationCapacity","bearBondCapacity","ladderAmount","gainAboveAnchor","pullback","drawdown","nextBatch","settledReserve","overdueReserve")),
            java.util.Map.entry("SHORT_TERM_AND_US_TACTICAL", Set.of("support","pressure","reboundPct","netUpsidePct","netDownsidePct","rewardRisk","positionCapacity","netProfitSpacePct","referencePrice","maximumNotional")));
    private static final Set<String> TOPUP_RECEIPTS=Set.of("QUOTE","RADAR","STRATEGIC_PRICE","TIER","STOP","REGIME","TRADABILITY","QUOTAS","PENDING","RESERVATIONS","SCHEDULED_DCA","FUNDING","DEPTH","ALLOCATION","CLASSIFICATION","EXECUTION_ACCOUNT","SHARED_CURRENCY_CAPACITY");
    /** Task 623 satellite build legs; Task 625 adds D215_FX to the VT set only. */
    static final Set<String> SATELLITE_009826_RECEIPTS=Set.of("SATELLITE_BUILD","SHARED_CURRENCY_CAPACITY");
    static final Set<String> SATELLITE_VT_RECEIPTS=Set.of("SATELLITE_BUILD","FX_CONVERSION","SHARED_CURRENCY_CAPACITY");
    static final Set<String> SATELLITE_VT_D215_RECEIPTS=Set.of("SATELLITE_BUILD","FX_CONVERSION","D215_FX","SHARED_CURRENCY_CAPACITY");
    static final String D215_RULE="D215_FX";
    /** Task 625 D215_FX receipt values: exact names and units (assetHigh52w / ceiling amount follow the leg currency). */
    private static final java.util.Map<String,String> D215_UNITS=java.util.Map.of("fxSpotSelling","TWD","fxRangeLow","TWD","fxRangeHigh","TWD",
            "fxRangePosition","RATIO","fxAbsoluteHigh","BOOLEAN","fxHigh","BOOLEAN","assetDrawdown52w","PERCENT","cheapAssetException","BOOLEAN",
            "d215WaitCeilingLots","LOTS","d215WaitCeilingUnits","SHARES");
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
        JsonNode modules=root.get("modules");
        SrppZeroTargetExitValidator.routes(modules,new SrppZeroTargetExitValidator.Context(model,sources,vector,owner,q.tradingDate(),q.policyBundleSha256(),captured,false,q.reportSlot()));array(modules,12);if(modules.size()!=12)fail();
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
                if("ALLOCATION_GAP".equals(id)) d222AllocationFact(fact);
                if("DIVIDEND_TOPUP".equals(id)&&text(fact,"name").startsWith("d218")){
                    String name=text(fact,"name"),unit=switch(name){case "d218ObservedNoNewLowSessions"->"COUNT";case "d218Stabilized"->"BOOLEAN";case "d218CumulativeFraction"->"RATIO";default->"TWD";};
                    if(!unit.equals(text(fact,"unit")))fail();
                    if(!fact.get("value").isNull()){
                        BigDecimal value=new BigDecimal(fact.get("value").textValue());if(value.signum()<0)fail();
                        if(name.equals("d218Stabilized")&&value.compareTo(BigDecimal.ZERO)!=0&&value.compareTo(BigDecimal.ONE)!=0)fail();
                        if(name.equals("d218ObservedNoNewLowSessions")&&value.stripTrailingZeros().scale()>0)fail();
                        if(name.equals("d218CumulativeFraction")&&value.compareTo(BigDecimal.ONE)>0)fail();
                    }
                }
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
            int namedExitCount=0;for(JsonNode candidate:data.get("legs"))if(Set.of("NAMED_RADAR_ROTATION_D226","NAMED_HIGH_ZONE_EXIT_D235").contains(candidate.path("strategy").asText())&&++namedExitCount>1)fail();
            for(JsonNode leg:data.get("legs")){if(!SrppZeroTargetExitValidator.STRATEGY.equals(leg.path("strategy").asText()))leg(id,leg,sourceIds,verifiedSources,model,sources,q.tradingDate());if(index>=2 && "ACTIONABLE_FOR_REPORT".equals(text(leg,"status")))fail();}
        }
        JsonNode coverage=root.get("coverage");fields(coverage,"requestedCount","completeCount","partialCount","unavailableCount","notEvaluatedCount");
        if(integer(coverage,"requestedCount")!=12 || integer(coverage,"completeCount")!=counts[0]
                ||integer(coverage,"partialCount")!=counts[1] ||integer(coverage,"unavailableCount")!=counts[2]
                ||integer(coverage,"notEvaluatedCount")!=counts[3])fail();
        sha(root,"contentSha256");ObjectNode projection=((ObjectNode)root).deepCopy();projection.remove("contentSha256");
        if(!text(root,"contentSha256").equals(SrppJcs.sha256Hex(SrppJcs.canonicalize(projection))))fail();
    }
    /** Validate D-222 wire domains only; frozen financial results are never recomputed here. */
    private static void d222AllocationFact(JsonNode fact) {
        String name=text(fact,"name");
        if(!Set.of("protectedReserveTarget","referenceCashDelta","equityTarget","reserveSelectionLow52",
                "reserveSelectionHigh52","reserveSelectionPosition52","marketAdjustmentReviewAmount","marketAdjustmentUnallocated").contains(name))return;
        boolean symbolScoped=switch(name) {
            case "reserveSelectionLow52","reserveSelectionHigh52","reserveSelectionPosition52","marketAdjustmentReviewAmount" -> true;
            case "protectedReserveTarget","referenceCashDelta","equityTarget","marketAdjustmentUnallocated" -> false;
            default -> false;
        };
        String subject=null;
        if(symbolScoped) {
            subject=text(fact,"subject");
            if(!Set.of("VOO","0050","00881").contains(subject))fail();
        } else if(!fact.get("subject").isNull())fail();
        boolean range=name.equals("reserveSelectionLow52")||name.equals("reserveSelectionHigh52");
        boolean ratio=name.equals("equityTarget")||name.equals("reserveSelectionPosition52");
        String unit=ratio?"RATIO":range&&"VOO".equals(subject)?"USD":"TWD";
        if(!unit.equals(text(fact,"unit")))fail();
        if(fact.get("value").isNull())return; // fact() already enforces UNAVAILABLE plus a named reason.
        BigDecimal value=new BigDecimal(fact.get("value").textValue());
        if(name.equals("referenceCashDelta"))return;
        if(value.signum()<0 || range&&value.signum()==0 || ratio&&value.compareTo(BigDecimal.ONE)>0)fail();
    }
    private static void leg(String moduleId,JsonNode leg,Set<String> sources,Set<String> verifiedSources,JsonNode model,JsonNode rawSources,String tradingDate){
        fields(leg,"symbol","market","broker","subaccount","strategy","status","action","lots","units","limitPrice","amount","currency","fees","netProfitSpace","rankingReason","blockingReasons","receipts");
        text(leg,"symbol");text(leg,"market");text(leg,"strategy");text(leg,"currency");
        for(String key:Set.of("broker","subaccount","rankingReason"))nullableText(leg,key);
        String status=text(leg,"status"),action=text(leg,"action"),strategy=text(leg,"strategy");
        String symbol=text(leg,"symbol"),market=text(leg,"market"),currency=text(leg,"currency");
        boolean satelliteLots="GLOBAL_EQUITY_PACE".equals(moduleId)&&"SATELLITE_BUILD_D143".equals(strategy)&&"009826".equals(symbol)&&"台股".equals(market)&&"TWD".equals(currency);
        boolean satelliteVt="GLOBAL_EQUITY_PACE".equals(moduleId)&&"SATELLITE_BUILD_D234".equals(strategy)&&"VT".equals(symbol)&&"美股".equals(market)&&"USD".equals(currency);
        // These 09:05/12:00 Taiwan report slots expose no individual US leg except the D-234 VT build ceiling (TWD-funded sub-brokerage).
        if(!"台股".equals(market)&&!satelliteVt)fail();
        boolean highSell="NAMED_HIGH_ZONE_EXIT_D235".equals(strategy);
        boolean planBuy="NAMED_CASH_BUY_D226".equals(strategy),shortStrategy=SHORT_STRATEGIES.contains(strategy),namedSell=highSell||"NAMED_RADAR_ROTATION_D226".equals(strategy);
        boolean boundStrategy=switch(moduleId){
            case "BOND_D130_D195" -> Set.of("STRATEGIC_FUBON","EMERGENCY_CATHAY").contains(strategy);
            case "DIVIDEND_TOPUP" -> "DIVIDEND_TOPUP".equals(strategy);
            case "STRATEGIC_BUY_GATES" -> "NAMED_DEEP_DRAWDOWN_D215".equals(strategy)&&Set.of("0050","00881").contains(text(leg,"symbol"))||planBuy&&"00881".equals(text(leg,"symbol"))||namedSell&&"2881".equals(text(leg,"symbol"));
            case "SHORT_TERM_AND_US_TACTICAL" -> shortStrategy;
            case "GLOBAL_EQUITY_PACE" -> satelliteLots||satelliteVt;
            default -> false;
        };
        if(!boundStrategy)fail();
        if(highSell&&(!"SELL".equals(action)||!"CORE_D226".equals(text(leg,"subaccount"))))fail();
        if(!Set.of("ACTIONABLE_FOR_REPORT","BLOCKED","NOT_EVALUATED").contains(status)||!Set.of("BUY","SELL","NONE").contains(action))fail();
        JsonNode lots=leg.get("lots");if(!lots.isNull() && integer(leg,"lots")<0)fail();
        for(String key:Set.of("units","limitPrice","amount","fees","netProfitSpace"))decimal(leg.get(key));
        texts(leg.get("blockingReasons"),1000);receipts(leg.get("receipts"),sources);
        boolean active="ACTIONABLE_FOR_REPORT".equals(status);
        boolean d215=d215(model);
        d215Receipt(leg,"BOND_D130_D195".equals(moduleId)||satelliteVt,d215,satelliteVt,active);
        if(active){
            if(namedSell&&!highSell&&highRouteExpected(model,rawSources,tradingDate))fail();
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
                boolean mediumOptional=("NAMED_DEEP_DRAWDOWN_D215".equals(strategy)||planBuy||namedSell)&&"TRADABILITY".equals(rule)&&"UNAVAILABLE".equals(gate)&&"D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY".equals(text(receipt,"reason"));
                if(!"PASS".equals(gate) && !d130Optional && !mediumOptional)fail();
                if(!d130Optional)for(JsonNode ref:receipt.get("inputRefs"))if(!verifiedSources.contains(ref.textValue()))fail();
                if("SHARED_CURRENCY_CAPACITY".equals(rule)){Set<String> sharedRefs=new HashSet<>();receipt.get("inputRefs").forEach(r->sharedRefs.add(r.textValue()));if(!sharedRefs.equals(Set.of("assets","report-reservations","topup-state","policy")))fail();}
            }
            if(Set.of("STRATEGIC_FUBON","EMERGENCY_CATHAY").contains(text(leg,"strategy"))) {
                Set<String> required=new HashSet<>(Set.of("POSITION","PENDING","RADAR","RADAR_DIRECTION","VALUATION","TRADABILITY","FX","FX_CAP","BEAR_WINDOW","DEDUP","QUOTE","BOOK","D-195","FUNDS_FLOOR","INTRADAY"));
                if("STRATEGIC_FUBON".equals(text(leg,"strategy")))required.addAll(Set.of("TARGET","D-130_CAPACITY"));
                else required.add("EMERGENCY_CAPACITY");
                if("00865B".equals(text(leg,"symbol")))required.add("SUBACCOUNTS");
                required.add("SHARED_CURRENCY_CAPACITY");
                if(d215)required.add(D215_RULE);
                if(!gates.containsAll(required))fail();
            }
            if("DIVIDEND_TOPUP".equals(strategy)){
                boolean d218=model.path("execution_policy").path("high_dividend_stabilization_d218").path("enabled").asBoolean()
                        && Set.of("0056","00713","00878").contains(text(leg,"symbol"));
                Set<String> required=new HashSet<>(TOPUP_RECEIPTS);if(d218)required.add("D218_STABILIZATION");
                if(!"BUY".equals(action) || !"國泰證券".equals(text(leg,"broker"))
                        || !"HIGH_DIVIDEND_TOPUP".equals(text(leg,"subaccount")) || !gates.equals(required))fail();
                if(d218){
                    JsonNode ceiling=null;
                    for(JsonNode r:leg.get("receipts"))if("D218_STABILIZATION".equals(text(r,"ruleId"))){
                        if(r.get("values").size()!=1)fail();ceiling=r.get("values").get(0);
                    }
                    if(ceiling==null || !"d218BudgetCeiling".equals(text(ceiling,"name")) || !text(leg,"symbol").equals(text(ceiling,"subject"))
                            || !"TWD".equals(text(ceiling,"unit")) || !"EXACT".equals(text(ceiling,"quality")) || !positive(ceiling.get("value")))fail();
                    if(new BigDecimal(leg.get("amount").textValue()).add(new BigDecimal(leg.get("fees").textValue())).compareTo(new BigDecimal(ceiling.get("value").textValue()))>0)fail();
                }
                Set<String> expectedRefs=Set.of("market:台股:"+text(leg,"symbol"),"assets","policy","ledger","topup-state","report-reservations","classifications");
                for(JsonNode receipt:leg.get("receipts")){
                    Set<String> actualRefs=new HashSet<>();receipt.get("inputRefs").forEach(r->actualRefs.add(r.textValue()));
                    if(!actualRefs.equals("SHARED_CURRENCY_CAPACITY".equals(text(receipt,"ruleId"))?Set.of("assets","report-reservations","topup-state","policy"):expectedRefs))fail();
                }
            }
            if("NAMED_DEEP_DRAWDOWN_D215".equals(strategy)||planBuy){
                Set<String> required=new HashSet<>(Set.of("D215_NAMED_PATH","QUOTE","RADAR","STRATEGIC_PRICE","ALLOCATION","FUNDING","PENDING","RESERVATIONS","DEDUPLICATION","DEPTH","EXECUTION_ACCOUNT","THESIS","TRADABILITY","SHARED_CURRENCY_CAPACITY"));
                if(planBuy){required.remove("D215_NAMED_PATH");required.add("D226_BUY_PATH");required.add("D223_PLAN_BUDGET");}
                if(!gates.equals(required)||!"BUY".equals(action)||!"國泰證券".equals(text(leg,"broker"))||!(planBuy?"CORE_D226_BUY":"CORE_D215").equals(text(leg,"subaccount")))fail();
                for(JsonNode receipt:leg.get("receipts")){
                    String rule=text(receipt,"ruleId"),code=text(leg,"symbol");Set<String> expected;
                    if("SHARED_CURRENCY_CAPACITY".equals(rule))expected=Set.of("assets","report-reservations","topup-state","policy");
                    else if("ALLOCATION".equals(rule))expected=Set.of("assets","policy","classifications","fx","market:美股:VOO","market:台股:0050","market:台股:00881");
                    else if("THESIS".equals(rule))expected=Set.of("policy","market:台股:"+code,"classifications");
                    else if("TRADABILITY".equals(rule))expected=Set.of("policy","market:台股:"+code,"topup-state");
                    else if(Set.of("D215_NAMED_PATH","D226_BUY_PATH","QUOTE","RADAR","STRATEGIC_PRICE","DEPTH").contains(rule))expected=Set.of("policy","market:台股:"+code);
                    else expected=Set.of("assets","policy","topup-state","report-reservations","classifications","market:台股:"+code);
                    Set<String> actual=new HashSet<>();receipt.get("inputRefs").forEach(r->actual.add(r.asText()));if(!actual.equals(expected))fail();
                }
            }
            if(namedSell){
                Set<String> required=new HashSet<>(Set.of("D226_SELL_PATH","QUOTE","RADAR","POSITION","PENDING","RESERVATIONS","DEDUPLICATION","CORE_RETENTION","D152_BATCH","COOLDOWN","DEPTH","EXECUTION_ACCOUNT","COSTS","TRADABILITY"));
                if(highSell){required.remove("D226_SELL_PATH");required.add("D235_HIGH_ZONE_PATH");}
                if(!gates.equals(required)||!"SELL".equals(action)||!"CORE_D226".equals(text(leg,"subaccount"))||!lots.isNull()||!positiveUnits||!leg.get("units").textValue().matches("[1-9][0-9]*")||new BigDecimal(leg.get("units").textValue()).compareTo(BigDecimal.valueOf(9007199254740991L))>0)fail();text(leg,"broker");
                for(JsonNode receipt:leg.get("receipts")){
                    String rule=text(receipt,"ruleId");Set<String> expected=Set.of("QUOTE","RADAR","DEPTH","D235_HIGH_ZONE_PATH").contains(rule)?Set.of("policy","market:台股:2881"):"TRADABILITY".equals(rule)?Set.of("policy","market:台股:2881","topup-state"):Set.of("assets","policy","topup-state","report-reservations","classifications","market:台股:2881");
                    Set<String> actual=new HashSet<>();receipt.get("inputRefs").forEach(r->actual.add(r.asText()));if(!actual.equals(expected))fail();
                }
            }
            if(highSell)highZoneFacts(leg,rawSources,model,tradingDate);
            if(satelliteLots||satelliteVt)satellite(leg,gates,satelliteVt,positiveLots,positiveUnits,verifiedSources,d215);
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
            if(satelliteVt){if(!"USD".equals(currency) || !lots.isNull() || !positiveUnits)fail();}
            else if(!"TWD".equals(currency) || (!shortStrategy && !namedSell && (!positiveLots || !leg.get("units").isNull())))fail();
        } else {
            if(!lots.isNull() && lots.longValue()!=0 || !leg.get("units").isNull() && new BigDecimal(leg.get("units").textValue()).signum()!=0
                    || !leg.get("limitPrice").isNull() || !leg.get("amount").isNull())fail();
            if("BLOCKED".equals(status) && leg.get("blockingReasons").isEmpty())fail();
        }
    }
    /** Exact Task 623 satellite ACTIONABLE contract: fixed account, receipts, refs and the VT TWD conversion identity. */
    private static void satellite(JsonNode leg,Set<String> gates,boolean vt,boolean positiveLots,boolean positiveUnits,Set<String> verifiedSources,boolean d215){
        if(!"BUY".equals(text(leg,"action")) || !gates.equals(vt?(d215?SATELLITE_VT_D215_RECEIPTS:SATELLITE_VT_RECEIPTS):SATELLITE_009826_RECEIPTS))fail();
        if(vt){
            if(!leg.get("lots").isNull() || !positiveUnits || !leg.get("units").textValue().matches("[1-9][0-9]*")
                    || new BigDecimal(leg.get("units").textValue()).compareTo(BigDecimal.valueOf(9007199254740991L))>0
                    || !"國泰證券".equals(text(leg,"broker")) || !"CORE_D234".equals(text(leg,"subaccount")) || !"3".equals(leg.get("fees").textValue()))fail();
        }else if(!positiveLots || !leg.get("units").isNull() || !"富邦證券".equals(text(leg,"broker")) || !"CORE_D143".equals(text(leg,"subaccount")))fail();
        Set<String> required=vt?Set.of("policy","assets","fx","satellite-build-fills","market:美股:VT"):Set.of("policy","assets","satellite-build-fills","market:台股:009826");
        Set<String> optional=vt?Set.of("usd-twd-month-mids"):Set.of("usd-twd-month-mids","fx");
        for(JsonNode receipt:leg.get("receipts")){
            String rule=text(receipt,"ruleId");Set<String> refs=new HashSet<>();receipt.get("inputRefs").forEach(r->refs.add(r.textValue()));
            if("SATELLITE_BUILD".equals(rule)){
                if(!refs.containsAll(required))fail();
                for(String ref:refs)if(!required.contains(ref)&&!optional.contains(ref))fail();
                for(String ref:optional)if(verifiedSources.contains(ref)!=refs.contains(ref))fail();
                boolean fxStatus=false;for(JsonNode v:receipt.get("values"))if("fxStatusVerified".equals(text(v,"name"))){if(!"BOOLEAN".equals(text(v,"unit")))fail();fxStatus=true;}
                if(!fxStatus)fail();
            }else if("FX_CONVERSION".equals(rule)){
                if(!refs.equals(Set.of("fx","policy")))fail();
                BigDecimal spot=null,twdAmount=null,twdFee=null;
                for(JsonNode v:receipt.get("values")){
                    if(v.get("value").isNull())continue;BigDecimal value=new BigDecimal(v.get("value").textValue());
                    switch(text(v,"name")){case "spotSellingRate"->spot=value;case "twdAmount"->twdAmount=value;case "twdFee"->twdFee=value;default->{}}
                }
                if(spot==null || twdAmount==null || twdFee==null || spot.signum()<=0)fail();
                BigDecimal units=new BigDecimal(leg.get("units").textValue()),limit=new BigDecimal(leg.get("limitPrice").textValue());
                if(twdAmount.compareTo(units.multiply(limit).multiply(spot))!=0 || twdFee.compareTo(new BigDecimal("3").multiply(spot))!=0)fail();
            }
        }
    }
    private static boolean positive(JsonNode n){return n!=null && !n.isNull() && new BigDecimal(n.textValue()).signum()>0;}
    private static boolean validHighPolicy(JsonNode c){
        try{
            if(!c.isObject()||!c.path("enabled").isBoolean()||!c.path("enabled").asBoolean()||!"D-235".equals(c.path("decision").asText())||!"2881".equals(c.path("symbol").asText())||!"台股".equals(c.path("market").asText())||c.path("minimum_week52_position_inclusive").isBoolean()||new BigDecimal(c.path("minimum_week52_position_inclusive").asText()).compareTo(new BigDecimal("0.95"))!=0)return false;
            java.time.LocalDate.parse(c.path("effective_date").asText());
            for(String flag:Set.of("require_distribution_adjusted","first_batch_disallow_small_clear","pause_outside_high_zone","requires_llm_adoption"))if(!c.path(flag).isBoolean()||!c.path(flag).asBoolean())return false;
            if(!c.path("radar_actions").isArray())return false;Set<String> actions=new HashSet<>();for(JsonNode a:c.path("radar_actions"))if(!a.isTextual()||!actions.add(a.asText()))return false;
            return actions.equals(Set.of("HOLD","HOLD_CAUTION","EXIT_CANDIDATE","REDUCE_CANDIDATE"));
        }catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean highRouteExpected(JsonNode model,JsonNode sources,String tradingDate){
        JsonNode cfg=model.path("execution_policy").path("named_high_zone_exit_d235");boolean valid=validHighPolicy(cfg);
        if("D-235".equals(model.path("decision").asText())&&!valid)return true;
        if(!valid||java.time.LocalDate.parse(tradingDate).isBefore(java.time.LocalDate.parse(cfg.path("effective_date").asText())))return false;
        JsonNode stock=sources.path("market:台股:2881").path("radar").path("stock");
        if(Set.of("HOLD","HOLD_CAUTION").contains(stock.path("action").asText()))return true;
        try{BigDecimal position=new BigDecimal(stock.path("week52Position").asText());return stock.path("distributionAdjusted").isBoolean()&&stock.path("distributionAdjusted").asBoolean()&&position.compareTo(new BigDecimal("0.95"))>=0&&position.compareTo(BigDecimal.ONE)<=0;}catch(IllegalArgumentException invalid){return false;}
    }
    private static BigDecimal exactValue(JsonNode leg,String rule,String name,String unit){
        JsonNode match=null;int count=0;
        for(JsonNode r:leg.path("receipts"))if(rule.equals(r.path("ruleId").asText()))for(JsonNode value:r.path("values"))if(name.equals(value.path("name").asText())){count++;match=value;}
        if(count!=1||!"2881".equals(match.path("subject").asText())||!unit.equals(match.path("unit").asText())||!"EXACT".equals(match.path("quality").asText())||match.path("value").isNull())fail();
        return new BigDecimal(match.path("value").asText());
    }
    private static void highZoneFacts(JsonNode leg,JsonNode sources,JsonNode model,String tradingDate){
        JsonNode stock=sources.path("market:台股:2881").path("radar").path("stock"),cfg=model.path("execution_policy").path("named_high_zone_exit_d235");
        if(!validHighPolicy(cfg))fail();
        BigDecimal position=exactValue(leg,"D235_HIGH_ZONE_PATH","d235Week52Position","RATIO"),threshold=exactValue(leg,"D235_HIGH_ZONE_PATH","d235Threshold","RATIO");
        if(!stock.path("distributionAdjusted").isBoolean()||!stock.path("distributionAdjusted").asBoolean()||!stock.path("week52Position").isNumber()&&!stock.path("week52Position").isTextual()||position.compareTo(new BigDecimal(stock.path("week52Position").asText()))!=0||position.compareTo(new BigDecimal("0.95"))<0||position.compareTo(BigDecimal.ONE)>0||threshold.compareTo(new BigDecimal("0.95"))!=0||exactValue(leg,"D235_HIGH_ZONE_PATH","d235DistributionAdjusted","BOOLEAN").compareTo(BigDecimal.ONE)!=0)fail();
        if(!"D-235".equals(cfg.path("decision").asText())||!cfg.path("enabled").isBoolean()||!cfg.path("enabled").asBoolean()||!"2881".equals(cfg.path("symbol").asText())||!"台股".equals(cfg.path("market").asText())||new BigDecimal(cfg.path("minimum_week52_position_inclusive").asText()).compareTo(threshold)!=0)fail();
        if(java.time.LocalDate.parse(tradingDate).isBefore(java.time.LocalDate.parse(cfg.path("effective_date").asText())))fail();
        for(String flag:Set.of("require_distribution_adjusted","first_batch_disallow_small_clear","pause_outside_high_zone","requires_llm_adoption"))if(!cfg.path(flag).isBoolean()||!cfg.path(flag).asBoolean())fail();
        if(!Set.of("HOLD","HOLD_CAUTION","EXIT_CANDIDATE","REDUCE_CANDIDATE").contains(stock.path("action").asText()))fail();
        long baseline=exactValue(leg,"D152_BATCH","baselineShares","SHARES").longValueExact(),batches=exactValue(leg,"D152_BATCH","confirmedBatches","COUNT").longValueExact();
        if(baseline<=0||batches<0||exactValue(leg,"D152_BATCH","firstBatchSmallClearAllowed","BOOLEAN").signum()!=0)fail();
        JsonNode state=sources.path("topup-state"),plan=state.path("rotationExit").path("2881");String broker=state.path("executionAccounts").path("CORE_D226").path("brokerId").asText();
        if(!plan.path("verified").isBoolean()||!plan.path("verified").asBoolean()||!plan.path("baselineSnapshot").path("stocks").isArray()||!plan.path("fills").path("rows").isArray()||!plan.path("fills").path("verified").asBoolean())fail();
        long rawBaseline=0;for(JsonNode row:plan.path("baselineSnapshot").path("stocks"))if("2881".equals(row.path("stockCode").asText())&&"台股".equals(row.path("market").asText())&&broker.equals(row.path("brokerId").asText()))rawBaseline=Math.addExact(rawBaseline,new BigDecimal(row.path("shares").asText()).longValueExact());
        Set<String> rawBatches=new HashSet<>();for(JsonNode fill:plan.path("fills").path("rows"))if(fill.path("confirmed").isBoolean()&&fill.path("confirmed").asBoolean())rawBatches.add(text(fill,"batchId"));else fail();
        if(baseline!=rawBaseline||batches!=rawBatches.size())fail();
        if(batches==0){long units=new BigDecimal(leg.path("units").asText()).longValueExact(),step=baseline<3000?1:1000,ceiling=Math.max(baseline/3/step*step,step);if(units>=baseline||units>ceiling||units%step!=0)fail();}
    }
    private static boolean d215(JsonNode model){return model!=null && model.path("execution_policy").has("usd_asset_fx_preference_d215");}
    /**
     * Task 625: with a D-215 bundle every bond leg and the VT build leg carries exactly one D215_FX receipt with refs
     * {fx, policy, market:<market>:<symbol>}; status/reason pairs and value names/units are closed sets. An ACTIONABLE
     * leg's D215_FX must PASS (enforced by the generic ACTIONABLE receipt rule). No other leg, and no legacy bundle, may carry it.
     */
    private static void d215Receipt(JsonNode leg,boolean scoped,boolean d215,boolean vt,boolean active){
        JsonNode found=null;
        for(JsonNode r:leg.get("receipts"))if(D215_RULE.equals(text(r,"ruleId"))){if(found!=null)fail();found=r;}
        if(!scoped||!d215){if(found!=null)fail();return;}
        if(found==null)fail();
        String symbol=text(leg,"symbol"),market=text(leg,"market"),status=text(found,"status"),reason=text(found,"reason");
        Set<String> refs=new HashSet<>();found.get("inputRefs").forEach(r->refs.add(r.textValue()));
        if(!refs.equals(Set.of("fx","policy","market:"+market+":"+symbol)))fail();
        boolean ok=switch(status){
            case "PASS"->Set.of("D215_NO_FX_PREFERENCE_VETO","D215_FX_PREFERENCE_EXCEPTION_REVIEW").contains(reason);
            case "BLOCK"->"D215_FX_WAIT_PREFERRED".equals(reason);
            case "UNAVAILABLE"->Set.of("D215_FX_EVIDENCE_UNKNOWN","D215_FX_POLICY_UNAVAILABLE").contains(reason)||reason.matches("SOURCE_NOT_VERIFIED:[^,]+(,SOURCE_NOT_VERIFIED:[^,]+)*");
            default->false;
        };
        if(!ok||active&&!"PASS".equals(status))fail();
        String currency=vt?"USD":"TWD";Set<String> names=new HashSet<>();
        for(JsonNode v:found.get("values")){
            String name=text(v,"name"),unit=text(v,"unit");if(!names.add(name)||!symbol.equals(v.get("subject").textValue()))fail();
            String expected=switch(name){case "assetHigh52w","d215WaitCeilingAmount"->currency;default->D215_UNITS.get(name);};
            if(expected==null||!expected.equals(unit))fail();
            if(name.equals(vt?"d215WaitCeilingLots":"d215WaitCeilingUnits"))fail();
            if("BOOLEAN".equals(unit)&&!v.get("value").isNull()&&!Set.of("0","1").contains(v.get("value").textValue()))fail();
        }
        for(String name:Set.of("fxSpotSelling","fxRangeLow","fxRangeHigh","fxRangePosition","fxAbsoluteHigh","fxHigh","assetHigh52w","assetDrawdown52w","cheapAssetException"))if(!names.contains(name))fail();
        if(names.stream().anyMatch(n->n.startsWith("d215WaitCeiling"))&&(active||!"BLOCKED".equals(text(leg,"status"))))fail();
    }
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
