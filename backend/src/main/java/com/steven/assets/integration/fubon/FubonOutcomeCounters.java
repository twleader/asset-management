package com.steven.assets.integration.fubon;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Component
public class FubonOutcomeCounters {
    private final EnumMap<FubonOutcome, LongAdder> counters = new EnumMap<>(FubonOutcome.class);

    public FubonOutcomeCounters() {
        for (FubonOutcome outcome : FubonOutcome.values()) counters.put(outcome, new LongAdder());
    }

    public void increment(FubonOutcome outcome) {
        counters.get(outcome).increment();
    }

    public Map<FubonOutcome, Long> snapshot() {
        EnumMap<FubonOutcome, Long> snapshot = new EnumMap<>(FubonOutcome.class);
        counters.forEach((outcome, value) -> snapshot.put(outcome, value.sum()));
        return Map.copyOf(snapshot);
    }
}
