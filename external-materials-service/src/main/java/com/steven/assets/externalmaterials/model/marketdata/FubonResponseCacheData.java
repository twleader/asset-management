package com.steven.assets.externalmaterials.model.marketdata;

import java.time.Instant;

/** Immutable market contract shared by core and IO adapters. */
public final class FubonResponseCacheData {
    private FubonResponseCacheData() {}
    public enum WriteOutcome { WRITTEN, REJECTED_STALE, FAILED }
    public record CachedResponse(
            Instant receivedAt,
            String batchId,
            String countersJson,
            String responseRowJson) {}
}
