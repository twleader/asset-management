package com.steven.assets.externalmaterials.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Task425 one-minute, read-only broker producer.  Radar requests only use the cache reader. */
@Service
public class FubonIntradayPriceVolumeSyncService {
    private final String enabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final FubonIntradayPriceVolumeCache cache;
    private final MarketClock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Result lastResult = new Result("DISABLED", "NOT_RUN", 0, 0, 0, 0);

    @Autowired
    public FubonIntradayPriceVolumeSyncService(@Value("${fubon.intraday-price-volume-sync-enabled:false}") String enabled,
                                                FubonMarketRunGate gate, FubonRadarScope radar, FubonMarketDataPort client,
                                                FubonIntradayPriceVolumeCache cache, MarketClock clock) {
        this.enabled = enabled; this.gate = gate; this.radar = radar; this.client = client; this.cache = cache; this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void scheduled() { lastResult = sync(); }
    public Result lastResult() { return lastResult; }

    public Result sync() {
        String reason = gate.reason(enabled, "INTRADAY_PRICE_VOLUME_SYNC_DISABLED", false);
        Instant now = clock.instant();
        if (reason == null && !inSession(now)) reason = "OUTSIDE_SESSION";
        if (reason != null) return finish(reason, 0, 0, 0, 0);
        if (!inFlight.compareAndSet(false, true)) return finish("IN_FLIGHT", 0, 0, 0, 0);
        try {
            List<String> codes;
            try { codes = radar.current(30); }
            catch (Unavailable unavailable) { return finish(unavailable.reason(), 0, 0, 0, 0); }
            if (codes.isEmpty()) return finish("NO_SYMBOLS", 0, 0, 0, 0);
            int requested = 0, written = 0, stale = 0, failed = 0;
            for (String code : codes) {
                requested++;
                try {
                    IntradayVolumesRead snapshot = client.intradayVolumes(code, gate.today());
                    FubonIntradayPriceVolumeCache.Write outcome = cache.write(snapshot);
                    if (outcome == FubonIntradayPriceVolumeCache.Write.WRITTEN) written++;
                    else if (outcome == FubonIntradayPriceVolumeCache.Write.REJECTED_STALE) stale++;
                    else failed++;
                } catch (Unavailable unavailable) {
                    failed++;
                    if (unavailable.stopRun()) return finish(unavailable.reason(), requested, written, stale, failed);
                }
            }
            return finish(failed == 0 ? "SUCCESS" : written > 0 || stale > 0 ? "PARTIAL" : "FAILED", requested, written, stale, failed);
        } finally { inFlight.set(false); }
    }
    private Result finish(String reason, int requested, int written, int stale, int failed) {
        Result result = new Result("SUCCESS".equals(reason) ? "SUCCESS" : reason.startsWith("PARTIAL") ? "PARTIAL" :
                "FAILED".equals(reason) ? "FAILED" : reason, reason, requested, written, stale, failed);
        lastResult = result; return result;
    }
    public record Result(String outcome, String reason, int requested, int written, int stale, int failed) {}
}
