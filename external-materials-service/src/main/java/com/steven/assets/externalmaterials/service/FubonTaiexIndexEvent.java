package com.steven.assets.externalmaterials.service;

/** Immutable, normalized-only event received from the adapter's internal SSE response. */
public record FubonTaiexIndexEvent(
        String symbol,
        String exchange,
        String type,
        String index,
        long timeMicros) {
}
