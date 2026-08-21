package com.steven.assets.externalmaterials.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** One gate and one selected strategy shared by every Taiwan LIVE entry point. */
@Slf4j
@Component
public class TwLiveQuoteDispatcher {

    static final int MAX_CODES_PER_ROUND = 100;

    private final MarketClock clock;
    private final TwLiveQuoteProvider provider;
    private final TwLiveQuoteOutcomeCounters counters;

    public TwLiveQuoteDispatcher(
            MarketClock clock,
            TwLiveQuoteProvider provider,
            TwLiveQuoteOutcomeCounters counters) {
        this.clock = clock;
        this.provider = provider;
        this.counters = counters;
    }

    public TwLiveQuoteBatchResult refresh(Set<String> rawCodes) {
        Authorization authorization = authorize();
        return refresh(rawCodes, authorization);
    }

    Authorization authorize() {
        try {
            Optional<Boolean> known = clock.isTwMarketOpenKnown();
            if (known.isEmpty()) {
                counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_UNKNOWN);
                return new Authorization(TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN);
            }
            if (known.get()) return new Authorization(TwLiveQuoteBatchResult.MarketState.OPEN);
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_CLOSED);
            return new Authorization(TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED);
        } catch (Exception ex) {
            counters.increment(TwLiveQuoteOutcomeCounters.Outcome.MARKET_UNKNOWN);
            return new Authorization(TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN);
        }
    }

    TwLiveQuoteBatchResult refresh(Set<String> rawCodes, Authorization authorization) {
        Set<String> codes = cappedCodes(rawCodes);
        if (authorization.state == TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN) {
            log.warn("tw-live-round status=MARKET_UNKNOWN requested={} providerCalls=0", codes.size());
            return TwLiveQuoteBatchResult.unknown(codes.size());
        }
        if (authorization.state == TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED) {
            log.info("tw-live-round status=MARKET_CLOSED requested={} providerCalls=0", codes.size());
            return TwLiveQuoteBatchResult.closed(codes.size());
        }
        return provider.refresh(codes, true);
    }

    private static Set<String> cappedCodes(Set<String> rawCodes) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (rawCodes == null) return result;
        for (String code : rawCodes) {
            if (code == null || code.isBlank() || "0000".equals(code)) continue;
            result.add(code);
            if (result.size() == MAX_CODES_PER_ROUND) break;
        }
        return result;
    }

    static final class Authorization {
        private final TwLiveQuoteBatchResult.MarketState state;

        private Authorization(TwLiveQuoteBatchResult.MarketState state) {
            this.state = state;
        }

        boolean marketOpen() {
            return state == TwLiveQuoteBatchResult.MarketState.OPEN;
        }

        boolean marketUnknown() {
            return state == TwLiveQuoteBatchResult.MarketState.MARKET_UNKNOWN;
        }
    }
}
