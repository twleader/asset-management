package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient.DividendFetchResult;
import com.steven.assets.externalmaterials.client.DividendFetchClient.FetchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Adds only PARTIAL Fubon evidence through the existing append-only store. */
@Service
public class FubonDividendEvidenceSyncService {
    private final String enabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final DividendSnapshotStore store;
    private final MarketClock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Result lastResult = new Result("DISABLED", false, "NOT_RUN", 0, 0, 0, 0);

    public FubonDividendEvidenceSyncService(@Value("${fubon.dividend-sync-enabled:false}") String enabled,
            FubonMarketRunGate gate, FubonRadarScope radar, FubonMarketDataPort client,
            DividendSnapshotStore store, MarketClock clock) {
        this.enabled = enabled; this.gate = gate; this.radar = radar; this.client = client;
        this.store = store; this.clock = clock;
    }
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Taipei")
    @Scheduled(cron = "0 30 13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduled() { lastResult = sync(false); }
    public Result lastResult() { return lastResult; }

    public Result sync(boolean dryRun) {
        String reason = gate.reason(enabled, "DIVIDEND_SYNC_DISABLED", false);
        if (reason != null) return result(reason, dryRun, reason, 0, 0, 0, 0);
        if (!inFlight.compareAndSet(false, true)) return result("IN_FLIGHT", dryRun, "IN_FLIGHT", 0, 0, 0, 0);
        try { return perform(dryRun); }
        finally { inFlight.set(false); }
    }
    private Result perform(boolean dryRun) {
        List<String> codes;
        try { codes = radar.current(2000); }
        catch (Unavailable failure) { return result("DIVIDEND_FAILED", dryRun, failure.reason(), 0, 0, 0, 0); }
        if (codes.isEmpty()) return result("NO_SYMBOLS", dryRun, "NO_SYMBOLS", 0, 0, 0, 0);
        LocalDate date = gate.today();
        DividendBatch batch;
        try { batch = client.dividends(codes, date); }
        catch (Unavailable failure) {
            if (!dryRun && gate.sameDay(date)) {
                for (String code : codes) {
                    try {
                        store.record(code, MARKET, new DividendFetchResult(PROVIDER, List.of(), FetchStatus.FAILED,
                                date.minusDays(320), date.plusDays(45), null, failure.reason(), List.of()), clock.instant());
                    } catch (RuntimeException ignored) { /* count remains failed; no raw exception escapes */ }
                }
            }
            return result("DIVIDEND_FAILED", dryRun, failure.reason(), codes.size(), 0, 0, codes.size());
        }
        int processed = 0, persisted = 0, failed = 0, skipped = 0;
        for (DividendRow row : batch.rows()) {
            if (!gate.sameDay(date)) {
                skipped += codes.size() - processed;
                return result("PARTIAL", dryRun, "STALE_QUERY", processed, persisted, skipped, failed);
            }
            processed++;
            try {
                // A removal while the date batch was in flight must not append new evidence.
                if (!radar.current(2000).contains(row.symbol())) { skipped++; continue; }
                boolean rowFailed = "FAILED".equals(row.status());
                if (!dryRun) {
                    var written = store.record(row.symbol(), MARKET,
                            new DividendFetchResult(PROVIDER, row.events(), rowFailed ? FetchStatus.FAILED : FetchStatus.PARTIAL,
                                    batch.scopeFrom(), batch.scopeTo(), null, row.reason(), List.of()), batch.observedAt());
                    if (written.complete() || !"PARTIAL".equals(written.status()) && !rowFailed) {
                        failed++; continue;
                    }
                    if (!rowFailed) persisted++;
                }
                if (rowFailed) failed++;
            } catch (RuntimeException failure) { failed++; }
        }
        String outcome = failed == codes.size() ? "DIVIDEND_FAILED" : dryRun ? "DRY_RUN" : "PARTIAL";
        // A date-batch response cannot prove upcoming-calendar completeness, even when every symbol has cash events.
        return result(outcome, dryRun, failed > 0 ? "PARTIAL_FAILURE" : "SOURCE_COVERAGE_PARTIAL",
                processed, persisted, skipped, failed);
    }
    private Result result(String outcome, boolean dryRun, String reason, int processed, int persisted, int skipped, int failed) {
        Result result = new Result(outcome, dryRun, reason, processed, persisted, skipped, failed);
        lastResult = result;
        return result;
    }
    public record Result(String outcome, boolean dryRun, String reason, int processedCount,
                         int persistedCount, int skippedCount, int failedCount) {}
}
