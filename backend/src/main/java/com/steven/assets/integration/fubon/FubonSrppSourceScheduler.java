package com.steven.assets.integration.fubon;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.fubon.*;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Calendar/window first. One background observer; latest failed attempt is never a stale success. */
@Component
public class FubonSrppSourceScheduler {
    private static final ZoneId TAIPEI=ZoneId.of("Asia/Taipei");
    private final MarketDataService calendar;private final FubonConfigState config;private final FubonSyncOwnerPort owners;
    private final BrokerRepository brokers;private final FubonSrppObservationPort adapter;private final FubonSrppSourceWriter writer;
    private final Clock clock;private final AtomicBoolean inFlight=new AtomicBoolean();
    @Autowired public FubonSrppSourceScheduler(MarketDataService calendar,FubonConfigState config,FubonSyncOwnerPort owners,BrokerRepository brokers,FubonSrppObservationPort adapter,FubonSrppSourceWriter writer){this(calendar,config,owners,brokers,adapter,writer,Clock.systemUTC());}
    FubonSrppSourceScheduler(MarketDataService calendar,FubonConfigState config,FubonSyncOwnerPort owners,BrokerRepository brokers,FubonSrppObservationPort adapter,FubonSrppSourceWriter writer,Clock clock){this.calendar=calendar;this.config=config;this.owners=owners;this.brokers=brokers;this.adapter=adapter;this.writer=writer;this.clock=clock;}
    @Scheduled(cron="${fubon.srpp-source-cron:0 * * * * *}",zone="Asia/Taipei")
    public void observe(){
        ZonedDateTime now=ZonedDateTime.now(clock.withZone(TAIPEI));if(now.toLocalTime().isBefore(LocalTime.of(9,0))||!now.toLocalTime().isBefore(LocalTime.of(14,0)))return;
        try{if(!Optional.of(true).equals(calendar.isTwTradingDayCachedOnly(now.toLocalDate())))return;}catch(RuntimeException noCalendar){return;}
        var owner=owners.preflight();if(!owner.allowed())return;
        if(config.snapshot().state()!=FubonConfigState.State.READY)return;
        if(brokers.findByCode("fubon").filter(b->Boolean.TRUE.equals(b.getActive())).isEmpty())return;
        if(!inFlight.compareAndSet(false,true))return;
        try{
            ObjectNode payload;String status,account=null,revision;
            try{payload=adapter.observe();validate(payload,now.toLocalDate(),clock.instant());status="VERIFIED";account=payload.path("accountFingerprint").asText();revision=payload.path("revision").asText();}
            catch(RuntimeException failure){payload=JsonNodeFactory.instance.objectNode();payload.put("schemaVersion",1);payload.put("broker","fubon");payload.put("queryDate",now.toLocalDate().toString());payload.put("capturedAt",clock.instant().toString());payload.put("complete",false);payload.put("verifiedZero",false);payload.put("reason","FUBON_OFFICIAL_CAPTURE_FAILED");status="UNAVAILABLE";revision=SrppJcs.hash(payload);payload.put("revision",revision);}
            writer.append(new FubonSrppSourceStore.Observation(UUID.randomUUID(),owner.ownerId(),now.toLocalDate(),account,Instant.parse(payload.path("capturedAt").asText()),status,revision,payload));
        }finally{inFlight.set(false);}
    }
    static void validate(ObjectNode payload,LocalDate date,Instant now){FubonSrppSourceContract.validate(payload,date,now);}
}