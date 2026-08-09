package com.steven.assets.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Raw data-access port for append-only dividend snapshot observations. */
public interface DividendEventEvidenceStore {

    DividendEventEvidenceResolver.Resolution resolve(
            String code, String market, Instant decisionInstant, List<LocalDate> sessions);

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
