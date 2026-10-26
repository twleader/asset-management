package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.OrderBookData;
import com.steven.assets.externalmaterials.model.marketdata.OrderBookRules;
import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;
import com.steven.assets.externalmaterials.service.port.OrderBookCachePort;
import com.steven.assets.externalmaterials.service.port.OrderBookRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
    private final OrderBookRepository store;
    private final OrderBookCachePort cache;
    private final AtomicBoolean running = new AtomicBoolean();

    public TwFubonOrderBookRoundWriter(
            @Qualifier("twFubonOrderBookWriterExecutor") ExecutorService executor,
            OrderBookRepository store,
            OrderBookCachePort cache) {
        this.executor = executor;
        this.store = store;
        this.cache = cache;
    }

    public void submit(Map<String, QuoteDetailData.QuoteDetailResult> snapshots) {
        if (snapshots == null || snapshots.isEmpty() || !running.compareAndSet(false, true)) return;
        final Map<String, QuoteDetailData.QuoteDetailResult> immutable;
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

    private void run(Map<String, QuoteDetailData.QuoteDetailResult> snapshots) {
        for (QuoteDetailData.QuoteDetailResult snapshot : snapshots.values()) {
            try {
                if (!OrderBookRules.isPersistable(snapshot)) continue;
                OrderBookData.PersistResult persisted = store.persist(snapshot);
                if (persisted.status() == OrderBookData.PersistStatus.FAILED
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
