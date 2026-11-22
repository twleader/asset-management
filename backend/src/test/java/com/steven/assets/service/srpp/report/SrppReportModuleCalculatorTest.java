package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.srpp.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;
import static org.assertj.core.api.Assertions.*;

class SrppReportModuleCalculatorTest {
    static final ObjectMapper MAPPER=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static JsonNode model() throws Exception{return MAPPER.readTree(new DecisionPolicyAuthority().modelBytes());}
    @Test void originalSRPPGoldenAllMigratedPureFormulaScopes()throws Exception{
        JsonNode model=model(),golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));assertThat(golden.path("policyBundleSha256").asText()).isEqualTo(new DecisionPolicyAuthority().policy().bundleHash());assertThat(golden.path("originalCalculatorHashes").size()).isEqualTo(7);int count=0;
        for(JsonNode c:golden.path("cases")){JsonNode actual;String kind=c.path("kind").asText();LocalDate date=LocalDate.parse(c.path("date").asText("2026-09-22"));JsonNode e=model.path("execution_policy");
            if ("core_unavailable".equals(kind)) {
                assertThat(c.path("expected").path("timingPass").asBoolean()).isFalse();
                assertThatThrownBy(() -> SrppReportModuleCalculator.coreTiming(c.path("body"), c.path("state"), date, e.path("core_sell_guard_d174")))
                    .as(c.path("name").asText()).isInstanceOf(IllegalArgumentException.class);
                count++; continue;
            }
            switch(kind){
            case "topup" -> actual=SrppReportModuleCalculator.topupTier(c.path("body"),date,e.path("daily_topup_tier_policy"));
            case "strategic" -> actual=SrppReportModuleCalculator.strategicGate(c.path("body"),date,e.path("strategic_buy_high_gate_d188"));
            case "core" -> actual=SrppReportModuleCalculator.coreTiming(c.path("body"),c.path("state"),date,e.path("core_sell_guard_d174"));
            case "short" -> {JsonNode policy=e.path(c.path("policyKey").asText());ObjectNode scenario=SrppReportModuleCalculator.shortScenario(n(c.get("price")),n(c.get("support")),n(c.get("pressure")),n(c.get("shares")),policy.path("scenario_costs"));ArrayNode reasons=scenario.putArray("reasons");SrppReportModuleCalculator.shortEntryReasons(c.path("body"),scenario,policy,model).forEach(reasons::add);actual=scenario;}
            case "us" -> {ObjectNode policy=e.path("us_megacap_tactical_policy_d133").path("order_check_d186").deepCopy();policy.set("minimum_profit_pct",e.path("us_megacap_tactical_policy_d133").path("minimum_profit_space_d185").get("minimum_pct"));actual=SrppReportModuleCalculator.usTacticalCheck(c.path("item"),date,n(c.get("total")),policy);}
            case "payroll" -> actual=SrppReportModuleCalculator.payrollAmounts(c.path("row"));
            case "pace" -> actual=SrppReportModuleCalculator.paceCapacity(model.path("execution_policy_overrides").path("global_equity_build_cadence_d141"),c.path("state"),c.path("code").asText(),n(c.get("fx")),n(c.get("price")));
            case "regime" -> actual=SrppReportModuleCalculator.regimeCapacity(c.path("live"),e.path("dynamic_allocation_policy_d141"),e.path("stock_purchase_funding_policy_d150"),n(c.get("total")),n(c.get("cash")),n(c.get("bonds")),n(c.get("equity")),n(c.get("inflation")));
            default -> throw new AssertionError(kind);
        }for(var expected:c.path("expected").properties()){JsonNode value=actual.get(expected.getKey());assertThat(value).as(kind+":"+expected.getKey()).isNotNull();if(expected.getValue().isBoolean()||expected.getValue().isArray()||expected.getKey().equals("reason"))assertThat(value).isEqualTo(expected.getValue());else assertThat(n(value).subtract(n(expected.getValue())).abs()).as(kind+":"+expected.getKey()).isLessThan(new BigDecimal("0.000000000001"));}count++;}
        assertThat(count).isEqualTo(111);
    }
    @Test void formalTargetsAndRegistryArtifactUseExactCurrentAuthorityBytes()throws Exception{JsonNode model=model();JsonNode artifact=SrppJcs.parseStrict(new String(getClass().getResourceAsStream("/srpp/report/d201-policy-document.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertThat(SrppReportModuleCalculator.registryDocument(model)).isEqualTo(artifact);assertThat(SrppReportModuleCalculator.formalTargets(model).path("00865B").asText()).isEqualTo("0.025");assertThat(SrppReportModuleCalculator.targetAllocationSha256(model)).matches("[a-f0-9]{64}");assertThat(SrppPolicyDocument.parse(SrppJcs.canonicalize(artifact)).targets()).hasSize(18);}
    @Test void d227PublishesOnlyExactTaiwanThresholdObservationsIndependentlyOfD174Watch() throws Exception {
        FrozenFixture base = fixture();
        ObjectNode input = base.frozen.input().deepCopy();
        ObjectNode sources = (ObjectNode) input.path("sources");
        ObjectNode vector = base.frozen.sourceVector().deepCopy();
        ArrayNode holdings = (ArrayNode) sources.path("assets").path("snapshot").path("stocks");
        holdings.addObject().put("stockCode", "0050").put("market", "台股").put("shares", "100");
        holdings.addObject().put("stockCode", "0000").put("market", "台股").put("shares", "0");
        holdings.addObject().put("stockCode", "VOO").put("market", "美股").put("shares", "100");
        String sourceId = "market:台股:0050";
        ObjectNode body = sources.putObject(sourceId).put("symbol", "0050").put("market", "台股");
        vector.putObject(sourceId).put("sourceId", sourceId);
        ObjectNode radar = body.putObject("radar").put("ruleVersion", "fixture")
                .put("actionPolicyVersion", "EVIDENCE_GATE_V1").put("generatedAt", "2026-10-08T11:59:00");
        ObjectNode stock = radar.putObject("stock").put("stockCode", "0050").put("market", "台股")
                .put("asOfDate", "2026-10-08").put("quoteStatus", "LIVE").put("dataComplete", true)
                .put("dValue", "80.001").put("week52Position", "0.80");
        var frozen = new DecisionInputCapturePort.Frozen(base.frozen.facts(), input, vector, 1, base.frozen.assetGeneratedAt());
        ObjectNode configured = model().deepCopy();
        ObjectNode execution = (ObjectNode) configured.path("execution_policy");
        execution.putObject("bear_high_sell_d227").put("decision", "D-227").put("market", "台股")
                .put("minimum_d_exclusive", 80).put("minimum_week52_position_inclusive", new BigDecimal("0.80"));
        JsonNode module = new PureSrppReportCalculations().core(frozen, configured, NullNode.instance, "Codex");
        assertThat(module.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(module.path("scope").get(0).asText()).isEqualTo("D174_CONSUMER_ISOLATED_HIGH_WATER_AND_KD");
        assertThat(module.path("data").path("facts").isEmpty()).isTrue();
        assertThat(module.path("data").path("legs").isEmpty()).isTrue();
        assertThat(module.path("receipts").size()).isEqualTo(1);
        JsonNode receipt = module.path("receipts").get(0);
        assertThat(receipt.path("ruleId").asText()).isEqualTo("D227:0050");
        assertThat(receipt.path("status").asText()).isEqualTo("PASS");
        assertThat(receipt.path("reason").asText()).isEqualTo("D227_THRESHOLDS_MET_REGIME_AND_LLM_REQUIRED");
        assertThat(receipt.path("inputRefs").toString()).isEqualTo("[\"assets\",\"market:台股:0050\",\"policy\"]");
        assertThat(receipt.path("values").get(0).path("value").asText()).isEqualTo("80.001");
        assertThat(receipt.path("values").get(1).path("value").asText()).isEqualTo("0.8");
        stock.put("dValue", "80");
        assertThat(d227Receipt(base.frozen, input, vector, configured).path("status").asText()).isEqualTo("BLOCK");
        stock.put("dValue", "80.001").put("week52Position", "0.7999");
        assertThat(d227Receipt(base.frozen, input, vector, configured).path("status").asText()).isEqualTo("BLOCK");
        stock.put("week52Position", "0.8").put("stockCode", "00881");
        receipt = d227Receipt(base.frozen, input, vector, configured);
        assertThat(receipt.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(receipt.path("values").isEmpty()).isTrue();
        execution.remove("bear_high_sell_d227");
        assertThat(new PureSrppReportCalculations().core(frozen, configured, NullNode.instance, "Codex")
                .path("receipts").isEmpty()).isTrue();
    }

    private static JsonNode d227Receipt(DecisionInputCapturePort.Frozen template, ObjectNode input,
            ObjectNode vector, JsonNode configured) {
        var frozen = new DecisionInputCapturePort.Frozen(template.facts(), input, vector, 1, template.assetGeneratedAt());
        return new PureSrppReportCalculations().core(frozen, configured, NullNode.instance, "Codex").path("receipts").get(0);
    }

    @Test void d211ScopeCannotBeBypassedWithMissingOrUnknownMarket() throws Exception {
        JsonNode all=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));
        JsonNode c=java.util.stream.StreamSupport.stream(all.path("cases").spliterator(),false)
            .filter(v->"D211_high_zone_live_d_79.99".equals(v.path("name").asText())).findFirst().orElseThrow();
        JsonNode policy=model().path("execution_policy").path("core_sell_guard_d174");
        for(String identity:List.of("missing","英股")) {
            ObjectNode body=c.path("body").deepCopy();
            for(ObjectNode node:List.of(body,(ObjectNode)body.path("quote"),(ObjectNode)body.path("radar").path("stock"))) {
                if(identity.equals("missing"))node.remove("market");else node.put("market",identity);
            }
            assertThatThrownBy(()->SrppReportModuleCalculator.coreTiming(body,c.path("state"),LocalDate.parse(c.path("date").asText()),policy))
                .hasMessage("EXACT_SELECTOR_MISMATCH");
        }
        ObjectNode invalidPolicy=policy.deepCopy();invalidPolicy.remove("ordinary_equity_high_sell_d211");
        assertThatThrownBy(()->SrppReportModuleCalculator.coreTiming(c.path("body"),c.path("state"),LocalDate.parse(c.path("date").asText()),invalidPolicy))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void settledCashCapacityDoesNotRequireAReadySellerAndProtectsFloors() throws Exception {
        FrozenFixture base=fixture(); JsonNode model=model(); ObjectNode input=base.frozen.input().deepCopy();
        ObjectNode sources=(ObjectNode)input.path("sources");
        ((ArrayNode)sources.path("report-reservations").path("rows")).addObject().put("reservationId","buy-r1")
            .put("action","BUY").put("status","ACTIVE").put("currency","TWD").put("amount","100000").put("fees","100");
        ((ArrayNode)sources.path("topup-state").path("pending").path("orders")).addObject().put("orderId","buy-p1")
            .put("side","BUY").put("market","台股").put("currency","TWD").put("remainingShares","1000").put("limitPrice","100").put("remainingFees","100");
        var frozen=new DecisionInputCapturePort.Frozen(base.frozen.facts(),input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());
        JsonNode unavailableSeller=SrppReportModuleCalculator.calculateCore(frozen,model,NullNode.instance,"Codex");
        assertThat(unavailableSeller.path("status").asText()).isEqualTo("UNAVAILABLE");
        JsonNode funding=SrppReportModuleCalculator.calculateShared(frozen,base.policy,model,base.registered,MAPPER.createObjectNode()).get(2);
        BigDecimal floor=base.policy.totalFloor().multiply(DailyDecisionCalculator.inflation(base.policy,base.frozen.facts().date()));
        BigDecimal expected=new BigDecimal("5000000").subtract(new BigDecimal("200200")).subtract(floor);
        assertThat(expected).isPositive(); assertThat(n(fact(funding,"deployableCapacity","TWD").path("value"))).isEqualByComparingTo(expected);
        ((ArrayNode)sources.path("topup-state").path("pending").path("orders")).addObject().put("orderId","sell-p1")
            .put("side","SELL").put("market","台股").put("currency","TWD").put("remainingShares","100000").put("limitPrice","1000");
        var withUnfilledSeller=new DecisionInputCapturePort.Frozen(base.frozen.facts(),input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());
        JsonNode unchanged=SrppReportModuleCalculator.calculateShared(withUnfilledSeller,base.policy,model,base.registered,MAPPER.createObjectNode()).get(2);
        assertThat(fact(unchanged,"deployableCapacity","TWD")).isEqualTo(fact(funding,"deployableCapacity","TWD"));
        // A verified future receivable is excluded from deployable cash even when it is large.
        ObjectNode receiver=((ArrayNode)sources.path("assets").path("snapshot").path("deposits")).addObject();
        receiver.put("id",3).put("bankId",1).put("currency","TRANSIT_TWD").put("depositType","TRANSIT").put("amount","1000000").put("originalAmount","1000000").put("estimatedAnnualInterest","0");
        for(JsonNode n:List.of(sources.path("assets").path("snapshot"),sources.path("assets").path("liveAssets"))) {
            ((ObjectNode)n).put("totalDeposit","6000000").put(n.has("liveTotalAssets")?"liveTotalAssets":"totalAssets","21000000");
        }
        var withReceivable=new DecisionInputCapturePort.Frozen(base.frozen.facts(),input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());
        JsonNode excluded=SrppReportModuleCalculator.calculateShared(withReceivable,base.policy,model,base.registered,MAPPER.createObjectNode()).get(2);
        assertThat(n(fact(excluded,"deployableCapacity","TWD").path("value"))).isEqualByComparingTo(expected);
        assertThat(fact(excluded,"excludedReceivable","TWD").path("value").asText()).isEqualTo("1000000");
        Input old=base.frozen.facts(); Input protectedInput=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),
            BigDecimal.ZERO,old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());
        var protectedFrozen=new DecisionInputCapturePort.Frozen(protectedInput,input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());
        JsonNode blocked=SrppReportModuleCalculator.calculateShared(protectedFrozen,base.policy,model,base.registered,MAPPER.createObjectNode()).get(2);
        assertThat(fact(blocked,"deployableCapacity","TWD").path("value").asText()).isEqualTo("0");
    }
    @Test void unknownCoreStateAndCrossConsumerStateCannotBecomeComplete()throws Exception{FrozenFixture fixture=fixture();JsonNode model=model();for(JsonNode state:List.of(NullNode.instance,MAPPER.readTree("{\"verified\":true,\"ownerUserId\":7,\"consumer\":\"Claude\",\"items\":[]}"))){ObjectNode result=SrppReportModuleCalculator.calculateCore(fixture.frozen,model,state,"Codex");assertThat(result.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(result.path("missing").toString()).contains("CONSUMER_CORE_STATE_UNAVAILABLE");}}
    @Test void twelveModulePublishedShapeContainsTrueActionableBondFixtureAndUnknownSources()throws Exception{FrozenFixture fixture=fixture();JsonNode model=model();ArrayNode shared=SrppReportModuleCalculator.calculateShared(fixture.frozen,fixture.policy,model,fixture.registered,fixture.frozen.input().path("sources"));ObjectNode core=SrppReportModuleCalculator.calculateCore(fixture.frozen,model,NullNode.instance,"Codex");ArrayNode all=SrppReportModuleCalculator.compose(shared,core);assertThat(all).hasSize(12);assertThat(all.get(5).path("data").path("legs").get(0).path("lots").asInt()).isEqualTo(2);assertThat(all.get(5).path("data").path("legs").get(0).path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(all.get(3).path("status").asText()).isEqualTo("PARTIAL");assertThat(all.get(4).path("status").asText()).isEqualTo("UNAVAILABLE");SrppJcs.canonicalize(all);for(JsonNode module:all){assertThat(module.path("scope").isArray()).isTrue();for(JsonNode leg:module.path("data").path("legs"))if(leg.path("status").asText().equals("BLOCKED")){assertThat(leg.path("limitPrice").isNull()).isTrue();assertThat(leg.path("amount").isNull()).isTrue();}}}
    @Test void d222MarketAllocationUnknownIsLocalizedAndEmergencyTargetIsSeparate()throws Exception {
        FrozenFixture base=fixture();ObjectNode model=model().deepCopy();ObjectNode config=(ObjectNode)MAPPER.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../spec/fixtures/srpp_d222_reserve_document_example.json"))).path("reserveAllocation").deepCopy();
        for(String key:List.of("annual_inflation_rate","living_term_deposit_twd_2026_real","living_demand_deposit_twd_2026_real","emergency_term_deposit_twd_2026_real","emergency_etf_twd_2026_real","reference_cash_weight"))config.put(key,new BigDecimal(config.path(key).asText()));
        ((ObjectNode)model.path("execution_policy")).set("reserve_allocation_d222",config);
        JsonNode allocation=SrppReportModuleCalculator.calculateShared(base.frozen,base.policy,model,base.registered,MAPPER.createObjectNode()).get(1);
        assertThat(fact(allocation,"targetWeight","VOO").path("value").isNull()).isTrue();
        assertThat(fact(allocation,"targetWeight","0050").path("value").isNull()).isTrue();
        assertThat(fact(allocation,"targetWeight","SGOV").path("value").asText()).isEqualTo("0.02");
        assertThat(fact(allocation,"targetWeight","00865B").path("value").asText()).isEqualTo("0.025");
        assertThat(n(fact(allocation,"currentValue","emergency_00865B").path("value"))).isEqualByComparingTo(base.frozen.facts().emergencyValue());
        assertThat(n(fact(allocation,"targetValue","emergency_00865B").path("value"))).isCloseTo(new BigDecimal("800000").multiply(DailyDecisionCalculator.inflation(base.policy,base.frozen.facts().date())),within(new BigDecimal("0.000001")));
        assertThat(allocation.path("missing").toString()).contains("D222_MARKET_ALLOCATION_REQUIRED");
    }
    @Test void d222ProductionTaiwanCorePathCannotPassAnUnselectedTarget() throws Exception {
        FrozenFixture base=fixture();ObjectNode state=MAPPER.createObjectNode().put("verified",true).put("consumer","Codex").put("ownerUserId",base.frozen.input().path("ownerUserId").asLong());state.putArray("items").addObject().put("stock_code","0050").put("market","台股").putObject("state");
        JsonNode result=SrppReportModuleCalculator.calculateCore(base.frozen,model(),state,"Codex");assertThat(result.path("missing").toString()).contains("D222_MARKET_SELECTION_REQUIRED:0050");assertThat(result.path("data").path("facts").toString()).doesNotContain("\"timingPass\",\"subject\":\"0050\",\"value\":\"1\"");
    }
    @Test void productionBearFloorExcludesEmergencyAndFundsAcrossD222AndD224() throws Exception {
        for(boolean d224:List.of(false,true)){
            for(String etf:List.of("1500000","500000")){
                FrozenFixture base=fixture();Input old=base.frozen.facts();ObjectNode input=base.frozen.input().deepCopy(),sources=(ObjectNode)input.path("sources"),assets=(ObjectNode)sources.path("assets");
                ArrayNode stocks=((ObjectNode)assets.path("liveAssets")).putArray("stocks");stocks.addObject().put("stockCode","0050").put("market","台股").put("liveValue","11150000");stocks.addObject().put("stockCode","00719B").put("market","台股").put("liveValue",etf);stocks.addObject().put("stockCode","00865B").put("market","台股").put("liveValue","800000");
                ((ObjectNode)assets.path("snapshot")).putArray("funds").addObject().put("fundName","SYNTHETIC_ORDINARY_BOND_FUND").put("currentValue","4000000");
                ObjectNode classes=(ObjectNode)sources.path("classifications");classes.putObject("resolvedStockClasses").put("0050:台股","STOCK").put("00719B:台股","BOND").put("00865B:台股","BOND");classes.putObject("resolvedFundClasses").put("SYNTHETIC_ORDINARY_BOND_FUND","BOND");
                Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),new BigDecimal("20000000"),new BigDecimal("3050000"),new BigDecimal("2300000"),new BigDecimal(etf).add(new BigDecimal("800000")),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),new Evidence("PASS","SYNTHETIC_RECONCILED_EMERGENCY"),new BigDecimal("800000"));
                var frozen=new DecisionInputCapturePort.Frozen(facts,input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());ObjectNode extra=MAPPER.createObjectNode();extra.putObject("regime").put("verified",true).put("ownerUserId",input.path("ownerUserId").asLong()).put("tradingDate",old.date().toString()).put("policyBundleSha256",base.policy.bundleHash()).put("coverage","COMPLETE").put("mode","BEAR").put("rotationCompleted",true);
                ObjectNode rule=(ObjectNode)model().deepCopy();
                ObjectNode execution=(ObjectNode)rule.path("execution_policy");
                ObjectNode reserve=(ObjectNode)execution.path("reserve_allocation_d222");
                reserve.remove(List.of("bear_emergency_etf_cap_twd","bear_living_term_2026_real"));
                ((ObjectNode)execution.path("dynamic_allocation_policy_d141"))
                        .put("total_bond_floor", new BigDecimal(d224 ? "0.05" : "0.03"));
                if(d224)reserve.put("bear_emergency_etf_cap_twd",400000).put("bear_living_term_2026_real",750000);
                JsonNode projected=com.steven.assets.srpp.SrppReserveAllocation.projectedModel(rule,facts.totalAssets(),facts.date());
                JsonNode result=SrppReportModuleCalculator.regime(frozen,base.policy,projected,extra);
                assertThat(fact(result,"bondFloor",null).path("value").asText()).isEqualTo(d224?"1000000":"600000");
                assertThat(fact(result,"bearBondCapacity",null).path("value").asText())
                        .isEqualTo(etf.equals("1500000")?(d224?"500000":"900000"):"0");
                if(d224){
                    assertThat(fact(result,"deployableLiving",null).path("value").isNull()).isTrue();
                    assertThat(fact(result,"ladderAmount","BEAR").path("value").isNull()).isTrue();
                    assertThat(result.path("missing").toString()).contains("D224_BEAR_RESERVE_USAGE_AND_COMMITMENTS_UNAVAILABLE");
                }
            }
        }
    }
    @Test void lowerTargetBandAndBrokerSubaccountUnknownRemainLocalized() throws Exception {
        FrozenFixture fixture = fixture();
        JsonNode model = model();
        ArrayNode shared = SrppReportModuleCalculator.calculateShared(fixture.frozen, fixture.policy, model,
                fixture.registered, MAPPER.createObjectNode());
        JsonNode allocation = shared.get(1);
        BigDecimal total = fixture.frozen.facts().totalAssets();
        BigDecimal protectedWeight = fixture.policy.totalFloor()
                .multiply(DailyDecisionCalculator.inflation(fixture.policy, fixture.frozen.facts().date()))
                .divide(total, MathContext.DECIMAL128);
        JsonNode dynamic = model.path("execution_policy").path("dynamic_allocation_policy_d141");
        BigDecimal stockTarget = com.steven.assets.srpp.SrppReserveAllocation.enabled(model)
                ? com.steven.assets.srpp.SrppReserveAllocation.project(model, total, fixture.frozen.facts().date()).equityTarget()
                : n(dynamic.path("normal_equity_target"));
        BigDecimal bondTarget = n(dynamic.path("bond_allocation_band_d143").path("strategic_target"));
        BigDecimal expected = bondTarget.min(BigDecimal.ONE.subtract(protectedWeight).subtract(stockTarget))
                .max(BigDecimal.ZERO);
        assertThat(n(fact(allocation, "bondTarget", null).path("value"))).isEqualByComparingTo(expected);
    }
    @Test void tamperedStatePolicyAndIncompleteSGOVCannotPass()throws Exception{JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));JsonNode c=java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false).filter(v->v.path("kind").asText().equals("core")).findFirst().orElseThrow();ObjectNode state=c.path("state").deepCopy();state.put("policy_sha256","0".repeat(64));assertThatThrownBy(()->SrppReportModuleCalculator.coreTiming(c.path("body"),state,LocalDate.parse(c.path("date").asText()),model().path("execution_policy").path("core_sell_guard_d174"))).hasMessageContaining("WATCH_STATE_UNVERIFIED");}
    @Test void sameSymbolInDifferentMarketNeverInflatesFormalTaiwanTarget()throws Exception{
        FrozenFixture base=fixture();ObjectNode input=base.frozen.input().deepCopy();ObjectNode a=(ObjectNode)input.path("sources").path("assets"),snapshot=(ObjectNode)a.path("snapshot"),live=(ObjectNode)a.path("liveAssets");ObjectNode h=((ArrayNode)snapshot.path("stocks")).addObject();h.put("id",2).put("stockCode","0050").put("market","美股").put("shares","1").put("currentValue","100").put("estimatedDividend","0");ObjectNode v=((ArrayNode)live.path("stocks")).addObject();v.put("holdingId",2).put("stockCode","0050").put("market","美股").put("shares","1").put("liveValue","100");snapshot.put("totalStockValue","15000100").put("totalAssets","20000100");live.put("liveStockValue","15000100").put("liveTotalAssets","20000100");Input old=base.frozen.facts();Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),new BigDecimal("20000100"),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());var frozen=new DecisionInputCapturePort.Frozen(facts,input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());JsonNode allocation=SrppReportModuleCalculator.calculateShared(frozen,base.policy,model(),base.registered,MAPPER.createObjectNode()).get(1);assertThat(fact(allocation,"currentValue","0050").path("value").asText()).isEqualTo("15000000");assertThat(allocation.path("missing").toString()).contains("UNMAPPED_FORMAL_TARGET:美股:0050");
    }
    @Test void optionalTradabilityReceiptConservativelyTightensPriceWithoutDroppingQuantity()throws Exception{
        FrozenFixture base=fixture();Input old=base.frozen.facts();Map<String,Symbol>symbols=new TreeMap<>(old.symbols());Symbol v=symbols.get("00865B");symbols.put(v.code(),new Symbol(v.code(),v.quote(),v.book(),v.closes(),v.distributions(),v.distributionVerified(),v.currentValue(),v.position(),v.pending(),v.radar(),v.valuation(),new Evidence("UNAVAILABLE","TRADABILITY_SOURCE_UNAVAILABLE"),v.mediumAction(),v.mediumCandidateAction(),v.intraday()));Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),symbols,old.budget(),old.subaccounts(),old.emergencyValue());var frozen=new DecisionInputCapturePort.Frozen(facts,base.frozen.input(),base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());JsonNode bond=SrppReportModuleCalculator.calculateShared(frozen,base.policy,model(),base.registered,base.frozen.input().path("sources")).get(5),leg=bond.path("data").path("legs").get(0);assertThat(leg.path("lots").asInt()).isEqualTo(2);assertThat(leg.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(leg.path("limitPrice").asText()).isEqualTo("9.81");assertThat(leg.path("receipts").toString()).contains("TRADABILITY_SOURCE_UNAVAILABLE","NOT_EVALUATED","HORIZON_NOT_APPLICABLE","SHORT_ONLY");for(JsonNode receipt:leg.path("receipts"))if(!"D215_FX".equals(receipt.path("ruleId").asText()))for(JsonNode value:receipt.path("values"))assertThat(value.path("value").isNull()).isFalse();
    }
    @Test void reportQuoteFreshnessUsesExactSharesAndKeepsUnknownOrFractionalVolumesOnOrdinaryLimit()throws Exception{
        FrozenFixture fixture=fixture();JsonNode model=model();ObjectNode body=MAPPER.createObjectNode();body.put("symbol","00713").put("market","台股").put("volumeUnit","SHARES");ObjectNode q=body.putObject("quote");q.put("stockCode","00713").put("market","台股").put("tradingDate",fixture.frozen.facts().date().toString()).put("quoteStatus","LIVE").put("closed",false).put("source","FUBON").put("price","20").put("previousClose","20");for(long shares:List.of(1000000L,1001000L,1500000L,1501000L,1000001L)){int maximum=shares<=1500000&&shares%1000==0?shares<=1000000?20:15:5;for(int excess:List.of(0,1)){q.put("volume",shares).put("updatedAt",fixture.frozen.facts().capturedAt().minusSeconds(maximum*60L+excess).toString());if(excess==0)assertThat(SrppReportModuleCalculator.quoteFreshness(body,fixture.frozen,model,false)).isNotBlank();else assertThatThrownBy(()->SrppReportModuleCalculator.quoteFreshness(body,fixture.frozen,model,false)).hasMessage("LIVE_QUOTE_STALE");}}q.putNull("volume").put("updatedAt",fixture.frozen.facts().capturedAt().minusSeconds(301).toString());assertThatThrownBy(()->SrppReportModuleCalculator.quoteFreshness(body,fixture.frozen,model,false)).hasMessage("LIVE_QUOTE_STALE");
    }
    @Test void sgovMissingOneExDateCloseCannotRaiseItsAverage()throws Exception{
        JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));JsonNode c=java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false).filter(v->v.path("body").path("symbol").asText().equals("SGOV")).findFirst().orElseThrow();ObjectNode body=c.path("body").deepCopy();String day=body.path("dividends").get(0).path("exDividendDate").asText();for(JsonNode row:body.path("daily"))if(row.path("tradingDate").asText().equals(day))((ObjectNode)row).putNull("closePrice");assertThatThrownBy(()->SrppReportModuleCalculator.strategicGate(body,LocalDate.parse(c.path("date").asText()),model().path("execution_policy").path("strategic_buy_high_gate_d188"))).hasMessage("EX_DIVIDEND_HISTORY_INSUFFICIENT");
    }
    static JsonNode fact(JsonNode module,String name,String subject){for(JsonNode n:module.path("data").path("facts"))if(name.equals(n.path("name").asText())&&Objects.equals(subject,n.path("subject").isNull()?null:n.path("subject").asText()))return n;throw new AssertionError(name);}
    static BigDecimal n(JsonNode value){return new BigDecimal(value.asText());}
    @Test void vooAndVtRequireBothNinetyPercentPositionAndOneOverheatSignal() throws Exception {
        ObjectNode policy=model().path("execution_policy").path("strategic_buy_high_gate_d188").deepCopy();policy.putArray("overheat_required_symbols").add("VOO").add("VT");
        for(String code:List.of("VOO","VT")){
            for(String price:List.of("108","109","110"))assertThat(SrppReportModuleCalculator.strategicGate(strategicBody(code,price,null),LocalDate.parse("2026-09-22"),policy).path("eligible").asBoolean()).isTrue();
            for(String signal:List.of("KD","rsi5","bias10","wr9")){
                assertThat(SrppReportModuleCalculator.strategicGate(strategicBody(code,"108",signal),LocalDate.parse("2026-09-22"),policy).path("reason").asText()).isEqualTo("HIGH_ZONE_OVERHEAT_SIGNAL");
                for(String price:List.of("106","107.999"))assertThat(SrppReportModuleCalculator.strategicGate(strategicBody(code,price,signal),LocalDate.parse("2026-09-22"),policy).path("eligible").asBoolean()).isTrue();
            }
        }
        for(String code:List.of("009826","00713","0056","00878","00919")){
            assertThat(SrppReportModuleCalculator.strategicGate(strategicBody(code,"108",null),LocalDate.parse("2026-09-22"),policy).path("reason").asText()).isEqualTo("HIGH_POINT_52W_POSITION");
            assertThat(SrppReportModuleCalculator.strategicGate(strategicBody(code,"106","rsi5"),LocalDate.parse("2026-09-22"),policy).path("reason").asText()).isEqualTo("HIGH_ZONE_OVERHEAT_SIGNAL");
        }
    }
    static ObjectNode strategicBody(String code,String price,String signal) throws Exception {
        JsonNode golden=MAPPER.readTree(SrppReportModuleCalculatorTest.class.getResourceAsStream("/srpp/report-same-input-golden.json"));ObjectNode body=java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false).filter(c->c.path("kind").asText().equals("strategic")&&c.path("body").path("symbol").asText().equals("00713")).findFirst().orElseThrow().path("body").deepCopy();String market=Set.of("VOO","VT").contains(code)?"美股":"台股";body.put("symbol",code).put("market",market);body.withObject("quote").put("stockCode",code).put("market",market).put("price",price);for(JsonNode row:body.path("dividends"))((ObjectNode)row).put("stockCode",code).put("market",market);
        for(JsonNode row:body.path("indicators")){ObjectNode ind=(ObjectNode)row;ind.put("k",50).put("d",50).put("rsi5",50).put("bias10",0).put("wr9",50);if("KD".equals(signal))ind.put("k",91).put("d",81);else if("rsi5".equals(signal))ind.put("rsi5",80);else if("bias10".equals(signal))ind.put("bias10",8);else if("wr9".equals(signal))ind.put("wr9",20);}
        return body;
    }
    @Test void d205CorePullbackBoundaryIsOnePointFivePercent() throws Exception {
        JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));JsonNode c=java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false).filter(row->row.path("kind").asText().equals("core")&&row.path("expected").path("timingPass").asBoolean()).findFirst().orElseThrow();JsonNode policy=model().path("execution_policy").path("core_sell_guard_d174");assertThat(n(policy.get("pullback_fraction"))).isEqualByComparingTo("0.015");ObjectNode body=c.path("body").deepCopy();BigDecimal high=n(c.path("expected").path("highWater"));((ObjectNode)body.path("book").path("levels").get(0)).put("bidPrice",high.multiply(new BigDecimal("0.985")).toPlainString());assertThat(SrppReportModuleCalculator.coreTiming(body,c.path("state"),LocalDate.parse(c.path("date").asText()),policy).path("timingPass").asBoolean()).isTrue();((ObjectNode)body.path("book").path("levels").get(0)).put("bidPrice",high.multiply(new BigDecimal("0.985001")).toPlainString());assertThat(SrppReportModuleCalculator.coreTiming(body,c.path("state"),LocalDate.parse(c.path("date").asText()),policy).path("reason").asText()).isEqualTo("HIGH_WATER_PULLBACK_NOT_MET");
    }
    @Test void d208RetainsStableAndPreExecutionGateWatchIdentityWithoutAcceptingUnknownPolicies() throws Exception {
        JsonNode c = d209Case("high_zone_k_below_80_original_gates_pass");
        ObjectNode policy = model().path("execution_policy").path("core_sell_guard_d174").deepCopy();
        ObjectNode stable = MAPPER.createObjectNode();
        for (String key : List.of("decision", "scope", "arming", "non_distributing_symbols")) stable.set(key, policy.get(key));
        ObjectNode priorD210 = policy.deepCopy(); priorD210.remove("ordinary_equity_high_sell_d211");
        ObjectNode priorD208 = priorD210.deepCopy(); priorD208.remove("high_zone_reversal_d209");
        ObjectNode prior = priorD208.deepCopy(); prior.remove("low_point_sell_protection_d208");
        for (JsonNode hashPolicy : List.of(stable, priorD210, priorD208, prior)) {
            ObjectNode state = c.path("state").deepCopy();
            state.put("policy_sha256", com.steven.assets.service.srpp.us.UsPythonJson.hash(hashPolicy));
            ObjectNode before = state.deepCopy();
            JsonNode actual = SrppReportModuleCalculator.coreTiming(c.path("body"), state, LocalDate.parse(c.path("date").asText()), policy);
            assertThat(actual.path("timingPass").asBoolean()).isTrue();
            assertThat(actual.path("highWater").asText()).isEqualTo("100");
            assertThat(state).isEqualTo(before);
            ObjectNode tampered = policy.deepCopy(); tampered.put("arming", "UNVERIFIED_REPLACEMENT");
            assertThatThrownBy(() -> SrppReportModuleCalculator.coreTiming(c.path("body"), state, LocalDate.parse(c.path("date").asText()), tampered))
                .hasMessageContaining("WATCH_STATE_UNVERIFIED");
        }
        ObjectNode neverPublished = policy.deepCopy(); neverPublished.remove("low_point_sell_protection_d208");
        ObjectNode state = c.path("state").deepCopy();
        state.put("policy_sha256", com.steven.assets.service.srpp.us.UsPythonJson.hash(neverPublished));
        assertThatThrownBy(() -> SrppReportModuleCalculator.coreTiming(c.path("body"), state, LocalDate.parse(c.path("date").asText()), policy))
            .hasMessageContaining("WATCH_STATE_UNVERIFIED");
    }

    @Test void d208PublishedModuleCannotTurnLowOrMissingEvidenceIntoPass() throws Exception {
        JsonNode c = d208Case("live_k_50");
        ObjectNode module = d208Module(c.path("body"), c.path("state"));
        assertThat(module.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(module.path("scope")).isEqualTo(MAPPER.readTree("[\"D174_CONSUMER_ISOLATED_HIGH_WATER_AND_KD\"]"));
        assertThat(fact(module, "timingPass", "00919").path("value").asText()).isEqualTo("0");
        assertThat(module.path("receipts").toString()).contains("D208_LOW_POINT_SELL_BLOCKED");
        // Explicit opt-in export for the original Python consumer's field-binding
        // regression. This synthetic module is not an actual API/adoption receipt.
        String exportDirectory = System.getProperty("srpp.core.scope.exportDir");
        if (exportDirectory != null) {
            java.nio.file.Path directory = java.nio.file.Path.of(exportDirectory);
            assertThat(directory.isAbsolute()).isTrue();
            java.nio.file.Files.createDirectories(directory);
            ObjectNode fixture = MAPPER.createObjectNode();
            fixture.put("testScope", "SYNTHETIC_OFFLINE_FIELD_ADOPTION_CONTRACT_ONLY");
            fixture.put("policyBundleSha256", new DecisionPolicyAuthority().policy().bundleHash());
            fixture.set("module", module);
            java.nio.file.Files.writeString(directory.resolve("core-scope-consumer.json"), MAPPER.writeValueAsString(fixture));
        }
        ObjectNode missing = c.path("body").deepCopy();
        ((ObjectNode) missing.path("radar").path("stock").path("extendedIndicators")).remove("rsi5");
        JsonNode unavailable = d208Module(missing, c.path("state"));
        assertThat(unavailable.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.path("missing").toString()).contains("D208_LOW_POINT_EVIDENCE_UNVERIFIED:00919");
        assertThat(unavailable.path("data").path("facts").isEmpty()).isTrue();
        assertThat(unavailable.path("receipts").isEmpty()).isTrue();
        for (String key : List.of("timingState", "counterTrendState")) {
            for (JsonNode malformed : List.of(MAPPER.createObjectNode(), MAPPER.createArrayNode())) {
                ObjectNode malformedBody = d208Case("above_midpoint_original_gates_pass").path("body").deepCopy();
                ((ObjectNode)malformedBody.path("radar").path("stock")).set(key, malformed);
                JsonNode invalid = d208Module(malformedBody, c.path("state"));
                assertThat(invalid.path("status").asText()).isEqualTo("UNAVAILABLE");
                assertThat(invalid.path("missing").toString()).contains("D208_LOW_POINT_EVIDENCE_UNVERIFIED:00919");
                assertThat(invalid.path("data").path("facts").isEmpty()).isTrue();
                assertThat(invalid.path("receipts").isEmpty()).isTrue();
            }
        }
    }

    @Test void d208InvalidFieldsAndPolicyCannotBeReplacedWithZeroOrAnEmergencyFlag() throws Exception {
        JsonNode c = d208Case("above_midpoint_original_gates_pass");
        JsonNode policy = model().path("execution_policy").path("core_sell_guard_d174");
        for (JsonNode value : List.of(NullNode.instance, BooleanNode.TRUE, TextNode.valueOf("NaN"), TextNode.valueOf("Infinity"))) {
            ObjectNode body = c.path("body").deepCopy();
            ((ObjectNode)body.path("radar").path("stock")).set("kValue", value);
            assertThatThrownBy(() -> SrppReportModuleCalculator.coreTiming(body, c.path("state"), LocalDate.parse(c.path("date").asText()), policy))
                .isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode body = d208Case("live_k_50").path("body").deepCopy();
        body.put("mandatoryRiskExit", true).put("bypassLowPointGuard", true);
        assertThat(SrppReportModuleCalculator.coreTiming(body, c.path("state"), LocalDate.parse(c.path("date").asText()), policy).path("timingPass").asBoolean()).isFalse();
    }

    private JsonNode d208Case(String suffix) throws Exception {
        JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));
        return java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false)
            .filter(value -> ("D208_" + suffix).equals(value.path("name").asText())).findFirst().orElseThrow();
    }

    private JsonNode d209Case(String suffix) throws Exception {
        JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));
        return java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false)
            .filter(value -> ("D209_" + suffix).equals(value.path("name").asText())).findFirst().orElseThrow();
    }

    @Test void d209InclusiveHighZoneAllowsKBelow80ButStillRequiresOriginalReversal() throws Exception {
        JsonNode policy=model().path("execution_policy").path("core_sell_guard_d174");
        for (String suffix : List.of("high_zone_k_below_80_original_gates_pass", "completed_d_80", "live_d_80", "both_d_exactly_80")) {
            JsonNode c=d209Case(suffix);
            assertThat(SrppReportModuleCalculator.coreTiming(c.path("body"),c.path("state"),LocalDate.parse(c.path("date").asText()),policy).path("timingPass").asBoolean())
                .as(suffix).isTrue();
        }
        for (String suffix : List.of("completed_d_79.99", "live_d_79.99")) {
            JsonNode c=d209Case(suffix);
            assertThat(SrppReportModuleCalculator.coreTiming(c.path("body"),c.path("state"),LocalDate.parse(c.path("date").asText()),policy).path("reason").asText())
                .as(suffix).isEqualTo("D209_HIGH_ZONE_SELL_BLOCKED");
        }
        for (String suffix : List.of("live_k_81_not_below_d", "live_k_82_not_below_d", "completed_k_equal_d", "completed_k_rising")) {
            JsonNode c=d209Case(suffix);
            assertThat(SrppReportModuleCalculator.coreTiming(c.path("body"),c.path("state"),LocalDate.parse(c.path("date").asText()),policy).path("timingPass").asBoolean())
                .as(suffix).isFalse();
        }
    }

    @Test void d209MissingPolicyFailsClosedWhileRetainedWatchAndD208StayIntact() throws Exception {
        JsonNode c=d209Case("high_zone_k_below_80_original_gates_pass");
        ObjectNode policy=model().path("execution_policy").path("core_sell_guard_d174").deepCopy(),stable=MAPPER.createObjectNode();
        for (String key : List.of("decision","scope","arming","non_distributing_symbols")) stable.set(key,policy.get(key));
        ObjectNode state=c.path("state").deepCopy();state.put("policy_sha256",com.steven.assets.service.srpp.us.UsPythonJson.hash(stable));
        ObjectNode before=state.deepCopy();JsonNode lowPoint=policy.path("low_point_sell_protection_d208").deepCopy();
        policy.remove("high_zone_reversal_d209");
        assertThatThrownBy(() -> SrppReportModuleCalculator.coreTiming(c.path("body"),state,LocalDate.parse(c.path("date").asText()),policy))
            .hasMessageContaining("D209_HIGH_ZONE_EVIDENCE_UNVERIFIED");
        assertThat(state).isEqualTo(before);
        assertThat(policy.path("low_point_sell_protection_d208")).isEqualTo(lowPoint);
    }

    private ObjectNode d208Module(JsonNode body, JsonNode state) throws Exception {
        FrozenFixture base = fixture(); Input old = base.frozen.facts(); ObjectNode input = base.frozen.input();
        ((ObjectNode)input.path("sources")).set("market:台股:00919", body);
        ((ArrayNode)input.path("sources").path("assets").path("liveAssets").path("stocks")).addObject()
            .put("stockCode", "00919").put("market", "台股").put("liveValue", "50000");
        LocalDate date=LocalDate.parse(body.path("quote").path("tradingDate").asText());
        Instant at=date.atTime(9,40).atZone(ZoneId.of("Asia/Taipei")).toInstant();
        Input facts=new Input(date,"09:05",at,old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());
        var frozen=new DecisionInputCapturePort.Frozen(facts,input,base.frozen.sourceVector(),1,at.toString());
        ObjectNode wrapper=MAPPER.createObjectNode(); wrapper.put("verified",true).put("ownerUserId",7).put("consumer","Codex");
        ObjectNode item=wrapper.putArray("items").addObject(); item.put("stock_code","00919").put("market","台股").set("state",state);
        return SrppReportModuleCalculator.calculateCore(frozen,model(),wrapper,"Codex");
    }

    // ---- Task 624 D-152 pooled post-buy headroom (FUNDING_CAPACITY) ----
    @Test void pooledHeadroomMatchesOriginalPythonGolden() throws Exception {
        JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/pooled-post-buy-headroom-golden.json"));
        assertThat(golden.path("schema").asText()).isEqualTo("D152_POOLED_HEADROOM_GOLDEN_V1");
        assertThat(golden.path("cases").size()).isGreaterThanOrEqualTo(15);
        for(JsonNode c:golden.path("cases")){
            String name=c.path("name").asText();LocalDate date=LocalDate.parse(c.path("tradingDate").asText());
            BigDecimal factor=DailyDecisionCalculator.inflation(new BigDecimal("0.0225"),LocalDate.parse("2026-08-31"),date);
            assertThat(factor).as(name).isEqualByComparingTo(new BigDecimal(c.path("inflationFactor").asText()));
            BigDecimal termBase=new BigDecimal(c.path("floors").path("termBase").asText()),totalBase=new BigDecimal(c.path("floors").path("totalBase").asText());
            Map<String,SrppPooledFunding.Result> results=new HashMap<>();
            for(String currency:List.of("TWD","USD")){
                JsonNode e=c.path("expected").path(currency);
                SrppPooledFunding.Result r=SrppPooledFunding.evaluate(c.path("assets"),date,currency,termBase,totalBase,factor);results.put(currency,r);
                assertThat(r.currency()).isEqualTo(currency);
                assertThat(r.status()).as(name+":"+currency).isEqualTo(e.path("status").asText());
                assertThat(r.reason()).as(name+":"+currency).isEqualTo(e.path("reason").asText());
                Map<String,BigDecimal> actual=new LinkedHashMap<>();actual.put("settled",r.settled());actual.put("knownPayables",r.knownPayables());actual.put("term",r.term());actual.put("termFloor",r.termFloor());actual.put("totalFloor",r.totalFloor());actual.put("headroom",r.headroom());
                actual.forEach((key,value)->{JsonNode want=e.path(key);if(want.isNull())assertThat(value).as(name+":"+currency+":"+key).isNull();else assertThat(value).as(name+":"+currency+":"+key).isNotNull().isEqualByComparingTo(new BigDecimal(want.asText()));});
            }
            assertThat(c.path("probes").size()).as(name).isGreaterThan(0);
            for(JsonNode probe:c.path("probes")){
                BigDecimal headroom=results.get(probe.path("currency").asText()).headroom(),cash=new BigDecimal(probe.path("cashRequired").asText());
                assertThat(headroom!=null&&cash.compareTo(headroom)<=0).as(name+":"+probe).isEqualTo("PASS".equals(probe.path("referenceStatus").asText()));
            }
        }
        assertThatThrownBy(()->SrppPooledFunding.evaluate(MAPPER.createObjectNode(),LocalDate.parse("2026-11-16"),"EUR",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE)).hasMessage("FUNDING_CURRENCY_UNSUPPORTED");
    }
    static DecisionInputCapturePort.Frozen pooledFrame(FrozenFixture base,String termAmount,String demandAmount,boolean sourcesAvailable,boolean reservation){return pooledFrame(base,termAmount,demandAmount,sourcesAvailable,reservation,null);}
    static DecisionInputCapturePort.Frozen pooledFrame(FrozenFixture base,String termAmount,String demandAmount,boolean sourcesAvailable,boolean reservation,String generatedAt){
        ObjectNode input=base.frozen.input().deepCopy();ObjectNode sources=(ObjectNode)input.path("sources");
        ArrayNode deposits=(ArrayNode)sources.path("assets").path("snapshot").path("deposits");
        ((ObjectNode)deposits.get(0)).put("depositType","定存").put("amount",termAmount).put("originalAmount",termAmount);
        ((ObjectNode)deposits.get(1)).put("depositType","活存").put("amount",demandAmount).put("originalAmount",demandAmount);
        if(generatedAt!=null)((ObjectNode)sources.path("assets")).put("generatedAt",generatedAt);
        if(!sourcesAvailable){sources.remove("report-reservations");sources.remove("topup-state");}
        if(reservation){((ArrayNode)sources.path("report-reservations").path("rows")).addObject().put("reservationId","buy-r1").put("action","BUY").put("status","ACTIVE").put("currency","TWD").put("amount","100000").put("fees","100");
            ((ArrayNode)sources.path("topup-state").path("pending").path("orders")).addObject().put("orderId","buy-p1").put("side","BUY").put("market","台股").put("currency","TWD").put("remainingShares","1000").put("limitPrice","100").put("remainingFees","100");}
        return new DecisionInputCapturePort.Frozen(base.frozen.facts(),input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());
    }
    static JsonNode pooledFunding(FrozenFixture base,DecisionInputCapturePort.Frozen frozen)throws Exception{return SrppReportModuleCalculator.calculateShared(frozen,base.policy,model(),base.registered,MAPPER.createObjectNode()).get(2);}
    @Test void pooledHeadroomIsIndependentOfReservationAndPendingSources() throws Exception {
        FrozenFixture base=fixture();
        JsonNode missing=pooledFunding(base,pooledFrame(base,"1000000","4000000",false,false));
        assertThat(missing.path("moduleId").asText()).isEqualTo("FUNDING_CAPACITY");
        assertThat(fact(missing,"deployableCapacity","TWD").path("value").isNull()).isTrue();
        assertThat(receipts(missing).get("CURRENCY_POOL").path("status").asText()).isEqualTo("UNAVAILABLE");
        BigDecimal floor=base.policy.totalFloor().multiply(DailyDecisionCalculator.inflation(base.policy,base.frozen.facts().date()));
        BigDecimal expected=new BigDecimal("5000000").subtract(floor);
        JsonNode twd=fact(missing,"pooledPostBuyHeadroom","TWD"),usd=fact(missing,"pooledPostBuyHeadroom","USD");
        assertThat(twd.path("quality").asText()).isEqualTo("EXACT");assertThat(twd.path("reason").isNull()).isTrue();assertThat(twd.path("unit").asText()).isEqualTo("TWD");
        assertThat(n(twd.path("value"))).isEqualByComparingTo(expected);
        assertThat(usd.path("quality").asText()).isEqualTo("EXACT");assertThat(usd.path("value").asText()).isEqualTo("0");assertThat(usd.path("unit").asText()).isEqualTo("USD");
        JsonNode receipt=receipts(missing).get("D152_POOLED:TWD");
        assertThat(receipt.path("status").asText()).isEqualTo("PASS");assertThat(receipt.path("reason").asText()).isEqualTo("ALL_BANK_SETTLED_DEPOSITS_D152");
        assertThat(receipt.path("inputRefs")).isEqualTo(MAPPER.readTree("[\"assets\",\"policy\"]"));
        List<String> names=new ArrayList<>();receipt.path("values").forEach(v->{names.add(v.path("name").asText());assertThat(v.path("subject").asText()).isEqualTo("TWD");assertThat(v.path("unit").asText()).isEqualTo("TWD");});
        assertThat(names).containsExactly("pooledSettledBalance","pooledKnownPayables","pooledTermBalance","termFloor","totalFloor","pooledPostBuyHeadroom");
        assertThat(new BigDecimal(value(receipt,"pooledPostBuyHeadroom"))).isEqualByComparingTo(expected);
        assertThat(value(receipt,"pooledKnownPayables")).isEqualTo("0");
        assertThat(receipts(missing).get("D152_POOLED:USD").path("status").asText()).isEqualTo("PASS");
        assertThat(missing.path("missing").toString()).doesNotContain("POOLED");
        JsonNode reserved=pooledFunding(base,pooledFrame(base,"1000000","4000000",true,true));
        assertThat(n(fact(reserved,"deployableCapacity","TWD").path("value"))).isEqualByComparingTo(expected.subtract(new BigDecimal("200200")));
        assertThat(fact(reserved,"pooledPostBuyHeadroom","TWD")).isEqualTo(twd);
        assertThat(fact(reserved,"pooledPostBuyHeadroom","USD")).isEqualTo(usd);
        assertThat(receipts(reserved).get("D152_POOLED:TWD")).isEqualTo(receipt);
    }
    @Test void pooledTermBelowFloorIsAVerifiedBlockNotAMissingSource() throws Exception {
        FrozenFixture base=fixture();
        JsonNode blocked=pooledFunding(base,pooledFrame(base,"700000","4300000",true,false));
        JsonNode twd=fact(blocked,"pooledPostBuyHeadroom","TWD");
        assertThat(twd.path("value").isNull()).isTrue();assertThat(twd.path("quality").asText()).isEqualTo("UNAVAILABLE");assertThat(twd.path("reason").asText()).isEqualTo("POOLED_TERM_BELOW_FLOOR");
        JsonNode receipt=receipts(blocked).get("D152_POOLED:TWD");
        assertThat(receipt.path("status").asText()).isEqualTo("BLOCK");assertThat(receipt.path("reason").asText()).isEqualTo("POOLED_TERM_BELOW_FLOOR");
        assertThat(value(receipt,"pooledPostBuyHeadroom")).isNull();assertThat(value(receipt,"pooledTermBalance")).isEqualTo("700000");
        for(JsonNode v:receipt.path("values"))if(v.path("value").isNull()){assertThat(v.path("quality").asText()).isEqualTo("UNAVAILABLE");assertThat(v.path("reason").asText()).isEqualTo("POOLED_TERM_BELOW_FLOOR");}
        assertThat(blocked.path("missing").toString()).contains("POOLED_TERM_BELOW_FLOOR:TWD");
        assertThat(blocked.path("status").asText()).isNotEqualTo("COMPLETE");
        assertThat(fact(blocked,"pooledPostBuyHeadroom","USD").path("quality").asText()).isEqualTo("EXACT");
        // A stale asset snapshot is a missing same-round source, not a block.
        DecisionInputCapturePort.Frozen frame=pooledFrame(base,"1000000","4000000",true,false,"2026-10-07T15:59:59Z");
        JsonNode stale=pooledFunding(base,frame);
        assertThat(receipts(stale).get("D152_POOLED:USD").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(fact(stale,"pooledPostBuyHeadroom","USD").path("reason").asText()).isEqualTo("POOLED_ASSETS_NOT_SAME_ROUND");
        assertThat(stale.path("missing").toString()).contains("POOLED_ASSETS_NOT_SAME_ROUND:TWD").contains("POOLED_ASSETS_NOT_SAME_ROUND:USD");
    }

    record FrozenFixture(DecisionInputCapturePort.Frozen frozen,Policy policy,SupportedPolicy registered){}
    static FrozenFixture fixture()throws Exception{Policy p=new DecisionPolicyAuthority().policy();LocalDate date=LocalDate.parse("2026-10-08");Instant at=Instant.parse("2026-10-08T04:00:00Z");Evidence pass=new Evidence("PASS","ISOLATED_OFFICIAL_EVIDENCE_FIXTURE");List<LocalDate>sessions=java.util.stream.IntStream.rangeClosed(1,20).mapToObj(i->date.minusDays(21-i)).toList();Map<String,Symbol>symbols=new TreeMap<>();ObjectNode sources=MAPPER.createObjectNode();for(String code:p.symbols()){BigDecimal price=new BigDecimal(code.equals("00865B")?"9.8":code.equals("00719B")?"9.85":"9.9");Quote q=new Quote(price,BigDecimal.TEN,100L,date,at,"FUBON","LIVE",false,BigDecimal.ZERO,date);List<Level>levels=new ArrayList<>();for(int i=0;i<5;i++)levels.add(new Level(price.subtract(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i+1))),10,price.add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i+1))),10));symbols.put(code,new Symbol(code,q,new Book("FUBON_BOOKS",date,at,at,levels,true),sessions.stream().map(d->new Close(d,BigDecimal.TEN,true)).toList(),List.of(),true,BigDecimal.ZERO,pass,pass,pass,pass,pass,"HOLD","HOLD",pass));for(String kind:List.of("quote","book","radar","daily","dividends","pending","tradability","minute"))sources.putObject(kind+":"+code).put("isolatedFixture",true);}
        ObjectNode assets=MAPPER.createObjectNode(),snapshot=assets.putObject("snapshot"),live=assets.putObject("liveAssets");snapshot.put("id",1);snapshot.put("snapshotDate",date.toString());live.put("snapshotId",1);live.put("snapshotDate",date.toString());for(ObjectNode n:List.of(snapshot,live)){n.put("totalDeposit","5000000");n.put("totalFundValue","0");}snapshot.put("totalStockValue","15000000");snapshot.put("totalAssets","20000000");live.put("liveStockValue","15000000");live.put("liveTotalAssets","20000000");ArrayNode deposits=snapshot.putArray("deposits");for(int i=0;i<2;i++){ObjectNode d=deposits.addObject();d.put("id",i+1);d.put("bankId",1);d.put("currency","TWD");d.put("depositType",i==0?"TERM":"DEMAND");d.put("amount",i==0?"1000000":"4000000");d.put("originalAmount",i==0?"1000000":"4000000");d.put("estimatedAnnualInterest","0");d.put("annualInterestRate","0");}snapshot.putArray("funds");ObjectNode holding=snapshot.putArray("stocks").addObject();holding.put("id",1);holding.put("stockCode","0050");holding.put("market","台股");holding.put("shares","1000");holding.put("currentValue","15000000");holding.put("estimatedDividend","0");ObjectNode item=live.putArray("stocks").addObject();item.put("holdingId",1);item.put("stockCode","0050");item.put("market","台股");item.put("shares","1000");item.put("liveValue","15000000");assets.put("generatedAt",at.toString());assets.put("targetPriceComplete",true);sources.set("assets",assets);sources.putObject("classifications").putObject("resolvedStockClasses").put("0050:台股","STOCK");sources.putArray("ledger");sources.putArray("reservations");for(String id:List.of("policy","calendar","subaccounts","fx","bear-window"))sources.putObject(id).put("isolatedFixture",true);ObjectNode reportReservations=sources.putObject("report-reservations");reportReservations.put("verified",true).put("ownerUserId",7).put("tradingDate",date.toString()).put("policyBundleSha256",p.bundleHash()).put("coverage","COMPLETE").putArray("rows");ObjectNode reportState=sources.putObject("topup-state");reportState.put("verified",true).put("ownerUserId",7).put("tradingDate",date.toString()).put("policyBundleSha256",p.bundleHash());reportState.putObject("pending").put("verified",true).put("coverage","COMPLETE").putArray("orders");sources.withObject("policy").put("bundleHash",p.bundleHash());ObjectNode input=MAPPER.createObjectNode();input.put("ownerUserId",7);input.set("sources",sources);
        Input facts=new Input(date,"12:00",at,sessions,new BigDecimal("20000000"),new BigDecimal("5000000"),new BigDecimal("1000000"),BigDecimal.ZERO,new BigDecimal("32"),pass,pass,symbols,new Budget(0,0,0,true,BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),pass,BigDecimal.ZERO);D215Golden.addNormal(sources,date,at);ObjectNode vector=MAPPER.createObjectNode();sources.fields().forEachRemaining(e->{ObjectNode receipt=vector.putObject(e.getKey());receipt.put("sourceId",e.getKey());receipt.put("revision","isolated-fixture");receipt.put("sha256",SrppJcs.hash(e.getValue()));receipt.put("capturedAt",at.toString());receipt.put("coverage","COMPLETE");receipt.put("validation","VERIFIED");});for(String id:List.of("fx","policy"))if(!vector.has(id))vector.putObject(id).put("sourceId",id).put("revision","isolated-fixture").put("sha256","0".repeat(64)).put("capturedAt",at.toString()).put("coverage","COMPLETE").put("validation","VERIFIED");SrppPolicyDocument doc=SrppPolicyDocument.parse(SrppJcs.canonicalize(SrppReportModuleCalculator.registryDocument(model())));String formula=model().path("execution_policy").has("high_dividend_stabilization_d218")?SrppFormulaCatalog.FORMULA_VERSION_V3:SrppFormulaCatalog.FORMULA_VERSION_V2;SupportedPolicy registered=new SupportedPolicy(p.bundleHash(),formula,SrppJcs.hash(doc.document()),SrppFormulaCatalog.formulaSetSha256(formula),doc.document(),SrppFormulaCatalog.manifest(formula),doc.targets(),at.minusSeconds(1));return new FrozenFixture(new DecisionInputCapturePort.Frozen(facts,input,vector,1,at.toString()),p,registered);
    }

    // ---- Task 623 satellite build legs (GLOBAL_EQUITY_PACE) ----
    static JsonNode satellitePackage(DecisionInputCapturePort.Frozen frozen,JsonNode model){return SrppSatelliteBuildFixtures.publish(frozen,frozen.facts().slot(),model);}
    static DecisionInputCapturePort.Frozen satelliteFrame()throws Exception{return SrppSatelliteBuildFixtures.withSatellite(SrppReportFixtures.qualified("09:05",SrppReportFixtures.AT,true),SrppReportFixtures.AT);}
    static JsonNode pace(JsonNode envelope){return SrppSatelliteBuildFixtures.module(envelope,"GLOBAL_EQUITY_PACE");}
    static Map<String,JsonNode> receipts(JsonNode node){Map<String,JsonNode> out=new HashMap<>();node.path("receipts").forEach(r->out.put(r.path("ruleId").asText(),r));return out;}
    static String value(JsonNode receipt,String name){for(JsonNode v:receipt.path("values"))if(name.equals(v.path("name").asText()))return v.path("value").isNull()?null:v.path("value").asText();throw new AssertionError(name);}
    static String satelliteFact(JsonNode module,String name,String subject){for(JsonNode v:module.path("data").path("facts"))if(name.equals(v.path("name").asText())&&subject.equals(v.path("subject").asText()))return v.path("value").isNull()?null:v.path("value").asText();throw new AssertionError(name+":"+subject);}
    @Test void legacyBundleGlobalEquityPaceIsByteIdenticalAndIgnoresNewSources()throws Exception{
        var plain=SrppReportFixtures.qualified("09:05",SrppReportFixtures.AT,true);var withSources=satelliteFrame();
        JsonNode legacy=pace(satellitePackage(plain,SrppSatelliteBuildFixtures.legacyModel())),ignored=pace(satellitePackage(withSources,SrppSatelliteBuildFixtures.legacyModel()));
        assertThat(SrppJcs.canonicalize(ignored)).isEqualTo(SrppJcs.canonicalize(legacy));
        String expected="{\"data\":{\"eventEvidenceIntegrationStatus\":\"NOT_BOUND\",\"facts\":[{\"name\":\"monthlyBaseline\",\"quality\":\"EXACT\",\"reason\":null,\"subject\":\"VOO\",\"unit\":\"TWD\",\"value\":\"900000\"},{\"name\":\"monthlyCap\",\"quality\":\"EXACT\",\"reason\":null,\"subject\":\"VOO\",\"unit\":\"TWD\",\"value\":\"1350000\"},{\"name\":\"monthlyBaseline\",\"quality\":\"EXACT\",\"reason\":null,\"subject\":\"009826\",\"unit\":\"TWD\",\"value\":\"140000\"},{\"name\":\"monthlyCap\",\"quality\":\"EXACT\",\"reason\":null,\"subject\":\"009826\",\"unit\":\"TWD\",\"value\":\"210000\"}],\"legs\":[],\"ranking\":[]},\"formulaVersion\":\"SRPP_REPORT_CALCULATIONS_V1\",\"inputRefs\":[\"fx\",\"global-pace\",\"policy\"],\"missing\":[\"OWNER_BOUND_GLOBAL_PACE_STATE_AND_FILLS_UNAVAILABLE\"],\"moduleId\":\"GLOBAL_EQUITY_PACE\",\"reasonCodes\":[\"OWNER_BOUND_GLOBAL_PACE_STATE_AND_FILLS_UNAVAILABLE\"],\"receipts\":[{\"calculatorVersion\":\"SRPP_REPORT_CALCULATIONS_V1\",\"inputRefs\":[\"fx\",\"global-pace\",\"policy\"],\"reason\":\"OWNER_BOUND_GLOBAL_PACE_STATE_AND_FILLS_UNAVAILABLE\",\"ruleId\":\"CONFIRMED_FILL_PACE_STATE_EVOLUTION\",\"scope\":null,\"status\":\"UNAVAILABLE\",\"values\":[]}],\"scope\":[\"D143_CONFIRMED_FILL_MONTH_WEEK_DAY_AND_FIXED_DCA_DEBITS\"],\"status\":\"PARTIAL\"}";
        assertThat(SrppJcs.canonicalize(legacy)).isEqualTo(expected);
    }
    @Test void enabledBundleEmitsBothSatelliteLegsWithoutTouchingVooOrDuplicatingFacts()throws Exception{
        JsonNode legacy=pace(satellitePackage(satelliteFrame(),SrppSatelliteBuildFixtures.legacyModel())),enabled=pace(satellitePackage(satelliteFrame(),SrppSatelliteBuildFixtures.enabledModel()));
        List<JsonNode> legacyVoo=new ArrayList<>(),enabledVoo=new ArrayList<>();legacy.path("data").path("facts").forEach(f->{if("VOO".equals(f.path("subject").asText()))legacyVoo.add(f);});enabled.path("data").path("facts").forEach(f->{if("VOO".equals(f.path("subject").asText()))enabledVoo.add(f);});
        assertThat(enabledVoo).isEqualTo(legacyVoo);assertThat(receipts(enabled).get("CONFIRMED_FILL_PACE_STATE_EVOLUTION")).isEqualTo(receipts(legacy).get("CONFIRMED_FILL_PACE_STATE_EVOLUTION"));
        assertThat(enabled.path("data").path("ranking").toString()).isEqualTo("[\"009826\",\"VT\"]");
        JsonNode lots=SrppSatelliteBuildFixtures.leg(enabled,"009826"),vt=SrppSatelliteBuildFixtures.leg(enabled,"VT");
        assertThat(lots.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(lots.path("strategy").asText()).isEqualTo("SATELLITE_BUILD_D143");assertThat(lots.path("lots").asInt()).isEqualTo(3);assertThat(lots.path("units").isNull()).isTrue();
        assertThat(lots.path("limitPrice").asText()).isEqualTo("10.06");assertThat(lots.path("amount").asText()).isEqualTo("30180");assertThat(lots.path("fees").asText()).isEqualTo(new BigDecimal("30180").multiply(new DecisionPolicyAuthority().policy().feeRate()).setScale(2,RoundingMode.CEILING).stripTrailingZeros().toPlainString());
        assertThat(lots.path("broker").asText()).isEqualTo("富邦證券");assertThat(lots.path("subaccount").asText()).isEqualTo("CORE_D143");assertThat(lots.path("currency").asText()).isEqualTo("TWD");assertThat(receipts(lots).keySet()).isEqualTo(Set.of("SATELLITE_BUILD","SHARED_CURRENCY_CAPACITY"));
        assertThat(vt.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(vt.path("market").asText()).isEqualTo("美股");assertThat(vt.path("currency").asText()).isEqualTo("USD");assertThat(vt.path("lots").isNull()).isTrue();assertThat(vt.path("units").asText()).isEqualTo("5");
        assertThat(vt.path("limitPrice").asText()).isEqualTo("150");assertThat(vt.path("amount").asText()).isEqualTo("750");assertThat(vt.path("fees").asText()).isEqualTo("3");assertThat(vt.path("broker").asText()).isEqualTo("國泰證券");assertThat(vt.path("subaccount").asText()).isEqualTo("CORE_D234");
        assertThat(receipts(vt).keySet()).isEqualTo(Set.of("SATELLITE_BUILD","FX_CONVERSION","D215_FX","SHARED_CURRENCY_CAPACITY"));assertThat(receipts(vt).get("D215_FX").path("reason").asText()).isEqualTo("D215_NO_FX_PREFERENCE_VETO");JsonNode fx=receipts(vt).get("FX_CONVERSION");
        assertThat(value(fx,"spotSellingRate")).isEqualTo("32.5");assertThat(value(fx,"twdAmount")).isEqualTo("24375");assertThat(value(fx,"twdFee")).isEqualTo("97.5");assertThat(fx.path("inputRefs").toString()).isEqualTo("[\"fx\",\"policy\"]");
        receipts(vt).values().forEach(r->assertThat(r.path("status").asText()).isEqualTo("PASS"));receipts(lots).values().forEach(r->assertThat(r.path("status").asText()).isEqualTo("PASS"));
        JsonNode module=receipts(enabled).get("SATELLITE_BUILD:VT");assertThat(module.path("status").asText()).isEqualTo("PASS");assertThat(module.path("inputRefs").toString()).isEqualTo("[\"assets\",\"fx\",\"market:美股:VT\",\"policy\",\"satellite-build-fills\",\"usd-twd-month-mids\"]");
        assertThat(value(module,"fxStatusVerified")).isEqualTo("1");assertThat(value(module,"fxHalved")).isEqualTo("0");assertThat(value(module,"fxMonthMaxMid")).isEqualTo("32.45");
        assertThat(receipts(enabled).get("SATELLITE_BUILD:009826").path("inputRefs").toString()).isEqualTo("[\"assets\",\"fx\",\"market:台股:009826\",\"policy\",\"satellite-build-fills\",\"usd-twd-month-mids\"]");
        assertThat(satelliteFact(enabled,"ceilingLots","009826")).isEqualTo("3");assertThat(satelliteFact(enabled,"ceilingShares","VT")).isEqualTo("5");assertThat(satelliteFact(enabled,"remainingDayLots","009826")).isEqualTo("3");assertThat(satelliteFact(enabled,"remainingWeekShares","VT")).isEqualTo("15");assertThat(satelliteFact(enabled,"minOrderShares","VT")).isEqualTo("5");
        assertThat(satelliteFact(enabled,"monthlyCap","VT")).isEqualTo("80000");assertThat(satelliteFact(enabled,"spent","009826")).isEqualTo("0");assertThat(satelliteFact(enabled,"remaining","009826")).isEqualTo("210000");
        Set<String> identities=new HashSet<>();enabled.path("data").path("facts").forEach(f->assertThat(identities.add(f.path("name").asText()+":"+f.path("subject").asText())).isTrue());
        JsonNode bond=SrppSatelliteBuildFixtures.module(satellitePackage(satelliteFrame(),SrppSatelliteBuildFixtures.enabledModel()),"BOND_D130_D195");assertThat(bond.path("data").path("legs").get(0).path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
        writeWire("enabled",satelliteFrame(),SrppSatelliteBuildFixtures.enabledModel());
    }
    @Test void unverifiedMidsAssumeHalvedAndAreNotReferencedWhileLegStaysActionable()throws Exception{
        var frame=SrppSatelliteBuildFixtures.withValidation(satelliteFrame(),SrppSatelliteBuildCapture.MIDS,"UNAVAILABLE");JsonNode enabled=pace(satellitePackage(frame,SrppSatelliteBuildFixtures.enabledModel()));
        for(String code:List.of("009826","VT")){JsonNode leg=SrppSatelliteBuildFixtures.leg(enabled,code);assertThat(leg.path("status").asText()).as(code).isEqualTo("ACTIONABLE_FOR_REPORT");JsonNode r=receipts(leg).get("SATELLITE_BUILD");assertThat(r.path("inputRefs").toString()).doesNotContain("usd-twd-month-mids");assertThat(value(r,"fxStatusVerified")).isEqualTo("0");assertThat(value(r,"fxHalved")).isEqualTo("1");assertThat(value(r,"fxMonthMaxMid")).isNull();}
        assertThat(satelliteFact(enabled,"monthlyCap","009826")).isEqualTo("105000");assertThat(satelliteFact(enabled,"monthlyCap","VT")).isEqualTo("40000");
        writeWire("mids-unavailable",frame,SrppSatelliteBuildFixtures.enabledModel());
    }
    @Test void positiveResultWithUnverifiedReferencedMarketSourceIsNeverActionable()throws Exception{
        for(String[] c:List.of(new String[]{"009826","market:台股:009826"},new String[]{"VT","market:美股:VT"})){
            var frame=SrppSatelliteBuildFixtures.withValidation(satelliteFrame(),c[1],"UNAVAILABLE");JsonNode enabled=pace(satellitePackage(frame,SrppSatelliteBuildFixtures.enabledModel()));
            JsonNode leg=SrppSatelliteBuildFixtures.leg(enabled,c[0]),module=receipts(enabled).get("SATELLITE_BUILD:"+c[0]);
            assertThat(module.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(module.path("reason").asText()).isEqualTo("SOURCE_NOT_VERIFIED:"+c[1]);
            assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");assertThat(leg.path("blockingReasons").toString()).isEqualTo("[\"SOURCE_NOT_VERIFIED:"+c[1]+"\"]");assertThat(leg.path("limitPrice").isNull()).isTrue();
            assertThat(c[0].equals("VT")?leg.path("units").asText():leg.path("lots").asText()).isEqualTo("0");
            assertThat(enabled.path("missing").toString()).contains("SOURCE_NOT_VERIFIED:"+c[1]+":"+c[0]);
            assertThat(satelliteFact(enabled,c[0].equals("VT")?"ceilingShares":"ceilingLots",c[0])).as("facts stay the pure calculation").isEqualTo(c[0].equals("VT")?"5":"3");
        }
    }
    @Test void zeroResultKeepsOriginalReasonOrderWithoutSourceCodes()throws Exception{
        var frame=SrppSatelliteBuildFixtures.mutate(satelliteFrame(),sources->{((ObjectNode)sources.path("market:台股:009826")).remove("quote");((ObjectNode)sources.path("market:美股:VT")).remove("quote");});
        frame=SrppSatelliteBuildFixtures.withValidation(frame,"market:台股:009826","UNAVAILABLE");frame=SrppSatelliteBuildFixtures.withValidation(frame,"fx","UNAVAILABLE");
        JsonNode enabled=pace(satellitePackage(frame,SrppSatelliteBuildFixtures.enabledModel()));
        JsonNode lots=SrppSatelliteBuildFixtures.leg(enabled,"009826"),vt=SrppSatelliteBuildFixtures.leg(enabled,"VT");
        assertThat(lots.path("blockingReasons").toString()).isEqualTo("[\"QUOTE_UNVERIFIED\"]");assertThat(lots.path("lots").asInt()).isZero();assertThat(lots.path("action").asText()).isEqualTo("BUY");
        assertThat(vt.path("blockingReasons").toString()).isEqualTo("[\"QUOTE_UNVERIFIED\",\"FX_CONVERSION_UNVERIFIED\"]");assertThat(vt.path("units").asText()).isEqualTo("0");
        assertThat(receipts(enabled).get("SATELLITE_BUILD:009826").path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(receipts(enabled).get("SATELLITE_BUILD:009826").path("inputRefs").toString()).doesNotContain("\"fx\"");
        // Task 625: only the VT D215_FX gate names the unverified fx source; the satellite receipts never add source codes to a zero result.
        assertThat(enabled.path("missing").toString()).contains("QUOTE_UNVERIFIED:009826","FX_CONVERSION_UNVERIFIED:VT","SOURCE_NOT_VERIFIED:fx:VT").doesNotContain("SOURCE_NOT_VERIFIED:market","SOURCE_NOT_VERIFIED:fx:009826");
    }
    @Test void invalidSatellitePolicyBlocksBothLegsWithoutGuessing()throws Exception{
        ObjectNode model=(ObjectNode)SrppSatelliteBuildFixtures.enabledModel();((ObjectNode)model.path("execution_policy_overrides").path("global_equity_build_cadence_d141").path("VT")).remove("min_shares_per_order");
        JsonNode enabled=pace(satellitePackage(satelliteFrame(),model));
        for(String code:List.of("009826","VT")){JsonNode leg=SrppSatelliteBuildFixtures.leg(enabled,code);assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");assertThat(leg.path("blockingReasons").toString()).isEqualTo("[\"SATELLITE_BUILD_POLICY_INVALID\"]");assertThat(receipts(enabled).get("SATELLITE_BUILD:"+code).path("status").asText()).isEqualTo("UNAVAILABLE");}
        assertThat(enabled.path("missing").toString()).contains("SATELLITE_BUILD_POLICY_INVALID");
    }
    @Test void vtConsumesTheTwdPoolAtFrozenConversionAndBlocksWhenExhausted()throws Exception{
        for(String twd:List.of("100000","24000")){
            ArrayNode modules=MAPPER.createArrayNode();for(int i=0;i<2;i++)modules.addObject().putObject("data").putArray("legs");
            ObjectNode funding=modules.addObject();funding.putArray("inputRefs");funding.putArray("missing");funding.putArray("reasonCodes");ArrayNode facts=funding.putObject("data").putArray("facts");funding.withObject("data").putArray("legs");
            facts.addObject().put("name","deployableCapacity").put("subject","TWD").put("quality","EXACT").put("value",twd);facts.addObject().put("name","deployableCapacity").put("subject","USD").put("quality","EXACT").put("value","0");
            JsonNode pace=pace(satellitePackage(satelliteFrame(),SrppSatelliteBuildFixtures.enabledModel()));ObjectNode module=modules.addObject();module.putArray("inputRefs");module.putArray("missing");module.putArray("reasonCodes");ArrayNode legs=module.putObject("data").putArray("legs");
            ObjectNode vt=SrppSatelliteBuildFixtures.leg(pace,"VT").deepCopy();ArrayNode kept=MAPPER.createArrayNode();vt.path("receipts").forEach(r->{if(!"SHARED_CURRENCY_CAPACITY".equals(r.path("ruleId").asText()))kept.add(r);});vt.set("receipts",kept);legs.add(vt);
            SrppReportModuleCalculator.sharedFunds(modules);JsonNode shared=receipts(vt).get("SHARED_CURRENCY_CAPACITY");
            if(twd.equals("100000")){assertThat(shared.path("status").asText()).isEqualTo("PASS");assertThat(vt.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");}
            else{assertThat(shared.path("status").asText()).isEqualTo("BLOCK");assertThat(vt.path("status").asText()).isEqualTo("BLOCKED");assertThat(vt.path("units").asText()).isEqualTo("0");assertThat(vt.path("amount").isNull()).isTrue();assertThat(vt.path("blockingReasons").toString()).contains("CURRENT_REPORT_SHARED_FUNDS_EXHAUSTED");}
        }
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> d215Golden()throws Exception{
        JsonNode golden=MAPPER.readTree(SrppReportModuleCalculatorTest.class.getResourceAsStream("/srpp/d215-fx-preference-golden.json"));
        assertThat(golden.path("schema").asText()).isEqualTo("D215_FX_PREFERENCE_GOLDEN_V1");assertThat(golden.path("cases").size()).isEqualTo(17);
        List<org.junit.jupiter.params.provider.Arguments> out=new ArrayList<>();for(JsonNode c:golden.path("cases"))out.add(org.junit.jupiter.params.provider.Arguments.of(c.path("name").asText(),golden.path("policy"),c));return out.stream();
    }
    /** Task 625.23: Java report-path binding equals original SRPP usd_asset_fx_preference.evaluate field by field. */
    @org.junit.jupiter.params.ParameterizedTest(name="{0}")@org.junit.jupiter.params.provider.MethodSource("d215Golden")
    void d215FxGoldenParity(String name,JsonNode policy,JsonNode c)throws Exception{
        SrppReportD215FxPreference.Result actual=D215Golden.evaluate(policy,c.path("payload"));JsonNode e=c.path("expected");
        assertThat(actual.status()).as(name).isEqualTo(e.path("status").asText());assertThat(actual.fxRisk()).as(name).isEqualTo(e.path("fx_risk").asText());
        assertThat(actual.rangeStatus()).as(name).isEqualTo(e.path("range_status").asText());
        D215Golden.decimal(name+":range_low",e.get("range_low"),actual.rangeLow());D215Golden.decimal(name+":range_high",e.get("range_high"),actual.rangeHigh());
        D215Golden.decimal(name+":range_position",e.get("range_position"),actual.rangePosition());D215Golden.decimal(name+":rate",e.get("rate"),actual.rate());
        assertThat(actual.absoluteRateHigh()).as(name).isEqualTo(e.has("absolute_rate_high")?e.path("absolute_rate_high").asBoolean():null);
        assertThat(actual.cheapExceptionVerified()).as(name).isEqualTo(e.has("cheap_exception_verified")?e.path("cheap_exception_verified").asBoolean():null);
        assertThat(actual.classificationStatus()).as(name).isEqualTo(e.has("classification_status")?e.path("classification_status").asText():null);
        assertThat(actual.scope()).as(name).isEqualTo(e.has("scope")?e.path("scope").asText():null);
        if(!e.path("reasons").isEmpty())assertThat(actual.reasons()).as(name).isNotEmpty().allMatch(r->r!=null&&!r.isBlank());
    }
    static final List<String> D215_BONDS=List.of("00865B","00719B","00697B");
    /** Frozen copies its JSON; every variant is a new frame (sources edited, vector unchanged). */
    static FrozenFixture d215Sources(FrozenFixture f,java.util.function.Consumer<ObjectNode> change){ObjectNode input=f.frozen.input();change.accept((ObjectNode)input.path("sources"));return new FrozenFixture(new DecisionInputCapturePort.Frozen(f.frozen.facts(),input,f.frozen.sourceVector(),1,f.frozen.assetGeneratedAt()),f.policy,f.registered);}
    static FrozenFixture d215Context(FrozenFixture f,String rate,String quality,boolean range,boolean typeVerified){return d215Sources(f,sources->{for(String code:D215_BONDS)((ObjectNode)sources.path("market:台股:"+code)).set("usdFxPurchase",D215Golden.context(code,"台股",f.frozen.facts().date(),f.frozen.facts().capturedAt(),rate,quality,range,typeVerified));});}
    static FrozenFixture d215Validation(FrozenFixture f,String ref,String validation){ObjectNode vector=f.frozen.sourceVector();((ObjectNode)vector.path(ref)).put("validation",validation);return new FrozenFixture(new DecisionInputCapturePort.Frozen(f.frozen.facts(),f.frozen.input(),vector,1,f.frozen.assetGeneratedAt()),f.policy,f.registered);}
    static JsonNode d215Bond(FrozenFixture f,JsonNode model){return SrppReportModuleCalculator.calculateShared(f.frozen,f.policy,model,f.registered,f.frozen.input().path("sources")).get(5);}
    static JsonNode bondLeg(JsonNode module,String code,String strategy){for(JsonNode leg:module.path("data").path("legs"))if(code.equals(leg.path("symbol").asText())&&strategy.equals(leg.path("strategy").asText()))return leg;throw new AssertionError(code+":"+strategy);}
    static ObjectNode d215Model(java.util.function.Consumer<ObjectNode> change)throws Exception{ObjectNode model=(ObjectNode)model().deepCopy();change.accept((ObjectNode)model.path("execution_policy").path(SrppReportD215FxPreference.KEY));return model;}
    @Test void d215NormalKeepsBondLegsAndEachOfTheFourLegsCarriesExactRefs()throws Exception{
        FrozenFixture f=fixture();JsonNode bond=d215Bond(f,model());assertThat(bond.path("data").path("legs")).hasSize(4);
        for(JsonNode leg:bond.path("data").path("legs")){JsonNode r=receipts(leg).get("D215_FX");assertThat(r).as(leg.path("symbol").asText()).isNotNull();
            assertThat(r.path("inputRefs").toString()).isEqualTo("[\"fx\",\"market:台股:"+leg.path("symbol").asText()+"\",\"policy\"]");assertThat(r.path("status").asText()).isEqualTo("PASS");assertThat(r.path("reason").asText()).isEqualTo("D215_NO_FX_PREFERENCE_VETO");
            assertThat(r.path("calculatorVersion").asText()).isEqualTo(SrppReportModuleCalculator.VERSION);assertThat(r.path("scope").isNull()).isTrue();
            assertThat(value(r,"fxSpotSelling")).isEqualTo("31");assertThat(value(r,"fxHigh")).isEqualTo("0");assertThat(value(r,"cheapAssetException")).isNull();}
        for(String strategy:List.of("STRATEGIC_FUBON","EMERGENCY_CATHAY")){JsonNode leg=bondLeg(bond,"00865B",strategy);assertThat(leg.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(leg.path("lots").asInt()).isEqualTo(2);assertThat(receipts(leg).get("D215_FX").path("values").toString()).doesNotContain("d215WaitCeiling");}
        for(JsonNode fact:bond.path("data").path("facts"))assertThat(fact.path("name").asText()).doesNotStartWith("fx");assertThat(bond.path("data").path("facts")).isEmpty();
    }
    @Test void d215HighBlocksSelectedStrategicAndEmergencyWithCeilingWithoutReflowOrSharedFunds()throws Exception{
        FrozenFixture f=d215Context(fixture(),"32.9","LIVE",true,true);JsonNode bond=d215Bond(f,model());
        for(String strategy:List.of("STRATEGIC_FUBON","EMERGENCY_CATHAY")){JsonNode leg=bondLeg(bond,"00865B",strategy),r=receipts(leg).get("D215_FX");
            assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");assertThat(leg.path("action").asText()).isEqualTo("NONE");assertThat(leg.path("lots").asInt()).isZero();
            for(String key:List.of("limitPrice","amount","fees"))assertThat(leg.path(key).isNull()).as(key).isTrue();
            assertThat(leg.path("blockingReasons").toString()).isEqualTo("[\"D215_FX_WAIT_PREFERRED\"]");assertThat(r.path("status").asText()).isEqualTo("BLOCK");assertThat(r.path("reason").asText()).isEqualTo("D215_FX_WAIT_PREFERRED");
            assertThat(value(r,"d215WaitCeilingLots")).isEqualTo("2");assertThat(value(r,"d215WaitCeilingAmount")).isEqualTo("19620");assertThat(value(r,"fxAbsoluteHigh")).isEqualTo("1");assertThat(value(r,"fxHigh")).isEqualTo("1");assertThat(value(r,"cheapAssetException")).isEqualTo("0");
            assertThat(receipts(leg)).doesNotContainKey("SHARED_CURRENCY_CAPACITY");}
        for(String code:List.of("00719B","00697B")){JsonNode leg=bondLeg(bond,code,"STRATEGIC_FUBON");assertThat(leg.path("status").asText()).as("no D-195 reflow").isEqualTo("BLOCKED");assertThat(leg.path("blockingReasons").toString()).isEqualTo("[\"NOT_SELECTED\"]");assertThat(receipts(leg).get("D215_FX").path("values").toString()).doesNotContain("d215WaitCeiling");}
        assertThat(bond.path("data").path("ranking")).isEqualTo(d215Bond(fixture(),model()).path("data").path("ranking"));
        assertThat(bond.path("missing")).isEmpty();
        // 625.24: the daily-decision calculator (and its endpoint) are untouched; the D-215 post-gate exists only in the report.
        Result daily=DailyDecisionCalculator.calculate(f.policy,f.frozen.facts());assertThat(daily.candidates()).anySatisfy(c->{assertThat(c.symbol()).isEqualTo("00865B");assertThat(c.status()).isEqualTo("ACTIONABLE_FOR_REPORT");});
        for(Candidate c:daily.candidates())assertThat(c.receipts()).noneMatch(r->r.ruleId().startsWith("D215"));
    }
    @Test void d215UnknownHistoricalProxyAndEmptyPolicyInstrumentsAreNamedMissingEvidence()throws Exception{
        for(String[] c:List.of(new String[]{"32","LIVE","false","true"},new String[]{"31","HISTORICAL_PROXY","true","true"},new String[]{"31","LIVE","true","false"})){
            FrozenFixture f=d215Context(fixture(),c[0],c[1],Boolean.parseBoolean(c[2]),Boolean.parseBoolean(c[3]));JsonNode bond=d215Bond(f,model());
            for(JsonNode leg:bond.path("data").path("legs")){JsonNode r=receipts(leg).get("D215_FX");assertThat(r.path("status").asText()).as(String.join("/",c)).isEqualTo("UNAVAILABLE");assertThat(r.path("reason").asText()).isEqualTo("D215_FX_EVIDENCE_UNKNOWN");assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");}
            assertThat(bondLeg(bond,"00865B","EMERGENCY_CATHAY").path("blockingReasons").toString()).isEqualTo("[\"D215_FX_EVIDENCE_UNKNOWN\"]");
            for(String code:D215_BONDS)assertThat(bond.path("missing").toString()).contains("D215_FX_EVIDENCE_UNKNOWN:"+code);assertThat(bond.path("status").asText()).isEqualTo("PARTIAL");
        }
    }
    @Test void d215VerifiedExceptionReleasesOnlyThePreferenceAndFxCapStillBlocks()throws Exception{
        FrozenFixture base=fixture();Input old=base.frozen.facts();
        Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),new BigDecimal("33.5"),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());
        FrozenFixture f=d215Context(new FrozenFixture(new DecisionInputCapturePort.Frozen(facts,base.frozen.input(),base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt()),base.policy,base.registered),"33.5","LIVE",true,true);LocalDate done=old.date().minusDays(1);
        f=d215Sources(f,sources->{for(String code:D215_BONDS){ObjectNode body=(ObjectNode)sources.path("market:台股:"+code);body.put("calendarVerified",true);body.putArray("completedSessions").add(done.toString());body.put("priceOnlyBasis","TW_SPLIT_STOCK_PRICE").put("priceOnlyBasisStatus","AVAILABLE");
            String price=old.symbols().get(code).quote().price().toPlainString();ArrayNode rows=body.putArray("priceOnlyDaily");rows.addObject().put("tradingDate",done.toString()).put("highPrice",price).put("closePrice",price).put("trusted",true);
            rows.addObject().put("tradingDate",done.minusMonths(5).toString()).put("highPrice","12").put("closePrice","11.9").put("trusted",true);rows.addObject().put("tradingDate",done.minusWeeks(52).toString()).put("highPrice","10").put("closePrice","9.9").put("trusted",true);}});
        JsonNode bond=d215Bond(f,model());
        for(JsonNode leg:bond.path("data").path("legs")){JsonNode r=receipts(leg).get("D215_FX");assertThat(r.path("status").asText()).isEqualTo("PASS");assertThat(r.path("reason").asText()).isEqualTo("D215_FX_PREFERENCE_EXCEPTION_REVIEW");assertThat(value(r,"cheapAssetException")).isEqualTo("1");assertThat(value(r,"assetHigh52w")).isEqualTo("12");
            assertThat(receipts(leg).get("FX_CAP").path("status").asText()).isEqualTo("BLOCK");assertThat(leg.path("status").asText()).as("exception never releases FX_CAP").isEqualTo("BLOCKED");}
        // Without the exception both FX receipts block side by side.
        f=d215Sources(f,sources->{for(String code:D215_BONDS)((ObjectNode)sources.path("market:台股:"+code)).put("priceOnlyBasisStatus","UNAVAILABLE");});
        JsonNode both=d215Bond(f,model());JsonNode leg=bondLeg(both,"00865B","STRATEGIC_FUBON");assertThat(receipts(leg).get("FX_CAP").path("status").asText()).isEqualTo("BLOCK");assertThat(receipts(leg).get("D215_FX").path("status").asText()).isEqualTo("BLOCK");
    }
    @Test void d215PolicyDriftFailsClosedAndLegacyBundleIsByteIdentical()throws Exception{
        FrozenFixture f=fixture();JsonNode drift=d215Bond(f,d215Model(p->p.put("range_position_threshold",new BigDecimal("0.91"))));
        for(JsonNode leg:drift.path("data").path("legs")){JsonNode r=receipts(leg).get("D215_FX");assertThat(r.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(r.path("reason").asText()).isEqualTo("D215_FX_POLICY_UNAVAILABLE");assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");}
        assertThat(drift.path("missing").toString()).contains("D215_FX_POLICY_UNAVAILABLE:00865B");
        ObjectNode legacy=(ObjectNode)model().deepCopy();((ObjectNode)legacy.path("execution_policy")).remove(SrppReportD215FxPreference.KEY);
        String normal=SrppJcs.canonicalize(SrppReportModuleCalculator.calculateShared(f.frozen,f.policy,legacy,f.registered,f.frozen.input().path("sources")).get(5));
        FrozenFixture high=d215Context(fixture(),"40","HISTORICAL_PROXY",false,false);
        assertThat(SrppJcs.canonicalize(SrppReportModuleCalculator.calculateShared(high.frozen,high.policy,legacy,high.registered,high.frozen.input().path("sources")).get(5))).isEqualTo(normal)
                .isEqualTo(SrppJcs.canonicalize(SrppReportModuleCalculator.calculateShared(f.frozen,f.policy,legacy,f.registered,MAPPER.createObjectNode()).get(5)));
        assertThat(normal).doesNotContain("D215").doesNotContain("market:台股:");
    }
    @Test void d215PassWithUnverifiedReferencedSourceIsNeverActionable()throws Exception{
        for(String ref:List.of("market:台股:00865B","fx")){
            FrozenFixture f=d215Validation(fixture(),ref,"UNAVAILABLE");JsonNode bond=d215Bond(f,model());
            for(String strategy:List.of("STRATEGIC_FUBON","EMERGENCY_CATHAY")){JsonNode leg=bondLeg(bond,"00865B",strategy),r=receipts(leg).get("D215_FX");
                assertThat(r.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(r.path("reason").asText()).isEqualTo("SOURCE_NOT_VERIFIED:"+ref);
                assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");assertThat(leg.path("lots").asInt()).isZero();assertThat(leg.path("blockingReasons").toString()).contains("SOURCE_NOT_VERIFIED:"+ref);assertThat(value(r,"d215WaitCeilingLots")).isEqualTo("2");}
            assertThat(bond.path("missing").toString()).contains("SOURCE_NOT_VERIFIED:"+ref+":00865B");
        }
        // Real publisher wire: the whole package still passes the envelope (and, via the written wire, the BFF validator).
        var bondFrame=SrppSatelliteBuildFixtures.withValidation(satelliteFrame(),"market:台股:00865B","UNAVAILABLE");JsonNode envelope=satellitePackage(bondFrame,SrppSatelliteBuildFixtures.enabledModel());
        for(JsonNode leg:SrppSatelliteBuildFixtures.module(envelope,"BOND_D130_D195").path("data").path("legs"))assertThat(leg.path("status").asText()).isNotEqualTo("ACTIONABLE_FOR_REPORT");
        writeWire("d215-bond-source-unverified",bondFrame,SrppSatelliteBuildFixtures.enabledModel());
        var vtFrame=SrppSatelliteBuildFixtures.withValidation(satelliteFrame(),"market:美股:VT","UNAVAILABLE");JsonNode vt=SrppSatelliteBuildFixtures.leg(pace(satellitePackage(vtFrame,SrppSatelliteBuildFixtures.enabledModel())),"VT");
        JsonNode r=receipts(vt).get("D215_FX");assertThat(r.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(r.path("reason").asText()).isEqualTo("SOURCE_NOT_VERIFIED:market:美股:VT");assertThat(r.path("inputRefs").toString()).isEqualTo("[\"fx\",\"market:美股:VT\",\"policy\"]");assertThat(vt.path("status").asText()).isEqualTo("BLOCKED");
    }
    @Test void d215VtHighWaitsWithShareCeilingKeepsBuyAndDoesNotChargeTheTwdPool()throws Exception{
        var frame=SrppSatelliteBuildFixtures.mutate(satelliteFrame(),sources->((ObjectNode)sources.path("market:美股:VT")).set("usdFxPurchase",D215Golden.context("VT","美股",SrppReportFixtures.DATE,SrppReportFixtures.AT,"32.9","LIVE",true,true)));
        JsonNode module=SrppReportModuleCalculator.pace(frame,new DecisionPolicyAuthority().policy(),SrppSatelliteBuildFixtures.enabledModel(),frame.input().path("sources"));
        JsonNode vt=SrppSatelliteBuildFixtures.leg(module,"VT"),r=receipts(vt).get("D215_FX");
        assertThat(vt.path("status").asText()).isEqualTo("BLOCKED");assertThat(vt.path("action").asText()).isEqualTo("BUY");assertThat(vt.path("units").asText()).isEqualTo("0");assertThat(vt.path("lots").isNull()).isTrue();
        for(String key:List.of("limitPrice","amount","fees"))assertThat(vt.path(key).isNull()).isTrue();assertThat(vt.path("blockingReasons").toString()).isEqualTo("[\"D215_FX_WAIT_PREFERRED\"]");
        assertThat(r.path("status").asText()).isEqualTo("BLOCK");assertThat(value(r,"d215WaitCeilingUnits")).isEqualTo("5");assertThat(value(r,"d215WaitCeilingAmount")).isEqualTo("750");assertThat(value(r,"assetHigh52w")).isNull();
        for(JsonNode v:r.path("values"))if(v.path("name").asText().equals("d215WaitCeilingAmount")||v.path("name").asText().equals("assetHigh52w"))assertThat(v.path("unit").asText()).isEqualTo("USD");
        assertThat(receipts(vt).keySet()).isEqualTo(Set.of("SATELLITE_BUILD","FX_CONVERSION","D215_FX"));assertThat(receipts(vt).get("FX_CONVERSION").path("status").asText()).isEqualTo("PASS");
        ArrayNode modules=MAPPER.createArrayNode();for(int i=0;i<2;i++)modules.addObject().putObject("data").putArray("legs");ObjectNode funding=modules.addObject();funding.putArray("inputRefs");funding.putArray("missing");funding.putArray("reasonCodes");ArrayNode facts=funding.putObject("data").putArray("facts");funding.withObject("data").putArray("legs");facts.addObject().put("name","deployableCapacity").put("subject","TWD").put("quality","EXACT").put("value","1000000");
        ObjectNode copy=module.deepCopy();modules.add(copy);SrppReportModuleCalculator.sharedFunds(modules);JsonNode waited=SrppSatelliteBuildFixtures.leg(copy,"VT");assertThat(receipts(waited)).doesNotContainKey("SHARED_CURRENCY_CAPACITY");
        assertThat(receipts(SrppSatelliteBuildFixtures.leg(copy,"009826"))).containsKey("SHARED_CURRENCY_CAPACITY");
        JsonNode lots=SrppSatelliteBuildFixtures.leg(module,"009826");assertThat(receipts(lots)).doesNotContainKey("D215_FX");assertThat(lots.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
    }
    @Test void d215AfterCloseInitializationKeepsVtBlockedWithoutCeilingAndNoActionableBond()throws Exception{
        Instant sourceAt=SrppReportFixtures.DATE.atTime(13,0).atZone(ZoneId.of("Asia/Taipei")).toInstant(),at=SrppReportFixtures.DATE.atTime(23,15).atZone(ZoneId.of("Asia/Taipei")).toInstant();
        var captured=SrppReportProducerScheduler.finishCapture(SrppSatelliteBuildFixtures.withSatellite(SrppReportFixtures.qualified("12:00",sourceAt,true),sourceAt),MAPPER.createObjectNode(),MAPPER.createObjectNode(),at);
        JsonNode envelope=SrppSatelliteBuildFixtures.publish(SrppReportProducerScheduler.withAfterCloseContext(captured),"12:00",SrppSatelliteBuildFixtures.enabledModel());
        JsonNode vt=SrppSatelliteBuildFixtures.leg(SrppSatelliteBuildFixtures.module(envelope,"GLOBAL_EQUITY_PACE"),"VT");
        assertThat(vt.path("status").asText()).isEqualTo("BLOCKED");assertThat(vt.path("units").asText()).isEqualTo("0");assertThat(vt.path("blockingReasons").get(0).asText()).isEqualTo(SrppReportModuleCalculator.AFTER_CLOSE_NON_EXECUTABLE);
        assertThat(receipts(vt).get("D215_FX").path("reason").asText()).isEqualTo("D215_NO_FX_PREFERENCE_VETO");assertThat(receipts(vt).get("D215_FX").path("values").toString()).doesNotContain("d215WaitCeiling");
        for(JsonNode leg:SrppSatelliteBuildFixtures.module(envelope,"BOND_D130_D195").path("data").path("legs"))assertThat(leg.path("status").asText()).isNotEqualTo("ACTIONABLE_FOR_REPORT");
    }
    /** 625.17: a WAIT leg is BLOCKED 0/null and its ceiling lives only in receipt values, so the unchanged envelope accepts it. */
    @Test void d215WaitLegsWithCeilingValuesPassTheUnchangedEnvelopeValidator()throws Exception{
        ObjectNode extra=SrppReportFixtures.extras();for(String code:List.of("00865B","00719B","00697B"))((ObjectNode)extra.path("market:台股:"+code)).set("usdFxPurchase",D215Golden.context(code,"台股",SrppReportFixtures.DATE,SrppReportFixtures.AT,"32.9","LIVE",true,true));
        ((ObjectNode)extra.path("market:美股:VT")).set("usdFxPurchase",D215Golden.context("VT","美股",SrppReportFixtures.DATE,SrppReportFixtures.AT,"32.9","LIVE",true,true));
        JsonNode envelope=SrppSatelliteBuildFixtures.publish(satelliteFrame(),"09:05",SrppSatelliteBuildFixtures.enabledModel(),extra);
        JsonNode bond=SrppSatelliteBuildFixtures.module(envelope,"BOND_D130_D195");assertThat(bond.toString()).contains("d215WaitCeilingLots","D215_FX_WAIT_PREFERRED");
        for(JsonNode leg:bond.path("data").path("legs"))assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");
        JsonNode vt=SrppSatelliteBuildFixtures.leg(SrppSatelliteBuildFixtures.module(envelope,"GLOBAL_EQUITY_PACE"),"VT");assertThat(vt.path("units").asText()).isEqualTo("0");assertThat(receipts(vt).get("D215_FX").toString()).contains("d215WaitCeilingUnits");
        writeWire("d215-wait",satelliteFrame(),SrppSatelliteBuildFixtures.enabledModel(),extra);
    }
    /** Real producer wire for the BFF validator test (copied into bff/src/test/resources/srpp-report). */
    static void writeWire(String name,DecisionInputCapturePort.Frozen frame,JsonNode model)throws Exception{String dir=System.getProperty("srpp.satelliteWire.dir");if(dir==null||dir.isBlank())return;java.nio.file.Files.writeString(java.nio.file.Path.of(dir,"report-producer-satellite-"+name+"-pg.json"),SrppSatelliteBuildFixtures.content(frame,"09:05",model));}
    static void writeWire(String name,DecisionInputCapturePort.Frozen frame,JsonNode model,JsonNode extra)throws Exception{String dir=System.getProperty("srpp.satelliteWire.dir");if(dir==null||dir.isBlank())return;java.nio.file.Files.writeString(java.nio.file.Path.of(dir,"report-producer-satellite-"+name+"-pg.json"),SrppSatelliteBuildFixtures.content(frame,"09:05",model,extra));}
}
