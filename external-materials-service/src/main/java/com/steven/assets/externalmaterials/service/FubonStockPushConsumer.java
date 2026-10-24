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
    private final StockSourceQuery source;
    // Only rows returned after the canonical service transaction committed enter this bounded map.
    private final Map<String, StockSourceQuery.IntradayQuote> pendingRepairs = new HashMap<>();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private volatile Authorization authorization = new Authorization(Set.of(), Instant.EPOCH);
    public FubonStockPushConsumer(MarketClock clock, FubonRadarScope radar, PriceCacheWriter writer, StockSourceQuery source) {
        this.clock = clock; this.radar = radar; this.writer = writer; this.source = source;
    }
    public synchronized void authorize(Collection<String> symbols, Instant validUntil) {
        authorization = new Authorization(Set.copyOf(symbols), validUntil);
        pendingRepairs.keySet().retainAll(authorization.symbols());
    }
    public synchronized void revoke() { authorization = new Authorization(Set.of(), Instant.EPOCH); pendingRepairs.clear(); }
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
                var raw = new com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult(
                        event.symbol(), MARKET, event.price(), null, null, STOCK_SOURCE, event.name(), null, null,
                        event.openPrice(), event.previousClose(), event.highPrice(), event.lowPrice(), null,
                        event.sourceDate(), event.tradeTime());
                // The separate proxied service returns only after its database transaction completes.
                var persisted = source.persistIntradayQuote(raw);
                if (persisted == null || persisted.status() == StockSourceQuery.IntradayPersistenceResult.Status.FAILED
                        || persisted.canonical() == null) return count("DB_FAILED");
                return count(project(persisted.canonical()).name());
            }
        } catch (RuntimeException failure) { return count("FAILED"); }
    }
    /** Existing manager renewal retries committed rows even when no new trade occurs. */
    public synchronized void repairPending() {
        Instant now = clock.instant();
        if (authorization.symbols().isEmpty() || !now.isBefore(authorization.validUntil())) return;
        try {
            if (!clock.isTwMarketOpenKnown().orElse(false) || !inSession(now)) return;
            Set<String> currentRadar = Set.copyOf(radar.current(300));
            for (var entry : List.copyOf(pendingRepairs.entrySet())) {
                var canonical = entry.getValue();
                if (!authorization.symbols().contains(entry.getKey()) || !currentRadar.contains(entry.getKey())
                        || !now.atZone(MarketClock.TW_ZONE).toLocalDate().equals(canonical.tradingDate())) {
                    pendingRepairs.remove(entry.getKey());
                    continue;
                }
                if (!clock.instant().isBefore(authorization.validUntil())
                        || !clock.isTwMarketOpenKnown().orElse(false)) return;
                var latest = source.findIntradayQuote(entry.getKey(), MARKET);
                if (latest.isEmpty()) continue;
                canonical = latest.get();
                if (!now.atZone(MarketClock.TW_ZONE).toLocalDate().equals(canonical.tradingDate())) {
                    pendingRepairs.remove(entry.getKey());
                    continue;
                }
                if (!clock.instant().isBefore(authorization.validUntil())
                        || !clock.isTwMarketOpenKnown().orElse(false) || !inSession(clock.instant())) return;
                count(project(canonical).name());
            }
        } catch (RuntimeException failure) { count("REPAIR_FAILED"); }
    }
    private PriceCacheWriter.CacheWriteOutcome project(StockSourceQuery.IntradayQuote canonical) {
        var result = canonical.asPriceResult();
        PriceCacheWriter.CacheWriteOutcome outcome;
        try {
            outcome = writer.writeTaiwanLive(result,
                    STOCK_SOURCE.equals(result.source()) || "FUBON_INTRADAY".equals(result.source()));
        } catch (RuntimeException failure) { outcome = PriceCacheWriter.CacheWriteOutcome.FAILED; }
        if (outcome == null) outcome = PriceCacheWriter.CacheWriteOutcome.FAILED;
        if (outcome == PriceCacheWriter.CacheWriteOutcome.FAILED) {
            if (pendingRepairs.size() < 300 || pendingRepairs.containsKey(canonical.stockCode()))
                pendingRepairs.put(canonical.stockCode(), canonical);
        } else pendingRepairs.remove(canonical.stockCode());
        return outcome;
    }
    private String count(String outcome) {
        counters.computeIfAbsent(outcome, key -> new LongAdder()).increment(); return outcome;
    }
    public Map<String, Long> counters() {
        Map<String, Long> copy = new TreeMap<>(); counters.forEach((key, count) -> copy.put(key, count.sum())); return Map.copyOf(copy);
    }
    private record Authorization(Set<String> symbols, Instant validUntil) {}
}
