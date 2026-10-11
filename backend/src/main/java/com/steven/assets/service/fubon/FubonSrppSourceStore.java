package com.steven.assets.service.fubon;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** Immutable official broker observations; no account secrets or inferred fills. */
public interface FubonSrppSourceStore {
    record Observation(UUID id, long owner, LocalDate date, String accountFingerprint,
                       Instant capturedAt, String status, String revision, ObjectNode payload) {
        public Observation { payload=payload.deepCopy(); }
        public ObjectNode payload(){return payload.deepCopy();}
    }
    Optional<Observation> latest(long owner, LocalDate date);
    void append(Observation observation);
}
