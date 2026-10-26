package com.steven.assets.externalmaterials.model.marketdata;

import java.time.Instant;
import java.util.List;

/** Immutable market contract shared by core and IO adapters. */
public final class FubonResponseData {
    private FubonResponseData() {}
    public enum WriteStatus { STORED, FAILED }
    public enum ReadStatus { FOUND, NOT_FOUND, FAILED }
    /** DB-returned row only; callers must never use a raw Fubon candidate as a Redis payload. */
    public record CanonicalResponse(
            String stockCode,
            String market,
            Instant receivedAt,
            String batchId,
            String countersJson,
            String responseRowJson) {}
    public record PersistResult(WriteStatus status, List<CanonicalResponse> canonicalRows) {
        public static PersistResult stored(List<CanonicalResponse> rows) {
            return new PersistResult(WriteStatus.STORED, List.copyOf(rows));
        }

        public static PersistResult failed() { return new PersistResult(WriteStatus.FAILED, List.of()); }
    }
    public record ReadResult(ReadStatus status, CanonicalResponse canonical) {
        public static ReadResult found(CanonicalResponse value) { return new ReadResult(ReadStatus.FOUND, value); }
        public static ReadResult missing() { return new ReadResult(ReadStatus.NOT_FOUND, null); }
        public static ReadResult failed() { return new ReadResult(ReadStatus.FAILED, null); }
    }
}
