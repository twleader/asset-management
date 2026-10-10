package com.steven.assets.externalmaterials.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Fixed-year bounded cleanup; unfinished rounds continue automatically. */
@Service
public class FubonMinuteRetentionService {
    private final FubonIntradayCandleRepository repository;
    private final MarketClock clock;
    private final AtomicBoolean inFlight=new AtomicBoolean();
    private volatile Result lastResult=new Result("NOT_RUN",null,0);
    public FubonMinuteRetentionService(FubonIntradayCandleRepository repository,MarketClock clock) {
        this.repository=repository; this.clock=clock;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { cleanup(); }
    @Scheduled(cron="0 10 0 * * *",zone="Asia/Taipei")
    public void daily() { cleanup(); }
    @Scheduled(cron="30 * * * * *",zone="Asia/Taipei")
    public void continuePending() {
        if ("INCOMPLETE".equals(lastResult.status()) || "FAILED".equals(lastResult.status())) cleanup();
    }
    public Result lastResult() { return lastResult; }
    public Result cleanup() {
        if (!inFlight.compareAndSet(false,true)) return new Result("IN_FLIGHT",null,0);
        LocalDate floor=FubonMinuteRetentionFloor.at(clock.instant());
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        int deleted=0;
        try {
            while (System.nanoTime()+Duration.ofSeconds(10).toNanos()<deadline) {
                int count=repository.deleteExpiredBatch(floor,false); deleted+=count;
                if (count>0) continue;
                count=repository.deleteExpiredBatch(floor,true); deleted+=count;
                if (count==0) return lastResult=new Result(repository.hasExpired(floor)?"INCOMPLETE":"COMPLETE",floor,deleted);
            }
            return lastResult=new Result("INCOMPLETE",floor,deleted);
        } catch (RuntimeException failure) { return lastResult=new Result("FAILED",floor,deleted); }
        finally { inFlight.set(false); }
    }
    public record Result(String status,LocalDate floor,int deleted) {}
}
