package com.steven.assets.service.srpp.us;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.*;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.report.SrppReportAuthority;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Natural bounded background ticks only. No HTTP/manual producer route is exposed. */
@Component
public final class UsReportProducerScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(UsReportProducerScheduler.class);
    private final UsReportCalendar calendar;private final SrppPolicyRegistryService registry;private final SrppReportAuthority authority;private final PublishedSwaggerIdentity swagger;private final AppUserRepository users;private final AssetSnapshotRepository snapshots;private final UsReportPublisher publisher;private final Clock clock;
    @Autowired public UsReportProducerScheduler(UsReportCalendar calendar,SrppPolicyRegistryService registry,SrppReportAuthority authority,PublishedSwaggerIdentity swagger,AppUserRepository users,AssetSnapshotRepository snapshots,UsReportPublisher publisher){this(calendar,registry,authority,swagger,users,snapshots,publisher,Clock.systemUTC());}
    UsReportProducerScheduler(UsReportCalendar calendar,SrppPolicyRegistryService registry,SrppReportAuthority authority,PublishedSwaggerIdentity swagger,AppUserRepository users,AssetSnapshotRepository snapshots,UsReportPublisher publisher,Clock clock){this.calendar=calendar;this.registry=registry;this.authority=authority;this.swagger=swagger;this.users=users;this.snapshots=snapshots;this.publisher=publisher;this.clock=clock;}
    @EventListener(ApplicationReadyEvent.class) public void ready(){produce();}
    @Scheduled(cron="${srpp.us-report-producer-cron:0 * * * * *}",zone="America/New_York")
    public synchronized void produce(){Instant at=clock.instant();try{if(!calendar.producerWindow(at))return;}catch(UsReportProblem closed){return;}
        var policy=registry.find(authority.decision().policy().bundleHash());var published=swagger.sha256();if(policy.isEmpty()||published.isEmpty()||!authority.matches(policy.get()))return;
        Set<Long> active=users.findByStatus(AppUser.STATUS_ACTIVE).stream().filter(AppUser::isActive).map(AppUser::getId).collect(Collectors.toSet());
        for(long owner:snapshots.findDistinctOwnerUserIds().stream().filter(active::contains).sorted().toList())try{publisher.publishOwner(owner,at.atZone(UsReportCalendar.NY).toLocalDate(),policy.get(),published.get());}catch(RuntimeException failure){LOG.warn("SRPP US producer skipped owner={} reason={}",owner,failure instanceof UsReportProblem p?p.code():failure.getClass().getSimpleName());}
    }
}
