package com.steven.assets.service;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable identity of the snapshot being changed by one full PUT transaction.
 *
 * <p>The target is created only after the snapshot row lock, owner validation and
 * final effective date validation have completed. Integrations may use it to make
 * one transaction-scoped ownership decision without reading a pre-update date.
 */
public record SnapshotUpdateTarget(
        Long snapshotId,
        Long ownerUserId,
        LocalDate effectiveSnapshotDate) {

    public SnapshotUpdateTarget {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(effectiveSnapshotDate, "effectiveSnapshotDate");
    }
}
