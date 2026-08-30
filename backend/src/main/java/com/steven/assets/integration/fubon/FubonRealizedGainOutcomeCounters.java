package com.steven.assets.integration.fubon;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Independent process-local counters for {@link FubonRealizedGainOutcome} (Requirement 130 / Task 395). */
@Component
public class FubonRealizedGainOutcomeCounters {
    private final EnumMap<FubonRealizedGainOutcome, LongAdder> counters =
            new EnumMap<>(FubonRealizedGainOutcome.class);

    public FubonRealizedGainOutcomeCounters() {
        for (FubonRealizedGainOutcome outcome : FubonRealizedGainOutcome.values()) {
            counters.put(outcome, new LongAdder());
        }
    }

    public void increment(FubonRealizedGainOutcome outcome) {
        counters.get(outcome).increment();
    }

    public Map<FubonRealizedGainOutcome, Long> snapshot() {
        EnumMap<FubonRealizedGainOutcome, Long> snapshot = new EnumMap<>(FubonRealizedGainOutcome.class);
        counters.forEach((outcome, value) -> snapshot.put(outcome, value.sum()));
        return Map.copyOf(snapshot);
    }
}
