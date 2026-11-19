package com.steven.assets.integration.fubon;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.service.fubon.*;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FubonSrppSourceSchedulerTest {
    static final LocalDate DATE=LocalDate.of(2026,8,21);static final Instant AT=DATE.atTime(10,0).atZone(ZoneId.of("Asia/Taipei")).toInstant();
    MarketDataService calendar;FubonConfigState config;FubonSyncOwnerPort owners;BrokerRepository brokers;FubonSrppObservationPort adapter;FubonSrppSourceWriter writer;
    @BeforeEach void setup(){calendar=mock(MarketDataService.class);config=mock(FubonConfigState.class);owners=mock(FubonSyncOwnerPort.class);brokers=mock(BrokerRepository.class);adapter=mock(FubonSrppObservationPort.class);writer=mock(FubonSrppSourceWriter.class);when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true));when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY,"test-token",null));when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(7L,null));BrokerEntity b=mock(BrokerEntity.class);when(b.getActive()).thenReturn(true);when(brokers.findByCode("fubon")).thenReturn(Optional.of(b));}
    FubonSrppSourceScheduler at(Instant time){return new FubonSrppSourceScheduler(calendar,config,owners,brokers,adapter,writer,Clock.fixed(time,ZoneOffset.UTC));}
    ObjectNode known()throws Exception{return (ObjectNode)SrppJcs.parseStrict(new ClassPathResource("srpp/fubon-source-knownzero.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void nonTradingUnknownAndOutsideWindowPerformZeroSecretOrBrokerWork(){
        at(DATE.atTime(8,59).atZone(ZoneId.of("Asia/Taipei")).toInstant()).observe();at(DATE.atTime(14,0).atZone(ZoneId.of("Asia/Taipei")).toInstant()).observe();verifyNoInteractions(calendar,config,owners,brokers,adapter,writer);
        when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(false));at(AT).observe();when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());at(AT).observe();verifyNoInteractions(config,owners,brokers,adapter,writer);
    }
    @Test void ownerAndFeatureGatesNeverQuerySdkOrAppend(){
        when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(null,FubonSyncOwnerPort.Denial.NO_OWNER));at(AT).observe();verifyNoInteractions(config,brokers,adapter,writer);
        when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(7L,null));when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.DISABLED,null,"DISABLED"));at(AT).observe();verifyNoInteractions(adapter,writer);
    }
    @Test void appendSuccessAndCaptureFailureExplicitlyNeverReuseSuccess()throws Exception{
        when(adapter.observe()).thenReturn(known()).thenThrow(new IllegalStateException("permission denied"));var worker=at(AT);worker.observe();worker.observe();var c=org.mockito.ArgumentCaptor.forClass(FubonSrppSourceStore.Observation.class);verify(writer,times(2)).append(c.capture());assertThat(c.getAllValues().getFirst().status()).isEqualTo("VERIFIED");assertThat(c.getAllValues().getLast().status()).isEqualTo("UNAVAILABLE");assertThat(c.getAllValues().getLast().payload().path("verifiedZero").asBoolean()).isFalse();assertThat(c.getAllValues().getLast().payload().path("reason").asText()).isEqualTo("FUBON_OFFICIAL_CAPTURE_FAILED");
    }
    @Test void singleFlightBlocksOverlappingTickAndResumesAfterCompletion()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);when(adapter.observe()).thenAnswer(inv->{entered.countDown();finish.await(5,TimeUnit.SECONDS);return known();});var worker=at(AT);var pool=Executors.newSingleThreadExecutor();try{var future=pool.submit(worker::observe);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();worker.observe();verify(adapter,times(1)).observe();finish.countDown();future.get(5,TimeUnit.SECONDS);worker.observe();verify(adapter,times(2)).observe();}finally{pool.shutdownNow();}
    }
    @Test void cronDefaultAndZoneMatchCatalog()throws Exception{var schedule=FubonSrppSourceScheduler.class.getMethod("observe").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);assertThat(schedule.cron()).isEqualTo("${fubon.srpp-source-cron:0 * * * * *}");assertThat(schedule.zone()).isEqualTo("Asia/Taipei");}
}
