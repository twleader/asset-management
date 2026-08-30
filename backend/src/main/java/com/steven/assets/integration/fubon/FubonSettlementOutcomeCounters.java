package com.steven.assets.integration.fubon;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Independent process-local counters for {@link FubonSettlementOutcome} (Requirement 129 / Task 394). */
@Component
public class FubonSettlementOutcomeCounters {
    private final EnumMap<FubonSettlementOutcome, LongAdder> counters =
            new EnumMap<>(FubonSettlementOutcome.class);

    public FubonSettlementOutcomeCounters() {
        for (FubonSettlementOutcome outcome : FubonSettlementOutcome.values()) {
            counters.put(outcome, new LongAdder());
        }
    }

    public void increment(FubonSettlementOutcome outcome) {
        counters.get(outcome).increment();
    }

    public Map<FubonSettlementOutcome, Long> snapshot() {
        EnumMap<FubonSettlementOutcome, Long> snapshot = new EnumMap<>(FubonSettlementOutcome.class);
        counters.forEach((outcome, value) -> snapshot.put(outcome, value.sum()));
        return Map.copyOf(snapshot);
    }
}
