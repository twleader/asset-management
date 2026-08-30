package com.steven.assets.externalmaterials.service;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Rechecks scope and time at consumption; removed/expired/closed packets never enter the writer. */
@Service
public class FubonStockPushConsumer {
    private final MarketClock clock;
    private final FubonRadarScope radar;
    private final PriceCacheWriter writer;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private volatile Authorization authorization = new Authorization(Set.of(), Instant.EPOCH);
    public FubonStockPushConsumer(MarketClock clock, FubonRadarScope radar, PriceCacheWriter writer) {
        this.clock = clock; this.radar = radar; this.writer = writer;
    }
    public synchronized void authorize(Collection<String> symbols, Instant validUntil) {
        authorization = new Authorization(Set.copyOf(symbols), validUntil);
    }
    public synchronized void revoke() { authorization = new Authorization(Set.of(), Instant.EPOCH); }
    public String accept(StockEvent event) {
        Authorization captured = authorization;
        Instant now = clock.instant();
        if (captured.symbols().isEmpty() || !now.isBefore(captured.validUntil())) return count("DISABLED");
        if (!FubonStockPushContract.valid(event, now) || !captured.symbols().contains(event.symbol())) return count("INVALID_EVENT");
        try {
            var known = clock.isTwMarketOpenKnown();
            if (known.isEmpty()) return count("CALENDAR_UNKNOWN");
            if (!known.get()) return count("MARKET_CLOSED");
            if (!radar.current(300).contains(event.symbol())) return count("NOT_RADAR");
            synchronized (this) {
                Instant commitAt = clock.instant();
                if (captured != authorization || !commitAt.isBefore(captured.validUntil())
                        || !FubonStockPushContract.valid(event, commitAt)
                        || !clock.isTwMarketOpenKnown().orElse(false)) return count("DISABLED");
                return count(writer.writeFubonStockPush(event, commitAt).name());
            }
        } catch (RuntimeException failure) { return count("FAILED"); }
    }
    private String count(String outcome) {
        counters.computeIfAbsent(outcome, key -> new LongAdder()).increment(); return outcome;
    }
    public Map<String, Long> counters() {
        Map<String, Long> copy = new TreeMap<>(); counters.forEach((key, count) -> copy.put(key, count.sum())); return Map.copyOf(copy);
    }
    private record Authorization(Set<String> symbols, Instant validUntil) {}
}
