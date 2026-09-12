package com.steven.assets.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Infrastructure boundary for Task408's two-key radar technical overlay.
 *
 * <p>The resolver owns strict document semantics; this port owns the atomic
 * Redis MGET/CAS/Lua operation and never contacts a market-data provider.</p>
 */
public interface RadarTechnicalCachePort {
    record Pair(String daily, String weekly) {}

    record Generations(String daily, String weekly) {}

    record PairWrite(String code, Generations expected, String dailyDocument,
                     String weeklyDocument, Instant freshUntil,
                     boolean allowFubonReplaceLocal) {}

    /**
     * A non-TW Radar target has no Fubon fact bundle, but still needs the
     * user's Redis-first local-result reuse.  This intentionally has a
     * market dimension rather than reusing the Taiwan/Fubon D/W keyspace.
     */
    record MarketLocalWrite(String market, String code, String expectedDocument,
                            String document, Instant freshUntil) {}

    /**
     * Exact market/code identity for the non-TW local technical cache.  The
     * market component is mandatory: a reused code must never read another
     * market's snapshot.
     */
    record MarketLocalKey(String market, String code) {}

    /** Reads every requested D/W pair using one infrastructure batch. */
    Map<String, Pair> readPairs(List<String> codes);

    /** Executes the pair CAS/Lua fence and returns its stable outcome token. */
    String writePair(PairWrite request);

    /** Reads one market-safe local snapshot; a malformed value is handled by the resolver. */
    String readMarketLocal(String market, String code);

    /**
     * Reads a request's market-safe local snapshots through one MGET.  Missing
     * keys are absent from the returned map; callers must not turn a miss into
     * an individual GET during a list evaluation.
     */
    Map<MarketLocalKey, String> readMarketLocals(List<MarketLocalKey> keys);

    /** Atomically writes a market-safe local snapshot through an absolute-deadline CAS. */
    String writeMarketLocal(MarketLocalWrite request);
}
