package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Disabled-mode compatibility strategy: the existing public MIS client and legacy writer. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fubon", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ExistingTwLiveQuoteProvider implements TwLiveQuoteProvider {

    private final PriceFetchClient client;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final TwLiveQuoteOutcomeCounters counters;

    public ExistingTwLiveQuoteProvider(
            PriceFetchClient client,
            PriceCacheWriter writer,
            StockSourceQuery source,
            TwLiveQuoteOutcomeCounters counters) {
        this.client = client;
        this.writer = writer;
        this.source = source;
        this.counters = counters;
    }

    @Override
    public TwLiveQuoteBatchResult refresh(Set<String> codes, boolean marketOpenAuthorized) {
        int requested = codes == null ? 0 : codes.size();
        if (!marketOpenAuthorized) return TwLiveQuoteBatchResult.closed(requested);
        if (codes == null || codes.isEmpty()) return TwLiveQuoteBatchResult.open(0, 0, 0, 0);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (String code : codes) {
                pool.submit(() -> refreshOne(code, succeeded, written, failed));
            }
        }
        if (failed.get() == 0) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.SUCCESS);
        else counters.increment(TwLiveQuoteOutcomeCounters.Outcome.PARTIAL_FAILURE);
        log.info("tw-live-round provider=TWSE requested={} succeeded={} written={} failed={}",
                requested, succeeded.get(), written.get(), failed.get());
        return TwLiveQuoteBatchResult.open(
                requested, succeeded.get(), written.get(), failed.get());
    }

    private void refreshOne(
            String code,
            AtomicInteger succeeded,
            AtomicInteger written,
            AtomicInteger failed) {
        try {
            Optional<PriceResult> optional = client.getStockPrice(code, "台股");
            if (optional.isEmpty() || optional.get().price() == null) {
                failed.incrementAndGet();
                return;
            }
            PriceResult result = optional.get();
            succeeded.incrementAndGet();
            writer.write(result, false);
            written.incrementAndGet();
            if (result.stockName() != null && !result.stockName().isBlank()) {
                source.upsertStockName(code, "台股", result.stockName());
            }
        } catch (Exception ex) {
            failed.incrementAndGet();
        }
    }
}
