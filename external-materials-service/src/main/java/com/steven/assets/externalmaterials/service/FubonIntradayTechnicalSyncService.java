package com.steven.assets.externalmaterials.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** One-minute, cache-only consumer for the Fubon intraday technical endpoint. */
@Service
public class FubonIntradayTechnicalSyncService {
    private static final Set<String> STOP_REASONS = Set.of(
            "RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED", "UPSTREAM_UNAVAILABLE", "INTERRUPTED");

    private final String enabled;
    private final String volumeSyncEnabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final FubonTechnicalCachePort cache;
    private final MarketClock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Result lastResult = new Result("DISABLED", "NOT_RUN", 0, 0, 0);

    public FubonIntradayTechnicalSyncService(
            @Value("${fubon.intraday-technical-indicator-sync-enabled:false}") String enabled,
            @Value("${fubon.intraday-price-volume-sync-enabled:false}") String volumeSyncEnabled,
            FubonMarketRunGate gate, FubonRadarScope radar, FubonMarketDataPort client,
            FubonTechnicalCachePort cache, MarketClock clock) {
        this.enabled = enabled;
        this.volumeSyncEnabled = volumeSyncEnabled;
        this.gate = gate;
        this.radar = radar;
        this.client = client;
        this.cache = cache;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduled() { lastResult = sync(); }

    public Result lastResult() { return lastResult; }

    public Result sync() {
        String reason = gate.reason(enabled, "INTRADAY_TECHNICAL_SYNC_DISABLED", false);
        Instant now = clock.instant();
        if (reason == null && !inSession(now)) reason = "OUTSIDE_SESSION";
        if (reason != null) return finish(reason, 0, 0, 0);
        if (!inFlight.compareAndSet(false, true)) return finish("IN_FLIGHT", 0, 0, 0);
        try { return perform(); }
        finally { inFlight.set(false); }
    }

    private Result perform() {
        final List<String> codes;
        try { codes = radar.current(30); }
        catch (Unavailable unavailable) { return finish(unavailable.reason(), 0, 0, 0); }
        if (codes.isEmpty()) return finish("NO_SYMBOLS", 0, 0, 0);

        final int start;
        try { start = nextIndex(codes, cache.readIntradayCursor()); }
        catch (RuntimeException unavailable) { return finish("CURSOR_UNAVAILABLE", 0, 0, 0); }
        int maxCandidates = "true".equalsIgnoreCase(volumeSyncEnabled) ? 5 : 10;
        int candidateCount = Math.min(codes.size(), maxCandidates);
        int requested = 0, written = 0, failed = 0;
        for (int offset = 0; offset < candidateCount; offset++) {
            String code = codes.get((start + offset) % codes.size());
            requested++;
            try {
                // Move the cursor before the request so a timeout or process restart cannot pin
                // the same first symbol at the head of every one-minute cycle.
                cache.advanceIntradayCursor(code);
                if (!stillEligible(code)) return finish("SESSION_OR_SCOPE_CHANGED", requested, written, failed);
                FubonIntradayTechnical.Bundle bundle = client.intradayTechnical(code, gate.today());
                if (!stillEligible(code)) return finish("SESSION_OR_SCOPE_CHANGED", requested, written, failed);
                FubonTechnicalCachePort.IntradayWrite outcome = cache.writeIntraday(bundle);
                if (outcome == FubonTechnicalCachePort.IntradayWrite.WRITTEN) written++;
                else {
                    failed++;
                    if (outcome == FubonTechnicalCachePort.IntradayWrite.FAILED)
                        return finish("CACHE_WRITE_FAILED", requested, written, failed);
                }
            } catch (Unavailable unavailable) {
                failed++;
                if (unavailable.stopRun() || STOP_REASONS.contains(unavailable.reason()))
                    return finish(unavailable.reason(), requested, written, failed);
            } catch (RuntimeException unavailable) {
                failed++;
                return finish("CACHE_OR_CURSOR_UNAVAILABLE", requested, written, failed);
            }
        }
        return finish(failed == 0 ? "SUCCESS" : written > 0 ? "PARTIAL" : "FAILED", requested, written, failed);
    }

    private boolean stillEligible(String code) {
        Instant now = clock.instant();
        if (!inSession(now) || !gate.sameDay(now.atZone(MarketClock.TW_ZONE).toLocalDate())) return false;
        if (gate.reason(enabled, "INTRADAY_TECHNICAL_SYNC_DISABLED", false) != null) return false;
        try { return radar.current(30).contains(code); }
        catch (Unavailable unavailable) { return false; }
    }

    static int nextIndex(List<String> sortedCodes, String lastCode) {
        if (sortedCodes == null || sortedCodes.isEmpty()) return 0;
        if (lastCode == null || lastCode.isBlank()) return 0;
        int previous = sortedCodes.indexOf(lastCode);
        if (previous >= 0) return (previous + 1) % sortedCodes.size();
        for (int index = 0; index < sortedCodes.size(); index++) {
            if (sortedCodes.get(index).compareTo(lastCode) > 0) return index;
        }
        return 0;
    }

    private Result finish(String reason, int requested, int written, int failed) {
        String outcome = Set.of("SUCCESS", "NO_SYMBOLS", "DISABLED", "OUTSIDE_SESSION", "IN_FLIGHT").contains(reason)
                ? reason : reason.startsWith("PARTIAL") || written > 0 && failed > 0 ? "PARTIAL"
                : written > 0 || requested == 0 ? reason : "FAILED";
        Result result = new Result(outcome, reason, requested, written, failed);
        lastResult = result;
        return result;
    }

    public record Result(String outcome, String reason, int requested, int written, int failed) {}
}
