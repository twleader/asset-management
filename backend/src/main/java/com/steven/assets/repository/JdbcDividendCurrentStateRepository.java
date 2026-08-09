package com.steven.assets.repository;

import com.steven.assets.service.DividendCurrentStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
                rs.getObject("observed_at", Instant.class)),
                code, market, decisionInstant, decisionInstant, requiredFrom, requiredTo);
        if (refs.isEmpty()) return Optional.empty();
        return Optional.of(loadSnapshot(code, market, refs.getFirst()));
    }

    @Override
    public Optional<Snapshot> findLatestHistorical(
            String code, String market, Instant decisionInstant, LocalDate throughDate) {
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
                rs.getObject("observed_at", Instant.class)),
                code, market, decisionInstant, decisionInstant, throughDate, throughDate);
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
            jdbc.update("""
                    INSERT INTO stock_dividend_history
                        (stock_code,market,year,cash_dividend,stock_dividend,ex_dividend_date,
                         cash_payment_date,stock_payment_date,source,event_key,event_status,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',NOW())
                    """, code, market, event.year(), event.cashDividend(),
                    event.stockDividend(), event.exDividendDate(), event.cashPaymentDate(),
                    event.stockPaymentDate(), provider, event.eventKey());
        } else {
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
                    """, event.cashDividend(), event.stockDividend(), event.cashPaymentDate(),
                    event.stockPaymentDate(), provider, event.eventKey(), ids.getFirst());
        }
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

    private List<Long> findMatchingIds(String code, String market, ProjectedEvent event) {
        if (event.eventKey() != null && !event.eventKey().isBlank()) {
            List<Long> byKey = jdbc.query("""
                    SELECT id FROM stock_dividend_history
                     WHERE stock_code=? AND market=? AND event_key=?
                     ORDER BY id LIMIT 1
                    """, (rs, rowNum) -> rs.getLong(1), code, market, event.eventKey());
            if (!byKey.isEmpty()) return byKey;
        }
        return jdbc.query("""
                SELECT id FROM stock_dividend_history
                 WHERE stock_code=? AND market=? AND year=? AND ex_dividend_date=?
                   AND cash_dividend IS NOT DISTINCT FROM ?
                   AND stock_dividend IS NOT DISTINCT FROM ?
                   AND cash_payment_date IS NOT DISTINCT FROM ?
                   AND stock_payment_date IS NOT DISTINCT FROM ?
                 ORDER BY id
                 LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), code, market, event.year(),
                event.exDividendDate(), event.cashDividend(), event.stockDividend(),
                event.cashPaymentDate(), event.stockPaymentDate());
    }
}
