package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.*;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SrppReportProducerSchedulerTest {
    MarketDataService calendar;SrppPolicyRegistryService registry;PublishedSwaggerIdentity swagger;AppUserRepository users;AssetSnapshotRepository snapshots;DecisionInputCapturePort capture;SrppReportExtraCapturePort extras;SrppReportConsumerStatePort states;SrppReportReservationCapture reservations;SrppReportPublisher publisher;SrppReportRepository reports;PlatformTransactionManager manager;
    @BeforeEach void setup(){calendar=mock(MarketDataService.class);registry=mock(SrppPolicyRegistryService.class);swagger=mock(PublishedSwaggerIdentity.class);users=mock(AppUserRepository.class);snapshots=mock(AssetSnapshotRepository.class);capture=mock(DecisionInputCapturePort.class);extras=mock(SrppReportExtraCapturePort.class);states=new UnavailableSrppReportConsumerState();reservations=mock(SrppReportReservationCapture.class);publisher=mock(SrppReportPublisher.class);reports=mock(SrppReportRepository.class);manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true));when(registry.find(supported().bundleHash())).thenReturn(Optional.of(supported()));when(swagger.sha256()).thenReturn(Optional.of(SWAGGER));AppUser user=mock(AppUser.class);when(user.getId()).thenReturn(7L);when(user.isActive()).thenReturn(true);when(users.findByStatus("ACTIVE")).thenReturn(List.of(user));when(snapshots.findDistinctOwnerUserIds()).thenReturn(List.of(7L,8L));when(reservations.readReportSource(anyLong(),any(),any(),any())).thenReturn(reportReservations(7));when(capture.capture(eq(7L),any(),any())).thenReturn(frozen(7,AT,"09:05","v1"));when(extras.capture(anyLong(),any(),any(),any(),any(),any(),any())).thenReturn(new SrppReportExtraCapturePort.ExtraCapture(JsonNodeFactory.instance.objectNode(),JsonNodeFactory.instance.objectNode()));}
    SrppReportProducerScheduler at(Instant at){return new SrppReportProducerScheduler(calendar,registry,swagger,AUTHORITY,users,snapshots,capture,extras,states,reservations,publisher,reports,manager,Clock.fixed(at,ZoneOffset.UTC));}
    @Test void halfOpenWindowsHaveActualNoonAndNeverLegacy1140(){assertThat(SrppReportProducerScheduler.slotFor(LocalTime.of(9,4,59))).isNull();assertThat(SrppReportProducerScheduler.slotFor(LocalTime.of(9,5))).isEqualTo("09:05");assertThat(SrppReportProducerScheduler.slotFor(LocalTime.of(11,59,59))).isEqualTo("09:05");assertThat(SrppReportProducerScheduler.slotFor(LocalTime.NOON)).isEqualTo("12:00");assertThat(SrppReportProducerScheduler.slotFor(LocalTime.of(13,59,59))).isEqualTo("12:00");assertThat(SrppReportProducerScheduler.slotFor(LocalTime.of(14,0))).isNull();}
    @Test void cachedCalendarGateStopsAllProducerSourceAndDatabaseWork(){when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());at(AT).produce();when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(false));at(AT).produce();verifyNoInteractions(registry,swagger,users,snapshots,capture,extras,reservations,publisher,reports);}
    @Test void restartAndLateL0RecoveryRetryCurrentWindowWithOneL0PerTick(){when(capture.capture(eq(7L),any(),any())).thenThrow(new IllegalStateException("temporary L0 gap")).thenReturn(frozen(7,AT,"09:05","v1"));var worker=at(AT.plusSeconds(30));worker.restartTick();verifyNoInteractions(publisher);worker.produce();verify(capture,times(2)).capture(eq(7L),any(),any());verify(extras,times(1)).capture(eq(7L),eq(DATE),eq("09:05"),any(),any(),any(),any());verify(publisher,times(1)).publish(any(),any(),any(),any(),any(),any());verify(capture,never()).capture(eq(8L),any(),any());var order=inOrder(reports,reservations,capture);order.verify(reports).lockOwnerDate(7,DATE);order.verify(reservations).read(7,DATE,"09:05");order.verify(capture).capture(eq(7L),any(),any());}
    @Test void noonRestartCapturesOnlyNoonSlot(){Instant noon=DATE.atTime(12,0).atZone(ZoneId.of("Asia/Taipei")).toInstant();when(capture.capture(eq(7L),any(),any())).thenReturn(frozen(7,noon,"12:00","v1"));at(noon.plusSeconds(1)).restartTick();verify(capture).capture(eq(7L),argThat(q->q.slot().equals("12:00")),any());verify(reservations).read(7,DATE,"12:00");}
    @Test void sourcesFreezeAfterAllExtraReadsAndFactsFrameMovesTogether(){var original=frozen(7,AT,"09:05","v1");Instant after=AT.plusSeconds(20);var result=SrppReportProducerScheduler.finishCapture(original,JsonNodeFactory.instance.objectNode(),JsonNodeFactory.instance.objectNode(),after);assertThat(result.facts().capturedAt()).isEqualTo(after);assertThat(result.input().path("capturedAt").asText()).isEqualTo(after.toString());assertThat(result.input().path("facts").path("capturedAt").asText()).isEqualTo(after.toString());assertThat(result.input().path("sources").path("assets")).isEqualTo(original.input().path("sources").path("assets"));}
    static Instant evening(){return DATE.atTime(23,15).atZone(ZoneId.of("Asia/Taipei")).toInstant();}
    static SrppReportRepository.Scope noonScope(String swaggerHash){return new SrppReportRepository.Scope(7,DATE,"12:00",supported().bundleHash(),swaggerHash,SrppReportModuleCalculator.VERSION);}
    static SrppReportRepository.Package saved(SrppReportRepository.Scope scope,String consumer){return new SrppReportRepository.Package(UUID.randomUUID(),UUID.randomUUID(),scope,consumer,"a".repeat(64),"b".repeat(64),"{}","c".repeat(64),evening());}
    @Test void beforeOpenAndNextDayNeverBackfillPriorTradingDate(){
        at(DATE.atTime(9,4,59).atZone(ZoneId.of("Asia/Taipei")).toInstant()).produce();
        when(calendar.isTwTradingDayCachedOnly(DATE.plusDays(1))).thenReturn(Optional.of(true));
        at(DATE.plusDays(1).atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant()).produce();
        verifyNoInteractions(registry,swagger,users,snapshots,capture,extras,reservations,publisher,reports);
    }
    @Test void afterCloseUnknownAndClosedCalendarStopBeforeAnySources(){
        when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());at(evening()).produce();
        when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(false));at(evening()).produce();
        verifyNoInteractions(registry,swagger,users,snapshots,capture,extras,reservations,publisher,reports);
    }
    @Test void fourteenBoundaryCapturesMissingNoonScopeAtActualClockAndStopsOncePublished(){
        Instant at=DATE.atTime(14,0).atZone(ZoneId.of("Asia/Taipei")).toInstant();var scope=noonScope(SWAGGER);
        when(capture.capture(eq(7L),any(),any())).thenReturn(frozen(7,at,"12:00","close"));
        doAnswer(call->{when(reports.latest(scope,"Codex")).thenReturn(Optional.of(saved(scope,"Codex")));when(reports.latest(scope,"Claude")).thenReturn(Optional.of(saved(scope,"Claude")));return List.of();}).when(publisher).publish(any(),any(),any(),any(),any(),any());
        var worker=at(at);worker.restartTick();worker.produce();worker.restartTick();
        var frozen=org.mockito.ArgumentCaptor.forClass(DecisionInputCapturePort.Frozen.class);verify(publisher).publish(eq(scope),frozen.capture(),any(),any(),any(),eq(at));
        var captured=frozen.getValue();assertThat(captured.facts().capturedAt()).isEqualTo(at);assertThat(captured.input().path("capturedAt").asText()).isEqualTo(at.toString());
        var context=captured.input().path("sources").path("publication-context");assertThat(context.path("mode").asText()).isEqualTo("AFTER_CLOSE_INITIALIZATION");assertThat(context.path("capturedAt").asText()).isEqualTo(at.toString());assertThat(context.path("reportSlot").asText()).isEqualTo("12:00");
        assertThat(captured.sourceVector().path("publication-context").path("sha256").asText()).isEqualTo(com.steven.assets.srpp.SrppJcs.hash(context));verify(capture).capture(eq(7L),argThat(q->q.slot().equals("12:00")&&q.date().equals(DATE)),any());
    }
    @Test void afterCloseBothConsumersReadyAvoidLockAndCapture(){
        var scope=noonScope(SWAGGER);for(String c:List.of("Codex","Claude"))when(reports.latest(scope,c)).thenReturn(Optional.of(saved(scope,c)));
        at(evening()).produce();verify(reports,never()).lockOwnerDate(anyLong(),any());verifyNoInteractions(capture,extras,reservations,publisher);
    }
    @Test void missingOneConsumerStillUsesRealCaptureInsteadOfRelabellingOtherPackage(){
        var scope=noonScope(SWAGGER);when(reports.latest(scope,"Codex")).thenReturn(Optional.of(saved(scope,"Codex")));
        when(capture.capture(eq(7L),any(),any())).thenReturn(frozen(7,evening(),"12:00","close"));at(evening()).produce();
        verify(capture).capture(eq(7L),any(),any());verify(publisher).publish(eq(scope),any(),any(),any(),any(),any());
    }
    @Test void lockRecheckAvoidsWorkWhenAnotherPublisherWins(){
        var scope=noonScope(SWAGGER);for(String c:List.of("Codex","Claude"))when(reports.latest(scope,c)).thenReturn(Optional.empty(),Optional.of(saved(scope,c)));
        at(evening()).produce();verify(reports).lockOwnerDate(7,DATE);verifyNoInteractions(capture,extras,reservations,publisher);
    }
    @Test void failedInitializationHasThreeSourceAttemptsAndNewSwaggerGetsOwnBound(){
        when(capture.capture(eq(7L),any(),any())).thenThrow(new IllegalStateException("L0 gap"));var worker=at(evening());for(int i=0;i<5;i++)worker.produce();
        verify(capture,times(3)).capture(eq(7L),any(),any());verifyNoInteractions(publisher);
        when(swagger.sha256()).thenReturn(Optional.of("c".repeat(64)));worker.produce();verify(capture,times(4)).capture(eq(7L),any(),any());
    }
    @Test void afterCloseContextRefusesPreCloseMorningAndCrossDayCapture(){
        assertThatThrownBy(()->SrppReportProducerScheduler.withAfterCloseContext(frozen(7,AT,"12:00","v"))).isInstanceOf(SrppReportProblem.class);
        assertThatThrownBy(()->SrppReportProducerScheduler.withAfterCloseContext(frozen(7,evening(),"09:05","v"))).isInstanceOf(SrppReportProblem.class);
        assertThatThrownBy(()->SrppReportProducerScheduler.withAfterCloseContext(frozen(7,evening().plus(Duration.ofDays(1)),"12:00","v"))).isInstanceOf(SrppReportProblem.class);
    }

}
