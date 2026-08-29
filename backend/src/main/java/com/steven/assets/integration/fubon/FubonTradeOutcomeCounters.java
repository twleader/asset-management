package com.steven.assets.integration.fubon;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Independent process-local counters for {@link FubonTradeOutcome} (Requirement 120 / Task 385). */
@Component
public class FubonTradeOutcomeCounters {
    private final EnumMap<FubonTradeOutcome, LongAdder> counters = new EnumMap<>(FubonTradeOutcome.class);

    public FubonTradeOutcomeCounters() {
        for (FubonTradeOutcome outcome : FubonTradeOutcome.values()) counters.put(outcome, new LongAdder());
    }

    public void increment(FubonTradeOutcome outcome) {
        counters.get(outcome).increment();
    }

    public Map<FubonTradeOutcome, Long> snapshot() {
        EnumMap<FubonTradeOutcome, Long> snapshot = new EnumMap<>(FubonTradeOutcome.class);
        counters.forEach((outcome, value) -> snapshot.put(outcome, value.sum()));
        return Map.copyOf(snapshot);
    }
}
