package com.steven.assets.externalmaterials.service;

/** Fixed-cardinality summary for one Taiwan LIVE provider round. */
public record TwLiveQuoteBatchResult(
        MarketState marketState,
        int requested,
        int succeeded,
        int written,
        int failed
) {
    public enum MarketState {
        OPEN,
        MARKET_CLOSED,
        MARKET_UNKNOWN
    }

    public static TwLiveQuoteBatchResult closed(int requested) {
        return new TwLiveQuoteBatchResult(MarketState.MARKET_CLOSED, requested, 0, 0, 0);
    }

    public static TwLiveQuoteBatchResult unknown(int requested) {
        return new TwLiveQuoteBatchResult(MarketState.MARKET_UNKNOWN, requested, 0, 0, 0);
    }

    public static TwLiveQuoteBatchResult open(int requested, int succeeded, int written, int failed) {
        return new TwLiveQuoteBatchResult(MarketState.OPEN, requested, succeeded, written, failed);
    }

    public boolean marketOpen() {
        return marketState == MarketState.OPEN;
    }
}
