package com.steven.assets.externalmaterials.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Bounded five-code round; uses the existing shared read-only SDK quota. */
@Service
public class FubonIntradayCandleSyncService {
    private static final String CURSOR="fubon:intraday-candle:cursor";
    private final String enabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope scope;
    private final FubonMarketDataPort client;
    private final FubonIntradayCandleRepository repository;
    private final MarketClock clock;
    private final StringRedisTemplate redis;
    private final AtomicBoolean inFlight=new AtomicBoolean();
    private volatile Result lastResult=new Result("NOT_RUN",0,0);
    public FubonIntradayCandleSyncService(
            @Value("${fubon.intraday-candle-sync-enabled:true}") String enabled,FubonMarketRunGate gate,
            FubonRadarScope scope,FubonMarketDataPort client,FubonIntradayCandleRepository repository,
            MarketClock clock,StringRedisTemplate redis) {
        this.enabled=enabled; this.gate=gate; this.scope=scope; this.client=client;
        this.repository=repository; this.clock=clock; this.redis=redis;
    }
    @Scheduled(cron="0 * 9-13 * * MON-FRI",zone="Asia/Taipei")
    public void scheduled() { sync(); }
    public Result lastResult() { return lastResult; }
    public Result sync() {
        String unavailable=gate.reason(enabled,"INTRADAY_CANDLE_SYNC_DISABLED",false);
        if (unavailable!=null) return finish(unavailable,0,0);
        if (!inSession(clock.instant())) return finish("OUTSIDE_SESSION",0,0);
        if (!inFlight.compareAndSet(false,true)) return new Result("IN_FLIGHT",0,0);
        int requested=0,available=0;
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();
        try {
            List<String> codes=scope.current(30);
            if (codes.isEmpty()) return finish("NO_SYMBOLS",0,0);
            int start=FubonIntradayTechnicalSyncService.nextIndex(codes,redis.opsForValue().get(CURSOR));
            for (int offset=0;offset<Math.min(5,codes.size());offset++) {
                if (System.nanoTime()+java.time.Duration.ofSeconds(8).toNanos()>deadline)
                    return finish("RUN_DEADLINE",requested,available);
                String code=codes.get((start+offset)%codes.size());
                redis.opsForValue().set(CURSOR,code);
                if (!eligible(code)) return finish("SESSION_OR_SCOPE_CHANGED",requested,available);
                Instant started=clock.instant();
                requested++;
                try {
                    IntradayCandlesRead read=client.candles(code,started.atZone(MarketClock.TW_ZONE).toLocalDate());
                    if (read==null || !code.equals(read.symbol())) throw new IllegalArgumentException("SOURCE_IDENTITY_INVALID");
                    if (!eligible(code)) {
                        repository.unavailable(code,started.atZone(MarketClock.TW_ZONE).toLocalDate(),started,
                                clock.instant(),"UNAVAILABLE","SESSION_OR_SCOPE_CHANGED");
                        return finish("SESSION_OR_SCOPE_CHANGED",requested,available);
                    }
                    var result=repository.capture(read,started);
                    if ("AVAILABLE".equals(result.status())) available++;
                } catch (Unavailable failure) {
                    repository.unavailable(code,started.atZone(MarketClock.TW_ZONE).toLocalDate(),started,
                            clock.instant(),"UNAVAILABLE",safeReason(failure.reason()));
                    if (failure.stopRun() || Set.of("RATE_LIMITED","HISTORY_BUDGET_EXHAUSTED","INTERRUPTED")
                            .contains(failure.reason())) return finish(failure.reason(),requested,available);
                } catch (RuntimeException failure) {
                    repository.unavailable(code,started.atZone(MarketClock.TW_ZONE).toLocalDate(),started,
                            clock.instant(),"UNAVAILABLE","SOURCE_UNAVAILABLE");
                }
            }
            return finish(available==requested?"SUCCESS":available>0?"PARTIAL":"UNAVAILABLE",requested,available);
        } catch (RuntimeException failure) { return finish("SOURCE_UNAVAILABLE",requested,available); }
        finally { inFlight.set(false); }
    }
    private boolean eligible(String code) {
        return inSession(clock.instant()) && gate.reason(enabled,"INTRADAY_CANDLE_SYNC_DISABLED",false)==null
                && scope.current(30).contains(code);
    }
    static boolean inSession(Instant time) {
        LocalTime local=time.atZone(MarketClock.TW_ZONE).toLocalTime();
        return !local.isBefore(LocalTime.of(9,0)) && local.isBefore(LocalTime.of(13,30));
    }
    private static String safeReason(String reason) {
        return reason!=null && reason.matches("[A-Z0-9_]{1,80}")?reason:"SOURCE_UNAVAILABLE";
    }
    private Result finish(String status,int requested,int available) {
        return lastResult=new Result(status,requested,available);
    }
    public record Result(String status,int requested,int available) {}
}
