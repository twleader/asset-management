package com.steven.assets.service;

import java.util.Objects;

/** Immutable ownership decision captured once for one complete snapshot update. */
public record SnapshotStockScopeOwnership(Ownership fubonTwOwnership) {

    public SnapshotStockScopeOwnership {
        Objects.requireNonNull(fubonTwOwnership, "fubonTwOwnership");
    }

    public static SnapshotStockScopeOwnership payloadOwned() {
        return new SnapshotStockScopeOwnership(Ownership.PAYLOAD_OWNED);
    }

    public static SnapshotStockScopeOwnership sourceOwnsFubonTw() {
        return new SnapshotStockScopeOwnership(Ownership.SOURCE_OWNED);
    }

    /** All scopes except Taiwan/Fubon remain payload-owned by design. */
    public Ownership ownershipFor(String market, String brokerCode) {
        if ("台股".equals(market) && "fubon".equals(brokerCode)) {
            return fubonTwOwnership;
        }
        return Ownership.PAYLOAD_OWNED;
    }

    public boolean isSourceOwned(String market, String brokerCode) {
        return ownershipFor(market, brokerCode) == Ownership.SOURCE_OWNED;
    }

    public enum Ownership {
        PAYLOAD_OWNED,
        SOURCE_OWNED
    }
}
