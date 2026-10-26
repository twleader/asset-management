package com.steven.assets.externalmaterials.model.marketdata;

import com.steven.assets.externalmaterials.model.marketdata.QuoteDetailData;

/** Immutable market contract shared by core and IO adapters. */
public final class OrderBookData {
    private OrderBookData() {}
    public enum PersistStatus { APPLIED, STALE_OR_EQUAL, FAILED }
    public enum ReadStatus { FOUND, NOT_FOUND, FAILED }
    /** A typed full snapshot paired only with the revision assigned by PostgreSQL. */
    public record CanonicalSnapshot(QuoteDetailData.QuoteDetailResult snapshot, long canonicalRevision) {}
    public record PersistResult(PersistStatus status, CanonicalSnapshot canonical) {
        public static PersistResult applied(CanonicalSnapshot value) {
            return new PersistResult(PersistStatus.APPLIED, value);
        }

        public static PersistResult stale(CanonicalSnapshot value) {
            return new PersistResult(PersistStatus.STALE_OR_EQUAL, value);
        }

        public static PersistResult failed() {
            return new PersistResult(PersistStatus.FAILED, null);
        }
    }
    /** Header-only pure-read result; it distinguishes a DB failure from a normal miss. */
    public record RevisionLookup(ReadStatus status, long canonicalRevision) {
        public static RevisionLookup found(long revision) { return new RevisionLookup(ReadStatus.FOUND, revision); }
        public static RevisionLookup missing() { return new RevisionLookup(ReadStatus.NOT_FOUND, 0L); }
        public static RevisionLookup failed() { return new RevisionLookup(ReadStatus.FAILED, 0L); }
    }
    /** Full pure-read result; callers must not treat a failed DB read as a cache hit. */
    public record CanonicalLookup(ReadStatus status, CanonicalSnapshot canonical) {
        public static CanonicalLookup found(CanonicalSnapshot canonical) { return new CanonicalLookup(ReadStatus.FOUND, canonical); }
        public static CanonicalLookup missing() { return new CanonicalLookup(ReadStatus.NOT_FOUND, null); }
        public static CanonicalLookup failed() { return new CanonicalLookup(ReadStatus.FAILED, null); }
    }
}
