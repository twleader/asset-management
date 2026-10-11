package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.*;
import com.steven.assets.repository.SrppReportRepository.Scope;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Every 60-second tick revisits the active half-open slot; a restart or late source needs no GET trigger. */
@Component
public class SrppReportProducerScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(SrppReportProducerScheduler.class);
    private final MarketDataService calendar;private final SrppPolicyRegistryService registry;private final PublishedSwaggerIdentity swagger;
    private final SrppReportAuthority authority;private final AppUserRepository users;private final AssetSnapshotRepository snapshots;
    private final DecisionInputCapturePort capture;private final SrppReportExtraCapturePort extras;private final SrppReportConsumerStatePort states;
    private final SrppReportReservationCapture reservations;private final SrppReportPublisher publisher;private final Clock clock;private final SrppReportRepository reportRepository;private final TransactionTemplate transaction;
    private SrppReportFubonCapturePort fubon;
    @Autowired
    public SrppReportProducerScheduler(MarketDataService calendar,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,
            SrppReportAuthority authority,AppUserRepository users,AssetSnapshotRepository snapshots,DecisionInputCapturePort capture,
            SrppReportExtraCapturePort extras,SrppReportConsumerStatePort states,SrppReportReservationCapture reservations,SrppReportPublisher publisher,SrppReportRepository reportRepository,PlatformTransactionManager manager,SrppReportFubonCapturePort fubon){
        this(calendar,registry,swagger,authority,users,snapshots,capture,extras,states,reservations,publisher,reportRepository,manager,Clock.system(SrppTime.TW_ZONE));this.fubon=fubon;
    }
    SrppReportProducerScheduler(MarketDataService calendar,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,
            SrppReportAuthority authority,AppUserRepository users,AssetSnapshotRepository snapshots,DecisionInputCapturePort capture,
            SrppReportExtraCapturePort extras,SrppReportConsumerStatePort states,SrppReportReservationCapture reservations,SrppReportPublisher publisher,SrppReportRepository reportRepository,PlatformTransactionManager manager,Clock clock){
        this.calendar=calendar;this.registry=registry;this.swagger=swagger;this.authority=authority;this.users=users;this.snapshots=snapshots;this.capture=capture;this.extras=extras;this.states=states;this.reservations=reservations;this.publisher=publisher;this.clock=clock;this.reportRepository=reportRepository;this.transaction=new TransactionTemplate(manager);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void restartTick(){produce();}
    @Scheduled(cron="${srpp.report-producer-cron:0 * * * * *}",zone="Asia/Taipei")
    public synchronized void produce(){
        ZonedDateTime now=ZonedDateTime.now(clock.withZone(SrppTime.TW_ZONE));Optional<Boolean> open;
        try{open=calendar.isTwTradingDayCachedOnly(now.toLocalDate());}catch(RuntimeException unavailable){return;}
        if(!Optional.of(true).equals(open))return;String slot=slotFor(now.toLocalTime());if(slot==null)return;
        Optional<String> swaggerHash=swagger.sha256();if(swaggerHash.isEmpty())return;
        Optional<SupportedPolicy> supported=registry.find(authority.decision().policy().bundleHash());if(supported.isEmpty()||!authority.matches(supported.get()))return;
        Set<Long> active=users.findByStatus(AppUser.STATUS_ACTIVE).stream().filter(AppUser::isActive).map(AppUser::getId).collect(Collectors.toSet());
        for(long owner:snapshots.findDistinctOwnerUserIds().stream().filter(active::contains).sorted().toList()){
            try{
                transaction.executeWithoutResult(tx -> {
                reportRepository.lockOwnerDate(owner,now.toLocalDate());
                ObjectNode reportReservations=reservations.readReportSource(owner,now.toLocalDate(),slot,supported.get().bundleHash());
                Frozen original=capture.capture(owner,new DecisionRequest(null,now.toLocalDate(),slot,supported.get().bundleHash(),swaggerHash.get()),reservations.read(owner,now.toLocalDate(),slot));
                original=withReportReservations(original,reportReservations);
                if(fubon!=null)original=SrppReportFubonOverlay.apply(original,fubon.capture(owner,now.toLocalDate(),original.facts().capturedAt()));
                JsonNode model=authority.model();var extra=extras.capture(owner,now.toLocalDate(),slot,original.facts().capturedAt(),model,original.input().path("sources").path("assets"),original.input().path("sources"));
                Frozen completed=finishCapture(original,extra.sources(),extra.sourceVector(),clock.instant());
                Map<String,ObjectNode> state=new LinkedHashMap<>();for(String consumer:List.of("Codex","Claude"))state.put(consumer,states.read(owner,consumer,now.toLocalDate(),slot));
                publisher.publish(new Scope(owner,now.toLocalDate(),slot,supported.get().bundleHash(),swaggerHash.get(),SrppReportModuleCalculator.VERSION),completed,supported.get(),extra.sources(),state,clock.instant());
                });
            }catch(RuntimeException failure){LOG.warn("SRPP report producer skipped owner={} reason={}",owner,failure instanceof SrppReportProblem p?p.code():failure.getClass().getSimpleName());}
        }
    }
    static String slotFor(LocalTime time){if(time.isBefore(LocalTime.of(9,5))||!time.isBefore(LocalTime.of(14,0)))return null;return time.isBefore(LocalTime.NOON)?"09:05":"12:00";}
    static Frozen withReportReservations(Frozen original,ObjectNode reservations){
        if(reservations==null)throw new SrppReportProblem("UPSTREAM_INVALID");ObjectNode input=original.input(),vector=original.sourceVector();
        if(!reservations.path("ownerUserId").equals(input.path("ownerUserId"))||!reservations.path("tradingDate").equals(input.path("tradingDate"))||!reservations.path("policyBundleSha256").equals(input.path("sources").path("policy").path("bundleHash")))throw new SrppReportProblem("UPSTREAM_INVALID");
        input.withObject("sources").set("report-reservations",reservations);
        String hash=SrppJcs.hash(reservations);ObjectNode receipt=vector.putObject("report-reservations");receipt.put("sourceId","report-reservations");receipt.put("revision",hash);receipt.put("capturedAt",original.facts().capturedAt().toString());receipt.put("sha256",hash);receipt.put("coverage",reservations.path("coverage").asText());receipt.put("validation","VERIFIED".equals(reservations.path("status").asText())?"VERIFIED":"UNAVAILABLE");
        return new Frozen(original.facts(),input,vector,original.assetSnapshotId(),original.assetGeneratedAt());
    }
    static Frozen finishCapture(Frozen original,ObjectNode extra,ObjectNode extraVector,Instant at){
        if(at.isBefore(original.facts().capturedAt()))throw new SrppReportProblem("UPSTREAM_INVALID");
        ObjectNode input=original.input(),vector=original.sourceVector(),sources=input.withObject("sources");
        extra.fields().forEachRemaining(e->{if(sources.has(e.getKey())&&!sources.path(e.getKey()).equals(e.getValue()))throw new SrppReportProblem("UPSTREAM_INVALID");sources.set(e.getKey(),e.getValue());});
        extraVector.fields().forEachRemaining(e->{if(vector.has(e.getKey())&&!vector.path(e.getKey()).equals(e.getValue()))throw new SrppReportProblem("UPSTREAM_INVALID");vector.set(e.getKey(),e.getValue());});
        sources.fields().forEachRemaining(e->{if(!vector.has(e.getKey())){ObjectNode receipt=vector.putObject(e.getKey());receipt.put("sourceId",e.getKey());receipt.put("revision",SrppJcs.hash(e.getValue()));receipt.put("capturedAt",at.toString());receipt.put("sha256",SrppJcs.hash(e.getValue()));receipt.put("coverage","COMPLETE");receipt.put("validation","VERIFIED");}});
        vector.forEach(v->{if(!v.isObject()||!v.path("capturedAt").isTextual()||Instant.parse(v.path("capturedAt").asText()).isAfter(at))throw new SrppReportProblem("UPSTREAM_INVALID");ObjectNode r=(ObjectNode)v;String validation=r.path("validation").asText();r.put("validation",Set.of("VERIFIED","PASS","CONFIRMED").contains(validation)?"VERIFIED":"UNAVAILABLE");String coverage=r.path("coverage").asText();if(!Set.of("COMPLETE","PARTIAL","NONE").contains(coverage))throw new SrppReportProblem("UPSTREAM_INVALID");});
        var old=original.facts();DecisionFacts.Input facts=new DecisionFacts.Input(old.date(),old.slot(),at,old.sessions(),old.totalAssets(),old.totalDeposits(),old.termDeposits(),old.bondValue(),old.fx(),old.fxEvidence(),old.bearWindow(),old.symbols(),old.budget(),old.subaccounts(),old.emergencyValue());
        input.put("capturedAt",at.toString());input.withObject("facts").put("capturedAt",at.toString());return new Frozen(facts,input,vector,original.assetSnapshotId(),original.assetGeneratedAt());
    }
}
