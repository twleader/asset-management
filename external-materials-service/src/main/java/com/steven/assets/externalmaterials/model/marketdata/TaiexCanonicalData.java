package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Immutable market contract shared by core and IO adapters. */
public final class TaiexCanonicalData {
    private TaiexCanonicalData() {}
    public enum PersistStatus { APPLIED, STALE_OR_EQUAL, FAILED }
    public enum ReadStatus { FOUND, NOT_FOUND, FAILED }
    /** Returned values came from PostgreSQL, never from an incoming raw SSE frame. */
    public record CanonicalIndex(
            String indexCode,
            String providerSymbol,
            String exchange,
            LocalDate tradingDate,
            Instant providerUpdatedAt,
            BigDecimal indexPoint,
            String source) {
    }
    public record Candidate(
            String providerSymbol,
            LocalDate tradingDate,
            Instant providerUpdatedAt,
            BigDecimal indexPoint) {
    }
    public record PersistResult(PersistStatus status, CanonicalIndex canonical) {
        public static PersistResult applied(CanonicalIndex row) {
            return new PersistResult(PersistStatus.APPLIED, row);
        }

        public static PersistResult stale(CanonicalIndex row) {
            return new PersistResult(PersistStatus.STALE_OR_EQUAL, row);
        }

        public static PersistResult failed() {
            return new PersistResult(PersistStatus.FAILED, null);
        }
    }
    public record FindResult(ReadStatus status, CanonicalIndex canonical) {
        public static FindResult found(CanonicalIndex row) {
            return new FindResult(ReadStatus.FOUND, row);
        }

        public static FindResult missing() {
            return new FindResult(ReadStatus.NOT_FOUND, null);
        }

        public static FindResult failed() {
            return new FindResult(ReadStatus.FAILED, null);
        }
    }
}
