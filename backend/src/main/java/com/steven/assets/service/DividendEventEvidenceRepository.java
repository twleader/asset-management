package com.steven.assets.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Application port for projection followed by decision-time dividend evidence resolution. */
public interface DividendEventEvidenceRepository {

    DividendEventEvidenceResolver.Resolution resolve(
            String code, String market, Instant decisionInstant, List<LocalDate> sessions);

    /**
     * Evidence-only bulk resolution for production and historical signal instants.
     * Implementations must never infer from or mutate dividend current-state rows.
     */
    default List<DividendEventEvidenceBatch.Result> resolveBatch(
            List<DividendEventEvidenceBatch.Query> queries) {
        if (queries == null || queries.isEmpty()) return List.of();
        return queries.stream().map(query -> {
            DividendEventEvidenceResolver.Resolution resolution = query == null
                    ? DividendEventEvidenceResolver.Resolution.MISSING
                    : resolve(query.code(), query.market(), query.decisionInstant(), query.sessions());
            return new DividendEventEvidenceBatch.Result(query, resolution);
        }).toList();
    }
}
