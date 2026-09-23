package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TradingRadarPanelDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, process-local manual jobs. Reading a status never starts or retries external work. */
@Service
public class TradingRadarRefreshJobService {
    private static final int MAX_JOBS = 128;
    private static final Duration DEADLINE = Duration.ofSeconds(45);
    private static final Duration RETENTION = Duration.ofMinutes(5);

    private final TradingRadarRefreshService refreshService;
    private final CurrentUserContext currentUserContext;
    private final Clock clock;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<Long, UUID> activeByOwner = new HashMap<>();
    private boolean closed;

    @Autowired
    public TradingRadarRefreshJobService(TradingRadarRefreshService refreshService,
                                         CurrentUserContext currentUserContext) {
        this(refreshService, currentUserContext, Clock.systemUTC(), new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
                    Thread thread = new Thread(task, "trading-radar-manual-refresh");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    /** Injectable clock and manual executor allow deterministic lifecycle tests without sleeping. */
    TradingRadarRefreshJobService(TradingRadarRefreshService refreshService, CurrentUserContext currentUserContext,
                                  Clock clock, ExecutorService executor) {
        this.refreshService = refreshService;
        this.currentUserContext = currentUserContext;
        this.clock = clock;
        this.executor = executor;
    }

    public TradingRadarPanelDto.RefreshJob start() {
        long owner = owner();
        synchronized (lock) {
            Instant now = clock.instant();
            cleanup(now);
            UUID active = activeByOwner.get(owner);
            if (active != null) return response(jobs.get(active));
            if (closed || jobs.size() >= MAX_JOBS) throw new TradingRadarRefreshUnavailableException();
            Job job = new Job(UUID.randomUUID(), owner, now);
            jobs.put(job.id, job);
            activeByOwner.put(owner, job.id);
            try {
                executor.execute(() -> run(job));
            } catch (RejectedExecutionException unavailable) {
                jobs.remove(job.id);
                activeByOwner.remove(owner, job.id);
                throw new TradingRadarRefreshUnavailableException();
            }
            return response(job);
        }
    }

    public TradingRadarPanelDto.RefreshJob get(String rawId) {
        long owner = owner();
        UUID id;
        try {
            id = UUID.fromString(rawId);
            if (!id.toString().equalsIgnoreCase(rawId)) throw new IllegalArgumentException();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("工作代碼格式不合法");
        }
        synchronized (lock) {
            cleanup(clock.instant());
            Job job = jobs.get(id);
            if (job == null || job.owner != owner) throw new NoSuchElementException("找不到更新工作");
            return response(job);
        }
    }

    private long owner() {
        if (!currentUserContext.hasUser()) throw new UnauthenticatedException();
        Long owner = currentUserContext.getEffectiveUserId();
        if (owner == null) throw new UnauthenticatedException();
        return owner;
    }

    private void run(Job job) {
        synchronized (lock) {
            expire(job, clock.instant());
            if (closed || job.finished) {
                finishFailed(job, clock.instant());
                return;
            }
            job.started = true;
            job.status = "RUNNING";
        }
        TradingRadarDto.PriceRefresh result = null;
        try {
            // Only owner is carried into the worker; no servlet, tenant proxy or JPA entity is retained.
            TradingRadarDto.RefreshResponse refreshed = refreshService.refreshForOwner(job.owner);
            if (refreshed != null) result = refreshed.priceRefresh();
        } catch (RuntimeException unavailable) {
            // The status contract intentionally exposes neither exception strings nor owner information.
        } finally {
            synchronized (lock) {
                Instant now = clock.instant();
                expire(job, now);
                if (!closed && !"FAILED".equals(job.status) && result != null) {
                    job.status = "COMPLETED";
                    job.priceRefresh = result;
                    job.completedAt = now;
                } else if (!"FAILED".equals(job.status)) {
                    job.status = "FAILED";
                    job.completedAt = now;
                }
                job.finished = true;
                activeByOwner.remove(job.owner, job.id);
            }
        }
    }

    private void expire(Job job, Instant now) {
        if (!job.finished && !now.isBefore(job.createdAt.plus(DEADLINE))) {
            job.status = "FAILED";
            job.completedAt = job.createdAt.plus(DEADLINE);
            job.priceRefresh = null;
            // A queued task is permanently fenced from executing. A running task keeps its owner fence.
            if (!job.started) {
                job.finished = true;
                activeByOwner.remove(job.owner, job.id);
            }
        }
    }

    private void cleanup(Instant now) {
        Iterator<Job> iterator = jobs.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            expire(job, now);
            if (job.finished && job.completedAt != null && !now.isBefore(job.completedAt.plus(RETENTION))) {
                iterator.remove();
            }
        }
    }

    private void finishFailed(Job job, Instant now) {
        job.status = "FAILED";
        if (job.completedAt == null) job.completedAt = now;
        job.priceRefresh = null;
        job.finished = true;
        activeByOwner.remove(job.owner, job.id);
    }

    private static TradingRadarPanelDto.RefreshJob response(Job job) {
        return new TradingRadarPanelDto.RefreshJob(job.id.toString(), job.status,
                job.createdAt.atOffset(ZoneOffset.UTC).toString(),
                job.completedAt == null ? null : job.completedAt.atOffset(ZoneOffset.UTC).toString(), job.priceRefresh);
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            closed = true;
            Instant now = clock.instant();
            for (Job job : jobs.values()) if (!job.started && !job.finished) finishFailed(job, now);
        }
        executor.shutdownNow();
    }

    private static final class Job {
        final UUID id;
        final long owner;
        final Instant createdAt;
        String status = "QUEUED";
        Instant completedAt;
        TradingRadarDto.PriceRefresh priceRefresh;
        boolean started;
        boolean finished;
        Job(UUID id, long owner, Instant createdAt) {
            this.id = id;
            this.owner = owner;
            this.createdAt = createdAt;
        }
    }
}
