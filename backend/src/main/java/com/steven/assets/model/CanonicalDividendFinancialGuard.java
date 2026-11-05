package com.steven.assets.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure qualification of the canonical financial projection; display-only announcements never enter here. */
public final class CanonicalDividendFinancialGuard {
    private CanonicalDividendFinancialGuard() {}

    public record Fact(String identity, String code, String market, LocalDate date,
                       BigDecimal cash, BigDecimal stock, String status, Long snapshotId,
                       String reason) {}
    public record EffectiveEvent(String code, String market, LocalDate date,
                                 BigDecimal cash, BigDecimal stock) {}
    public record Assessment(boolean available, List<EffectiveEvent> events, List<String> reasons) {
        public Assessment { events = List.copyOf(events); reasons = List.copyOf(reasons); }
        public void requireAvailable() {
            if (!available) throw new Unavailable(String.join(";", reasons));
        }
        /** US price history has no persisted split-range/basis proof; provider labels alone cannot prove comparability. */
        public void requireComparablePriceBasis() {
            requireAvailable();
            if (events.stream().anyMatch(event -> "美股".equals(event.market())
                    && (event.cash().signum() > 0 || event.stock().signum() > 0)))
                throw new Unavailable("US_DIVIDEND_PRICE_BASIS_UNVERIFIED");
        }
    }
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String reason) { super(reason); }
    }

    public static Assessment assess(List<Fact> facts) {
        if (facts == null) return unavailable("DIVIDEND_FACTS_MISSING");
        Map<String, Fact> seen = new HashMap<>();
        Map<Key, BigDecimal[]> byDate = new TreeMap<>(Comparator.comparing(Key::market)
                .thenComparing(Key::code).thenComparing(Key::date));
        Map<Key, Long> snapshots = new HashMap<>();
        for (Fact fact : facts) {
            if (fact == null) return unavailable("DIVIDEND_FACT_MISSING");
            if (!"RESOLVED".equals(fact.status()) || fact.date() == null || fact.snapshotId() == null
                    || fact.snapshotId() <= 0 || blank(fact.code()) || blank(fact.market()))
                return unavailable("CANONICAL_DIVIDEND_UNRESOLVED" + (blank(fact.reason()) ? "" : ":" + fact.reason()));
            if (fact.cash() == null || fact.stock() == null || fact.cash().signum() < 0 || fact.stock().signum() < 0)
                return unavailable("CANONICAL_DIVIDEND_AMOUNT_UNVERIFIED");
            if (blank(fact.identity())) return unavailable("CANONICAL_DIVIDEND_IDENTITY_MISSING");
            Fact prior = seen.putIfAbsent(fact.identity(), fact);
            if (prior != null) {
                if (!same(prior, fact)) return unavailable("CANONICAL_DIVIDEND_IDENTITY_CONFLICT");
                continue; // The same persisted row returned twice is still one fact.
            }
            Key key = new Key(fact.code(), fact.market(), fact.date());
            Long priorSnapshot = snapshots.putIfAbsent(key, fact.snapshotId());
            if (priorSnapshot != null && !priorSnapshot.equals(fact.snapshotId()))
                return unavailable("CANONICAL_DIVIDEND_SOURCE_SET_CONFLICT");
            BigDecimal[] totals = byDate.computeIfAbsent(key, ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            totals[0] = totals[0].add(fact.cash());
            totals[1] = totals[1].add(fact.stock());
        }
        List<EffectiveEvent> events = byDate.entrySet().stream().map(entry -> new EffectiveEvent(
                entry.getKey().code(), entry.getKey().market(), entry.getKey().date(),
                entry.getValue()[0], entry.getValue()[1])).toList();
        // An empty read proves no observed applied event, never completeness/non-distributing status.
        return new Assessment(true, events, List.of());
    }
    private record Key(String code, String market, LocalDate date) {}
    private static boolean same(Fact a, Fact b) {
        return Objects.equals(a.code(), b.code()) && Objects.equals(a.market(), b.market())
                && Objects.equals(a.date(), b.date()) && Objects.equals(a.snapshotId(), b.snapshotId())
                && a.cash().compareTo(b.cash()) == 0 && a.stock().compareTo(b.stock()) == 0;
    }
    private static Assessment unavailable(String reason) { return new Assessment(false, List.of(), List.of(reason)); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
