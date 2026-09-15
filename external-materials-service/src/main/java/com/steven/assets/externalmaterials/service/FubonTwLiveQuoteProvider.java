package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient.BatchResult;
import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient.BatchStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Legacy test fixture only. Task 370 routes every production Taiwan LIVE round through
 * {@link TwLiveQuoteDispatcher}, which persists the canonical database snapshot before Redis.
 */
@Slf4j
@Deprecated(forRemoval = false)
public class FubonTwLiveQuoteProvider implements TwLiveQuoteProvider {

    private final FubonNormalizedQuoteClient client;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final TwLiveQuoteOutcomeCounters counters;

    public FubonTwLiveQuoteProvider(
            FubonNormalizedQuoteClient client,
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

        BatchResult batch = client.fetch(List.copyOf(codes));
        if (batch.status() == BatchStatus.MISCONFIGURED) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MISCONFIGURED);
            log.warn("tw-live-round provider=FUBON status=MISCONFIGURED requested={} written=0", requested);
            return TwLiveQuoteBatchResult.open(requested, 0, 0, requested);
        }
        if (batch.status() != BatchStatus.SUCCESS && batch.status() != BatchStatus.PARTIAL_FAILURE) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.PROVIDER_FAILED);
            log.warn("tw-live-round provider=FUBON status={} requested={} written=0", batch.status(), requested);
            return TwLiveQuoteBatchResult.open(requested, 0, 0, requested);
        }

        int written = 0;
        int failed = batch.rejected();
        for (ProviderTimedPriceObservation observation : batch.observations()) {
            ProviderWriteResult result;
            try {
                result = writer.writeProviderTimed(observation, true, true);
            } catch (Exception ex) {
                failed++;
                counters.increment(TwLiveQuoteOutcomeCounters.Outcome.WRITE_FAILED);
                continue;
            }
            if (result.mainPriceWritten()) {
                written++;
            } else {
                failed++;
                countWriteOutcome(result.outcome());
            }
            if (result.tickAppendFailed()) {
                counters.increment(TwLiveQuoteOutcomeCounters.Outcome.TICK_APPEND_FAILED);
                log.warn("tw-live tick append failed provider=FUBON reason=TICK_APPEND_FAILED");
            }
        }
        if (failed == 0) counters.increment(TwLiveQuoteOutcomeCounters.Outcome.SUCCESS);
        else counters.increment(TwLiveQuoteOutcomeCounters.Outcome.PARTIAL_FAILURE);
        log.info("tw-live-round provider=FUBON status={} requested={} normalized={} written={} failed={}",
                batch.status(), requested, batch.observations().size(), written, failed);
        return TwLiveQuoteBatchResult.open(
                requested, batch.observations().size(), written, failed);
    }

    private void countWriteOutcome(ProviderWriteOutcome outcome) {
        switch (outcome) {
            case STALE_OR_EQUAL -> counters.increment(TwLiveQuoteOutcomeCounters.Outcome.STALE_OR_EQUAL);
            case CURRENT_MALFORMED -> counters.increment(TwLiveQuoteOutcomeCounters.Outcome.CURRENT_MALFORMED);
            case WRITE_FAILED -> counters.increment(TwLiveQuoteOutcomeCounters.Outcome.WRITE_FAILED);
            case MARKET_CLOSED -> counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_CLOSED);
            case WRITTEN, PROVIDER_TAKEOVER -> { /* counted by SUCCESS/PARTIAL_FAILURE summary */ }
        }
    }
}
