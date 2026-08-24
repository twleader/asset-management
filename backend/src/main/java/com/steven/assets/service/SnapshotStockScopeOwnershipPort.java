package com.steven.assets.service;

/**
 * Service-side boundary for integrations which can own a stock scope during a
 * full snapshot update. The returned decision must be reused for the entire
 * transaction rather than re-checking mutable integration state per row.
 */
public interface SnapshotStockScopeOwnershipPort {

    SnapshotStockScopeOwnership capture(SnapshotUpdateTarget target);
}
