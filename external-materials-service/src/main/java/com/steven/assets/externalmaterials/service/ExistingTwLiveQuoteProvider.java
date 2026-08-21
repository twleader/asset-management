package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Disabled-mode strategy backed by the bounded two-wave Task 350 MIS batch client. */
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

        PriceFetchClient.TwQuoteBatchSummary summary;
        try {
            summary = client.fetchTwBatch(codes);
        } catch (Exception ex) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.PROVIDER_FAILED);
            log.warn("tw-live-round provider=TWSE status=PROVIDER_FAILED requested={} written=0", requested);
            return TwLiveQuoteBatchResult.open(requested, 0, 0, requested);
        }

        int written = 0;
        int staleRejected = 0;
        int writeFailures = 0;
        for (var entry : summary.resolved().entrySet()) {
            PriceResult result = entry.getValue();
            PriceCacheWriter.CacheWriteOutcome outcome = writer.write(result, false);
            switch (outcome) {
                case WRITTEN -> {
                    written++;
                    if (result.stockName() != null && !result.stockName().isBlank()) {
                        try {
                            source.upsertStockName(entry.getKey(), "台股", result.stockName());
                        } catch (Exception ex) {
                            log.warn("tw-live stock-name update failed provider=TWSE reason=LOCAL_WRITE_FAILED");
                        }
                    }
                }
                case REJECTED_STALE -> {
                    staleRejected++;
                    counters.increment(TwLiveQuoteOutcomeCounters.Outcome.STALE_OR_EQUAL);
                }
                case FAILED, SKIPPED_INVALID_PRICE -> {
                    writeFailures++;
                    counters.increment(TwLiveQuoteOutcomeCounters.Outcome.WRITE_FAILED);
                }
            }
        }

        int failed = summary.missingCodes().size() + summary.invalidCodes().size() + writeFailures;
        boolean partialFailure = failed > 0 || summary.requestFailures() > 0
                || !summary.capacityRejectedCodes().isEmpty();
        counters.increment(partialFailure
                ? TwLiveQuoteOutcomeCounters.Outcome.PARTIAL_FAILURE
                : TwLiveQuoteOutcomeCounters.Outcome.SUCCESS);
        log.info("台股MIS batch requested={} resolved={} noTrade={} missing={} invalid={} "
                        + "httpRequests={} requestFailures={} written={} staleRejected={} writeFailures={} "
                        + "foreignCodes={} schemaAnomalies={} sourceTimeAnomalyCodes={} capacityRejectedCodes={}",
                summary.requestedCount(), summary.resolved().size(), summary.noTradeCodes().size(),
                summary.missingCodes().size(), summary.invalidCodes().size(),
                summary.httpRequests(), summary.requestFailures(), written, staleRejected, writeFailures,
                summary.foreignCodes(), summary.schemaAnomalies(), summary.sourceTimeAnomalyCodes(),
                summary.capacityRejectedCodes());
        return TwLiveQuoteBatchResult.open(requested, summary.resolved().size(), written, failed);
    }
}
