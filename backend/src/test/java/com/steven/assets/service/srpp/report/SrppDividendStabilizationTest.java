package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SrppDividendStabilizationTest {
    private static final ObjectMapper J=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test void matchesOriginalPythonNeutralGoldenExactly() throws Exception {
        JsonNode golden=J.readTree(getClass().getResourceAsStream("/srpp/dividend-stabilization-golden.json"));int count=0;
        for(JsonNode c:golden.path("cases")) {
            JsonNode actual="observe".equals(c.path("kind").asText())?
                SrppDividendStabilization.observe(c.path("fact"),golden.path("config")):
                SrppDividendStabilization.evaluate(c.path("fact"),c.path("state"),golden.path("config"));
            assertThat(actual).as(c.path("name").asText()).isEqualTo(c.path("expected"));count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(12);
    }

    private static ObjectNode currentModel() throws Exception {
        ObjectNode model=SrppReportStateTopupCalculatorTest.model().deepCopy();
        model.withObject("execution_policy").set("high_dividend_stabilization_d218",SrppDividendStabilization.config());return model;
    }
    private static DecisionInputCapturePort.Frozen rehash(SrppReportModuleCalculatorTest.FrozenFixture base,ObjectNode sources) {
        ObjectNode input=base.frozen().input().deepCopy(),vector=base.frozen().sourceVector().deepCopy();input.set("sources",sources);
        sources.fields().forEachRemaining(e->{ObjectNode v=(ObjectNode)vector.path(e.getKey());v.put("sha256",SrppJcs.hash(e.getValue()));});
        return new DecisionInputCapturePort.Frozen(base.frozen().facts(),input,vector,base.frozen().assetSnapshotId(),base.frozen().assetGeneratedAt());
    }
    private static JsonNode fact(JsonNode module,String name,String code) {
        for(JsonNode f:module.path("data").path("facts"))if(name.equals(f.path("name").asText())&&code.equals(f.path("subject").asText()))return f;
        throw new AssertionError("missing fact "+name+":"+code);
    }
    private static JsonNode leg(JsonNode module,String code) {
        for(JsonNode l:module.path("data").path("legs"))if(code.equals(l.path("symbol").asText()))return l;
        throw new AssertionError("missing leg "+code);
    }
    @Test void completeMarketObservationSurvivesMissingWaveAndZeroPriceTier() throws Exception {
        var base=SrppReportStateTopupCalculatorTest.fullFixture();ObjectNode sources=base.frozen().input().path("sources").deepCopy();
        ((ObjectNode)sources.path("topup-state")).remove("stabilization");
        ((ObjectNode)sources.path("market:台股:0056").path("quote")).put("price","100");
        var frozen=rehash(base,sources);JsonNode module=SrppReportModuleCalculator.calculateShared(frozen,base.policy(),currentModel(),base.registered(),sources).get(6);
        assertThat(new BigDecimal(fact(module,"d218ObservedNoNewLowSessions","0056").path("value").asText())).isGreaterThanOrEqualTo(new BigDecimal("2"));
        assertThat(fact(module,"d218Stabilized","0056").path("value").isNull()).isTrue();
        assertThat(fact(module,"d218BudgetCeiling","0056").path("value").isNull()).isTrue();
        assertThat(module.path("receipts").toString()).contains("STABILIZATION_STATE_UNAVAILABLE");
        assertThat(leg(module,"0056").path("lots").asInt()).isZero();
    }
    @Test void formalTopupUsesD218BudgetAndSealedLegReceipt() throws Exception {
        var base=SrppReportStateTopupCalculatorTest.fullFixture();JsonNode module=SrppReportModuleCalculator.calculateShared(base.frozen(),base.policy(),currentModel(),base.registered(),base.frozen().input().path("sources")).get(6);
        JsonNode selected=leg(module,"0056");assertThat(selected.path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
        JsonNode gate=java.util.stream.StreamSupport.stream(selected.path("receipts").spliterator(),false).filter(r->"D218_STABILIZATION".equals(r.path("ruleId").asText())).findFirst().orElseThrow();
        BigDecimal budget=new BigDecimal(gate.path("values").get(0).path("value").asText());
        assertThat(new BigDecimal(selected.path("amount").asText()).add(new BigDecimal(selected.path("fees").asText()))).isLessThanOrEqualTo(budget);
        assertThat(module.path("scope").toString()).contains("D198_D218_FULL_QUOTA_TARGET_GROUP_FUNDING_DEPTH_AND_D189");
    }
    @Test void budgetSmallerThanOneLotCannotBeRoundedUpAndWaitCannotBeGuessedAway() throws Exception {
        var base=SrppReportStateTopupCalculatorTest.fullFixture();ObjectNode sources=base.frozen().input().path("sources").deepCopy();
        ((ObjectNode)sources.path("topup-state").path("stabilization").path("0056")).put("budget_twd","500000");
        var frozen=rehash(base,sources);JsonNode module=SrppReportModuleCalculator.calculateShared(frozen,base.policy(),currentModel(),base.registered(),sources).get(6);
        assertThat(leg(module,"0056").path("lots").asInt()).isZero();assertThat(leg(module,"0056").path("blockingReasons").toString()).contains("D218_STAGE_BUDGET_EXHAUSTED");
        ((ObjectNode)sources.path("market:台股:00878").path("radar").path("stock")).put("action","WAIT");
        frozen=rehash(base,sources);module=SrppReportModuleCalculator.calculateShared(frozen,base.policy(),currentModel(),base.registered(),sources).get(6);
        assertThat(leg(module,"00878").path("blockingReasons").toString()).contains("RADAR_WAIT_CAUSE_NOT_VERIFIABLE");
    }
    @Test void ownerOrSealedStateMismatchNeverGrantsAStage() throws Exception {
        var base=SrppReportStateTopupCalculatorTest.fullFixture();ObjectNode sources=base.frozen().input().path("sources").deepCopy();
        ((ObjectNode)sources.path("topup-state")).put("ownerUserId",999);
        var frozen=rehash(base,sources);JsonNode module=SrppReportModuleCalculator.calculateShared(frozen,base.policy(),currentModel(),base.registered(),sources).get(6);
        assertThat(leg(module,"0056").path("lots").asInt()).isZero();assertThat(fact(module,"d218BudgetCeiling","0056").path("quality").asText()).isEqualTo("UNAVAILABLE");
    }
    @Test void exactOneLotBudgetIncludesFeeAndOneCentShortCannotBuy() throws Exception {
        var base=SrppReportStateTopupCalculatorTest.fullFixture();
        for(String budget:List.of("901282.50","901282.40")){
            ObjectNode sources=base.frozen().input().path("sources").deepCopy();
            ((ObjectNode)sources.path("topup-state").path("stabilization").path("0056")).put("budget_twd",budget);
            var frozen=rehash(base,sources);JsonNode module=SrppReportModuleCalculator.calculateShared(frozen,base.policy(),currentModel(),base.registered(),sources).get(6);
            assertThat(leg(module,"0056").path("lots").asInt()).as(budget).isEqualTo(budget.equals("901282.50")?1:0);
            if(budget.equals("901282.50"))assertThat(new BigDecimal(leg(module,"0056").path("fees").asText())).isEqualByComparingTo("128.25");
        }
    }
    @Test void missingCalendarRowNeverBecomesAConsecutiveSession() throws Exception {
        var base=SrppReportStateTopupCalculatorTest.fullFixture();ObjectNode body=base.frozen().input().path("sources").path("market:台股:0056").deepCopy();
        ((ArrayNode)body.path("daily")).remove(body.path("daily").size()-2);
        assertThatThrownBy(()->SrppReportModuleCalculator.stabilizationFact(body,LocalDate.of(2026,9,22),null)).hasMessage("D218_COMPLETED_ROWS_UNVERIFIED");
    }
}
