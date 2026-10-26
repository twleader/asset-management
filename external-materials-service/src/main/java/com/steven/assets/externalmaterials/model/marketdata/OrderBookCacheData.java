package com.steven.assets.externalmaterials.model.marketdata;

import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;

/** Immutable market contract shared by core and IO adapters. */
public final class OrderBookCacheData {
    private OrderBookCacheData() {}
    public enum WriteOutcome { WRITTEN, REJECTED_STALE, FAILED }
    public record CachedSnapshot(String canonicalRevision, QuoteDetailData.QuoteDetailResult snapshot) {}
}
