package com.steven.assets.externalmaterials.model.marketdata;

/** Immutable market contract shared by core and IO adapters. */
public final class DividendEvidenceData {
    private DividendEvidenceData() {}
    public record PersistResult(long snapshotId, String contentHash, boolean complete, String status) {}
}
