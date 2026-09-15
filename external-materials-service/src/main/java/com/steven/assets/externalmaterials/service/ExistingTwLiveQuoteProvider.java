package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Legacy test fixture only. Task 370 routes every production Taiwan LIVE round through
 * {@link TwLiveQuoteDispatcher}, which persists the canonical database snapshot before Redis.
 */
@Slf4j
@Deprecated(forRemoval = false)
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
