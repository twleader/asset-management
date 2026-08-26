package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Independent bounded Yahoo fallback for selected Fubon codes that lacked a complete Fubon book.
 *
 * <p>A busy worker drops the entire submitted round rather than retaining an unbounded queue. An
 * accepted round uses a stable fair cursor (eight codes), at most four concurrent probes, two
 * seconds per probe, and a nine-second cancellation boundary.</p>
 */
@Slf4j
@Component
public class TwYahooOrderBookFallbackRoundWriter {

    /** Dispatcher input is capped at its selected Fubon window; enforce the boundary defensively. */
    static final int MAX_CANDIDATES_PER_ROUND = 40;
    static final int CODES_PER_ROUND = 8;
    static final int MAX_PROBES_PER_ROUND = 16;
    static final int MAX_CONCURRENT_PROBES = 4;
    static final long ROUND_TIMEOUT_SECONDS = 9L;
    private static final Pattern TAIWAN_CODE = Pattern.compile("^[0-9]{4,6}[A-Z]?$");

    private final ExecutorService executor;
    private final TwQuoteDetailFetchClient yahoo;
    private final IntradayOrderBookSnapshotStore store;
    private final QuoteDetailCache cache;
    private final Duration roundTimeout;
    private final AtomicBoolean running = new AtomicBoolean();
    private int cursor;

    @Autowired
    public TwYahooOrderBookFallbackRoundWriter(
            @Qualifier("twYahooOrderBookFallbackExecutor") ExecutorService executor,
            TwQuoteDetailFetchClient yahoo,
            IntradayOrderBookSnapshotStore store,
            QuoteDetailCache cache) {
        this(executor, yahoo, store, cache, Duration.ofSeconds(ROUND_TIMEOUT_SECONDS));
    }

    /** Package-visible duration seam keeps the nine-second production boundary deterministic in tests. */
    TwYahooOrderBookFallbackRoundWriter(
            ExecutorService executor,
            TwQuoteDetailFetchClient yahoo,
            IntradayOrderBookSnapshotStore store,
            QuoteDetailCache cache,
            Duration roundTimeout) {
        this.executor = executor;
        this.yahoo = yahoo;
        this.store = store;
        this.cache = cache;
        if (roundTimeout == null || roundTimeout.isZero() || roundTimeout.isNegative()) {
            throw new IllegalArgumentException("roundTimeout must be positive");
        }
        this.roundTimeout = roundTimeout;
    }

    /** Accept only selected Taiwan candidates; no existing DB/Redis/API state influences this choice. */
    public void submit(Collection<String> candidates) {
        List<String> stable = stableCandidates(candidates);
        if (stable.isEmpty() || !running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    // Advance the fair cursor only after the executor has accepted this round.
                    // A rejected submission is not a successful fallback round and must not
                    // consume an eight-code window.
                    run(selectFairWindow(stable));
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
            log.info("tw Yahoo order-book round skipped: worker busy or unavailable");
        }
    }

    private void run(List<String> selected) {
        ExecutorService probes = Executors.newFixedThreadPool(MAX_CONCURRENT_PROBES,
                Thread.ofVirtual().name("tw-yahoo-order-book-probe-", 0).factory());
        try {
            List<Callable<Void>> tasks = selected.stream().<Callable<Void>>map(code -> () -> {
                probeAndPersist(code);
                return null;
            }).toList();
            List<Future<Void>> futures = probes.invokeAll(tasks, roundTimeout.toNanos(), TimeUnit.NANOSECONDS);
            for (Future<Void> future : futures) {
                if (!future.isDone()) future.cancel(true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            log.info("tw Yahoo order-book round ended without affecting the next dispatcher round");
        } finally {
            probes.shutdownNow();
        }
    }

    private void probeAndPersist(String code) {
        try {
            TwQuoteDetailFetchClient.YahooProbeOutcome outcome = yahoo.probeTw(code);
            if (outcome.kind() == TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.STRUCTURAL_MISS) {
                outcome = yahoo.probeTwo(code);
            }
            if (outcome.kind() != TwQuoteDetailFetchClient.YahooProbeOutcome.Kind.FOUND
                    || outcome.snapshot() == null || !store.isPersistable(outcome.snapshot())) {
                return;
            }
            IntradayOrderBookSnapshotStore.PersistResult persisted = store.persist(outcome.snapshot());
            if (persisted.status() != IntradayOrderBookSnapshotStore.PersistStatus.FAILED
                    && persisted.canonical() != null) {
                cache.writeStrictNewer(persisted.canonical());
            }
        } catch (RuntimeException isolatedFailure) {
            log.info("tw Yahoo order-book code={} unavailable for this round", code);
        }
    }

    private synchronized List<String> selectFairWindow(List<String> stable) {
        int start = Math.floorMod(cursor, stable.size());
        int count = Math.min(CODES_PER_ROUND, stable.size());
        List<String> selected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) selected.add(stable.get((start + index) % stable.size()));
        cursor = (start + count) % stable.size();
        if (stable.size() > count) {
            List<String> deferred = new ArrayList<>(stable.size() - count);
            for (int index = count; index < stable.size(); index++) deferred.add(stable.get((start + index) % stable.size()));
            log.info("tw Yahoo order-book deferredCodes={} reason=DEFERRED_BY_ROUND_BUDGET", deferred.size());
        }
        return List.copyOf(selected);
    }

    private static List<String> stableCandidates(Collection<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream().filter(TwYahooOrderBookFallbackRoundWriter::validTaiwanCode)
                .map(code -> code.trim().toUpperCase(java.util.Locale.ROOT)).distinct()
                .sorted(Comparator.naturalOrder()).limit(MAX_CANDIDATES_PER_ROUND).toList();
    }

    private static boolean validTaiwanCode(String code) {
        if (code == null) return false;
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        return !"0000".equals(normalized) && TAIWAN_CODE.matcher(normalized).matches();
    }
}
