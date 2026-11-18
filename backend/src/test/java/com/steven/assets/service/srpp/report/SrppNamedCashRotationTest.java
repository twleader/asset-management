package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.srpp.SrppNamedCashRotation;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort;
import com.steven.assets.service.srpp.decision.DecisionFacts.Input;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SrppNamedCashRotationTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate DATE = LocalDate.parse("2026-11-18");
    private static ObjectNode model() throws Exception {
        ObjectNode model=M.createObjectNode();
        model.putObject("execution_policy").set("named_cash_rotation_d223",M.readTree(SrppNamedCashRotationTest.class.getResourceAsStream("/srpp/d223-named-cash-rotation.json")));
        model.withObject("/execution_policy/short_term_opportunity_d171/ledger").put("strategy","SHORT_TERM_D173");
        return model;
    }
    private static BigDecimal n(String v){return new BigDecimal(v);}

    @Test void buyBoundaryAndUnknownBudgetRemainIndependentOfSellerAndDate() throws Exception {
        for(String price:List.of("37.99","38","38.01")) {
            ObjectNode result=SrppNamedCashRotation.buyObservation(model(),"00881","台股",DATE,n(price));
            assertThat(result.path("priceTriggered").asBoolean()).isEqualTo(price.equals("37.99"));
            assertThat(result.path("totalBudgetTwd").asText()).isEqualTo("400000");
            assertThat(result.path("spentTwd").isNull()).isTrue();
            assertThat(result.path("remainingTwd").isNull()).isTrue();
            assertThat(result.path("capacityAccepted").asBoolean()).isFalse();
            assertThat(SrppNamedCashRotation.observationReason(result)).contains("SPENT_TWD=UNKNOWN","REMAINING_TWD=UNKNOWN","BUY_REQUIRES_PRIOR_SELL=false");
        }
        assertThat(SrppNamedCashRotation.buyObservation(model(),"00881","台股",DATE.plusDays(1),n("37.99")))
            .isEqualTo(SrppNamedCashRotation.buyObservation(model(),"00881","台股",DATE,n("37.99")));
    }

    @Test void historicalOrAbsentPlanPreservesOtherSymbolsAndMalformedPresentPlanFailsClosed() throws Exception {
        assertThat(SrppNamedCashRotation.active(M.createObjectNode(),DATE)).isFalse();
        assertThat(SrppNamedCashRotation.active(model(),DATE.minusDays(1))).isFalse();
        for(String symbol:List.of("0050","2881","2885"))assertThat(SrppNamedCashRotation.buyObservation(model(),symbol,"台股",DATE,n("37.99")).path("applies").asBoolean()).isFalse();
        ObjectNode model=model();model.withObject("/execution_policy/named_cash_rotation_d223").put("enabled",false);
        assertThatThrownBy(()->SrppNamedCashRotation.active(model,DATE)).hasMessage("D223_POLICY_UNVERIFIED");
        ObjectNode tampered=model();tampered.withObject("/execution_policy/named_cash_rotation_d223").put("reset_budget_automatically",true);
        assertThatThrownBy(()->SrppNamedCashRotation.active(tampered,DATE)).hasMessage("D223_POLICY_UNVERIFIED");
    }

    @Test void sellNeedsBothLastAndExecutableBidAbove152AndCannotUpgradeOriginalBlock() throws Exception {
        ObjectNode pass=M.createObjectNode().put("timingPass",true).put("reason","CONFIRMED_REVERSAL_AND_PULLBACK");
        for(String price:List.of("151.99","152","152.01")){
            ObjectNode result=SrppNamedCashRotation.coreOverlay(model(),"2881","台股",DATE,n(price),n(price),pass);
            assertThat(result.path("timingPass").asBoolean()).isEqualTo(price.equals("152.01"));
        }
        assertThat(SrppNamedCashRotation.coreOverlay(model(),"2881","台股",DATE,n("153"),n("152"),pass).path("timingPass").asBoolean()).isFalse();
        ObjectNode original=pass.deepCopy().put("timingPass",false).put("reason","D211_LOW_POINT_PROTECTION");
        ObjectNode blocked=SrppNamedCashRotation.coreOverlay(model(),"2881","台股",DATE,n("153"),n("153"),original);
        assertThat(blocked.path("timingPass").asBoolean()).isFalse();assertThat(blocked.path("reason").asText()).isEqualTo("D211_LOW_POINT_PROTECTION");
        assertThat(pass.path("timingPass").asBoolean()).isTrue();
    }

    @Test void retainedOrdinaryCoreAndHistoricalDateAreScoped() throws Exception {
        ObjectNode pass=M.createObjectNode().put("timingPass",true).put("reason","CONFIRMED_REVERSAL_AND_PULLBACK");
        assertThat(SrppNamedCashRotation.coreOverlay(model(),"2885","台股",DATE,n("70"),n("70"),pass).path("reason").asText()).isEqualTo("D223_2885_RETAIN_ORDINARY_CORE");
        assertThat(SrppNamedCashRotation.coreOverlay(model(),"2891","台股",DATE,n("50"),n("50"),pass)).isEqualTo(pass);
        assertThat(SrppNamedCashRotation.coreOverlay(model(),"2885","台股",DATE.minusDays(1),n("70"),n("70"),pass)).isEqualTo(pass);
    }

    private static ObjectNode module(String id,String strategy,String status,String reason,String limit) {
        ObjectNode module=M.createObjectNode().put("moduleId",id).put("status","COMPLETE");module.putArray("missing");module.putArray("reasonCodes");
        ObjectNode leg=module.putObject("data").putArray("legs").addObject().put("symbol","00881").put("market","台股").put("action","BUY").put("strategy",strategy).put("status",status).put("lots",1).putNull("units").put("limitPrice",limit).put("amount","37990").put("fees","20");
        leg.putArray("receipts");ArrayNode reasons=leg.putArray("blockingReasons");if(reason!=null)reasons.add(reason);return module;
    }

    @Test void productionFinalGateRevokesOrdinaryBuyPreservesIndependentShortAndDoesNotResetOnNextDay() throws Exception {
        for(LocalDate date:List.of(DATE,DATE.plusDays(1))){
            ArrayNode modules=M.createArrayNode().add(module("FUTURE_ORDINARY","","ACTIONABLE_FOR_REPORT",null,"37.99"))
                .add(module("SHORT_TERM_AND_US_TACTICAL","SHORT_TERM_D173","ACTIONABLE_FOR_REPORT",null,"37.99"))
                .add(module("FUTURE_ORDINARY","SHORT_TERM_D173","ACTIONABLE_FOR_REPORT",null,"38"))
                .add(module("FUTURE_ORDINARY","","BLOCKED","ORIGINAL_RISK_BLOCK","37.99"));
            SrppReportModuleCalculator.namedCashRotationFinalGates(modules,model(),date);
            JsonNode ordinary=modules.get(0).path("data").path("legs").get(0);
            assertThat(ordinary.path("status").asText()).isEqualTo("NOT_EVALUATED");assertThat(ordinary.path("action").asText()).isEqualTo("NONE");assertThat(ordinary.path("lots").isNull()).isTrue();assertThat(ordinary.path("units").isNull()).isTrue();assertThat(ordinary.path("amount").isNull()).isTrue();
            assertThat(modules.get(1).path("data").path("legs").get(0).path("status").asText()).isEqualTo("ACTIONABLE_FOR_REPORT");
            assertThat(modules.get(2).path("data").path("legs").get(0).path("blockingReasons").toString()).contains("D223_BUY_LIMIT_NOT_STRICTLY_BELOW_38");
            assertThat(modules.get(3).path("data").path("legs").get(0).path("status").asText()).isEqualTo("BLOCKED");
            assertThat(modules.get(3).path("data").path("legs").get(0).path("blockingReasons").toString()).contains("ORIGINAL_RISK_BLOCK",SrppNamedCashRotation.LEDGER_UNAVAILABLE);
        }
    }

    @Test void productionStrategicOverloadDoesNotPublishPositiveEligibilityWithUnknownPlanBudget() throws Exception {
        JsonNode full=M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json"));
        ObjectNode body=SrppReportModuleCalculatorTest.strategicBody("00881","37.99",null);
        ((ObjectNode)body.path("quote")).put("tradingDate",DATE.toString());
        ObjectNode original=SrppReportModuleCalculator.strategicGate(body,DATE,full.path("execution_policy").path("strategic_buy_high_gate_d188"));
        ObjectNode guarded=SrppReportModuleCalculator.strategicGate(body,DATE,full.path("execution_policy").path("strategic_buy_high_gate_d188"),model());
        assertThat(original.path("eligible").asBoolean()).isTrue();
        assertThat(guarded.path("eligible").isNull()).isTrue();assertThat(guarded.path("reason").asText()).isEqualTo(SrppNamedCashRotation.LEDGER_UNAVAILABLE);
    }

    @Test void productionReportKeepsNamedPlanVisibleWhenQuoteAndLedgerAreUnavailable() throws Exception {
        var fixture=SrppReportModuleCalculatorTest.fixture();Input old=fixture.frozen().facts();
        Input facts=new Input(DATE,old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());
        var frozen=new DecisionInputCapturePort.Frozen(facts,fixture.frozen().input(),fixture.frozen().sourceVector(),1,fixture.frozen().assetGeneratedAt());
        ObjectNode full=(ObjectNode)M.readTree(getClass().getResourceAsStream("/srpp/decision/model_inputs.json"));
        full.withObject("/execution_policy").set("named_cash_rotation_d223",model().path("execution_policy").path("named_cash_rotation_d223"));
        ArrayNode modules=SrppReportModuleCalculator.calculateShared(frozen,fixture.policy(),full,fixture.registered(),M.createObjectNode());
        JsonNode strategic=java.util.stream.StreamSupport.stream(modules.spliterator(),false).filter(v->"STRATEGIC_BUY_GATES".equals(v.path("moduleId").asText())).findFirst().orElseThrow();
        assertThat(strategic.path("receipts").toString()).contains("D223:00881","TOTAL_BUDGET_TWD=400000","SPENT_TWD=UNKNOWN","REMAINING_TWD=UNKNOWN","PRICE_TRIGGER=UNKNOWN");
        assertThat(strategic.path("missing").toString()).contains(SrppNamedCashRotation.LEDGER_UNAVAILABLE+":00881");
    }
}
