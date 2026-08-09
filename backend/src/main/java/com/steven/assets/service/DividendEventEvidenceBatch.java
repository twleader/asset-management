package com.steven.assets.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Value types for bulk, decision-time dividend evidence resolution. */
public final class DividendEventEvidenceBatch {

    private DividendEventEvidenceBatch() {}

    public record Query(
            String code,
            String market,
            Instant decisionInstant,
            List<LocalDate> sessions) {
        public Query {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
        }
    }

    public record Result(
            Query query,
            DividendEventEvidenceResolver.Resolution resolution) {}
}
