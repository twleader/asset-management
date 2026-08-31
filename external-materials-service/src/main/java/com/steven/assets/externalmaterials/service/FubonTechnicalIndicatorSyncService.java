package com.steven.assets.externalmaterials.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

@Service
public class FubonTechnicalIndicatorSyncService {
    static final Duration RUN_BUDGET = Duration.ofMinutes(30);
    static final Duration SYMBOL_INTERVAL = Duration.ofMillis(3100);
    static final Duration BATCH_WINDOW = Duration.ofSeconds(90);
    static final int BATCH_SIZE = 3;
    static final Duration SYMBOL_RESERVATION = Duration.ofSeconds(86);
    private final String enabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final FubonTechnicalCachePort cache;
    private final FubonMarketDataHistoryStore history;
    private final FubonTechnicalV2CacheRepository v2Cache;
    private final Timing timing;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Result lastResult = new Result("DISABLED", false, "NOT_RUN", 0, 0, 0, 0, 0, Map.of());

    @Autowired
    public FubonTechnicalIndicatorSyncService(@Value("${fubon.technical-indicator-sync-enabled:false}") String enabled,
            FubonMarketRunGate gate, FubonRadarScope radar, FubonMarketDataPort client, FubonTechnicalCachePort cache,
            FubonMarketDataHistoryStore history, FubonTechnicalV2CacheRepository v2Cache) {
        this(enabled, gate, radar, client, cache, history, v2Cache, new Timing() {
            public long nanos() { return System.nanoTime(); }
            public void sleep(Duration duration) throws InterruptedException { Thread.sleep(duration); }
        });
    }
    /** Legacy isolated-test constructor; production must use the injected v2 history/cache path. */
    FubonTechnicalIndicatorSyncService(String enabled, FubonMarketRunGate gate, FubonRadarScope radar,
                                      FubonMarketDataPort client, FubonTechnicalCachePort cache) {
        this(enabled, gate, radar, client, cache, null, null, new Timing() {
            public long nanos() { return System.nanoTime(); }
            public void sleep(Duration duration) throws InterruptedException { Thread.sleep(duration); }
        });
    }
    FubonTechnicalIndicatorSyncService(String enabled, FubonMarketRunGate gate, FubonRadarScope radar,
                                      FubonMarketDataPort client, FubonTechnicalCachePort cache, Timing timing) {
        this(enabled, gate, radar, client, cache, null, null, timing);
    }
    FubonTechnicalIndicatorSyncService(String enabled, FubonMarketRunGate gate, FubonRadarScope radar,
                                      FubonMarketDataPort client, FubonTechnicalCachePort cache,
                                      FubonMarketDataHistoryStore history, FubonTechnicalV2CacheRepository v2Cache, Timing timing) {
        this.enabled = enabled; this.gate = gate; this.radar = radar; this.client = client; this.cache = cache;
        this.history = history; this.v2Cache = v2Cache; this.timing = timing;
    }
    @Scheduled(cron = "0 40 13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduled() { lastResult = sync(false); }
    public Result lastResult() { return lastResult; }
    public Result sync(boolean dryRun) {
        String reason = gate.reason(enabled, "TECHNICAL_INDICATOR_SYNC_DISABLED", true);
        if (reason != null) return finish(reason, dryRun, reason, 0, 0, 0, 0, 0, Map.of());
        if (!inFlight.compareAndSet(false, true)) return finish("IN_FLIGHT", dryRun, "IN_FLIGHT", 0, 0, 0, 0, 0, Map.of());
        try { return perform(dryRun); }
        finally { inFlight.set(false); }
    }
    private Result perform(boolean dryRun) {
        List<String> codes;
        try { codes = radar.current(Integer.MAX_VALUE); }
        catch (Unavailable failure) {
            return finish("TECHNICAL_INDICATOR_FAILED", dryRun, failure.reason(), 0, 0, 0, 0, 0, Map.of());
        }
        if (codes.isEmpty()) return finish("NO_SYMBOLS", dryRun, "NO_SYMBOLS", 0, 0, 0, 0, 0, Map.of());
        Run run = new Run(gate.today(), timing.nanos() + RUN_BUDGET.toNanos());
        AtomicInteger next = new AtomicInteger();
        Queue<SymbolResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService workers = Executors.newFixedThreadPool(2, action -> {
            Thread thread = Thread.ofVirtual().unstarted(action);
            run.threads.add(thread);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        boolean interrupted = false;
        try {
            for (int worker = 0; worker < 2; worker++) {
                futures.add(workers.submit(() -> {
                    while (run.stop.get() == null) {
                        int index = next.getAndIncrement();
                        if (index >= codes.size()) return;
                        results.add(processSymbol(codes.get(index), dryRun, run));
                    }
                }));
            }
            for (Future<?> future : futures) future.get(Math.max(1, run.deadline - timing.nanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
            interrupted = true; run.halt("INTERRUPTED");
        } catch (TimeoutException expired) { run.halt("RUN_DEADLINE"); }
        catch (ExecutionException unexpected) { run.halt("TECHNICAL_INDICATOR_FAILED"); }
        finally {
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(30, TimeUnit.SECONDS)) run.halt("CANCEL_PENDING");
            } catch (InterruptedException cancelled) { interrupted = true; run.halt("INTERRUPTED"); }
            if (interrupted) Thread.currentThread().interrupt();
        }
        int processed = 0, written = 0, partial = 0, failed = 0;
        Map<String, Integer> outcomes = new TreeMap<>();
        for (SymbolResult result : results) {
            if (result.processed()) processed++;
            if (result.written()) written++;
            if (result.partial()) partial++;
            if (result.failed()) failed++;
            result.outcomes().forEach((key, count) -> outcomes.merge(key, count, Integer::sum));
        }
        int skipped = codes.size() - processed;
        String stopReason = run.stop.get();
        boolean incomplete = partial > 0 || failed > 0 || skipped > 0 || stopReason != null;
        String outcome = processed == 0 || failed == processed ? "TECHNICAL_INDICATOR_FAILED"
                : dryRun ? "DRY_RUN" : incomplete ? "PARTIAL" : "SUCCESS";
        return finish(outcome, dryRun, stopReason != null ? stopReason : incomplete ? "PARTIAL_FAILURE" : null,
                processed, written, partial, failed, skipped, outcomes);
    }
    private SymbolResult processSymbol(String code, boolean dryRun, Run run) {
        if (history == null || v2Cache == null) return processLegacySymbol(code, dryRun, run);
        boolean started = false, written = false, anyUsable = false, anyFailure = false;
        Map<String, Integer> counts = new TreeMap<>();
        try {
            if (!eligible(code, run)) return new SymbolResult(false, false, false, false, counts);
            if (!run.dispatch(timing.nanos())) return new SymbolResult(false, false, false, false, counts);
            if (!eligible(code, run)) return new SymbolResult(false, false, false, false, counts);
            started = true;

            // Each source is intentionally independent: a malformed ticker cannot
            // erase a valid technical history transaction, and vice versa.
            try {
                TechnicalBundle technical = client.technicalV2(code, run.date);
                anyUsable |= technical.profiles().stream().anyMatch(TechnicalProfileRead::available);
                if (!dryRun && sameDay(run)) {
                    var persisted = history.persistTechnical(technical);
                    counts.merge("technicalDb:" + persisted.facts(), 1, Integer::sum);
                    written |= persisted.facts() == FubonMarketDataHistoryStore.Status.WRITTEN;
                    if (persisted.completeCaptureCommitted()) {
                        var pair = FubonTechnicalV2Cache.fromBundle(technical, "FUBON_SDK", "UNBOUND_FUBON_SOURCE", null,
                                UUID.randomUUID().toString(), null, null);
                        var expected = v2Cache.read(code, Instant.now(), null, false);
                        var projected = v2Cache.write(code, pair, expected, true);
                        counts.merge("technicalRedis:" + projected.outcome(), 1, Integer::sum);
                        written |= "WRITTEN".equals(projected.outcome());
                    }
                }
                if (!technical.complete()) anyFailure = true;
            } catch (Unavailable unavailable) {
                counts.merge("technicalDb:" + unavailable.reason(), 1, Integer::sum); anyFailure = true;
                if (unavailable.stopRun()) run.halt(unavailable.reason());
            }
            if (run.stop.get() == null && sameDay(run)) {
                try {
                    StockBasicRead basic = client.basic(code, run.date);
                    anyUsable = true;
                    if (!dryRun) {
                        var persisted = history.persistBasic(basic);
                        counts.merge("basicDb:" + persisted.status(), 1, Integer::sum);
                        written |= persisted.status() == FubonMarketDataHistoryStore.Status.WRITTEN;
                        anyFailure |= persisted.status() == FubonMarketDataHistoryStore.Status.FAILED;
                    }
                } catch (Unavailable unavailable) {
                    counts.merge("basicDb:" + unavailable.reason(), 1, Integer::sum); anyFailure = true;
                    if (unavailable.stopRun()) run.halt(unavailable.reason());
                }
            }
            if (run.stop.get() == null && sameDay(run)) {
                try {
                    IntradayCandlesRead candles = client.candles(code, run.date);
                    anyUsable |= "AVAILABLE".equals(candles.status()) || "NO_DATA".equals(candles.status());
                    if (!dryRun && "AVAILABLE".equals(candles.status())) {
                        var persisted = history.persistCandles(candles);
                        counts.merge("candleDb:" + persisted.status(), 1, Integer::sum);
                        written |= persisted.status() == FubonMarketDataHistoryStore.Status.WRITTEN;
                        anyFailure |= persisted.status() == FubonMarketDataHistoryStore.Status.FAILED;
                    }
                } catch (Unavailable unavailable) {
                    counts.merge("candleDb:" + unavailable.reason(), 1, Integer::sum); anyFailure = true;
                    if (unavailable.stopRun()) run.halt(unavailable.reason());
                }
            }
            return new SymbolResult(true, written, anyFailure && anyUsable, !anyUsable, counts);
        } catch (InterruptedException cancelled) {
            run.halt("INTERRUPTED"); Thread.currentThread().interrupt();
            return new SymbolResult(started, written, started && anyUsable, started && !anyUsable, counts);
        } catch (RuntimeException failure) {
            return new SymbolResult(started, written, started && anyUsable, started && !anyUsable, counts);
        }
    }
    /** Frozen Task398 seam used only by older isolated tests; production always uses v2 history. */
    private SymbolResult processLegacySymbol(String code, boolean dryRun, Run run) {
        boolean started = false, written = false, available = false;
        Map<String, Integer> counts = new TreeMap<>();
        try {
            if (!eligible(code, run)) return new SymbolResult(false, false, false, false, counts);
            if (!run.dispatch(timing.nanos())) return new SymbolResult(false, false, false, false, counts);
            if (!eligible(code, run)) return new SymbolResult(false, false, false, false, counts);
            started = true;
            TechnicalRead read = client.technical(code, run.date);
            available = read.groups().values().stream().anyMatch(TechnicalGroup::available);
            if (historyBudgetExhausted(read) && !read.rateLimited() && eligible(code, run)) {
                if (!dryRun) {
                    var first = cache.write(read);
                    collect(counts, first); written = first.hasWritten();
                }
                // One local-budget retry uses the same shared quota. It cannot create a second worker-local budget.
                if (!run.dispatch(timing.nanos() + Duration.ofSeconds(60).toNanos()) || !eligible(code, run))
                    return new SymbolResult(true, written, available, !available, counts);
                read = client.technical(code, run.date);
                available |= read.groups().values().stream().anyMatch(TechnicalGroup::available);
            }
            boolean rateLimited = read.rateLimited();
            if (rateLimited) run.halt("RATE_LIMITED");
            // Only the response that reported 429 may retain its already-observed healthy groups.
            // Other in-flight workers are cancelled and may not commit after this global stop.
            if ((!rateLimited && run.stop.get() != null) || !sameDay(run)
                    || !radar.current(Integer.MAX_VALUE).contains(code))
                return new SymbolResult(true, written, available, !available, counts);
            boolean sourceFailure = read.groups().values().stream().anyMatch(g -> !g.available());
            boolean allSourceFailed = read.groups().values().stream().noneMatch(TechnicalGroup::available);
            var result = dryRun ? null : cache.write(read);
            if (result != null) { collect(counts, result); written |= result.hasWritten(); }
            boolean allWritesFailed = result != null && result.outcomes().values().stream()
                    .noneMatch(s -> Set.of("WRITTEN", "UNCHANGED").contains(s));
            boolean failed = allSourceFailed || allWritesFailed;
            return new SymbolResult(true, written, !failed && (sourceFailure || result != null && result.hasFailure()),
                    failed, counts);
        } catch (Unavailable failure) {
            if (failure.stopRun()) run.halt(failure.reason());
            return new SymbolResult(started, written, started && available, started && !available, counts);
        } catch (InterruptedException cancelled) {
            run.halt("INTERRUPTED");
            Thread.currentThread().interrupt();
            return new SymbolResult(started, written, started && available, started && !available, counts);
        } catch (RuntimeException failure) {
            return new SymbolResult(started, written, started && available, started && !available, counts);
        }
    }
    private boolean eligible(String code, Run run) {
        return run.stop.get() == null && sameDay(run) && radar.current(Integer.MAX_VALUE).contains(code);
    }
    private boolean sameDay(Run run) {
        if (timing.nanos() >= run.deadline) { run.halt("RUN_DEADLINE"); return false; }
        if (gate.sameDay(run.date)) return true;
        run.halt("STALE_QUERY"); return false;
    }
    private final class Run {
        private final LocalDate date;
        private final long deadline;
        private final AtomicReference<String> stop = new AtomicReference<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private long nextStart = timing.nanos(), windowStart = nextStart;
        private int windowCount;
        private Run(LocalDate date, long deadline) { this.date = date; this.deadline = deadline; }
        private void halt(String reason) {
            if (stop.compareAndSet(null, reason))
                threads.stream().filter(t -> t != Thread.currentThread()).forEach(Thread::interrupt);
        }
        /** One admission budget for both workers: <=3 new jobs/90s and >=3.1s between grants. */
        private synchronized boolean dispatch(long requestedStart) throws InterruptedException {
            if (stop.get() != null) return false;
            long start = Math.max(requestedStart, nextStart);
            if (windowCount >= BATCH_SIZE) start = Math.max(start, windowStart + BATCH_WINDOW.toNanos());
            if (start + SYMBOL_RESERVATION.toNanos() >= deadline) { halt("NOT_ADMITTED_DEADLINE"); return false; }
            long wait = start - timing.nanos();
            if (wait > 0) timing.sleep(Duration.ofNanos(wait));
            if (stop.get() != null) return false;
            long now = timing.nanos();
            if (now + SYMBOL_RESERVATION.toNanos() >= deadline) { halt("NOT_ADMITTED_DEADLINE"); return false; }
            if (now >= windowStart + BATCH_WINDOW.toNanos()) { windowStart = now; windowCount = 0; }
            windowCount++;
            nextStart = now + SYMBOL_INTERVAL.toNanos();
            timing.dispatched(now);
            return true;
        }
    }
    private static boolean historyBudgetExhausted(TechnicalRead read) {
        return read.groups().values().stream().anyMatch(g -> "HISTORY_BUDGET_EXHAUSTED".equals(g.reason()));
    }
    private static void collect(Map<String, Integer> counts, FubonTechnicalCache.Write write) {
        write.outcomes().forEach((group, outcome) -> counts.merge(group + ":" + outcome, 1, Integer::sum));
    }
    private Result finish(String outcome, boolean dryRun, String reason, int processed, int written,
                          int partial, int failed, int skipped, Map<String, Integer> groups) {
        Result result = new Result(outcome, dryRun, reason, processed, written, partial, failed, skipped, groups);
        lastResult = result; return result;
    }
    interface Timing {
        long nanos();
        void sleep(Duration duration) throws InterruptedException;
        default void dispatched(long atNanos) {}
    }
    private record SymbolResult(boolean processed, boolean written, boolean partial, boolean failed, Map<String, Integer> outcomes) {
        private SymbolResult { outcomes = Map.copyOf(outcomes); }
    }
    public record Result(String outcome, boolean dryRun, String reason, int processedCount, int writtenCount,
                         int partialCount, int failedCount, int skippedCount, Map<String, Integer> groupWriteOutcomes) {
        public Result { groupWriteOutcomes = Map.copyOf(groupWriteOutcomes); }
    }
}
