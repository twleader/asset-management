package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import java.time.Instant;
import java.util.List;

/** Atomic persisted-fact operations, no source fetching. */
public interface CanonicalDividendRepository {
    Facts load(Key key, Instant instant);
    void lock(Key key);
    int apply(Facts before, List<Day> target);
    boolean matches(Facts current, List<Day> target);
    ObservationBatch observationsAfter(long watermark, int limit);
}
