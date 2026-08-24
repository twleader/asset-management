package com.steven.assets.externalmaterials.service;

/** Fixed-cardinality summary for one Taiwan LIVE provider round. */
public record TwLiveQuoteBatchResult(
        MarketState marketState,
        RoundState roundState,
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

    /** A non-completed round is distinct from an open market with no requested quotes. */
    public enum RoundState {
        COMPLETED,
        IN_FLIGHT_SKIPPED
    }

    public static TwLiveQuoteBatchResult closed(int requested) {
        return new TwLiveQuoteBatchResult(MarketState.MARKET_CLOSED, RoundState.COMPLETED, requested, 0, 0, 0);
    }

    public static TwLiveQuoteBatchResult unknown(int requested) {
        return new TwLiveQuoteBatchResult(MarketState.MARKET_UNKNOWN, RoundState.COMPLETED, requested, 0, 0, 0);
    }

    public static TwLiveQuoteBatchResult open(int requested, int succeeded, int written, int failed) {
        return new TwLiveQuoteBatchResult(MarketState.OPEN, RoundState.COMPLETED, requested, succeeded, written, failed);
    }

    public static TwLiveQuoteBatchResult inFlightSkipped(int requested) {
        return new TwLiveQuoteBatchResult(MarketState.OPEN, RoundState.IN_FLIGHT_SKIPPED, requested, 0, 0, 0);
    }

    public boolean marketOpen() {
        return marketState == MarketState.OPEN;
    }

    public boolean inFlightSkipped() {
        return roundState == RoundState.IN_FLIGHT_SKIPPED;
    }
}
