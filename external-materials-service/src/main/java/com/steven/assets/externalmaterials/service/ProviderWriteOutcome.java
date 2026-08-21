package com.steven.assets.externalmaterials.service;

/** Stable outcomes returned by the provider-timestamp Redis write path. */
public enum ProviderWriteOutcome {
    MARKET_CLOSED,
    WRITTEN,
    PROVIDER_TAKEOVER,
    STALE_OR_EQUAL,
    CURRENT_MALFORMED,
    WRITE_FAILED
}
