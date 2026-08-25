package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent, single-flight sink for same-round Fubon best-five snapshots.
 *
 * <p>The executor has no waiting queue.  Busy, rejected, database, or Redis outcomes discard
 * only this optional projection and never hold the generic price dispatcher's in-flight guard.</p>
 */
@Slf4j
@Component
public class TwFubonOrderBookRoundWriter {

    private final ExecutorService executor;
    private final IntradayOrderBookSnapshotStore store;
    private final QuoteDetailCache cache;
    private final AtomicBoolean running = new AtomicBoolean();

    public TwFubonOrderBookRoundWriter(
            @Qualifier("twFubonOrderBookWriterExecutor") ExecutorService executor,
            IntradayOrderBookSnapshotStore store,
            QuoteDetailCache cache) {
        this.executor = executor;
        this.store = store;
        this.cache = cache;
    }

    public void submit(Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> snapshots) {
        if (snapshots == null || snapshots.isEmpty() || !running.compareAndSet(false, true)) return;
        final Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> immutable;
        try {
            immutable = Map.copyOf(new LinkedHashMap<>(snapshots));
        } catch (RuntimeException invalidInput) {
            running.set(false);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    run(immutable);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            running.set(false);
            log.info("tw Fubon order-book round skipped: worker busy or unavailable");
        }
    }

    private void run(Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> snapshots) {
        for (TwQuoteDetailFetchClient.QuoteDetailResult snapshot : snapshots.values()) {
            try {
                if (!store.isPersistable(snapshot)) continue;
                IntradayOrderBookSnapshotStore.PersistResult persisted = store.persist(snapshot);
                if (persisted.status() == IntradayOrderBookSnapshotStore.PersistStatus.FAILED
                        || persisted.canonical() == null) {
                    continue;
                }
                // The canonical row from an APPLIED write or stale conflict is the only cache input.
                cache.writeStrictNewer(persisted.canonical());
            } catch (RuntimeException isolatedFailure) {
                log.warn("五檔 round 個別 sink 失敗");
            }
        }
    }
}
