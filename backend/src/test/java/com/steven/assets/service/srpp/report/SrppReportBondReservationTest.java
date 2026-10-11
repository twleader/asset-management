package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.srpp.decision.DecisionFacts.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SrppReportBondReservationTest {
    @Test void oldPolicyPreviousWeekStrategicReservationsStillFillEveryIndividualTarget() throws Exception {
        var fixture=SrppReportModuleCalculatorTest.fixture();ObjectNode input=fixture.frozen().input().deepCopy();
        for(String code:fixture.policy().symbols())reserve(input,code,fixture.policy().targets().get(code).multiply(fixture.frozen().facts().totalAssets()));
        var frozen=new DecisionInputCapturePort.Frozen(fixture.frozen().facts(),input,fixture.frozen().sourceVector(),1,fixture.frozen().assetGeneratedAt());
        JsonNode bond=SrppReportModuleCalculator.calculateShared(frozen,fixture.policy(),SrppReportModuleCalculatorTest.model(),fixture.registered(),input.path("sources")).get(5);
        for(JsonNode leg:bond.path("data").path("legs"))if("STRATEGIC_FUBON".equals(leg.path("strategy").asText())){assertThat(leg.path("lots").asInt()).isZero();assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");assertThat(leg.path("receipts")).anySatisfy(r->{assertThat(r.path("ruleId").asText()).isEqualTo("TARGET");assertThat(r.path("status").asText()).isEqualTo("BLOCK");});}
    }
    @Test void previousWeek00697BReservationBelowTargetStillConsumesIntermediateBondShareCap() throws Exception {
        var fixture=SrppReportModuleCalculatorTest.fixture();Input old=fixture.frozen().facts();Map<String,Symbol> symbols=new TreeMap<>(old.symbols());
        for(String code:List.of("00719B","00865B")){Symbol v=symbols.get(code);symbols.put(code,new Symbol(v.code(),v.quote(),v.book(),v.closes(),v.distributions(),v.distributionVerified(),fixture.policy().targets().get(code).multiply(old.totalAssets()),v.position(),v.pending(),v.radar(),v.valuation(),v.tradability(),v.mediumAction(),v.mediumCandidateAction(),v.intraday()));}
        Input facts=new Input(old.date(),old.slot(),old.capturedAt(),old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),new BigDecimal("500000"),old.fx(),old.fxEvidence(),old.bearWindow(),symbols,old.budget(),old.subaccounts(),old.emergencyValue());
        ObjectNode input=fixture.frozen().input().deepCopy();var baseline=new DecisionInputCapturePort.Frozen(facts,input,fixture.frozen().sourceVector(),1,fixture.frozen().assetGeneratedAt());
        JsonNode before=SrppReportModuleCalculator.calculateShared(baseline,fixture.policy(),SrppReportModuleCalculatorTest.model(),fixture.registered(),input.path("sources")).get(5);assertThat(leg(before,"00697B").path("lots").asInt()).isPositive();
        reserve(input,"00697B",new BigDecimal("220000"));assertThat(new BigDecimal("220000")).isLessThan(fixture.policy().targets().get("00697B").multiply(old.totalAssets()));
        var retained=new DecisionInputCapturePort.Frozen(facts,input,fixture.frozen().sourceVector(),1,fixture.frozen().assetGeneratedAt());JsonNode after=SrppReportModuleCalculator.calculateShared(retained,fixture.policy(),SrppReportModuleCalculatorTest.model(),fixture.registered(),input.path("sources")).get(5);JsonNode blocked=leg(after,"00697B");assertThat(blocked.path("lots").asInt()).isZero();assertThat(blocked.path("status").asText()).isEqualTo("BLOCKED");assertThat(blocked.path("receipts")).anySatisfy(r->{assertThat(r.path("ruleId").asText()).isEqualTo("TARGET");assertThat(r.path("status").asText()).isEqualTo("PASS");});assertThat(blocked.path("blockingReasons").toString()).contains("CAPACITY_OR_QUOTA_EXHAUSTED");
    }
    static JsonNode leg(JsonNode module,String symbol){for(JsonNode leg:module.path("data").path("legs"))if(symbol.equals(leg.path("symbol").asText())&&"STRATEGIC_FUBON".equals(leg.path("strategy").asText()))return leg;throw new AssertionError(symbol);}
    static void reserve(ObjectNode input,String code,BigDecimal amount){input.withObject("sources").withObject("report-reservations").withArray("rows").addObject().put("reservationId","prior-"+code).put("origin","REPORT").put("status","ACTIVE").put("tradingDate","2026-09-15").put("policyBundleSha256","0".repeat(64)).put("reportSlot","09:05").put("moduleId","BOND_D130_D195").put("symbol",code).put("market","台股").put("strategy","STRATEGIC_FUBON").put("action","BUY").put("lots",1).put("amount",amount.toPlainString()).put("currency","TWD").put("fees","0").putNull("linkedOrderId").putNull("linkedFillId");}
}
