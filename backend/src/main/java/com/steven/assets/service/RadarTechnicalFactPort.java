package com.steven.assets.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Read-only historical Fubon technical capture boundary.
 *
 * <p>Returned rows are immutable source facts.  The resolver still validates
 * the exact 17-profile semantic/canonical-hash contract before admitting a
 * capture into a decision.</p>
 */
public interface RadarTechnicalFactPort {
    record Candidate(String profileId, LocalDate sourceDate, String contentHash,
                     Map<String, Object> parameters, Map<String, String> payload,
                     Instant observedAt, LocalDate previousSourceDate,
                     String previousContentHash, Map<String, String> previousPayload) {
        public Candidate {
            parameters = parameters == null ? null : Map.copyOf(parameters);
            payload = payload == null ? null : Map.copyOf(payload);
            previousPayload = previousPayload == null ? null : Map.copyOf(previousPayload);
        }
    }

    record Capture(String captureId, Instant oldestObservedAt, List<Candidate> candidates) {
        public Capture { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
    }

    /** Latest fresh complete capture per requested stock code, never a partial bundle. */
    Map<String, Capture> findFreshCompleteCaptures(List<String> stockCodes, Instant now);
}
