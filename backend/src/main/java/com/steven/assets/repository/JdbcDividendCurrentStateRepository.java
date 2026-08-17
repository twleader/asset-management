package com.steven.assets.repository;

import com.steven.assets.service.DividendCurrentStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** JDBC adapter for the backend-owned dividend current-state projection. */
@Repository
@RequiredArgsConstructor
public class JdbcDividendCurrentStateRepository implements DividendCurrentStateRepository {

    private final JdbcTemplate jdbc;

    @Override
    public Optional<Snapshot> findLatestComplete(
            String code, String market, Instant decisionInstant,
            LocalDate requiredFrom, LocalDate requiredTo) {
        Timestamp decisionTimestamp = toTimestamp(decisionInstant);
        List<SnapshotRef> refs = jdbc.query("""
                SELECT s.id, s.provider, s.scope_from, s.scope_to, o.observed_at
                  FROM stock_dividend_snapshot s
                  JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id
                 WHERE s.stock_code=? AND s.market=?
                   AND o.observed_at<=? AND o.complete=true
                   AND o.status IN ('COMPLETE','EMPTY_COMPLETE')
                   AND (o.source_available_at IS NULL OR o.source_available_at<=?)
                   AND o.scope_from=s.scope_from AND o.scope_to=s.scope_to
                   AND s.scope_from<=? AND s.scope_to>=?
                 -- Authority is a safety ordering: a newer fallback cannot replace
                 -- an older complete official exchange calendar.  Freshness is only
                 -- compared within the selected provider tier.
                 ORDER BY CASE
                            WHEN UPPER(s.provider) LIKE '%TWSE%'
                              OR UPPER(s.provider) LIKE '%TPEX%'
                              OR UPPER(s.provider) LIKE '%NASDAQ_DIVIDEND_CALENDAR%' THEN 0
                            WHEN UPPER(s.provider) LIKE '%FINMIND%'
                              OR UPPER(s.provider) LIKE '%YAHOO%' THEN 1
                            ELSE 50 END,
                          o.observed_at DESC, s.id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new SnapshotRef(
                rs.getLong("id"), rs.getString("provider"),
                rs.getObject("scope_from", LocalDate.class),
                rs.getObject("scope_to", LocalDate.class),
                getInstant(rs, "observed_at")),
                code, market, decisionTimestamp, decisionTimestamp, requiredFrom, requiredTo);
        if (refs.isEmpty()) return Optional.empty();
        return Optional.of(loadSnapshot(code, market, refs.getFirst()));
    }

    @Override
    public Optional<Snapshot> findLatestHistorical(
            String code, String market, Instant decisionInstant, LocalDate throughDate) {
        Timestamp decisionTimestamp = toTimestamp(decisionInstant);
        List<SnapshotRef> refs = jdbc.query("""
                SELECT s.id, s.provider, s.scope_from, s.scope_to, o.observed_at
                  FROM stock_dividend_snapshot s
                  JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id
                 WHERE s.stock_code=? AND s.market=?
                   AND o.observed_at<=? AND o.status='PARTIAL' AND o.complete=false
                   AND (o.source_available_at IS NULL OR o.source_available_at<=?)
                   AND o.scope_from=s.scope_from AND o.scope_to=s.scope_to
                   AND s.scope_from<=? AND s.scope_to>=?
                 ORDER BY CASE
                            WHEN UPPER(s.provider) LIKE '%TWSE%'
                              OR UPPER(s.provider) LIKE '%TPEX%'
                              OR UPPER(s.provider) LIKE '%NASDAQ_DIVIDEND_CALENDAR%' THEN 0
                            WHEN UPPER(s.provider) LIKE '%FINMIND%'
                              OR UPPER(s.provider) LIKE '%YAHOO%' THEN 1
                            ELSE 50 END,
                          o.observed_at DESC, s.id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new SnapshotRef(
                rs.getLong("id"), rs.getString("provider"),
                rs.getObject("scope_from", LocalDate.class),
                rs.getObject("scope_to", LocalDate.class),
                getInstant(rs, "observed_at")),
                code, market, decisionTimestamp, decisionTimestamp, throughDate, throughDate);
        if (refs.isEmpty()) return Optional.empty();
        return Optional.of(loadSnapshot(code, market, refs.getFirst()));
    }

    @Override
    public List<ActiveFutureEvent> findActiveFutureEvents(
            String code, String market, LocalDate afterDate,
            LocalDate scopeFrom, LocalDate scopeTo) {
        return jdbc.query("""
                SELECT id, event_key, year, ex_dividend_date, cash_dividend, stock_dividend,
                       cash_payment_date, stock_payment_date
                  FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND event_status='ACTIVE'
                   AND ex_dividend_date>? AND ex_dividend_date>=? AND ex_dividend_date<=?
                """, (rs, rowNum) -> new ActiveFutureEvent(rs.getLong("id"),
                rs.getString("event_key"), rs.getObject("year", Integer.class),
                rs.getObject("ex_dividend_date", LocalDate.class),
                rs.getBigDecimal("cash_dividend"), rs.getBigDecimal("stock_dividend"),
                rs.getObject("cash_payment_date", LocalDate.class),
                rs.getObject("stock_payment_date", LocalDate.class)),
                code, market, afterDate, scopeFrom, scopeTo);
    }

    @Override
    public List<ActiveEventDetail> findActiveEventDetails(String code, String market) {
        return jdbc.query("""
                SELECT id, event_key, year, ex_dividend_date, cash_dividend, stock_dividend,
                       cash_payment_date, stock_payment_date, yield_pct, previous_close, fill_days
                  FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND event_status='ACTIVE'
                   AND ex_dividend_date IS NOT NULL
                 ORDER BY id
                """, (rs, rowNum) -> new ActiveEventDetail(rs.getLong("id"),
                rs.getString("event_key"), rs.getObject("year", Integer.class),
                rs.getObject("ex_dividend_date", LocalDate.class),
                rs.getBigDecimal("cash_dividend"), rs.getBigDecimal("stock_dividend"),
                rs.getObject("cash_payment_date", LocalDate.class),
                rs.getObject("stock_payment_date", LocalDate.class),
                rs.getBigDecimal("yield_pct"), rs.getBigDecimal("previous_close"),
                rs.getObject("fill_days", Integer.class)), code, market);
    }

    @Override
    public void applyMergedEnrichment(long id, String eventKey, LocalDate cashPaymentDate,
            LocalDate stockPaymentDate, BigDecimal yieldPct, BigDecimal previousClose,
            Integer fillDays) {
        CurrentRow current = readCurrentRow(id);
        if (current == null) return;
        deleteCancelledTupleTwins(current.stockCode(), current.market(), id, current.year(),
                current.exDividendDate(), current.cashDividend(), current.stockDividend(),
                cashPaymentDate, stockPaymentDate, eventKey);
        jdbc.update("""
                UPDATE stock_dividend_history
                   SET event_key=?, cash_payment_date=?, stock_payment_date=?,
                       yield_pct=?, previous_close=?, fill_days=?, updated_at=NOW()
                 WHERE id=?
                """, eventKey, cashPaymentDate, stockPaymentDate, yieldPct, previousClose,
                fillDays, id);
    }

    @Override
    public void upsertActiveEvent(
            String code, String market, String provider, ProjectedEvent event) {
        upsertEvent(code, market, provider, event, true);
    }

    @Override
    public void upsertHistoricalEvent(
            String code, String market, String provider, ProjectedEvent event) {
        upsertEvent(code, market, provider, event, false);
    }

    private void upsertEvent(
            String code, String market, String provider, ProjectedEvent event,
            boolean reviveCancelled) {
        List<Long> ids = findMatchingIds(code, market, event);
        if (ids.isEmpty()) {
            // Not even the relaxed segment matched, so no existing row shares this
            // ex-date/amount identity and uk_dividend_event cannot collide.
            jdbc.update("""
                    INSERT INTO stock_dividend_history
                        (stock_code,market,year,cash_dividend,stock_dividend,ex_dividend_date,
                         cash_payment_date,stock_payment_date,source,event_key,event_status,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',NOW())
                    """, code, market, event.year(), event.cashDividend(),
                    event.stockDividend(), event.exDividendDate(), event.cashPaymentDate(),
                    event.stockPaymentDate(), provider, event.eventKey());
            return;
        }
        long targetId = ids.getFirst();
        CurrentRow current = readCurrentRow(targetId);
        if (current == null) return;
        // Read-then-write: payment dates are announcement metadata, so a source that
        // omits them must not erase dates an earlier source already published.
        // Amounts, source and event_key follow the incoming event.
        LocalDate cashPaymentDate = event.cashPaymentDate() != null
                ? event.cashPaymentDate() : current.cashPaymentDate();
        LocalDate stockPaymentDate = event.stockPaymentDate() != null
                ? event.stockPaymentDate() : current.stockPaymentDate();
        Long activeTwin = findActiveTupleTwin(code, market, targetId, current.year(),
                current.exDividendDate(), event.cashDividend(), event.stockDividend(),
                cashPaymentDate, stockPaymentDate, event.eventKey());
        if (activeTwin != null) {
            // Should be unreachable (collapse runs first and the full-value segment
            // precedes the relaxed one); reconcile against the ACTIVE occupant of the
            // tuple instead of forcing a uk_dividend_event violation.
            targetId = activeTwin;
        }
        deleteCancelledTupleTwins(code, market, targetId, current.year(),
                current.exDividendDate(), event.cashDividend(), event.stockDividend(),
                cashPaymentDate, stockPaymentDate, event.eventKey());
        jdbc.update(reviveCancelled ? """
                UPDATE stock_dividend_history
                   SET cash_dividend=?, stock_dividend=?, cash_payment_date=?, stock_payment_date=?,
                       source=?, event_key=?, event_status='ACTIVE', updated_at=NOW()
                WHERE id=?
                """ : """
                UPDATE stock_dividend_history
                   SET cash_dividend=?, stock_dividend=?, cash_payment_date=?, stock_payment_date=?,
                       source=?, event_key=?, updated_at=NOW()
                WHERE id=? AND event_status='ACTIVE'
                """, event.cashDividend(), event.stockDividend(), cashPaymentDate,
                stockPaymentDate, provider, event.eventKey(), targetId);
    }

    @Override
    public void cancelActiveEvent(long id) {
        jdbc.update("UPDATE stock_dividend_history SET event_status='CANCELLED', updated_at=NOW() "
                + "WHERE id=? AND event_status='ACTIVE'", id);
    }

    private Snapshot loadSnapshot(String code, String market, SnapshotRef ref) {
        List<Event> events = jdbc.query("""
                SELECT event_key, year, ex_dividend_date, cash_dividend, stock_dividend,
                       cash_payment_date, stock_payment_date
                  FROM stock_dividend_snapshot_event
                 WHERE snapshot_id=?
                """, (rs, rowNum) -> new Event(
                rs.getString("event_key"), rs.getObject("year", Integer.class),
                rs.getObject("ex_dividend_date", LocalDate.class),
                rs.getBigDecimal("cash_dividend"), rs.getBigDecimal("stock_dividend"),
                rs.getObject("cash_payment_date", LocalDate.class),
                rs.getObject("stock_payment_date", LocalDate.class)), ref.id());
        return new Snapshot(ref.id(), code, market, ref.provider(),
                ref.scopeFrom(), ref.scopeTo(), ref.observedAt(), events);
    }

    private record SnapshotRef(long id, String provider, LocalDate scopeFrom,
                               LocalDate scopeTo, Instant observedAt) {}

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant getInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private List<Long> findMatchingIds(String code, String market, ProjectedEvent event) {
        // ACTIVE rows win outright: the historical upsert UPDATE is guarded by
        // event_status='ACTIVE', so picking a CANCELLED twin while an ACTIVE row
        // exists would turn the write into a silent no-op.  Within each status
        // block: provider key, then full value equality, then the relaxed
        // ex-date+amount identity (payment dates and year are announcement
        // metadata and must not split one real event into two rows).
        for (boolean activeOnly : new boolean[]{true, false}) {
            List<Long> byKey = findByEventKey(code, market, event, activeOnly);
            if (!byKey.isEmpty()) return byKey;
            List<Long> byValue = findByFullValue(code, market, event, activeOnly);
            if (!byValue.isEmpty()) return byValue;
            List<Long> byDateAmount = findByDateAndAmount(code, market, event, activeOnly);
            if (!byDateAmount.isEmpty()) return byDateAmount;
        }
        return List.of();
    }

    private List<Long> findByEventKey(
            String code, String market, ProjectedEvent event, boolean activeOnly) {
        if (event.eventKey() == null || event.eventKey().isBlank()) return List.of();
        return jdbc.query(activeOnly ? """
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND event_key=? AND event_status='ACTIVE'
                 ORDER BY id LIMIT 1
                """ : """
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND event_key=?
                 ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), code, market, event.eventKey());
    }

    private List<Long> findByFullValue(
            String code, String market, ProjectedEvent event, boolean activeOnly) {
        return jdbc.query(activeOnly ? """
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND year=? AND ex_dividend_date=?
                   AND cash_dividend IS NOT DISTINCT FROM ?
                   AND stock_dividend IS NOT DISTINCT FROM ?
                   AND cash_payment_date IS NOT DISTINCT FROM ?
                   AND stock_payment_date IS NOT DISTINCT FROM ?
                   AND event_status='ACTIVE'
                 ORDER BY id LIMIT 1
                """ : """
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND year=? AND ex_dividend_date=?
                   AND cash_dividend IS NOT DISTINCT FROM ?
                   AND stock_dividend IS NOT DISTINCT FROM ?
                   AND cash_payment_date IS NOT DISTINCT FROM ?
                   AND stock_payment_date IS NOT DISTINCT FROM ?
                 ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), code, market, event.year(),
                event.exDividendDate(), event.cashDividend(), event.stockDividend(),
                event.cashPaymentDate(), event.stockPaymentDate());
    }

    private List<Long> findByDateAndAmount(
            String code, String market, ProjectedEvent event, boolean activeOnly) {
        // Null amounts count as zero, matching the uk_dividend_event COALESCE
        // convention.  Rows that already carry a payment date sort first so
        // enrichment is never re-anchored onto an emptier duplicate.
        return jdbc.query(activeOnly ? """
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND ex_dividend_date=?
                   AND COALESCE(cash_dividend,0)=COALESCE(?,0)
                   AND COALESCE(stock_dividend,0)=COALESCE(?,0)
                   AND event_status='ACTIVE'
                 ORDER BY (cash_payment_date IS NULL), id LIMIT 1
                """ : """
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND ex_dividend_date=?
                   AND COALESCE(cash_dividend,0)=COALESCE(?,0)
                   AND COALESCE(stock_dividend,0)=COALESCE(?,0)
                 ORDER BY (cash_payment_date IS NULL), id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), code, market, event.exDividendDate(),
                event.cashDividend(), event.stockDividend());
    }

    private CurrentRow readCurrentRow(long id) {
        List<CurrentRow> rows = jdbc.query("""
                SELECT stock_code, market, year, ex_dividend_date, cash_dividend, stock_dividend,
                       cash_payment_date, stock_payment_date
                  FROM stock_dividend_history
                 WHERE id=?
                """, (rs, rowNum) -> new CurrentRow(
                rs.getString("stock_code"), rs.getString("market"),
                rs.getObject("year", Integer.class),
                rs.getObject("ex_dividend_date", LocalDate.class),
                rs.getBigDecimal("cash_dividend"), rs.getBigDecimal("stock_dividend"),
                rs.getObject("cash_payment_date", LocalDate.class),
                rs.getObject("stock_payment_date", LocalDate.class)), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Long findActiveTupleTwin(String code, String market, long excludeId, Integer year,
            LocalDate exDividendDate, BigDecimal cashDividend, BigDecimal stockDividend,
            LocalDate cashPaymentDate, LocalDate stockPaymentDate, String eventKey) {
        List<Long> twins = jdbc.query("""
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND event_status='ACTIVE' AND id<>?
                   AND year=?
                   AND COALESCE(ex_dividend_date, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')
                   AND COALESCE(cash_dividend,0)=COALESCE(?,0)
                   AND COALESCE(stock_dividend,0)=COALESCE(?,0)
                   AND COALESCE(cash_payment_date, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')
                   AND COALESCE(stock_payment_date, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')
                   AND COALESCE(event_key,'')=COALESCE(?,'')
                 ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), code, market, excludeId, year,
                exDividendDate, cashDividend, stockDividend, cashPaymentDate,
                stockPaymentDate, eventKey);
        return twins.isEmpty() ? null : twins.getFirst();
    }

    private void deleteCancelledTupleTwins(String code, String market, long keepId, Integer year,
            LocalDate exDividendDate, BigDecimal cashDividend, BigDecimal stockDividend,
            LocalDate cashPaymentDate, LocalDate stockPaymentDate, String eventKey) {
        // uk_dividend_event does not include event_status, so a CANCELLED tombstone
        // still occupies its tuple.  Remove tombstones equal to the post-write tuple
        // under the index's COALESCE convention before writing, or the UPDATE would
        // hit a duplicate-key violation and roll the whole projection back.  Any
        // tombstone that differs on the index columns keeps its finalized meaning
        // and is left untouched.
        jdbc.update("""
                DELETE FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND event_status='CANCELLED' AND id<>?
                   AND year=?
                   AND COALESCE(ex_dividend_date, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')
                   AND COALESCE(cash_dividend,0)=COALESCE(?,0)
                   AND COALESCE(stock_dividend,0)=COALESCE(?,0)
                   AND COALESCE(cash_payment_date, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')
                   AND COALESCE(stock_payment_date, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')
                   AND COALESCE(event_key,'')=COALESCE(?,'')
                """, code, market, keepId, year, exDividendDate, cashDividend, stockDividend,
                cashPaymentDate, stockPaymentDate, eventKey);
    }

    private record CurrentRow(String stockCode, String market, Integer year,
                              LocalDate exDividendDate, BigDecimal cashDividend,
                              BigDecimal stockDividend, LocalDate cashPaymentDate,
                              LocalDate stockPaymentDate) {}
}
