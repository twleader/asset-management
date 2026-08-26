package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Pure Redis-candidate plus PostgreSQL-revision reader for a canonical Taiwan best-five snapshot.
 * A cache hit is never served until PostgreSQL confirms its exact canonical revision.
 */
@Service
public class QuoteDetailReadService {

    private static final String UNAVAILABLE = "暫時無法取得行情五檔";

    private final QuoteDetailCache cache;
    private final IntradayOrderBookSnapshotStore store;

    public QuoteDetailReadService(QuoteDetailCache cache, IntradayOrderBookSnapshotStore store) {
        this.cache = cache;
        this.store = store;
    }

    public TwQuoteDetailFetchClient.QuoteDetailResult read(String code, String market) {
        if (!"台股".equals(market) || code == null || "0000".equals(code)) {
            return unavailable(code, market, false, "此市場不支援行情五檔");
        }
        Optional<QuoteDetailCache.CachedSnapshot> cached = cache.find(code, market);
        IntradayOrderBookSnapshotStore.RevisionLookup revision = store.findRevision(code, market);
        if (revision.status() != IntradayOrderBookSnapshotStore.ReadStatus.FOUND) {
            return unavailable(code, market, true, UNAVAILABLE);
        }
        if (cached.filter(value -> value.canonicalRevision().equals(QuoteDetailCache.revisionText(revision.canonicalRevision())))
                .isPresent()) {
            return cached.orElseThrow().snapshot();
        }
        IntradayOrderBookSnapshotStore.CanonicalLookup canonical = store.findCanonical(code, market);
        // The full lookup is one repeatable-read snapshot. A writer may legitimately commit r+1
        // after the earlier header-only lookup but before this transaction begins; that complete
        // r+1 canonical snapshot is safer and newer than turning a valid book into unavailable.
        if (canonical.status() == IntradayOrderBookSnapshotStore.ReadStatus.FOUND
                && canonical.canonical() != null) {
            return canonical.canonical().snapshot();
        }
        // A failed/full DB read is deliberately not allowed to fall back to freshness-unknown Redis.
        return unavailable(code, market, true, UNAVAILABLE);
    }

    static TwQuoteDetailFetchClient.QuoteDetailResult unavailable(
            String code, String market, boolean supported, String message) {
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                code, null, market, supported, false, null, message, null, null, "UNKNOWN",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of());
    }
}
