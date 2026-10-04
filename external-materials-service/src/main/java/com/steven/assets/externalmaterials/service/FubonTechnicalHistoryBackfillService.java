package com.steven.assets.externalmaterials.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Explicit, button-started Fubon official technical-history backfill. Never touches Redis. */
@Service
public class FubonTechnicalHistoryBackfillService {
    // The API does not document a minimum history date. Probe at most 35 windows and
    // report unknown depth rather than implying that this bound is the source floor.
    private static final int MAX_WINDOWS_PER_SYMBOL = 35;
    private static final int MAX_PROFILE_REQUESTS = 30 * MAX_WINDOWS_PER_SYMBOL * TECHNICAL_PROFILES.size();
    private static final Duration JOB_DEADLINE = Duration.ofHours(24);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final String enabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final FubonMarketDataHistoryStore history;
    private final Clock clock;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> Thread.ofVirtual().name("fubon-technical-history").unstarted(task),
            new ThreadPoolExecutor.AbortPolicy());
    private final Object lock = new Object();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private UUID active;

    @Autowired
    public FubonTechnicalHistoryBackfillService(
            @Value("${fubon.technical-history-backfill-enabled:false}") String enabled,
            FubonMarketRunGate gate, FubonRadarScope radar, FubonMarketDataPort client,
            FubonMarketDataHistoryStore history) {
        this(enabled, gate, radar, client, history, Clock.systemUTC());
    }

    FubonTechnicalHistoryBackfillService(String enabled, FubonMarketRunGate gate, FubonRadarScope radar,
            FubonMarketDataPort client, FubonMarketDataHistoryStore history, Clock clock) {
        this.enabled = enabled; this.gate = gate; this.radar = radar; this.client = client;
        this.history = history; this.clock = clock;
    }

    public View start() {
        String reason = gate.reasonForHistoricalRead(enabled, "TECHNICAL_HISTORY_BACKFILL_DISABLED");
        if (reason != null) throw new Unavailable(reason);
        List<String> symbols = radar.current(30);
        if (symbols.isEmpty()) throw new Unavailable("NO_SYMBOLS");
        synchronized (lock) {
            cleanup();
            if (active != null) return view(jobs.get(active));
            Job job = new Job(UUID.randomUUID(), symbols, clock.instant(), gate.latestCompletedTwDay());
            jobs.put(job.id, job);
            active = job.id;
            try { executor.execute(() -> run(job)); }
            catch (RuntimeException rejected) {
                job.status = "FAILED"; job.reason = "JOB_QUEUE_FULL"; job.completedAt = clock.instant(); active = null;
                throw new Unavailable("JOB_QUEUE_FULL");
            }
            return view(job);
        }
    }

    public View get(String rawId) {
        UUID id;
        try { id = UUID.fromString(rawId); }
        catch (RuntimeException invalid) { throw new Unavailable("JOB_NOT_FOUND"); }
        synchronized (lock) {
            cleanup();
            Job job = jobs.get(id);
            if (job == null) throw new Unavailable("JOB_NOT_FOUND");
            return view(job);
        }
    }

    private void run(Job job) {
        synchronized (lock) { job.status = "RUNNING"; }
        String terminalStatus = "FAILED";
        try {
            for (String symbol : job.symbols) {
                synchronized (lock) { job.currentSymbol = symbol; }
                LocalDate to = job.asOf;
                int emptyWindows = 0;
                int symbolWindows = 0;
                boolean boundaryUnknown = false;
                boolean symbolSchemaFailure = false;
                while (symbolWindows < MAX_WINDOWS_PER_SYMBOL) {
                    if (Duration.between(job.createdAt, clock.instant()).compareTo(JOB_DEADLINE) >= 0
                            || job.windows * TECHNICAL_PROFILES.size() >= MAX_PROFILE_REQUESTS) {
                        synchronized (lock) {
                            job.deadlineExceeded = Duration.between(job.createdAt, clock.instant()).compareTo(JOB_DEADLINE) >= 0;
                            job.depthUnknown = true;
                            // The budget/deadline stops every remaining symbol, so its
                            // job-level reason supersedes an earlier symbol-local schema failure.
                            job.reason = "HISTORY_DEPTH_UNKNOWN";
                        }
                        break;
                    }
                    LocalDate windowFrom = to.minusDays(TECHNICAL_MAX_SPAN_DAYS);
                    LocalDate windowTo = to;
                    synchronized (lock) {
                        job.currentFrom = windowFrom.toString();
                        job.currentTo = windowTo.toString();
                    }
                    TechnicalBundle bundle;
                    try {
                        bundle = client.technicalV2(symbol, windowFrom, windowTo);
                    } catch (RuntimeException failure) {
                        if (failure instanceof Unavailable unavailable
                                && "TECHNICAL_SCHEMA_INVALID".equals(unavailable.reason())) {
                            synchronized (lock) {
                                job.coverage.values().stream().filter(item -> item.symbol.equals(symbol))
                                        .forEach(item -> item.failed(windowFrom, windowTo, unavailable.reason()));
                                firstReason(job, unavailable.reason());
                                job.depthUnknown = true;
                            }
                            symbolSchemaFailure = true;
                            break;
                        }
                        synchronized (lock) {
                            job.coverage.values().stream().filter(item -> item.symbol.equals(symbol))
                                    .forEach(item -> item.failed(windowFrom, windowTo, failure instanceof Unavailable unavailable
                                            ? unavailable.reason() : "TECHNICAL_HISTORY_FAILED"));
                        }
                        throw failure;
                    }
                    symbolWindows++;
                    synchronized (lock) {
                        job.windows++;
                        job.profileResults += bundle.profiles().size();
                    }
                    boolean available = bundle.profiles().stream().anyMatch(profile -> profile.available() && !profile.history().isEmpty());
                    boolean allNoData = bundle.profiles().stream().allMatch(profile -> "NO_DATA".equals(profile.status()));
                    // A transport/quota failure stays job-wide even when a different
                    // profile in the same bundle has a symbol-local schema failure.
                    String globalProfileFailure = bundle.profiles().stream()
                            .filter(profile -> "UNAVAILABLE".equals(profile.status()))
                            .map(TechnicalProfileRead::reason).filter(java.util.Objects::nonNull)
                            .findFirst().orElse(null);
                    if (available) {
                        FubonMarketDataHistoryStore.TechnicalResult result = history.persistTechnical(bundle);
                        // FAILED rolls back the whole window. A conflict may still have
                        // committed other immutable facts, as reported by the writer.
                        if (result.facts() == FubonMarketDataHistoryStore.Status.FAILED
                                || result.facts() == FubonMarketDataHistoryStore.Status.CONFLICT_NO_SOURCE_REVISION) {
                            synchronized (lock) {
                                if (result.facts() != FubonMarketDataHistoryStore.Status.FAILED)
                                    job.factRows += result.factWritten() + result.factUnchanged();
                                job.coverage.values().stream().filter(item -> item.symbol.equals(symbol))
                                        .forEach(item -> item.failed(windowFrom, windowTo, "TECHNICAL_PERSISTENCE_FAILED"));
                                result.committedDatesByProfile().forEach((profileId, dates) ->
                                        job.coverage.get(symbol + "|" + profileId).observeCommitted(dates));
                            }
                            throw new Unavailable("TECHNICAL_PERSISTENCE_FAILED");
                        }
                        synchronized (lock) {
                            job.factRows += result.factWritten() + result.factUnchanged();
                            bundle.profiles().forEach(profile -> job.coverage.get(symbol + "|" + profile.profileId())
                                    .observe(profile, windowFrom, windowTo));
                            job.availableWindows++;
                        }
                        emptyWindows = 0;
                        if (globalProfileFailure != null) throw new Unavailable(globalProfileFailure);
                        String error = bundle.profiles().stream().filter(profile -> !profile.available()
                                        && !"NO_DATA".equals(profile.status()))
                                .map(profile -> "SCHEMA_INVALID".equals(profile.status())
                                        ? "TECHNICAL_SCHEMA_INVALID" : profile.reason())
                                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
                        if ("TECHNICAL_SCHEMA_INVALID".equals(error)) {
                            synchronized (lock) {
                                firstReason(job, error);
                                job.depthUnknown = true;
                            }
                            symbolSchemaFailure = true;
                            break;
                        }
                        if (error != null) throw new Unavailable(error);
                    } else {
                        synchronized (lock) {
                            bundle.profiles().forEach(profile -> job.coverage.get(symbol + "|" + profile.profileId())
                                    .observe(profile, windowFrom, windowTo));
                        }
                        if (allNoData) emptyWindows++;
                        else {
                            if (globalProfileFailure != null) throw new Unavailable(globalProfileFailure);
                            String reason = bundle.profiles().stream().filter(profile -> !"NO_DATA".equals(profile.status()))
                                    .map(profile -> "SCHEMA_INVALID".equals(profile.status())
                                            ? "TECHNICAL_SCHEMA_INVALID" : profile.reason())
                                    .filter(java.util.Objects::nonNull).findFirst().orElse("TECHNICAL_HISTORY_FAILED");
                            if ("TECHNICAL_SCHEMA_INVALID".equals(reason)) {
                                synchronized (lock) {
                                    firstReason(job, reason);
                                    job.depthUnknown = true;
                                }
                                symbolSchemaFailure = true;
                                break;
                            }
                            throw new Unavailable(reason);
                        }
                        if (emptyWindows >= 2) {
                            boundaryUnknown = true;
                            break;
                        }
                    }
                    to = windowFrom.minusDays(1);
                }
                synchronized (lock) {
                    if (symbolWindows >= MAX_WINDOWS_PER_SYMBOL || boundaryUnknown || symbolSchemaFailure)
                        job.depthUnknown = true;
                    if (!job.deadlineExceeded) job.completedSymbols++;
                }
                if (job.deadlineExceeded) break;
            }
            synchronized (lock) {
                terminalStatus = job.depthUnknown ? "PARTIAL" : "COMPLETED";
                if (job.depthUnknown) firstReason(job, "HISTORY_DEPTH_UNKNOWN");
            }
        } catch (Unavailable failure) {
            synchronized (lock) {
                job.reason = failure.reason();
                terminalStatus = job.availableWindows > 0 || job.factRows > 0 ? "PARTIAL" : "FAILED";
            }
        } catch (RuntimeException failure) {
            synchronized (lock) {
                job.reason = "TECHNICAL_HISTORY_FAILED";
                terminalStatus = job.availableWindows > 0 || job.factRows > 0 ? "PARTIAL" : "FAILED";
            }
        } finally {
            synchronized (lock) {
                job.status = terminalStatus;
                job.completedAt = clock.instant();
                if (job.id.equals(active)) active = null;
            }
        }
    }

    private static void firstReason(Job job, String reason) {
        if (job.reason == null) job.reason = reason;
    }

    private void cleanup() {
        Instant now = clock.instant();
        jobs.values().removeIf(job -> job.completedAt != null && !now.isBefore(job.completedAt.plus(RETENTION)));
    }

    private static View view(Job job) {
        return new View(job.id.toString(), job.status, job.createdAt.atOffset(ZoneOffset.UTC).toString(),
                job.completedAt == null ? null : job.completedAt.atOffset(ZoneOffset.UTC).toString(),
                job.symbols.size(), job.completedSymbols, job.currentSymbol,
                job.currentFrom == null ? null : job.currentFrom.toString(),
                job.currentTo == null ? null : job.currentTo.toString(), job.windows,
                job.availableWindows, job.factRows, job.profileResults, job.reason,
                job.coverage.values().stream().map(CoverageState::view).toList());
    }

    @PreDestroy public void close() { executor.shutdownNow(); }

    public record View(String jobId, String status, String createdAt, String completedAt,
                       int symbols, int completedSymbols, String currentSymbol,
                       String currentFrom, String currentTo, int windows,
                       int availableWindows, int factRows, int profileResults, String reason,
                       List<Coverage> coverage) {}

    public record Coverage(String symbol, String profileId, String status, String reason, String depthStatus,
                          String firstSourceDate, String lastSourceDate, int rows,
                          String lastQueryFrom, String lastQueryTo) {}

    private static final class CoverageState {
        final String symbol, profileId;
        volatile String status = "PENDING", reason, firstSourceDate, lastSourceDate, lastQueryFrom, lastQueryTo;
        volatile int rows;
        CoverageState(String symbol, String profileId) { this.symbol = symbol; this.profileId = profileId; }
        synchronized void observe(TechnicalProfileRead profile, LocalDate from, LocalDate to) {
            lastQueryFrom = from.toString(); lastQueryTo = to.toString();
            reason = profile.reason();
            if (profile.available() && !profile.history().isEmpty()) {
                status = "AVAILABLE";
                LocalDate earliest = profile.history().stream().map(TechnicalHistory::sourceDate).min(Comparator.naturalOrder()).orElseThrow();
                LocalDate latest = profile.history().stream().map(TechnicalHistory::sourceDate).max(Comparator.naturalOrder()).orElseThrow();
                if (firstSourceDate == null || earliest.isBefore(LocalDate.parse(firstSourceDate))) firstSourceDate = earliest.toString();
                if (lastSourceDate == null || latest.isAfter(LocalDate.parse(lastSourceDate))) lastSourceDate = latest.toString();
                rows += profile.history().size();
            } else if ("NO_DATA".equals(profile.status())) {
                if (rows == 0) status = "NO_DATA";
            } else if ("SCHEMA_INVALID".equals(profile.status())) status = "FAILED";
            else status = "FAILED";
        }
        synchronized void failed(LocalDate from, LocalDate to, String why) {
            status = "FAILED"; reason = why; lastQueryFrom = from.toString(); lastQueryTo = to.toString();
        }
        synchronized void observeCommitted(List<LocalDate> dates) {
            if (dates.isEmpty()) return;
            LocalDate earliest = dates.stream().min(Comparator.naturalOrder()).orElseThrow();
            LocalDate latest = dates.stream().max(Comparator.naturalOrder()).orElseThrow();
            if (firstSourceDate == null || earliest.isBefore(LocalDate.parse(firstSourceDate))) firstSourceDate = earliest.toString();
            if (lastSourceDate == null || latest.isAfter(LocalDate.parse(lastSourceDate))) lastSourceDate = latest.toString();
            rows += dates.size();
        }
        Coverage view() { return new Coverage(symbol, profileId, status, reason, "HISTORY_DEPTH_UNKNOWN",
                firstSourceDate, lastSourceDate, rows, lastQueryFrom, lastQueryTo); }
    }

    private static final class Job {
        final UUID id; final List<String> symbols; final Instant createdAt; final LocalDate asOf;
        volatile String status = "QUEUED", reason, currentSymbol, currentFrom, currentTo;
        volatile Instant completedAt;
        volatile int completedSymbols, windows, availableWindows, factRows;
        volatile int profileResults;
        volatile boolean depthUnknown, deadlineExceeded;
        final Map<String, CoverageState> coverage = new LinkedHashMap<>();
        Job(UUID id, List<String> symbols, Instant createdAt, LocalDate asOf) {
            this.id = id; this.symbols = List.copyOf(symbols); this.createdAt = createdAt; this.asOf = asOf;
            for (String symbol : symbols) for (TechnicalProfile profile : TECHNICAL_PROFILES) {
                coverage.put(symbol + "|" + profile.profileId(), new CoverageState(symbol, profile.profileId()));
            }
        }
    }
}
