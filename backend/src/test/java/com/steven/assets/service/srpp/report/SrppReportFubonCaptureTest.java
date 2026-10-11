package com.steven.assets.service.srpp.report;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.fubon.*;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SrppReportFubonCaptureTest {
    static final LocalDate DATE=LocalDate.of(2026,8,21);static final Instant AT=DATE.atTime(10,0).atZone(ZoneId.of("Asia/Taipei")).toInstant();
    FubonSrppSourceStore store;FubonSyncOwnerPort owners;PersistedSrppReportFubonCapture capture;
    @BeforeEach void setup(){store=mock(FubonSrppSourceStore.class);owners=mock(FubonSyncOwnerPort.class);when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(7L,null));capture=new PersistedSrppReportFubonCapture(store,owners);}
    static ObjectNode fixture(String name)throws Exception{return (ObjectNode)SrppJcs.parseStrict(new ClassPathResource("srpp/fubon-source-"+name+".json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    static FubonSrppSourceStore.Observation row(ObjectNode p,String status,Instant at){return new FubonSrppSourceStore.Observation(UUID.randomUUID(),7,DATE,p.path("accountFingerprint").asText(),at,status,p.path("revision").asText(),p);}
    @Test void originalPythonKnownZeroVerifiedOnlyForSelectedFubonAccount()throws Exception{
        var p=fixture("knownzero");when(store.latest(7,DATE)).thenReturn(Optional.of(row(p,"VERIFIED",AT)));var result=capture.capture(7,DATE,AT.plusSeconds(120));assertThat(result.verified()).isTrue();assertThat(result.pendingFor("00679B","fubon").verified()).isTrue();assertThat(result.pendingFor("00679B","國泰證券").verified()).isFalse();assertThat(result.subaccounts().verified()).isFalse();assertThat(result.source().path("orders")).isEmpty();
    }
    @Test void partialOfficialFillPreservesRawOrderAndBlocksExactUnlinkedCandidate()throws Exception{
        var p=fixture("partial");when(store.latest(7,DATE)).thenReturn(Optional.of(row(p,"VERIFIED",AT)));var result=capture.capture(7,DATE,AT);assertThat(result.verified()).isTrue();assertThat(result.pendingFor("00679B","fubon").reason()).isEqualTo("PENDING_EXACT_LINK_UNAVAILABLE");assertThat(result.source().path("orders").get(0).path("remainingQty").asInt()).isEqualTo(1000);assertThat(result.pendingFor("00719B","fubon").verified()).isTrue();
    }
    @Test void freshnessFutureCrossOwnerAndCrossDayFailClosedWithNoSyncDependency()throws Exception{
        var p=fixture("knownzero");when(store.latest(7,DATE)).thenReturn(Optional.of(row(p,"VERIFIED",AT)));assertThat(capture.capture(7,DATE,AT.plusSeconds(121)).verified()).isFalse();assertThat(capture.capture(7,DATE,AT.minusSeconds(1)).verified()).isFalse();assertThat(capture.capture(8,DATE,AT).verified()).isFalse();verify(store,never()).latest(eq(8L),any());when(store.latest(7,DATE.plusDays(1))).thenReturn(Optional.of(row(p,"VERIFIED",AT)));assertThat(capture.capture(7,DATE.plusDays(1),AT.plus(Duration.ofDays(1))).verified()).isFalse();
    }
    @Test void latestFailureNeverFallsBackToEarlierKnownZero()throws Exception{
        var p=fixture("knownzero");when(store.latest(7,DATE)).thenReturn(Optional.of(row(p,"UNAVAILABLE",AT)));assertThat(capture.capture(7,DATE,AT).verified()).isFalse();verify(store,times(1)).latest(7,DATE);
    }
    @Test void hashTamperingUnknownStatusAndAccountNeverUpgradeToKnownZero()throws Exception{
        for(String field:List.of("revision","status","accountFingerprint")){
            var p=fixture("partial");if(field.equals("status")){((ObjectNode)p.path("orders").get(0)).put("status",9);ObjectNode raw=p.deepCopy();raw.remove("revision");p.put("revision",SrppJcs.hash(raw));}else p.put(field,field.equals("revision")?"a".repeat(64):"b".repeat(24));when(store.latest(7,DATE)).thenReturn(Optional.of(row(p,"VERIFIED",AT)));assertThat(capture.capture(7,DATE,AT).verified()).as(field).isFalse();
        }
    }
    @Test void anyActiveUnlinkedOrderConservativelyBlocksSharedFundingAndRetainsReservations()throws Exception{
        var original=SrppReportFixtures.frozen(7,SrppReportFixtures.AT,"09:05","v1");ObjectNode source=fixture("partial");source.put("ownerUserId",7);source.put("tradingDate",original.facts().date().toString());var result=SrppReportFubonOverlay.apply(original,new SrppReportFubonCapturePort.Capture(source,true));assertThat(result.facts().budget().verified()).isFalse();assertThat(result.facts().budget().reservedFunds()).isEqualTo(original.facts().budget().reservedFunds());assertThat(result.facts().budget().reservedFees()).isEqualTo(original.facts().budget().reservedFees());
    }
    @Test void reportOverlayPreservesLegacyInputAnd00865BCombinedUnknown()throws Exception{
        var original=SrppReportFixtures.qualified("09:05",SrppReportFixtures.AT,false);ObjectNode legacy=original.input();ObjectNode source=fixture("knownzero");source.put("ownerUserId",7);source.put("tradingDate",original.facts().date().toString());source.put("status","VERIFIED");var result=SrppReportFubonOverlay.apply(original,new SrppReportFubonCapturePort.Capture(source,true));assertThat(original.input()).isEqualTo(legacy);assertThat(result.input().path("sources").path("fubon-account").path("ownerUserId").asInt()).isEqualTo(7);assertThat(result.facts().symbols().get("00719B").pending().verified()).isTrue();assertThat(result.facts().symbols().get("00865B").pending().verified()).isFalse();assertThat(result.facts().subaccounts()).isEqualTo(original.facts().subaccounts());assertThat(result.facts().budget()).isEqualTo(original.facts().budget());
    }
}
