package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.service.srpp.report.SrppReportModuleCalculator;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SrppReserveAllocationTest {
    private static final ObjectMapper J=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final LocalDate BASE=LocalDate.of(2026,8,31);
    private static BigDecimal n(String v){return new BigDecimal(v);}
    private static JsonNode example() throws Exception {return J.readTree(Files.readString(Path.of("../spec/fixtures/srpp_d222_reserve_document_example.json")));}
    static ObjectNode model() throws Exception {
        ObjectNode model=(ObjectNode)J.readTree(new DecisionPolicyAuthority().modelBytes());
        ObjectNode reserve=example().path("reserveAllocation").deepCopy();
        for(String key:List.of("annual_inflation_rate","living_term_deposit_twd_2026_real","living_demand_deposit_twd_2026_real","emergency_term_deposit_twd_2026_real","emergency_etf_twd_2026_real","reference_cash_weight"))reserve.put(key,n(reserve.path(key).asText()));
        reserve.putObject("ordinary_tw_core_caps").put("0050",n("0.13")).put("00881",n("0.0753"));
        ((ObjectNode)model.path("execution_policy")).set("reserve_allocation_d222",reserve);
        ((ObjectNode)model.path("detailed_target_allocation_2027")).put("00919",n("0.025")).put("009826",n("0.0216666667"));return model;
    }
    @Test void sharedCanonicalV2ExampleAndV1History() throws Exception {
        ObjectNode model=model();ObjectNode targets=model.putObject("detailed_target_allocation_2027");targets.put("TWD_term_deposits",n("0.164")).put("demand_deposits_and_cash",n("0.006")).put("VOO",n("0.83"));
        assertThat(SrppJcs.canonicalize(SrppReportModuleCalculator.registryDocument(model))+"\n").isEqualTo(Files.readString(Path.of("../spec/fixtures/srpp_d222_reserve_document_example.json")));
        assertThat(SrppPolicyDocument.parse(SrppJcs.canonicalize(example())).targets()).hasSize(3);
        assertThat(SrppPolicyDocument.parse("{\"schema\":\"SRPP_POLICY_DOCUMENT_V1\",\"targets\":{\"FUND:A\":\"0.2\"}}").targets()).hasSize(1);
        ObjectNode bad=example().deepCopy();((ObjectNode)bad.path("reserveAllocation")).put("extra",true);
        assertThatThrownBy(()->SrppPolicyDocument.parse(SrppJcs.canonicalize(bad))).hasMessage("D222_RESERVE_POLICY_INVALID");
    }
    @Test void positiveAndNegativeDeltaWithoutMarketAllocationLeaveOnlyThreeUnknown() throws Exception {
        for(String total:List.of("30000000","20000000")){
            var p=SrppReserveAllocation.project(model(),n(total),BASE);
            assertThat(p.status()).isEqualTo("MARKET_ALLOCATION_REQUIRED");
            assertThat(p.weights().get("VOO")).isNull();assertThat(p.weights().get("0050")).isNull();assertThat(p.weights().get("00881")).isNull();
            assertThat(p.weights().get("SGOV")).isEqualByComparingTo("0.02");assertThat(p.weights().get("00919")).isEqualByComparingTo("0.025");
            assertThat(p.weights().get("00865B")).isEqualByComparingTo("0.025");
            assertThat(p.weights().get(SrppReserveAllocation.EMERGENCY).multiply(n(total))).isCloseTo(n("800000"),within(n("0.000001")));
        }
    }
    @Test void evidencedMarketSplitConservesEveryDollarIncludingIndependentEmergencyEtf() throws Exception {
        var add=SrppReserveAllocation.project(model(),n("30000000"),BASE,null,Map.of("VOO",n("750000"),"0050",n("250000"),"00881",n("250000")));
        assertThat(add.status()).isEqualTo("TARGETS_READY");assertThat(add.weights().values().stream().reduce(BigDecimal.ZERO,BigDecimal::add)).isCloseTo(BigDecimal.ONE,within(n("0.00000001")));
        var reduce=SrppReserveAllocation.project(model(),n("20000000"),BASE,Map.of("VOO",n("450000")),null);
        assertThat(reduce.status()).isEqualTo("TARGETS_READY");assertThat(reduce.weights().get("VOO")).isEqualByComparingTo("0.1775");
        assertThat(reduce.weights().get("TWD_term_deposits").multiply(n("20000000"))).isEqualByComparingTo("2300000");
        assertThat(reduce.weights().get("demand_deposits_and_cash").multiply(n("20000000"))).isEqualByComparingTo("750000");
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),n("30000000"),BASE,null,Map.of("0050",n("1250000")))).hasMessage("D222_ORDINARY_CORE_CAP_EXCEEDED");
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),n("20000000"),BASE,Map.of("VT",n("450000")),null)).hasMessage("D222_REDUCTION_ALLOCATION_UNVERIFIED");
    }
    @Test void bearCapacityKeepsThreePercentOfNonEmergencyBondsAndProtectsLivingDemand() throws Exception {
        JsonNode model=model();ObjectNode dynamic=model.path("execution_policy").path("dynamic_allocation_policy_d141").deepCopy();dynamic.put("total_bond_floor",n("0.03")).put("bear_bond_sale_max_share",BigDecimal.ONE);
        ObjectNode live=J.createObjectNode().put("verified",true).put("mode","BEAR");
        JsonNode result=SrppReportModuleCalculator.regimeCapacity(live,dynamic,model.path("execution_policy").path("stock_purchase_funding_policy_d150"),n("20000000"),n("3050000"),n("2000000"),n("10000000"),BigDecimal.ONE);
        assertThat(n(result.path("bondFloor").asText())).isEqualByComparingTo("600000");
        assertThat(n(result.path("bearBondCapacity").asText())).isEqualByComparingTo("1400000");
        assertThat(n(result.path("deployableLiving").asText())).isEqualByComparingTo("1500000");
    }
    private static JsonNode sortedNode(JsonNode node){if(node.isObject()){ObjectNode out=J.createObjectNode();new TreeSet<>(node.properties().stream().map(Map.Entry::getKey).toList()).forEach(k->out.set(k,sortedNode(node.get(k))));return out;}if(node.isArray()){ArrayNode out=J.createArrayNode();node.forEach(v->out.add(sortedNode(v)));return out;}return node.deepCopy();}
    private static ObjectNode wrap(JsonNode payload){ObjectNode out=J.createObjectNode().put("source_ref","saved:test").put("sha256",SrppJcs.sha256Hex(sortedNode(payload).toString()));out.set("payload",payload);return out;}
    private static ObjectNode candidate(String code,int price,String side,int capacity){ObjectNode raw=J.createObjectNode().put("stock_code",code).put("trading_date",BASE.toString()).put("price",price).put("low52",10).put("high52",110).put("adjustment_basis","DISTRIBUTION_ADJUSTED_52_WEEK"),out=J.createObjectNode();out.set("source",wrap(raw));out.put("capacity_twd",capacity);ObjectNode gates=out.putObject("gate_receipts");for(String gate:List.of("FX",side)){ObjectNode receipt=J.createObjectNode().put("stock_code",code).put("trading_date",BASE.toString()).put("scope",gate).put("status","VERIFIED_PASS");receipt.putArray("source_evidence").add(wrap(J.createObjectNode().put("original",true)));gates.set(gate,wrap(receipt));}return out;}
    @Test void ownRangePositionExtremesTiesAndPartialCapacityNeverReflow() throws Exception {
        ArrayNode buys=J.createArrayNode().add(candidate("VOO",20,"BUY",1000000)).add(candidate("0050",50,"BUY",1000000)).add(candidate("00881",80,"BUY",1000000));
        JsonNode selected=SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,buys);
        assertThat(selected.path("selected_symbol").asText()).isEqualTo("VOO");assertThat(selected.path("status").asText()).isEqualTo("PARTIAL_CAPACITY_WAIT");assertThat(selected.path("allocation_twd").size()).isEqualTo(1);assertThat(selected.path("remaining_adjustment_twd").decimalValue()).isEqualByComparingTo("250000");assertThat(selected.path("transaction_authorized").asBoolean()).isFalse();assertThat(selected.path("api_capacity_acceptance").asBoolean()).isFalse();
        ArrayNode sells=J.createArrayNode().add(candidate("VOO",20,"SELL",1000000)).add(candidate("0050",50,"SELL",1000000)).add(candidate("00881",80,"SELL",1000000));
        assertThat(SrppReserveAllocation.selectMarketAdjustment(model(),n("20000000"),BASE,sells).path("selected_symbol").asText()).isEqualTo("00881");
        buys.set(1,candidate("0050",20,"BUY",1000000));assertThat(SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,buys).path("status").asText()).isEqualTo("AMBIGUOUS_TIE");
        buys.remove(2);assertThat(SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,buys).path("reason").asText()).isEqualTo("D222_ALL_THREE_CANDIDATES_REQUIRED");
        buys.add(candidate("00881",80,"BUY",1000000));((ObjectNode)buys.get(0).path("source")).put("sha256","0".repeat(64));assertThat(SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,buys).path("status").asText()).isEqualTo("UNVERIFIED");
    }
    @Test void livingReserveUsesItsOwnLedgerRatherThanSeventeenPercentOfAssets() throws Exception {
        ArrayNode sells=J.createArrayNode().add(candidate("VOO",20,"SELL",1000000)).add(candidate("0050",50,"SELL",1000000)).add(candidate("00881",80,"SELL",1000000));
        JsonNode missing=SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,sells,"LIVING_RESERVE_REBALANCE",null);assertThat(missing.path("reason").asText()).isEqualTo("D222_LIVING_SUBLEDGER_UNAVAILABLE");
        ObjectNode ledger=J.createObjectNode().put("valuation_date",BASE.toString()).put("ledger_scope","LIVING_RESERVE_EXCLUDING_EMERGENCY").put("living_term_twd",1000000).put("living_demand_twd",1000000).put("settled_unrestricted_available_twd",0);ledger.putArray("reconciled_deposit_ids").add("term-1").add("demand-1");
        JsonNode selected=SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,sells,"LIVING_RESERVE_REBALANCE",wrap(ledger));assertThat(selected.path("side").asText()).isEqualTo("SELL");assertThat(selected.path("selected_symbol").asText()).isEqualTo("00881");assertThat(selected.path("allocation_twd").path("00881").decimalValue()).isEqualByComparingTo("250000");
        ArrayNode buys=J.createArrayNode().add(candidate("VOO",20,"BUY",1000000)).add(candidate("0050",50,"BUY",1000000)).add(candidate("00881",80,"BUY",1000000));ledger.put("living_term_twd",2000000).put("living_demand_twd",1000000).put("settled_unrestricted_available_twd",100000);
        JsonNode partial=SrppReserveAllocation.selectMarketAdjustment(model(),n("30000000"),BASE,buys,"LIVING_RESERVE_REBALANCE",wrap(ledger));assertThat(partial.path("allocation_twd").path("VOO").decimalValue()).isEqualByComparingTo("100000");assertThat(partial.path("status").asText()).isEqualTo("PARTIAL_CAPACITY_WAIT");
    }
    @Test void originalPythonSelectorGoldenMatchesJavaSelectionAmountAndStatus() throws Exception {
        JsonNode golden=J.readTree(getClass().getResourceAsStream("/srpp/reserve-allocation-golden.json"));assertThat(golden.path("cases").size()).isEqualTo(18);
        for(JsonNode c:golden.path("cases")){JsonNode actual=SrppReserveAllocation.selectMarketAdjustment(golden.path("model"),c.path("total").decimalValue(),LocalDate.parse(c.path("date").asText()),c.path("candidates"),c.path("mode").asText(),c.path("living").isNull()?null:c.path("living")),expected=c.path("expected");
            for(String key:List.of("status","side","selected_symbol","source_consistency_only","api_capacity_acceptance","transaction_authorized"))assertThat(actual.get(key)).as(c.path("name").asText()+":"+key).isEqualTo(expected.get(key));
            assertThat(actual.path("remaining_adjustment_twd").decimalValue()).as(c.path("name").asText()).isCloseTo(expected.path("remaining_adjustment_twd").decimalValue(),within(n("0.000001")));
            assertThat(actual.path("allocation_twd").size()).isEqualTo(expected.path("allocation_twd").size());for(var e:expected.path("allocation_twd").properties())assertThat(actual.path("allocation_twd").path(e.getKey()).decimalValue()).isCloseTo(e.getValue().decimalValue(),within(n("0.000001")));
        }
    }
    @Test void realMoneyInflationAndInvalidEvidenceAreExplicit() throws Exception {
        var p=SrppReserveAllocation.project(model(),n("30000000"),BASE.plusDays(365));
        assertThat(p.reserve()).isCloseTo(n("3850000").multiply(BigDecimal.valueOf(StrictMath.pow(1.0225,365/365.25))),within(n("0.000001")));
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),n("4000000"),BASE)).hasMessage("D222_INSUFFICIENT_ASSETS");
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),n("6000000"),BASE)).hasMessage("D222_INSUFFICIENT_ADJUSTMENT_CAPACITY");
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),n("1000000"),BASE)).hasMessage("D222_INSUFFICIENT_ASSETS");
        var equal=SrppReserveAllocation.project(model(),n("3850000").divide(n("0.17"),MathContext.DECIMAL128),BASE);assertThat(equal.status()).isEqualTo("TARGETS_READY");
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),BigDecimal.ZERO,BASE)).hasMessage("D222_TOTAL_ASSETS_UNVERIFIED");
        assertThatThrownBy(()->SrppReserveAllocation.project(model(),n("30000000"),BASE.minusDays(1))).hasMessage("D222_DATE_BEFORE_REAL_BASE");
        ObjectNode bad=model();((ObjectNode)bad.path("execution_policy").path("reserve_allocation_d222")).put("excess_requires_explicit_allocation",false);
        assertThatThrownBy(()->SrppReserveAllocation.project(bad,n("30000000"),BASE)).hasMessage("D222_RESERVE_POLICY_INVALID");
    }
}
