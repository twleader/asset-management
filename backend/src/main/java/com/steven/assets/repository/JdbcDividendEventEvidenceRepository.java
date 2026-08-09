package com.steven.assets.repository;

import com.steven.assets.service.DividendEventEvidenceBatch;
import com.steven.assets.service.DividendEventEvidenceResolver;
import com.steven.assets.service.DividendEventEvidenceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC adapter for append-only dividend snapshots; all decision semantics stay pure. */
@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcDividendEventEvidenceRepository implements DividendEventEvidenceStore {

    private final JdbcTemplate jdbc;

    @Override
    public DividendEventEvidenceResolver.Resolution resolve(
            String code, String market, Instant decisionInstant, List<LocalDate> sessions) {
        List<DividendEventEvidenceBatch.Result> results = resolveBatch(List.of(
                new DividendEventEvidenceBatch.Query(code, market, decisionInstant, sessions)));
        return results.isEmpty()
                ? DividendEventEvidenceResolver.Resolution.MISSING : results.getFirst().resolution();
    }

    /**
     * Loads each code/market observation stream once through the latest requested
     * decision instant, then applies the pure resolver independently to every
     * signal instant.  No current-state table participates in this path.
     */
    @Override
    public List<DividendEventEvidenceBatch.Result> resolveBatch(
            List<DividendEventEvidenceBatch.Query> queries) {
        if (queries == null || queries.isEmpty()) return List.of();

        Map<StockKey, Instant> latestDecision = new LinkedHashMap<>();
        for (DividendEventEvidenceBatch.Query query : queries) {
            if (!valid(query)) continue;
            StockKey key = new StockKey(query.code(), query.market());
            latestDecision.merge(key, query.decisionInstant(),
                    (left, right) -> left.isAfter(right) ? left : right);
        }

        Map<StockKey, List<DividendEventEvidenceResolver.SnapshotObservation>> observations =
                new LinkedHashMap<>();
        latestDecision.forEach((key, decision) ->
                observations.put(key, loadObservations(key, decision)));

        List<DividendEventEvidenceBatch.Result> results = new ArrayList<>(queries.size());
        for (DividendEventEvidenceBatch.Query query : queries) {
            DividendEventEvidenceResolver.Resolution resolution;
            if (!valid(query)) {
                resolution = DividendEventEvidenceResolver.Resolution.MISSING;
            } else {
                LocalDate decisionDate = query.decisionInstant()
                        .atZone(zone(query.market())).toLocalDate();
                resolution = DividendEventEvidenceResolver.resolve(
                        observations.getOrDefault(
                                new StockKey(query.code(), query.market()), List.of()),
                        decisionDate, query.decisionInstant(), query.sessions());
            }
            results.add(new DividendEventEvidenceBatch.Result(query, resolution));
        }
        return List.copyOf(results);
    }

    private List<DividendEventEvidenceResolver.SnapshotObservation> loadObservations(
            StockKey key, Instant latestDecision) {
        try {
            List<SnapshotRef> refs = jdbc.query("""
                    SELECT s.id, s.provider, s.source_url, s.scope_from, s.scope_to,
                           o.observed_at, o.source_available_at, o.status, o.complete
                    FROM stock_dividend_snapshot s
                    JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id
                    WHERE s.stock_code=? AND s.market=?
                      AND o.observed_at<=?
                      AND (o.status='PARTIAL'
                           OR (o.status IN ('COMPLETE','EMPTY_COMPLETE') AND o.complete=TRUE))
                      AND o.scope_from=s.scope_from AND o.scope_to=s.scope_to
                    ORDER BY o.observed_at DESC, o.id DESC
                    """, (rs, rowNum) -> new SnapshotRef(
                    rs.getLong("id"), rs.getString("provider"),
                    rs.getString("source_url"),
                    rs.getObject("scope_from", LocalDate.class),
                    rs.getObject("scope_to", LocalDate.class),
                    rs.getObject("observed_at", Instant.class),
                    rs.getObject("source_available_at", Instant.class),
                    parseStatus(rs.getString("status")), rs.getBoolean("complete")),
                    key.code(), key.market(), latestDecision);
            if (refs.isEmpty()) return List.of();

            List<Long> snapshotIds = refs.stream().map(SnapshotRef::id).distinct().toList();
            String placeholders = String.join(",", Collections.nCopies(snapshotIds.size(), "?"));
            List<RawEvent> rawEvents = jdbc.query("""
                    SELECT snapshot_id, ex_dividend_date, cash_dividend, stock_dividend,
                           cash_payment_date, stock_payment_date, source_available_at
                    FROM stock_dividend_snapshot_event
                    WHERE snapshot_id IN (%s) AND ex_dividend_date IS NOT NULL
                    """.formatted(placeholders), (rs, rowNum) -> new RawEvent(
                    rs.getLong("snapshot_id"),
                    rs.getObject("ex_dividend_date", LocalDate.class),
                    rs.getBigDecimal("cash_dividend"), rs.getBigDecimal("stock_dividend"),
                    rs.getObject("cash_payment_date", LocalDate.class),
                    rs.getObject("stock_payment_date", LocalDate.class),
                    rs.getObject("source_available_at", Instant.class)), snapshotIds.toArray());
            Map<Long, List<RawEvent>> eventsBySnapshot = new LinkedHashMap<>();
            for (RawEvent event : rawEvents) {
                eventsBySnapshot.computeIfAbsent(event.snapshotId(), ignored -> new ArrayList<>())
                        .add(event);
            }

            List<DividendEventEvidenceResolver.SnapshotObservation> out = new ArrayList<>();
            for (SnapshotRef ref : refs) {
                List<DividendEventEvidenceResolver.Event> events = eventsBySnapshot
                        .getOrDefault(ref.id(), List.of()).stream()
                        .map(event -> new DividendEventEvidenceResolver.Event(
                                event.exDividendDate(), event.cashDividend(), event.stockDividend(),
                                event.cashPaymentDate(), event.stockPaymentDate(),
                                max(ref.observedAt(), max(ref.sourceAvailableAt(),
                                        event.sourceAvailableAt())), ref.provider(),
                                sourceUrls(ref.sourceUrl())))
                        .toList();
                out.add(new DividendEventEvidenceResolver.SnapshotObservation(
                        ref.provider(), ref.scopeFrom(), ref.scopeTo(), ref.observedAt(),
                        ref.sourceAvailableAt(), ref.status(), ref.declaredComplete(), events));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            // Never fall back to current-state history: it has no known-at and
            // would leak later revisions into historical decisions.
            log.warn("future dividend evidence unavailable for {} {}: {}",
                    key.market(), key.code(), e.getMessage());
            return List.of();
        }
    }

    private static boolean valid(DividendEventEvidenceBatch.Query query) {
        return query != null && query.code() != null && query.market() != null
                && query.decisionInstant() != null;
    }

    private record StockKey(String code, String market) {}

    private record SnapshotRef(
            long id,
            String provider,
            String sourceUrl,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            Instant observedAt,
            Instant sourceAvailableAt,
            DividendEventEvidenceResolver.Status status,
            boolean declaredComplete) {}

    private record RawEvent(
            long snapshotId,
            LocalDate exDividendDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            LocalDate cashPaymentDate,
            LocalDate stockPaymentDate,
            Instant sourceAvailableAt) {}

    private static DividendEventEvidenceResolver.Status parseStatus(String value) {
        if ("COMPLETE".equals(value)) return DividendEventEvidenceResolver.Status.AVAILABLE;
        if ("FAILED".equals(value)) return DividendEventEvidenceResolver.Status.MISSING;
        try {
            return DividendEventEvidenceResolver.Status.valueOf(value);
        } catch (RuntimeException e) {
            return DividendEventEvidenceResolver.Status.MISSING;
        }
    }

    private static ZoneId zone(String market) {
        return "美股".equals(market)
                ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Taipei");
    }

    private static Instant max(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static List<String> sourceUrls(String manifest) {
        if (manifest == null || manifest.isBlank()) return List.of();
        return java.util.Arrays.stream(manifest.split("\\R"))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }
}
