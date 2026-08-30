package com.steven.assets.integration.fubon;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Independent process-local counters for {@link FubonBankBalanceOutcome} (Requirement 128 / Task 393). */
@Component
public class FubonBankBalanceOutcomeCounters {
    private final EnumMap<FubonBankBalanceOutcome, LongAdder> counters =
            new EnumMap<>(FubonBankBalanceOutcome.class);

    public FubonBankBalanceOutcomeCounters() {
        for (FubonBankBalanceOutcome outcome : FubonBankBalanceOutcome.values()) {
            counters.put(outcome, new LongAdder());
        }
    }

    public void increment(FubonBankBalanceOutcome outcome) {
        counters.get(outcome).increment();
    }

    public Map<FubonBankBalanceOutcome, Long> snapshot() {
        EnumMap<FubonBankBalanceOutcome, Long> snapshot = new EnumMap<>(FubonBankBalanceOutcome.class);
        counters.forEach((outcome, value) -> snapshot.put(outcome, value.sum()));
        return Map.copyOf(snapshot);
    }
}
