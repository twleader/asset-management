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
            case "short" -> {JsonNode policy=e.path(c.path("policyKey").asText());ObjectNode scenario=SrppReportModuleCalculator.shortScenario(n(c.get("price")),n(c.get("support")),n(c.get("pressure")),n(c.get("shares")),policy.path("scenario_costs"));ArrayNode reasons=scenario.putArray("reasons");SrppReportModuleCalculator.shortEntryReasons(c.path("body"),scenario,policy).forEach(reasons::add);actual=scenario;}
            case "us" -> {ObjectNode policy=e.path("us_megacap_tactical_policy_d133").path("order_check_d186").deepCopy();policy.set("minimum_profit_pct",e.path("us_megacap_tactical_policy_d133").path("minimum_profit_space_d185").get("minimum_pct"));actual=SrppReportModuleCalculator.usTacticalCheck(c.path("item"),date,n(c.get("total")),policy);}
            case "payroll" -> actual=SrppReportModuleCalculator.payrollAmounts(c.path("row"));
            case "pace" -> actual=SrppReportModuleCalculator.paceCapacity(model.path("execution_policy_overrides").path("global_equity_build_cadence_d141"),c.path("state"),c.path("code").asText(),n(c.get("fx")),n(c.get("price")));
            case "regime" -> actual=SrppReportModuleCalculator.regimeCapacity(c.path("live"),e.path("dynamic_allocation_policy_d141"),e.path("stock_purchase_funding_policy_d150"),n(c.get("total")),n(c.get("cash")),n(c.get("bonds")),n(c.get("equity")),n(c.get("inflation")));
            default -> throw new AssertionError(kind);
        }for(var expected:c.path("expected").properties()){JsonNode value=actual.get(expected.getKey());assertThat(value).as(kind+":"+expected.getKey()).isNotNull();if(expected.getValue().isBoolean()||expected.getValue().isArray()||expected.getKey().equals("reason"))assertThat(value).isEqualTo(expected.getValue());else assertThat(n(value).subtract(n(expected.getValue())).abs()).as(kind+":"+expected.getKey()).isLessThan(new BigDecimal("0.000000000001"));}count++;}
        assertThat(count).isEqualTo(57);
    }
    @Test void formalTargetsAndRegistryArtifactUseExactCurrentAuthorityBytes()throws Exception{JsonNode model=model();JsonNode artifact=SrppJcs.parseStrict(new String(getClass().getResourceAsStream("/srpp/report/d201-policy-document.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertThat(SrppReportModuleCalculator.registryDocument(model)).isEqualTo(artifact);assertThat(SrppReportModuleCalculator.formalTargets(model).path("00865B").asText()).isEqualTo("0.063333333333");assertThat(SrppReportModuleCalculator.targetAllocationSha256(model)).matches("[a-f0-9]{64}");assertThat(SrppPolicyDocument.parse(SrppJcs.canonicalize(artifact)).targets()).hasSize(18);}
    @Test void unknownCoreStateAndCrossConsumerStateCannotBecomeComplete()throws Exception{FrozenFixture fixture=fixture();JsonNode model=model();for(JsonNode state:List.of(NullNode.instance,MAPPER.readTree("{\"verified\":true,\"ownerUserId\":7,\"consumer\":\"Claude\",\"items\":[]}"))){ObjectNode result=SrppReportModuleCalculator.calculateCore(fixture.frozen,model,state,"Codex");assertThat(result.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(result.path("missing").toString()).contains("CONSUMER_CORE_STATE_UNAVAILABLE");}}
    @Test void twelveModulePublishedShapeContainsTrueActionableBondFixtureAndUnknownSources()throws Exception{FrozenFixture fixture=fixture();JsonNode model=model();ArrayNode shared=SrppReportModuleCalculator.calculateShared(fixture.frozen,fixture.policy,model,fixture.registered,MAPPER.readTree("{}"));ObjectNode core=SrppReportModuleCalculator.calculateCore(fixture.frozen,model,NullNode.instance,"Codex");ArrayNode all=SrppReportModuleCalculator.compose(shared,core);assertThat(all).hasSize(12);assertThat(all.get(5).path("data").path("legs").get(0).path("lots").asInt()).isEqualTo(2);assertThat(all.get(5).path("data").path("legs").get(0).path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(all.get(3).path("status").asText()).isEqualTo("PARTIAL");assertThat(all.get(4).path("status").asText()).isEqualTo("UNAVAILABLE");SrppJcs.canonicalize(all);for(JsonNode module:all){assertThat(module.path("scope").isArray()).isTrue();for(JsonNode leg:module.path("data").path("legs"))if(leg.path("status").asText().equals("BLOCKED")){assertThat(leg.path("limitPrice").isNull()).isTrue();assertThat(leg.path("amount").isNull()).isTrue();}}}
    @Test void lowerTargetBandAndBrokerSubaccountUnknownRemainLocalized()throws Exception{FrozenFixture fixture=fixture();ArrayNode shared=SrppReportModuleCalculator.calculateShared(fixture.frozen,fixture.policy,model(),fixture.registered,MAPPER.readTree("{}"));JsonNode allocation=shared.get(1);BigDecimal factor=DailyDecisionCalculator.inflation(fixture.policy,fixture.frozen.facts().date()),weight=fixture.policy.totalFloor().multiply(factor).divide(fixture.frozen.facts().totalAssets(),MathContext.DECIMAL128);JsonNode target=fact(allocation,"bondTarget",null);assertThat(n(target.path("value"))).isEqualByComparingTo(new BigDecimal("0.28").min(BigDecimal.ONE.subtract(weight).subtract(new BigDecimal("0.55"))).max(BigDecimal.ZERO));}
    @Test void tamperedStatePolicyAndIncompleteSGOVCannotPass()throws Exception{JsonNode golden=MAPPER.readTree(getClass().getResourceAsStream("/srpp/report-same-input-golden.json"));JsonNode c=java.util.stream.StreamSupport.stream(golden.path("cases").spliterator(),false).filter(v->v.path("kind").asText().equals("core")).findFirst().orElseThrow();ObjectNode state=c.path("state").deepCopy();state.put("policy_sha256","0".repeat(64));assertThatThrownBy(()->SrppReportModuleCalculator.coreTiming(c.path("body"),state,LocalDate.parse(c.path("date").asText()),model().path("execution_policy").path("core_sell_guard_d174"))).hasMessageContaining("WATCH_STATE_UNVERIFIED");}
    @Test void sameSymbolInDifferentMarketNeverInflatesFormalTaiwanTarget()throws Exception{
        FrozenFixture base=fixture();ObjectNode input=base.frozen.input().deepCopy();ObjectNode a=(ObjectNode)input.path("sources").path("assets"),snapshot=(ObjectNode)a.path("snapshot"),live=(ObjectNode)a.path("liveAssets");ObjectNode h=((ArrayNode)snapshot.path("stocks")).addObject();h.put("id",2).put("stockCode","0050").put("market","美股").put("shares","1").put("currentValue","100").put("estimatedDividend","0");ObjectNode v=((ArrayNode)live.path("stocks")).addObject();v.put("holdingId",2).put("stockCode","0050").put("market","美股").put("shares","1").put("liveValue","100");snapshot.put("totalStockValue","15000100").put("totalAssets","20000100");live.put("liveStockValue","15000100").put("liveTotalAssets","20000100");Input old=base.frozen.facts();Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),new BigDecimal("20000100"),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());var frozen=new DecisionInputCapturePort.Frozen(facts,input,base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());JsonNode allocation=SrppReportModuleCalculator.calculateShared(frozen,base.policy,model(),base.registered,MAPPER.createObjectNode()).get(1);assertThat(fact(allocation,"currentValue","0050").path("value").asText()).isEqualTo("15000000");assertThat(allocation.path("missing").toString()).contains("UNMAPPED_FORMAL_TARGET:美股:0050");
    }
    @Test void optionalTradabilityReceiptConservativelyTightensPriceWithoutDroppingQuantity()throws Exception{
        FrozenFixture base=fixture();Input old=base.frozen.facts();Map<String,Symbol>symbols=new TreeMap<>(old.symbols());Symbol v=symbols.get("00865B");symbols.put(v.code(),new Symbol(v.code(),v.quote(),v.book(),v.closes(),v.distributions(),v.distributionVerified(),v.currentValue(),v.position(),v.pending(),v.radar(),v.valuation(),new Evidence("UNAVAILABLE","TRADABILITY_SOURCE_UNAVAILABLE"),v.mediumAction(),v.mediumCandidateAction(),v.intraday()));Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),symbols,old.budget(),old.subaccounts(),old.emergencyValue());var frozen=new DecisionInputCapturePort.Frozen(facts,base.frozen.input(),base.frozen.sourceVector(),1,base.frozen.assetGeneratedAt());JsonNode bond=SrppReportModuleCalculator.calculateShared(frozen,base.policy,model(),base.registered,MAPPER.createObjectNode()).get(5),leg=bond.path("data").path("legs").get(0);assertThat(leg.path("lots").asInt()).isEqualTo(2);assertThat(leg.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(leg.path("limitPrice").asText()).isEqualTo("9.81");assertThat(leg.path("receipts").toString()).contains("TRADABILITY_SOURCE_UNAVAILABLE","NOT_EVALUATED","HORIZON_NOT_APPLICABLE","SHORT_ONLY");for(JsonNode receipt:leg.path("receipts"))for(JsonNode value:receipt.path("values"))assertThat(value.path("value").isNull()).isFalse();
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
        JsonNode c = d208Case("above_midpoint_original_gates_pass");
        ObjectNode policy = model().path("execution_policy").path("core_sell_guard_d174").deepCopy();
        ObjectNode stable = MAPPER.createObjectNode();
        for (String key : List.of("decision", "scope", "arming", "non_distributing_symbols")) stable.set(key, policy.get(key));
        ObjectNode prior = policy.deepCopy(); prior.remove("low_point_sell_protection_d208");
        for (JsonNode hashPolicy : List.of(stable, prior)) {
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
    }

    @Test void d208PublishedModuleCannotTurnLowOrMissingEvidenceIntoPass() throws Exception {
        JsonNode c = d208Case("live_k_50");
        ObjectNode module = d208Module(c.path("body"), c.path("state"));
        assertThat(module.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(module.path("scope").toString()).contains("D208_00919_LOW_POINT_SELL_PROTECTION");
        assertThat(fact(module, "timingPass", "00919").path("value").asText()).isEqualTo("0");
        assertThat(module.path("receipts").toString()).contains("D208_LOW_POINT_SELL_BLOCKED");
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

    record FrozenFixture(DecisionInputCapturePort.Frozen frozen,Policy policy,SupportedPolicy registered){}
    static FrozenFixture fixture()throws Exception{Policy p=new DecisionPolicyAuthority().policy();LocalDate date=LocalDate.parse("2026-10-08");Instant at=Instant.parse("2026-10-08T04:00:00Z");Evidence pass=new Evidence("PASS","ISOLATED_OFFICIAL_EVIDENCE_FIXTURE");List<LocalDate>sessions=java.util.stream.IntStream.rangeClosed(1,20).mapToObj(i->date.minusDays(21-i)).toList();Map<String,Symbol>symbols=new TreeMap<>();ObjectNode sources=MAPPER.createObjectNode();for(String code:p.symbols()){BigDecimal price=new BigDecimal(code.equals("00865B")?"9.8":code.equals("00719B")?"9.85":"9.9");Quote q=new Quote(price,BigDecimal.TEN,100L,date,at,"FUBON","LIVE",false,BigDecimal.ZERO,date);List<Level>levels=new ArrayList<>();for(int i=0;i<5;i++)levels.add(new Level(price.subtract(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i+1))),10,price.add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i+1))),10));symbols.put(code,new Symbol(code,q,new Book("FUBON_BOOKS",date,at,at,levels,true),sessions.stream().map(d->new Close(d,BigDecimal.TEN,true)).toList(),List.of(),true,BigDecimal.ZERO,pass,pass,pass,pass,pass,"HOLD","HOLD",pass));for(String kind:List.of("quote","book","radar","daily","dividends","pending","tradability","minute"))sources.putObject(kind+":"+code).put("isolatedFixture",true);}
        ObjectNode assets=MAPPER.createObjectNode(),snapshot=assets.putObject("snapshot"),live=assets.putObject("liveAssets");snapshot.put("id",1);snapshot.put("snapshotDate",date.toString());live.put("snapshotId",1);live.put("snapshotDate",date.toString());for(ObjectNode n:List.of(snapshot,live)){n.put("totalDeposit","5000000");n.put("totalFundValue","0");}snapshot.put("totalStockValue","15000000");snapshot.put("totalAssets","20000000");live.put("liveStockValue","15000000");live.put("liveTotalAssets","20000000");ArrayNode deposits=snapshot.putArray("deposits");for(int i=0;i<2;i++){ObjectNode d=deposits.addObject();d.put("id",i+1);d.put("bankId",1);d.put("currency","TWD");d.put("depositType",i==0?"TERM":"DEMAND");d.put("amount",i==0?"1000000":"4000000");d.put("originalAmount",i==0?"1000000":"4000000");d.put("estimatedAnnualInterest","0");d.put("annualInterestRate","0");}snapshot.putArray("funds");ObjectNode holding=snapshot.putArray("stocks").addObject();holding.put("id",1);holding.put("stockCode","0050");holding.put("market","台股");holding.put("shares","1000");holding.put("currentValue","15000000");holding.put("estimatedDividend","0");ObjectNode item=live.putArray("stocks").addObject();item.put("holdingId",1);item.put("stockCode","0050");item.put("market","台股");item.put("shares","1000");item.put("liveValue","15000000");assets.put("generatedAt",at.toString());assets.put("targetPriceComplete",true);sources.set("assets",assets);sources.putObject("classifications").putObject("resolvedStockClasses").put("0050:台股","STOCK");sources.putArray("ledger");sources.putArray("reservations");for(String id:List.of("policy","calendar","subaccounts","fx","bear-window"))sources.putObject(id).put("isolatedFixture",true);ObjectNode reportReservations=sources.putObject("report-reservations");reportReservations.put("verified",true).put("ownerUserId",7).put("tradingDate",date.toString()).put("policyBundleSha256",p.bundleHash()).put("coverage","COMPLETE").putArray("rows");ObjectNode reportState=sources.putObject("topup-state");reportState.put("verified",true).put("ownerUserId",7).put("tradingDate",date.toString()).put("policyBundleSha256",p.bundleHash());reportState.putObject("pending").put("verified",true).put("coverage","COMPLETE").putArray("orders");sources.withObject("policy").put("bundleHash",p.bundleHash());ObjectNode input=MAPPER.createObjectNode();input.put("ownerUserId",7);input.set("sources",sources);
        Input facts=new Input(date,"12:00",at,sessions,new BigDecimal("20000000"),new BigDecimal("5000000"),new BigDecimal("1000000"),BigDecimal.ZERO,new BigDecimal("32"),pass,pass,symbols,new Budget(0,0,0,true,BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),pass,BigDecimal.ZERO);ObjectNode vector=MAPPER.createObjectNode();sources.fields().forEachRemaining(e->{ObjectNode receipt=vector.putObject(e.getKey());receipt.put("sourceId",e.getKey());receipt.put("revision","isolated-fixture");receipt.put("sha256",SrppJcs.hash(e.getValue()));receipt.put("capturedAt",at.toString());receipt.put("coverage","COMPLETE");receipt.put("validation","VERIFIED");});SrppPolicyDocument doc=SrppPolicyDocument.parse(SrppJcs.canonicalize(SrppReportModuleCalculator.registryDocument(model())));SupportedPolicy registered=new SupportedPolicy(p.bundleHash(),SrppFormulaCatalog.FORMULA_VERSION_V2,SrppJcs.hash(doc.document()),SrppFormulaCatalog.formulaSetSha256(SrppFormulaCatalog.FORMULA_VERSION_V2),doc.document(),SrppFormulaCatalog.manifest(SrppFormulaCatalog.FORMULA_VERSION_V2),doc.targets(),at.minusSeconds(1));return new FrozenFixture(new DecisionInputCapturePort.Frozen(facts,input,vector,1,at.toString()),p,registered);
    }
}
