package com.steven.assets.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Read-only numeric market-source port; implementations must never parse news text. */
public interface TradingRadarMarketFeaturePort {

    TradingRadarMarketFeatureResolver.Sources load(String market, Instant decisionInstant);

    /**
     * Bounded batch hook. Database adapters override this so a walk-forward run can load all
     * source rows once instead of issuing one query set per signal date.
     */
    default TradingRadarMarketFeatureResolver.Sources loadRange(
            String market, Instant earliestDecision, Instant latestDecision) {
        return load(market, latestDecision);
    }

    default TradingRadarMarketFeatureResolver.Evidence resolve(
            String market, Instant decisionInstant) {
        return TradingRadarMarketFeatureResolver.resolve(
                market, decisionInstant, load(market, decisionInstant));
    }

    /** Resolve with calendar-selected terminal sessions; unknown strict dates fail closed. */
    default TradingRadarMarketFeatureResolver.Evidence resolve(
            String market,
            Instant decisionInstant,
            TradingRadarMarketFeatureResolver.ExpectedSessions expectedSessions) {
        return TradingRadarMarketFeatureResolver.resolve(
                market, decisionInstant, load(market, decisionInstant), expectedSessions);
    }

    /** Resolve many decision instants from one immutable source snapshot. */
    default Map<Instant, TradingRadarMarketFeatureResolver.Evidence> resolveBatch(
            String market, List<Instant> decisionInstants) {
        return resolveBatch(market, decisionInstants, TradingRadarMarketFeatureResolver.ExpectedSessions.none());
    }

    /** Batch variant with the same strict terminal-session contract. */
    default Map<Instant, TradingRadarMarketFeatureResolver.Evidence> resolveBatch(
            String market,
            List<Instant> decisionInstants,
            TradingRadarMarketFeatureResolver.ExpectedSessions expectedSessions) {
        if (decisionInstants == null || decisionInstants.isEmpty()) return Map.of();
        List<Instant> ordered = new ArrayList<>(decisionInstants.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList());
        if (ordered.isEmpty()) return Map.of();
        TradingRadarMarketFeatureResolver.Sources sources = loadRange(
                market, ordered.get(0), ordered.get(ordered.size() - 1));
        Map<Instant, TradingRadarMarketFeatureResolver.Evidence> out = new LinkedHashMap<>();
        for (Instant decision : ordered) {
            out.put(decision, TradingRadarMarketFeatureResolver.resolve(
                    market, decision, sources, expectedSessions));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Batch resolver for historical decisions whose calendar-selected terminal
     * session differs by instant.  It still performs one immutable loadRange;
     * only the pure as-of projection varies per decision.
     */
    default Map<Instant, TradingRadarMarketFeatureResolver.Evidence> resolveBatch(
            String market,
            List<Instant> decisionInstants,
            Function<Instant, TradingRadarMarketFeatureResolver.ExpectedSessions> expectedByInstant) {
        if (decisionInstants == null || decisionInstants.isEmpty()) return Map.of();
        List<Instant> ordered = decisionInstants.stream().filter(java.util.Objects::nonNull)
                .distinct().sorted(Comparator.naturalOrder()).toList();
        if (ordered.isEmpty()) return Map.of();
        TradingRadarMarketFeatureResolver.Sources sources = loadRange(
                market, ordered.getFirst(), ordered.getLast());
        Map<Instant, TradingRadarMarketFeatureResolver.Evidence> out = new LinkedHashMap<>();
        for (Instant decision : ordered) {
            TradingRadarMarketFeatureResolver.ExpectedSessions expected = expectedByInstant == null
                    ? TradingRadarMarketFeatureResolver.ExpectedSessions.none()
                    : expectedByInstant.apply(decision);
            out.put(decision, TradingRadarMarketFeatureResolver.resolve(
                    market, decision, sources, expected));
        }
        return Collections.unmodifiableMap(out);
    }
}
