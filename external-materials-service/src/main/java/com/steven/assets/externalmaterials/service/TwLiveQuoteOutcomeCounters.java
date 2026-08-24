package com.steven.assets.externalmaterials.service;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Process-local counters with a fixed enum key space; symbols and provider text never become labels. */
@Component
public class TwLiveQuoteOutcomeCounters {

    public enum Outcome {
        MARKET_CLOSED,
        MARKET_UNKNOWN,
        SUCCESS,
        PARTIAL_FAILURE,
        PROVIDER_FAILED,
        MISCONFIGURED,
        MAPPING_REJECTED,
        STALE_OR_EQUAL,
        CURRENT_MALFORMED,
        WRITE_FAILED,
        TICK_APPEND_FAILED,
        IN_FLIGHT_SKIPPED,
        DB_APPLIED,
        DB_STALE_OR_EQUAL,
        DB_FAILED,
        REDIS_WRITTEN,
        REDIS_REJECTED,
        REDIS_FAILED,
        CANONICAL_REPAIR
    }

    private final EnumMap<Outcome, LongAdder> values = new EnumMap<>(Outcome.class);

    public TwLiveQuoteOutcomeCounters() {
        for (Outcome outcome : Outcome.values()) {
            values.put(outcome, new LongAdder());
        }
    }

    public void increment(Outcome outcome) {
        values.get(outcome).increment();
    }

    public Map<Outcome, Long> snapshot() {
        EnumMap<Outcome, Long> snapshot = new EnumMap<>(Outcome.class);
        values.forEach((outcome, value) -> snapshot.put(outcome, value.sum()));
        return Map.copyOf(snapshot);
    }
}
