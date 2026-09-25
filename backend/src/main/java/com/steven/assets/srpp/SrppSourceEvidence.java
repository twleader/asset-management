package com.steven.assets.srpp;

import java.time.Instant;

/**
 * Requirement 163／Task 452.6：一個已凍結的 AVAILABLE source 與其原始 body。
 * {@code bodySha256 = sha256(UTF-8(body))} 於輸出時計算，不另存欄位。
 */
public record SrppSourceEvidence(
        String sourceId,
        String kind,
        String revision,
        Instant capturedAt,
        Instant dataAsOf,
        String body) {

    public String bodySha256() {
        return SrppJcs.sha256Hex(body);
    }
}
