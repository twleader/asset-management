package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.SrppDecisionRepository;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.repository.SrppReportRepository.ReservationRef;
import com.steven.assets.repository.SrppReportRepository.Scope;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SrppReportReservationCacheTest {
    static String content;
    static ReservationRef original;
    @BeforeAll static void fixture()throws Exception{
        var frame=SrppSatelliteBuildFixtures.withSatellite(SrppReportFixtures.qualified("09:05",SrppReportFixtures.AT,true),SrppReportFixtures.AT);
        content=SrppSatelliteBuildFixtures.content(frame,"09:05",SrppSatelliteBuildFixtures.enabledModel());original=refForBody(content);
    }
    static ReservationRef refForBody(String body){
        JsonNode n=SrppJcs.parseStrict(body),i=n.path("identity"),s=n.path("inputSnapshot");
        return new ReservationRef(UUID.fromString(n.path("calculationRunId").asText()),UUID.fromString(n.path("sharedCalculationId").asText()),
                new Scope(i.path("ownerUserId").asLong(),LocalDate.parse(i.path("tradingDate").asText()),i.path("reportSlot").asText(),i.path("policyBundleSha256").asText(),i.path("swaggerSha256").asText(),n.path("authorityRevision").path("calculatorVersion").asText()),
                i.path("consumer").asText(),s.path("sourceVector").path("core-state").path("sha256").asText(),s.path("inputSnapshotSha256").asText(),n.path("contentSha256").asText(),n.path("status").asText(),SrppReportFixtures.AT);
    }
    static ObjectNode emptyProjection(){ObjectNode n=JsonNodeFactory.instance.objectNode();n.putArray("bondCandidates");n.putArray("reportRows");return n;}
    static ReservationRef synthetic(long owner,long id){return new ReservationRef(new UUID(owner,id),new UUID(id,owner),new Scope(owner,LocalDate.of(2026,1,1),"09:05","a".repeat(64),"b".repeat(64),"version"),"Codex","c".repeat(64),"d".repeat(64),"e".repeat(64),"FINAL",Instant.EPOCH);}
    @Test void warmReportAndLegacyWeekReadsShareValidatedProjectionAndDoNotFetchBodyAgain(){
        var reports=mock(SrppReportRepository.class);List<ReservationRef> visible=new ArrayList<>(List.of(original));stubAll(reports,visible);
        doAnswer(c->{Consumer<ReservationRef> visitor=c.getArgument(4);visible.forEach(visitor);return null;}).when(reports).visitPriorReportReservationMetadata(anyLong(),any(),any(),any(),any());
        when(reports.reservationBody(original)).thenReturn(Optional.of(content));var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),reports);
        var first=capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy());assertThat(first.path("rows")).isNotEmpty();
        String expected=SrppJcs.canonicalize(first);((ObjectNode)first.path("rows").get(0)).put("amount","0");
        assertThat(SrppJcs.canonicalize(capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy()))).isEqualTo(expected);
        var weekly=capture.read(7,SrppReportFixtures.DATE,"12:00");assertThat(weekly).hasSize(1);assertThat(weekly.getFirst().path("decision").path("candidates")).isNotEmpty();
        JsonNode report=SrppJcs.parseStrict(content);assertThat(weekly.getFirst().path("identity")).isEqualTo(report.path("identity"));
        verify(reports,times(1)).reservationBody(original);verify(reports,never()).visitUnlinkedReportReservations(anyLong(),any(),any(),any());verify(reports,never()).visitPriorReportReservations(anyLong(),any(),any(),any(),any());
    }
    @Test void missingBodyOrMetadataBodyIdentityMismatchCannotBecomeVerifiedOrCached(){
        var reports=mock(SrppReportRepository.class);List<ReservationRef> refs=new ArrayList<>(List.of(original));stubAll(reports,refs);when(reports.reservationBody(original)).thenReturn(Optional.empty());
        var cache=new SrppReportReservationProjectionCache();var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),reports,cache);
        assertThatThrownBy(()->capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy())).isInstanceOf(SrppReportProblem.class);assertThat(cache.stats().entries()).isZero();
        var forged=new ReservationRef(original.id(),UUID.randomUUID(),original.scope(),original.consumer(),original.stateHash(),original.inputHash(),original.contentHash(),original.status(),original.publishedAt());refs.set(0,forged);when(reports.reservationBody(forged)).thenReturn(Optional.of(content));
        assertThatThrownBy(()->capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy())).isInstanceOf(SrppReportProblem.class);assertThat(cache.stats().entries()).isZero();
    }
    @Test void changedHashesForceBodyRevalidationAndCrossOwnerMetadataFailsClosed(){
        var reports=mock(SrppReportRepository.class);List<ReservationRef> refs=new ArrayList<>(List.of(original));stubAll(reports,refs);when(reports.reservationBody(any())).thenReturn(Optional.of(content));var cache=new SrppReportReservationProjectionCache();var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),reports,cache);
        capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy());
        var changed=new ReservationRef(original.id(),original.sharedId(),original.scope(),original.consumer(),original.stateHash(),original.inputHash(),"0".repeat(64),original.status(),original.publishedAt());refs.set(0,changed);
        assertThatThrownBy(()->capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy())).isInstanceOf(SrppReportProblem.class);verify(reports).reservationBody(changed);assertThat(cache.stats().entries()).isEqualTo(1);
        refs.set(0,new ReservationRef(original.id(),original.sharedId(),new Scope(8,original.scope().date(),original.scope().slot(),original.scope().policy(),original.scope().swagger(),original.scope().calculator()),original.consumer(),original.stateHash(),original.inputHash(),original.contentHash(),original.status(),original.publishedAt()));
        assertThatThrownBy(()->capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy())).isInstanceOf(SrppReportProblem.class);verify(reports,times(2)).reservationBody(any());
    }
    @Test void failedLaterTraversalDoesNotPruneAnEarlierValidatedEntry(){
        var reports=mock(SrppReportRepository.class);List<ReservationRef> refs=new ArrayList<>(List.of(original));stubAll(reports,refs);when(reports.reservationBody(original)).thenReturn(Optional.of(content));var cache=new SrppReportReservationProjectionCache();var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),reports,cache);
        capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy());
        ReservationRef bad=synthetic(7,99);refs.clear();refs.add(bad);when(reports.reservationBody(bad)).thenReturn(Optional.of("{\"bad\":true}"));
        assertThatThrownBy(()->capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy())).isInstanceOf(SrppReportProblem.class);assertThat(cache.stats().entries()).isEqualTo(1);
        refs.clear();capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy());assertThat(cache.stats().entries()).isZero();
    }
    @Test void saturatedCachesNeverEvictHotEntriesOrDropTheLoadedUncachedProjection(){
        var cache=new SrppReportReservationProjectionCache(3,1,10_000);AtomicInteger loads=new AtomicInteger();
        var one=SrppReportReservationProjectionCache.key(synthetic(1,1));var sameOwner=SrppReportReservationProjectionCache.key(synthetic(1,2));var other=SrppReportReservationProjectionCache.key(synthetic(2,1));
        cache.get(one,()->{loads.incrementAndGet();return emptyProjection();});
        ObjectNode projection=emptyProjection();projection.put("uncachedEvidence","retained");
        assertThat(cache.get(sameOwner,()->projection).path("uncachedEvidence").asText()).isEqualTo("retained");
        cache.get(other,SrppReportReservationCacheTest::emptyProjection);cache.get(one,()->{throw new AssertionError("hot entry was evicted");});
        assertThat(cache.stats().entries()).isEqualTo(2);assertThat(cache.stats().admissionRejected()).isEqualTo(1);assertThat(loads).hasValue(1);
        var bytes=new SrppReportReservationProjectionCache(10,10,600);bytes.get(one,()->emptyProjection().put("large","x".repeat(1000)));assertThat(bytes.stats().entries()).isZero();assertThat(bytes.stats().admissionRejected()).isEqualTo(1);
    }
    @Test void saturatedCaptureProcessesAllHistoryButPruningTracksOnlyAdmittedKeys(){
        List<ReservationRef> refs=new ArrayList<>();Map<ReservationRef,String> bodies=new LinkedHashMap<>();
        for(int i=1;i<=24;i++){
            ObjectNode body=(ObjectNode)SrppJcs.parseStrict(content);body.put("calculationRunId",new UUID(7,i).toString()).put("sharedCalculationId",new UUID(8,i).toString());body.remove("contentSha256");body.put("contentSha256",SrppJcs.hash(body));
            String raw=SrppJcs.canonicalize(body);ReservationRef ref=refForBody(raw);refs.add(ref);bodies.put(ref,raw);
        }
        var reports=mock(SrppReportRepository.class);var baselineReports=mock(SrppReportRepository.class);stubAll(reports,refs);stubAll(baselineReports,refs);
        bodies.forEach((ref,raw)->{when(reports.reservationBody(ref)).thenReturn(Optional.of(raw));when(baselineReports.reservationBody(ref)).thenReturn(Optional.of(raw));});
        var cache=spy(new SrppReportReservationProjectionCache(2,2,10_000));List<Set<SrppReportReservationProjectionCache.Key>> prunedWith=new ArrayList<>();List<Integer> initialSizes=new ArrayList<>();
        doAnswer(call->{Set<SrppReportReservationProjectionCache.Key> initial=(Set<SrppReportReservationProjectionCache.Key>)call.callRealMethod();initialSizes.add(initial.size());return initial;}).when(cache).ownerKeys(7);
        doAnswer(call->{Set<SrppReportReservationProjectionCache.Key> missing=call.getArgument(1);prunedWith.add(Set.copyOf(missing));call.callRealMethod();return null;}).when(cache).pruneMissing(eq(7L),any());
        var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),reports,cache);
        var baseline=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),baselineReports).readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy());
        assertThat(baseline.path("rows").size()).isGreaterThan(refs.size());
        for(int round=0;round<2;round++)assertThat(capture.readReportSource(7,SrppReportFixtures.DATE,"12:00",original.scope().policy())).isEqualTo(baseline);
        assertThat(initialSizes).containsExactly(0,2);assertThat(prunedWith).containsExactly(Set.of(),Set.of());assertThat(cache.stats().entries()).isEqualTo(2);assertThat(cache.stats().admissionRejected()).isEqualTo(44);
        verify(reports,times(46)).reservationBody(any());
    }
    @Test void pruningUsesBoundedInitialSnapshotAndPreservesNewAdmissionsAndOtherOwners(){
        var cache=new SrppReportReservationProjectionCache(4,2,10_000);var one=SrppReportReservationProjectionCache.key(synthetic(1,1));var two=SrppReportReservationProjectionCache.key(synthetic(2,1));var newer=SrppReportReservationProjectionCache.key(synthetic(1,2));
        cache.get(one,SrppReportReservationCacheTest::emptyProjection);cache.get(two,SrppReportReservationCacheTest::emptyProjection);Set<SrppReportReservationProjectionCache.Key> missing=cache.ownerKeys(1);assertThat(missing).containsExactly(one);
        cache.get(newer,SrppReportReservationCacheTest::emptyProjection);assertThat(missing).doesNotContain(newer);cache.pruneMissing(1,missing);assertThat(cache.stats().entries()).isEqualTo(2);
        cache.get(two,()->{throw new AssertionError("another owner's entry was pruned");});cache.get(newer,()->{throw new AssertionError("a new admission was pruned");});assertThat(cache.stats().bytes()).isGreaterThan(0);
    }
    @Test void processMemoryProjectionCannotBeMutatedThroughEitherLoaderOrReturnedNodes(){
        var cache=new SrppReportReservationProjectionCache();ObjectNode original=emptyProjection();original.putArray("test").add("verified");var key=SrppReportReservationProjectionCache.key(synthetic(1,1));
        ObjectNode first=cache.get(key,()->original);original.put("tampered",true);((ArrayNode)first.path("test")).removeAll();
        assertThat(cache.get(key,()->{throw new AssertionError("warm body reread");}).path("test").get(0).asText()).isEqualTo("verified");assertThat(cache.get(key,()->original).has("tampered")).isFalse();
    }
    @Test void actualNinetyDayThreeOwnerEmptyProjectionPopulationFitsDefaultBoundAndStaysWarm(){
        var cache=new SrppReportReservationProjectionCache();int count=90*295;
        for(long owner=1;owner<=3;owner++)for(int i=0;i<count;i++)cache.get(SrppReportReservationProjectionCache.key(synthetic(owner,i)),SrppReportReservationCacheTest::emptyProjection);
        assertThat(cache.stats().entries()).isEqualTo(79_650);assertThat(cache.stats().bytes()).isLessThanOrEqualTo(SrppReportReservationProjectionCache.MAX_WEIGHT);assertThat(cache.stats().admissionRejected()).isZero();
        for(long owner=1;owner<=3;owner++)for(int i=0;i<count;i++)cache.get(SrppReportReservationProjectionCache.key(synthetic(owner,i)),()->{throw new AssertionError("full population thrashed");});
        assertThat(cache.stats().hits()).isEqualTo(79_650);
    }
    private static void stubAll(SrppReportRepository reports,List<ReservationRef> visible){doAnswer(call->{Consumer<ReservationRef> visitor=call.getArgument(3);List.copyOf(visible).forEach(visitor);return null;}).when(reports).visitUnlinkedReportReservationMetadata(anyLong(),any(),any(),any());}
}
