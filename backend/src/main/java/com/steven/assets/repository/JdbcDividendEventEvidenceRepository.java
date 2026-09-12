package com.steven.assets.repository;

import com.steven.assets.service.DividendEventEvidenceBatch;
import com.steven.assets.service.DividendEventEvidenceResolver;
import com.steven.assets.service.DividendEventEvidenceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
                loadObservationsBatch(latestDecision);

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

    /**
     * Reads snapshot headers and events for every exact (code, market) pair in two statements.
     * The old implementation called {@link #loadObservations} once per key, which made an API
     * named resolveBatch an N+1 loop.  Keep the decision instant alongside each requested pair
     * in SQL so a same-code cross-market row can never leak into another list entry.
     */
    private Map<StockKey, List<DividendEventEvidenceResolver.SnapshotObservation>> loadObservationsBatch(
            Map<StockKey, Instant> latestDecision) {
        if (latestDecision == null || latestDecision.isEmpty()) return Map.of();
        List<Map.Entry<StockKey, Instant>> requested = latestDecision.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(java.util.Comparator.comparing((Map.Entry<StockKey, Instant> entry) -> entry.getKey().market())
                        .thenComparing(entry -> entry.getKey().code())).toList();
        if (requested.isEmpty()) return Map.of();
        Map<StockKey, List<DividendEventEvidenceResolver.SnapshotObservation>> empty = new LinkedHashMap<>();
        requested.forEach(entry -> empty.put(entry.getKey(), new ArrayList<>()));
        try {
            // PostgreSQL treats an untyped VALUES placeholder column as text before it sees the
            // later comparison.  Bind/cast every decision boundary explicitly so a multi-key
            // request cannot become `timestamptz <= text` (and poison the transaction with 25P02).
            String values = String.join(",", Collections.nCopies(requested.size(),
                    "(?,?,CAST(? AS TIMESTAMP WITH TIME ZONE))"));
            List<Object> args = new ArrayList<>(requested.size() * 3);
            for (Map.Entry<StockKey, Instant> entry : requested) {
                args.add(entry.getKey().code());
                args.add(entry.getKey().market());
                args.add(toTimestamp(entry.getValue()));
            }
            List<BatchSnapshotRef> refs = jdbc.query("""
                    WITH requested(stock_code, market, decision_at) AS (VALUES %s)
                    SELECT s.stock_code, s.market, s.id, s.provider, s.source_url, s.scope_from, s.scope_to,
                           o.observed_at, o.source_available_at, o.status, o.complete
                    FROM stock_dividend_snapshot s
                    JOIN requested r ON s.stock_code=r.stock_code AND s.market=r.market
                    JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id
                    WHERE o.observed_at<=r.decision_at
                      AND (o.status='PARTIAL'
                           OR (o.status IN ('COMPLETE','EMPTY_COMPLETE') AND o.complete=TRUE))
                      AND o.scope_from=s.scope_from AND o.scope_to=s.scope_to
                    ORDER BY r.market ASC, r.stock_code ASC, o.observed_at DESC, o.id DESC
                    """.formatted(values), (rs, rowNum) -> new BatchSnapshotRef(
                    new StockKey(rs.getString("stock_code"), rs.getString("market")),
                    new SnapshotRef(rs.getLong("id"), rs.getString("provider"),
                            rs.getString("source_url"), rs.getObject("scope_from", LocalDate.class),
                            rs.getObject("scope_to", LocalDate.class), getInstant(rs, "observed_at"),
                            getInstant(rs, "source_available_at"), parseStatus(rs.getString("status")),
                            rs.getBoolean("complete"))), args.toArray());
            if (refs.isEmpty()) return immutableObservations(empty);
            List<Long> snapshotIds = refs.stream().map(value -> value.ref().id()).distinct().toList();
            String placeholders = String.join(",", Collections.nCopies(snapshotIds.size(), "?"));
            List<RawEvent> rawEvents = jdbc.query("""
                    SELECT snapshot_id, ex_dividend_date, ex_rights_date, cash_dividend,
                           stock_dividend, cash_payment_date, stock_payment_date, source_available_at
                    FROM stock_dividend_snapshot_event
                    WHERE snapshot_id IN (%s)
                      AND LEAST(ex_dividend_date, ex_rights_date) IS NOT NULL
                    """.formatted(placeholders), (rs, rowNum) -> new RawEvent(
                    rs.getLong("snapshot_id"), rs.getObject("ex_dividend_date", LocalDate.class),
                    rs.getObject("ex_rights_date", LocalDate.class), rs.getBigDecimal("cash_dividend"),
                    rs.getBigDecimal("stock_dividend"), rs.getObject("cash_payment_date", LocalDate.class),
                    rs.getObject("stock_payment_date", LocalDate.class), getInstant(rs, "source_available_at")),
                    snapshotIds.toArray());
            Map<Long, List<RawEvent>> eventsBySnapshot = new LinkedHashMap<>();
            for (RawEvent event : rawEvents) {
                eventsBySnapshot.computeIfAbsent(event.snapshotId(), ignored -> new ArrayList<>()).add(event);
            }
            for (BatchSnapshotRef batchRef : refs) {
                SnapshotRef ref = batchRef.ref();
                List<DividendEventEvidenceResolver.Event> events = eventsBySnapshot
                        .getOrDefault(ref.id(), List.of()).stream().map(event ->
                                new DividendEventEvidenceResolver.Event(event.exDividendDate(), event.cashDividend(),
                                        event.stockDividend(), event.cashPaymentDate(), event.stockPaymentDate(),
                                        max(ref.observedAt(), max(ref.sourceAvailableAt(), event.sourceAvailableAt())),
                                        ref.provider(), sourceUrls(ref.sourceUrl()), event.exRightsDate())).toList();
                // SQL's join guarantees this key is one of requested pairs.  The single-key
                // fallback retains compatibility with old ResultSet fixtures that did not mock
                // the newly selected natural-key columns; it is never used for a real multi-key
                // result and therefore cannot cross markets.
                StockKey destination = empty.containsKey(batchRef.key()) ? batchRef.key()
                        : requested.size() == 1 ? requested.getFirst().getKey() : null;
                if (destination == null) continue;
                empty.get(destination).add(new DividendEventEvidenceResolver.SnapshotObservation(
                        ref.provider(), ref.scopeFrom(), ref.scopeTo(), ref.observedAt(), ref.sourceAvailableAt(),
                        ref.status(), ref.declaredComplete(), events));
            }
            return immutableObservations(empty);
        } catch (Exception e) {
            log.warn("future dividend evidence batch unavailable: {}", e.getMessage());
            return immutableObservations(empty);
        }
    }

    private static Map<StockKey, List<DividendEventEvidenceResolver.SnapshotObservation>> immutableObservations(
            Map<StockKey, List<DividendEventEvidenceResolver.SnapshotObservation>> source) {
        Map<StockKey, List<DividendEventEvidenceResolver.SnapshotObservation>> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, List.copyOf(value)));
        return Map.copyOf(out);
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
                    getInstant(rs, "observed_at"),
                    getInstant(rs, "source_available_at"),
                    parseStatus(rs.getString("status")), rs.getBoolean("complete")),
                    key.code(), key.market(), toTimestamp(latestDecision));
            if (refs.isEmpty()) return List.of();

            List<Long> snapshotIds = refs.stream().map(SnapshotRef::id).distinct().toList();
            String placeholders = String.join(",", Collections.nCopies(snapshotIds.size(), "?"));
            // Task 357／357.3d-1：這是交易雷達「下一配息」的資料來源；純配股事件的
            // ex_dividend_date 為 null，改用 anchorDate = LEAST(ex_dividend_date,
            // ex_rights_date) 判斷事件是否存在，否則這類事件永遠不會成為下一配息證據。
            // **是 LEAST 不是 COALESCE**：LEAST 忽略 NULL、取較早者，與 Java 端
            // DividendDates.anchorDate 逐位相同；COALESCE 取「第一個非 null」，兩欄皆有值
            // 且除權日較早時會取到較晚的除息日，讓落在視窗內的事件被排除。
            List<RawEvent> rawEvents = jdbc.query("""
                    SELECT snapshot_id, ex_dividend_date, ex_rights_date, cash_dividend,
                           stock_dividend, cash_payment_date, stock_payment_date,
                           source_available_at
                    FROM stock_dividend_snapshot_event
                    WHERE snapshot_id IN (%s)
                      AND LEAST(ex_dividend_date, ex_rights_date) IS NOT NULL
                    """.formatted(placeholders), (rs, rowNum) -> new RawEvent(
                    rs.getLong("snapshot_id"),
                    rs.getObject("ex_dividend_date", LocalDate.class),
                    rs.getObject("ex_rights_date", LocalDate.class),
                    rs.getBigDecimal("cash_dividend"), rs.getBigDecimal("stock_dividend"),
                    rs.getObject("cash_payment_date", LocalDate.class),
                    rs.getObject("stock_payment_date", LocalDate.class),
                    getInstant(rs, "source_available_at")), snapshotIds.toArray());
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
                                sourceUrls(ref.sourceUrl()), event.exRightsDate()))
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

    private record BatchSnapshotRef(StockKey key, SnapshotRef ref) {}

    private record RawEvent(
            long snapshotId,
            LocalDate exDividendDate,
            LocalDate exRightsDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            LocalDate cashPaymentDate,
            LocalDate stockPaymentDate,
            Instant sourceAvailableAt) {}

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant getInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

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
