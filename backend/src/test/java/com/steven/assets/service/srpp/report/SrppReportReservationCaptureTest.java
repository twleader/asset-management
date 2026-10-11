package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.*;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrppReportReservationCaptureTest {
    static final LocalDate DATE=LocalDate.of(2026,10,8);
    @Test void legacyOnlyPriorWeekAndPolicyRecommendationsAreReservedOnceWithExactSavedFeeRate(){
        var legacy=mock(SrppDecisionRepository.class);var reports=mock(SrppReportRepository.class);var run=legacyRun(true);
        when(legacy.unlinkedReservations(7,DATE,"12:00")).thenReturn(List.of(run));var source=new SrppReportReservationCapture(legacy,reports).readReportSource(7,DATE,"12:00","b".repeat(64));
        assertThat(source.path("status").asText()).isEqualTo("VERIFIED");assertThat(source.path("rows")).hasSize(1);var row=source.path("rows").get(0);
        assertThat(row.path("origin").asText()).isEqualTo("DAILY_DECISION");assertThat(row.path("decisionRunId").asText()).isEqualTo(run.id().toString());assertThat(row.path("policyBundleSha256").asText()).isEqualTo("a".repeat(64));assertThat(row.path("tradingDate").asText()).isEqualTo(DATE.minusWeeks(2).toString());assertThat(row.path("amount").asText()).isEqualTo("10000");assertThat(row.path("fees").asText()).isEqualTo("14.25");assertThat(row.path("linkedFillId").isNull()).isTrue();
        verify(legacy,never()).reservations(anyLong(),any(),any());
    }
    @Test void missingOriginalFeeAuthorityKeepsUnlinkedPrincipalButBlocksVerifiedCapacity(){
        var legacy=mock(SrppDecisionRepository.class);when(legacy.unlinkedReservations(7,DATE,"12:00")).thenReturn(List.of(legacyRun(false)));var source=new SrppReportReservationCapture(legacy,mock(SrppReportRepository.class)).readReportSource(7,DATE,"12:00","b".repeat(64));
        assertThat(source.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(source.path("rows")).hasSize(1);assertThat(source.path("rows").get(0).path("amount").asText()).isEqualTo("10000");assertThat(source.path("rows").get(0).path("fees").isNull()).isTrue();assertThat(source.path("missing")).hasSize(1);
    }
    static SrppDecisionRepository.Run legacyRun(boolean feeAvailable){
        UUID id=UUID.randomUUID();LocalDate date=DATE.minusWeeks(2);ObjectNode input=JsonNodeFactory.instance.objectNode().put("ownerUserId",7).put("tradingDate",date.toString()).put("slot","09:05");ObjectNode policy=input.putObject("sources").putObject("policy").put("bundleHash","a".repeat(64));if(feeAvailable)policy.putObject("typedPolicy").put("feeRate","0.001425");String inputJcs=SrppJcs.canonicalize(input),inputHash=SrppJcs.sha256Hex(inputJcs);
        ObjectNode content=JsonNodeFactory.instance.objectNode().put("status","FINAL").put("decisionRunId",id.toString());content.putObject("identity").put("ownerUserId",7).put("tradingDate",date.toString()).put("slot","09:05").put("policyBundleSha256","a".repeat(64)).put("swaggerSha256","c".repeat(64));content.putObject("inputSnapshot").put("inputBundleJcs",inputJcs).put("inputSnapshotSha256",inputHash);content.putObject("decision").putArray("candidates").addObject().put("status","ACTIONABLE_FOR_REPORT").put("symbol","00719B").put("strategy","STRATEGIC_FUBON").put("action","BUY").put("lots",1).put("amountTwd","10000");String contentHash=SrppJcs.hash(content);content.put("decisionContentSha256",contentHash);
        return new SrppDecisionRepository.Run(id,7,date,"09:05","a".repeat(64),"c".repeat(64),inputJcs,inputHash,SrppJcs.canonicalize(content),contentHash,Instant.EPOCH,Instant.EPOCH);
    }
}
