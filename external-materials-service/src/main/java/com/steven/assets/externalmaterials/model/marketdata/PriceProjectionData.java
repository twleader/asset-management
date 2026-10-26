package com.steven.assets.externalmaterials.model.marketdata;

/** Immutable market contract shared by core and IO adapters. */
public final class PriceProjectionData {
    private PriceProjectionData() {}
    public enum CacheWriteOutcome {
        WRITTEN,
        REJECTED_STALE,
        SKIPPED_INVALID_PRICE,
        FAILED
    }
}
