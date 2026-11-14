package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class SrppReportShortUsCalculatorTest {
    private static final ObjectMapper M=new ObjectMapper();
    private static JsonNode model()throws Exception{return SrppReportModuleCalculatorTest.model();}
    private JsonNode golden()throws Exception{return M.readTree(getClass().getResourceAsStream("/srpp/report-short-us-full-golden.json"));}
    private ObjectNode d210Body(String price)throws Exception{
        ObjectNode body=M.createObjectNode();body.put("symbol","00881");body.putObject("quote").put("price",price);
        ObjectNode stock=golden().path("cases").get(0).path("input").path("items").get(0).path("radar").path("stock").deepCopy();
        stock.put("monthlyMa","101").put("weeklyMa","102");body.putObject("radar").set("stock",stock);return body;
    }
    private static ObjectNode d210Scenario(ObjectNode body,JsonNode policy,String pressure){
        return SrppReportModuleCalculator.shortScenario(new BigDecimal(body.path("quote").path("price").asText()),new BigDecimal("100"),new BigDecimal(pressure),new BigDecimal("1000"),policy.path("scenario_costs"));
    }
    @Test void d210EquityReboundUsesExactThreePercentBeforeDisplayRounding()throws Exception{
        JsonNode policy=model().path("execution_policy").path("short_term_opportunity_d171");
        assertThat(new BigDecimal(policy.path("minimum_rebound_pct").asText())).isEqualByComparingTo("3");
        for(String price:new String[]{"102.9999","102.99999","103","103.00001","105"}){
            ObjectNode body=d210Body(price),scenario=d210Scenario(body,policy,"130");
            var reasons=SrppReportModuleCalculator.shortEntryReasons(body,scenario,policy);
            if(new BigDecimal(price).compareTo(new BigDecimal("103"))<0)assertThat(reasons).as(price).containsExactly("REBOUND_NOT_CONFIRMED");
            else assertThat(reasons).as(price).isEmpty();
            if(price.equals("102.99999"))assertThat(new BigDecimal(scenario.path("reboundPct").asText())).isEqualByComparingTo("3");
        }
    }
    @Test void d210ThreePercentStillRequiresOriginalTrendRewardAndRadarGates()throws Exception{
        JsonNode policy=model().path("execution_policy").path("short_term_opportunity_d171");
        for(String gate:new String[]{"monthlyMa","weeklyMa","kValue","monthlyConfirmation","shortAction","rewardRisk"}){
            ObjectNode body=d210Body("103"),stock=(ObjectNode)body.path("radar").path("stock");
            switch(gate){
                case "monthlyMa","weeklyMa" -> stock.put(gate,"104");
                case "kValue" -> stock.put(gate,"45");
                case "monthlyConfirmation" -> stock.put(gate,"BELOW");
                case "shortAction" -> stock.put(gate,"WAIT");
                default -> { }
            }
            ObjectNode scenario=d210Scenario(body,policy,gate.equals("rewardRisk")?"104":"130");
            String reason=gate.equals("rewardRisk")?"NET_REWARD_RISK_INSUFFICIENT":gate.equals("shortAction")?"RADAR_WAIT_OR_CONFLICT":"REBOUND_NOT_CONFIRMED";
            assertThat(SrppReportModuleCalculator.shortEntryReasons(body,scenario,policy)).as(gate).contains(reason);
        }
    }
    @Test void d210DoesNotChangeBondReboundOrUsNetProfitThresholds()throws Exception{
        JsonNode policies=model().path("execution_policy"),bond=policies.path("bond_short_term_opportunity_d185");
        assertThat(new BigDecimal(bond.path("minimum_rebound_pct").asText())).isEqualByComparingTo("5");
        assertThat(new BigDecimal(policies.path("us_megacap_tactical_policy_d133").path("minimum_profit_space_d185").path("minimum_pct").asText())).isEqualByComparingTo("15");
        ObjectNode body=d210Body("104.99999"),scenario=d210Scenario(body,bond,"130");
        assertThat(SrppReportModuleCalculator.shortEntryReasons(body,scenario,bond)).doesNotContain("REBOUND_NOT_CONFIRMED");
    }
    private static void compare(JsonNode actual,JsonNode expected,String path){assertThat(actual).as(path).isNotNull();if(expected.isObject()){for(var e:expected.properties())compare(actual.get(e.getKey()),e.getValue(),path+"."+e.getKey());}else if(expected.isArray()){assertThat(actual.size()).as(path).isEqualTo(expected.size());for(int i=0;i<expected.size();i++)compare(actual.get(i),expected.get(i),path+"["+i+"]");}else if(expected.isNumber()||expected.isTextual()&&expected.asText().matches("-?[0-9]+(?:\\.[0-9]+)?")){assertThat(actual.isNull()).as(path+" actual="+actual).isFalse();assertThat(new BigDecimal(actual.asText()).subtract(new BigDecimal(expected.asText())).abs()).as(path).isLessThan(new BigDecimal("0.00000001"));}else assertThat(actual).as(path).isEqualTo(expected);}
    @Test void originalThirteenSameInputFullScreenAndD186Parity()throws Exception{int count=0;for(JsonNode c:golden().path("cases")){JsonNode actual="short".equals(c.path("kind").asText())?SrppReportShortUsCalculator.screen(c.path("input"),model(),"short_term_opportunity_d171"):SrppReportShortUsCalculator.usCheck(c.path("input"),c.path("date").asText(),new BigDecimal(c.path("total").asText()),model());compare(actual,c.path("expected"),c.path("name").asText());count++;}assertThat(count).isEqualTo(13);}
    @Test void originalPythonFullHoldingPeriodPositiveAndNegativeParity()throws Exception{
        JsonNode fixture=M.readTree(getClass().getResourceAsStream("/srpp/report-long-short-exit-golden.json"));
        assertThat(fixture.path("policyBundleSha256").asText()).isEqualTo(new com.steven.assets.service.srpp.decision.DecisionPolicyAuthority().policy().bundleHash());
        int count=0;
        for(JsonNode c:fixture.path("cases")){
            JsonNode input=c.path("input"),actual=SrppReportShortUsCalculator.screen(input,model(),"bond_short_term_opportunity_d185");
            compare(actual,c.path("expected"),c.path("name").asText());
            for(JsonNode row:actual.path("results")){
                assertThat(row.path("reasons").toString()).as(c.path("name").asText()+" flat range entry gate").contains("\"NO_SUPPORT_PRESSURE_INTERVAL\"");
                assertThat(row.path("may_recommend_after_daily_gates").asBoolean()).isFalse();
                assertThat(row.path("share_ceiling_before_funding").asLong()).isZero();
            }
            JsonNode position=actual.path("sleeve").path("positions").get(0),locked=input.path("sleeve_ledger").path("positions").get(0);
            assertThat(position.path("support")).as(c.path("name").asText()+" immutable support").isEqualTo(locked.path("support"));
            assertThat(position.path("pressure")).as(c.path("name").asText()+" immutable pressure").isEqualTo(locked.path("pressure"));
            assertThat(position.path("shares").asLong()).as(c.path("name").asText()+" isolated short shares").isEqualTo(1000);
            assertThat(position.path("exit_share_ceiling").asLong()).isLessThanOrEqualTo(1000);
            count++;
        }
        assertThat(count).isEqualTo(15);
    }
    @Test void reversedReferencesAndNonpositiveInputsRemainRejected()throws Exception{
        JsonNode costs=model().path("execution_policy").path("bond_short_term_opportunity_d185").path("scenario_costs");
        assertThatThrownBy(()->SrppReportModuleCalculator.shortScenario(new BigDecimal("50"),new BigDecimal("60"),new BigDecimal("45"),new BigDecimal("1000"),costs)).hasMessage("SHORT_REFERENCE_UNVERIFIED");
        for(int invalid=0;invalid<3;invalid++){
            BigDecimal price=invalid==0?BigDecimal.ZERO:new BigDecimal("50"),support=invalid==1?BigDecimal.ZERO:new BigDecimal("50"),shares=invalid==2?BigDecimal.ZERO:new BigDecimal("1000");
            assertThatThrownBy(()->SrppReportModuleCalculator.shortScenario(price,support,new BigDecimal("50"),shares,costs)).hasMessage("SHORT_REFERENCE_UNVERIFIED");
        }
    }
    @Test void fullHoldingPeriodCannotTreatNullSourceAsVerifiedText()throws Exception{
        JsonNode fixture=M.readTree(getClass().getResourceAsStream("/srpp/report-long-short-exit-golden.json"));
        JsonNode original=java.util.stream.StreamSupport.stream(fixture.path("cases").spliterator(),false).filter(c->"00679B_120_sessions".equals(c.path("name").asText())).findFirst().orElseThrow().path("input");
        for(String missing:new String[]{"quote","history"}){
            ObjectNode input=original.deepCopy();ObjectNode quote=(ObjectNode)input.path("items").get(0).path("quote");
            if("quote".equals(missing))quote.putNull("source");else((ObjectNode)quote.path("dividendHistory")).putNull("source");
            JsonNode result=SrppReportShortUsCalculator.screen(input,model(),"bond_short_term_opportunity_d185");
            assertThat(result.path("sleeve").path("positions").get(0).path("exit_status").asText()).as(missing).isEqualTo("EXIT_BLOCKED");
            assertThat(result.path("sleeve").path("new_buys_frozen").asBoolean()).as(missing).isTrue();
        }
    }
    @Test void attributionUnknownAndConflictingFillNeverBecomeVerifiedEmpty()throws Exception{JsonNode original=golden().path("cases").get(0).path("input");ObjectNode missing=original.deepCopy();missing.remove("sleeve_ledger");assertThat(SrppReportShortUsCalculator.screen(missing,model(),"short_term_opportunity_d171").path("sleeve").path("status").asText()).isEqualTo("無法判定");ObjectNode changed=original.deepCopy();((ObjectNode)changed.path("sleeve_evidence")).put("ledger_sha256","0".repeat(64));assertThat(SrppReportShortUsCalculator.screen(changed,model(),"short_term_opportunity_d171").path("sleeve").path("reason").asText()).isEqualTo("SHORT_TERM_CONFIRMATION_UNVERIFIED");}
    @Test void publicTwModuleHasNoUsIndividualTradeAndUnknownSleeveIsUnavailable()throws Exception{var fixture=SrppReportModuleCalculatorTest.fixture();ObjectNode extra=M.createObjectNode();extra.putObject("us-tactical").put("verified",true).put("sessionApplicable",true).put("ownerUserId",7);JsonNode result=SrppReportShortUsCalculator.calculate(fixture.frozen(),model(),extra);assertThat(result.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(result.path("data").path("legs")).isEmpty();assertThat(result.path("receipts").toString()).contains("HORIZON_NOT_APPLICABLE");assertThat(result.path("missing").toString()).doesNotContain("CALCULATOR_SCOPE_NOT_VERIFIED");}
    private ObjectNode usFullItem()throws Exception{ObjectNode item=golden().path("cases").get(10).path("input").deepCopy();((ObjectNode)item.path("quote")).put("asOf","2026-09-25T14:00:00Z");ObjectNode radar=(ObjectNode)item.path("radar");radar.put("ruleVersion","US_RULES_V1").put("snapshotVerified",true).put("stale",false).put("indicatorsVerified",true);ObjectNode e=item.putObject("full_evidence");e.put("ownerUserId",7).put("radarDetailSuccessfulReads",1).put("radarListHit",true).put("radarListDetailConsistent",true);
        e.putObject("market").put("verified",true).put("completedSessionVerified",true).put("close","110").put("ma200","100").put("previousMa200","99").put("peak","120").put("riskMode","NORMAL");e.putObject("drawdown").put("verified",true).put("materialDrawdown",true).put("stabilizationVerified",true);e.putObject("fundamentals").put("verified",true).put("latestFinancialReportRef","official-report").put("guidanceRef","official-guidance").put("permanentImpairment",false).put("unresolvedMaterialEvent",false);e.putObject("valuation").put("verified",true).put("basis","qualified-evaluation").put("reasonable",true).put("pessimisticPrice","80").put("probabilityWeightedNetReturnPct","20").put("invalidationConditions","guidance-impaired");e.putObject("priceCrossCheck").put("verified",true).put("session","2026-09-25").put("source","INDEPENDENT").put("consistent",true).put("maximumUnitsByVolumeAndSpread",1000);e.putObject("positions").put("verified",true).put("ownerUserId",7).put("tradeId","ISOLATED-US-1").put("companyOpenTradeIds",0).put("batchCount",0);e.putObject("pending").put("verified",true).put("ownerUserId",7).putArray("orders");e.putObject("funding").put("verified",true).put("ownerUserId",7).put("bucket","CATHAY_TWD_US_TACTICAL").put("broker","國泰").put("account","ISOLATED").put("settlementDateVerified",true).put("brokerFxTwdPerUsd","32").put("availableTwd","50000").put("termTwd","1000000").put("totalDepositsTwd","5000000").put("inflationAdjustedTermFloorTwd","800000").put("inflationAdjustedTotalFloorTwd","1550000").put("minimumCommissionUsd","3").put("platformFeeUsd","1");return item;}
    @Test void isolatedUsFullPositiveHasWholeUnitsActualCostsAndProtectedTwdFunding()throws Exception{JsonNode result=SrppReportShortUsCalculator.usFull(usFullItem(),"2026-09-25",Instant.parse("2026-09-25T14:00:00Z"),new BigDecimal("20000000"),7,model());assertThat(result.path("status").asText()).isEqualTo("PASS_FOR_REPORT_REVIEW");assertThat(result.path("units").asLong()).isPositive();assertThat(new BigDecimal(result.path("settlement_twd").asText())).isLessThanOrEqualTo(new BigDecimal("50000"));assertThat(new BigDecimal(result.path("amount_usd").asText())).isEqualByComparingTo(new BigDecimal(result.path("checked_limit_price").asText()).multiply(BigDecimal.valueOf(result.path("units").asLong())));assertThat(new BigDecimal(result.path("fees_usd").asText())).isPositive();}
    @Test void fullUsUnknownOwnerBullFloorAndPendingRemainLocalBlocks()throws Exception{for(String kind:new String[]{"owner","bull","funding","pending","fundamental"}){ObjectNode item=usFullItem();ObjectNode e=(ObjectNode)item.path("full_evidence");switch(kind){case "owner"->e.put("ownerUserId",8);case "bull"->((ObjectNode)e.path("market")).put("riskMode","RISK_OFF");case "funding"->((ObjectNode)e.path("funding")).put("termTwd","799999");case "pending"->((ObjectNode)e.path("pending")).put("verified",false);case "fundamental"->((ObjectNode)e.path("fundamentals")).put("permanentImpairment",true);}JsonNode result=SrppReportShortUsCalculator.usFull(item,"2026-09-25",Instant.parse("2026-09-25T14:00:00Z"),new BigDecimal("20000000"),7,model());assertThat(result.path("status").asText()).as(kind).isEqualTo("NO_EXECUTABLE_ORDER");assertThat(result.path("units").asLong()).isZero();assertThat(result.path("amount_usd").isNull()).isTrue();}}
    @Test void frozenShortModuleSizesExecutableSharesAndConfinesExitsToSleeve()throws Exception{
        var base=SrppReportModuleCalculatorTest.fixture();for(int scenario=0;scenario<2;scenario++){
            ObjectNode bundle=golden().path("cases").get(scenario==0?0:4).path("input").deepCopy();String today=bundle.path("trading_date").asText();Instant now=Instant.parse("2026-09-22T07:00:00Z");bundle.put("policyKey","short_term_opportunity_d171");bundle.putObject("funding").put("verified",true).put("ownerUserId",7).put("availableTwd","1000000");ObjectNode gates=bundle.putObject("executionGates").putObject("00881");for(String key:new String[]{"portfolio","group","protectedCash","deduplication","intraday","aggregateFloors"})gates.putObject(key).put("verified",true).put("pass",true);
            gates.putObject("executionAccount").put("verified",true).put("ownerUserId",7).put("broker","富邦").put("subaccount","ISOLATED");ObjectNode input=base.frozen().input();((ObjectNode)input.path("sources")).set("assets",bundle.path("assets"));var old=base.frozen().facts();var facts=new com.steven.assets.service.srpp.decision.DecisionFacts.Input(LocalDate.parse(today),"12:00",now,old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());var frozen=new com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen(facts,input,base.frozen().sourceVector(),15,now.toString());ObjectNode extra=M.createObjectNode();ObjectNode shortSource=extra.putObject("short-sleeve");shortSource.put("verified",true).put("ownerUserId",7).putArray("bundles").add(bundle);ObjectNode body=extra.putObject("symbols").putObject("台股:00881");body.put("symbol","00881").put("market","台股").put("volumeUnit","SHARES");ObjectNode quote=body.putObject("quote");quote.put("stockCode","00881").put("market","台股").put("tradingDate",today).put("quoteStatus","LIVE").put("closed",false).put("source","FUBON").put("price","50").put("previousClose","49").put("volume",10000000).put("updatedAt",now.toString());ObjectNode book=body.putObject("book");book.put("stockCode","00881").put("market","台股").put("supported",true).put("available",true).put("marketStatus","OPEN").put("source","FUBON_BOOKS").put("sourceTime",now.toString()).put("fetchedAt",now.toString());ArrayNode levels=book.putArray("levels");for(int i=0;i<5;i++)levels.addObject().put("level",i+1).put("bidPrice",new BigDecimal("49.99").subtract(new BigDecimal(".01").multiply(BigDecimal.valueOf(i))).toPlainString()).put("askPrice",new BigDecimal("50").add(new BigDecimal(".01").multiply(BigDecimal.valueOf(i))).toPlainString()).put("bidVolumeLots",10).put("askVolumeLots",10);
            JsonNode result=SrppReportShortUsCalculator.calculate(frozen,model(),extra);JsonNode legs=result.path("data").path("legs");if(scenario==0){JsonNode buy=legs.get(0);assertThat(buy.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(buy.path("lots").isNull()).isTrue();assertThat(new BigDecimal(buy.path("units").asText()).longValueExact()).isPositive();assertThat(new BigDecimal(buy.path("amount").asText())).isLessThan(new BigDecimal("100000"));assertThat(buy.path("receipts")).isNotEmpty();}else{JsonNode sell=legs.get(1);assertThat(sell.path("action").asText()).isEqualTo("SELL");assertThat(sell.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");assertThat(sell.path("units").asText()).isEqualTo("1000");}for(JsonNode leg:legs)assertThat(leg.path("market").asText()).isEqualTo("台股");}
    }

    @Test void fallbackTwelveMonthPricesCostsProbabilitiesAndBoundaryRankAreCalculated()throws Exception{
        ObjectNode item=usFullItem();item.put("qualification_path","FALLBACK");item.putObject("fallback_evidence").put("global_top10_market_cap",true).put("us_tech_giant",true).put("twelve_month_base_net_return_pct","20").put("twelve_month_upside_to_downside","3");ObjectNode e=(ObjectNode)item.path("full_evidence");e.put("radarListHit",false);ObjectNode fallback=e.putObject("fallback");fallback.put("verified",true).put("independentRankSources",2).put("rankDate","2026-09-25").put("globalMarketCapRank",11).put("boundary10And11Only",true).put("usRegistered",true).put("usPrimarilyOperated",true).put("coreTechBusiness",true).put("threeScenariosVerified",true).put("horizonMonths",12).put("scenarioBasis","ISOLATED_AUDITED_SCENARIOS").put("basePrice","125").put("pessimisticPrice","95").put("optimisticPrice","140").put("baseProbability","0.5").put("pessimisticProbability","0.2").put("optimisticProbability","0.3");JsonNode pass=SrppReportShortUsCalculator.usFull(item,"2026-09-25",Instant.parse("2026-09-25T14:00:00Z"),new BigDecimal("20000000"),7,model());assertThat(pass.path("status").asText()).isEqualTo("PASS_FOR_REPORT_REVIEW");assertThat(pass.path("market_cap_boundary").asText()).isEqualTo("MARKET_CAP_BOUNDARY_10_11");assertThat(new BigDecimal(pass.path("twelve_month_upside_to_downside").asText())).isGreaterThanOrEqualTo(new BigDecimal("2"));fallback.put("optimisticProbability","0.4");JsonNode invalid=SrppReportShortUsCalculator.usFull(item,"2026-09-25",Instant.parse("2026-09-25T14:00:00Z"),new BigDecimal("20000000"),7,model());assertThat(invalid.path("status").asText()).isEqualTo("NO_EXECUTABLE_ORDER");assertThat(invalid.path("units").asInt()).isZero();}

    private static ObjectNode d216Model() throws Exception {
        ObjectNode value = model().deepCopy();
        ((ObjectNode)value.path("execution_policy").path("short_term_opportunity_d171").path("limits"))
                .put("combined_symbol_cap_mode", "CORE_TARGET_PLUS_SLEEVE");
        return value;
    }

    private ObjectNode coreAtTargetBundle(String code, String eachHoldingValue) throws Exception {
        ObjectNode value = golden().path("cases").get(0).path("input").deepCopy();
        for (JsonNode row : value.path("assets").path("snapshot").path("stocks"))
            ((ObjectNode)row).put("stockCode", code);
        for (JsonNode row : value.path("assets").path("liveAssets").path("stocks"))
            ((ObjectNode)row).put("stockCode", code).put("liveValue", eachHoldingValue);
        ObjectNode item = (ObjectNode)value.path("items").get(0);
        item.put("stock_code", code);
        ((ObjectNode)item.path("quote")).put("stockCode", code);
        ((ObjectNode)item.path("quote").path("dividendHistory")).put("stockCode", code);
        ((ObjectNode)item.path("radar").path("stock")).put("stockCode", code);
        return value;
    }

    @Test void d216CoreAtTargetCanUseItsSeparateSmallEquitySleeve() throws Exception {
        ObjectNode current = d216Model();
        for (String code : new String[]{"0050", "00881"}) {
            ObjectNode bundle = coreAtTargetBundle(code, code.equals("0050") ? "1000000" : "453000");
            JsonNode actual = SrppReportShortUsCalculator.screen(bundle, current, "short_term_opportunity_d171");
            JsonNode row = actual.path("results").get(0), sleeve = actual.path("sleeve");
            assertThat(sleeve.path("status").asText()).isEqualTo("VERIFIED");
            assertThat(row.path("reasons")).isEmpty();
            assertThat(row.path("share_ceiling_before_funding").asLong()).isPositive();
            BigDecimal denominator = new BigDecimal(sleeve.path("capacity_denominator_twd").asText());
            BigDecimal budget = new BigDecimal(row.path("buy_budget_ceiling_before_funding_twd").asText());
            assertThat(budget).isLessThanOrEqualTo(denominator.multiply(new BigDecimal(".005")));
            BigDecimal held = new BigDecimal(row.path("existing_value_twd").asText());
            BigDecimal price = new BigDecimal(row.path("analysis_price").asText());
            BigDecimal cap = SrppReportShortUsCalculator.combinedSymbolCap(current,
                    current.path("execution_policy").path("short_term_opportunity_d171"), code);
            assertThat(held.add(price.multiply(BigDecimal.valueOf(row.path("share_ceiling_before_funding").asLong()))))
                    .isLessThanOrEqualTo(cap.multiply(denominator));
        }
        ObjectNode legacy = d216Model();
        ((ObjectNode)legacy.path("execution_policy").path("short_term_opportunity_d171").path("limits"))
                .remove("combined_symbol_cap_mode");
        JsonNode blocked = SrppReportShortUsCalculator.screen(coreAtTargetBundle("0050", "1000000"), legacy,
                "short_term_opportunity_d171");
        assertThat(blocked.path("results").get(0).path("share_ceiling_before_funding").asLong()).isZero();
        assertThat(blocked.path("results").get(0).path("reasons").toString()).contains("SHORT_TERM_CAPACITY_EXHAUSTED");
    }

    @Test void d216CombinedCapKeepsBondLegacyAndInvalidPolicyClosed() throws Exception {
        ObjectNode current = d216Model();
        JsonNode equity = current.path("execution_policy").path("short_term_opportunity_d171");
        assertThat(SrppReportShortUsCalculator.combinedSymbolCap(current, equity, "0050")).isEqualByComparingTo(".125");
        assertThat(SrppReportShortUsCalculator.combinedSymbolCap(current, equity, "00881")).isEqualByComparingTo(".0703");
        assertThat(SrppReportShortUsCalculator.combinedSymbolCap(current, equity, "0056")).isEqualByComparingTo(".05");
        JsonNode bond = current.path("execution_policy").path("bond_short_term_opportunity_d185");
        assertThat(SrppReportShortUsCalculator.combinedSymbolCap(current, bond, "00697B")).isEqualByComparingTo(".03");
        ObjectNode invalidBond = bond.deepCopy();
        ((ObjectNode)invalidBond.path("limits")).put("combined_symbol_cap_mode", "CORE_TARGET_PLUS_SLEEVE");
        assertThatThrownBy(() -> SrppReportShortUsCalculator.combinedSymbolCap(current, invalidBond, "00697B"))
                .hasMessage("D216_COMBINED_CAP_POLICY_UNVERIFIED");
        ((ObjectNode)current.path("detailed_target_allocation_2027")).remove("0050");
        assertThatThrownBy(() -> SrppReportShortUsCalculator.combinedSymbolCap(current, equity, "0050"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void d216NewCapDoesNotReleaseUnknownAttributionOrOpposingRadar() throws Exception {
        ObjectNode current = d216Model(), bundle = coreAtTargetBundle("0050", "1000000");
        ((ObjectNode)bundle.path("sleeve_evidence")).put("pending_orders_verified", false);
        JsonNode unknown = SrppReportShortUsCalculator.screen(bundle, current, "short_term_opportunity_d171");
        assertThat(unknown.path("results").get(0).path("share_ceiling_before_funding").asLong()).isZero();
        bundle = coreAtTargetBundle("0050", "1000000");
        ((ObjectNode)bundle.path("items").get(0).path("radar").path("stock")).put("action", "WAIT");
        JsonNode wait = SrppReportShortUsCalculator.screen(bundle, current, "short_term_opportunity_d171");
        assertThat(wait.path("results").get(0).path("share_ceiling_before_funding").asLong()).isZero();
        assertThat(wait.path("results").get(0).path("reasons").toString()).contains("RADAR_WAIT_OR_CONFLICT");
    }

    @Test void d216FinalExecutableRepriceUsesTheSameCapAndKeepsDailyRiskGates() throws Exception {
        ObjectNode current = d216Model(), bundle = coreAtTargetBundle("0050", "1000000");
        var base = SrppReportStateTopupCalculatorTest.fullFixture();
        String at = base.frozen().facts().capturedAt().toString();
        bundle.put("as_of", at).put("policyKey", "short_term_opportunity_d171");
        ((ObjectNode)bundle.path("assets")).put("generatedAt", at);
        ((ObjectNode)bundle.path("sleeve_evidence")).put("fetched_at", at);
        ((ObjectNode)bundle.path("items").get(0).path("radar")).put("generatedAt", at);
        bundle.putObject("funding").put("verified", true).put("availableTwd", "1000000");
        ObjectNode gates = bundle.putObject("executionGates").putObject("0050");
        for (String key : new String[]{"portfolio", "group", "protectedCash", "deduplication", "intraday"})
            gates.putObject(key).put("verified", true).put("pass", true);
        gates.putObject("executionAccount").put("verified", true).put("ownerUserId", 7)
                .put("broker", "國泰證券").put("subaccount", "SHORT_TERM_D173");
        ObjectNode input = base.frozen().input();
        ((ObjectNode)input.path("sources")).set("assets", bundle.path("assets"));
        var frozen = new com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen(
                base.frozen().facts(), input, base.frozen().sourceVector(), 15, at);
        ObjectNode extra = M.createObjectNode(), source = extra.putObject("short-sleeve");
        source.put("verified", true).put("ownerUserId", 7).putArray("bundles").add(bundle);
        ObjectNode body = extra.putObject("market:台股:0050");
        body.put("symbol", "0050").put("market", "台股").put("volumeUnit", "SHARES");
        ObjectNode quote = bundle.path("items").get(0).path("quote").deepCopy();
        quote.put("quoteStatus", "LIVE").put("closed", false).put("updatedAt", at)
                .put("previousClose", "49").put("volume", 2000000);
        body.set("quote", quote);
        ObjectNode book = body.putObject("book");
        book.put("available", true).put("supported", true).put("stockCode", "0050").put("market", "台股")
                .put("marketStatus", "OPEN").put("source", "FUBON_BOOKS").put("sourceTime", at).put("fetchedAt", at);
        ArrayNode levels = book.putArray("levels");
        for (int level = 1; level <= 5; level++) levels.addObject().put("level", level)
                .put("bidPrice", new BigDecimal("50").subtract(new BigDecimal(".1").multiply(BigDecimal.valueOf(level))).toPlainString())
                .put("askPrice", new BigDecimal("50").add(new BigDecimal(".1").multiply(BigDecimal.valueOf(level))).toPlainString())
                .put("bidVolumeLots", 100).put("askVolumeLots", 100);
        JsonNode screening = SrppReportShortUsCalculator.screen(bundle, current, "short_term_opportunity_d171");
        JsonNode result = SrppReportShortUsCalculator.calculate(frozen, current, extra);
        JsonNode leg = result.path("data").path("legs").get(0);
        assertThat(leg.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
        assertThat(leg.path("units").asLong()).isPositive()
                .isLessThan(screening.path("results").get(0).path("share_ceiling_before_funding").asLong());
        BigDecimal after = new BigDecimal("2000000").add(new BigDecimal(leg.path("amount").asText()));
        BigDecimal denominator = new BigDecimal(screening.path("sleeve").path("capacity_denominator_twd").asText());
        assertThat(after).isLessThanOrEqualTo(denominator.multiply(new BigDecimal(".105")));
        ((ObjectNode)gates.path("portfolio")).put("pass", false);
        JsonNode blocked = SrppReportShortUsCalculator.calculate(frozen, current, extra).path("data").path("legs").get(0);
        assertThat(blocked.path("units").asLong()).isZero();
        assertThat(blocked.path("blockingReasons").toString()).contains("SHORT_DAILY_GATE_BLOCKED:portfolio");
    }

}
