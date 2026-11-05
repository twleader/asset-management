package com.steven.assets.repository;

import com.steven.assets.model.CanonicalDividend.*;
import com.steven.assets.service.CanonicalDividendRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Repository
public class JdbcCanonicalDividendRepository implements CanonicalDividendRepository {
    private final JdbcTemplate jdbc;
    public JdbcCanonicalDividendRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void lock(Key key) {
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        jdbc.execute("SET LOCAL statement_timeout='10s'");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL", Boolean.class,
                "DIVIDEND_CANONICAL|" + key.market() + "|" + key.code());
    }
    @Override public Facts load(Key key, Instant instant) {
        List<Current> current = jdbc.query("""
                SELECT * FROM stock_dividend_history WHERE stock_code=? AND market=? ORDER BY id
                """, (r,n) -> new Current(r.getLong("id"), r.getObject("year", Integer.class),
                r.getObject("ex_dividend_date",LocalDate.class),r.getObject("ex_rights_date",LocalDate.class),
                r.getBigDecimal("cash_dividend"),r.getBigDecimal("stock_dividend"),
                r.getObject("cash_payment_date",LocalDate.class),r.getObject("stock_payment_date",LocalDate.class),
                r.getString("source"),r.getString("event_key"),r.getString("event_status"),
                r.getObject("canonical_date",LocalDate.class),r.getObject("source_snapshot_id",Long.class),
                r.getString("resolution_status"),r.getString("resolution_reason"),r.getBigDecimal("yield_pct"),
                r.getBigDecimal("previous_close"),r.getObject("fill_days",Integer.class),r.getString("updated_at")), key.code(),key.market());
        List<Evidence> evidence = jdbc.query("""
                SELECT s.id,s.provider,s.normalizer_version,s.amount_basis,s.date_groups_complete,
                       s.scope_from,s.scope_to,s.content_hash,o.id observation_id,o.observed_at,
                       o.source_available_at,o.status,o.complete
                FROM stock_dividend_snapshot s JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id
                WHERE s.stock_code=? AND s.market=? AND s.normalizer_version=2
                  AND o.observed_at<=? AND (o.source_available_at IS NULL OR o.source_available_at<=?)
                  AND o.scope_from=s.scope_from AND o.scope_to=s.scope_to
                ORDER BY o.observed_at,o.id
                """, (r,n) -> new Evidence(r.getLong("id"),r.getLong("observation_id"),r.getString("provider"),
                r.getInt("normalizer_version"),r.getString("amount_basis"),r.getBoolean("date_groups_complete"),
                r.getString("status"),r.getBoolean("complete"),r.getObject("scope_from",LocalDate.class),
                r.getObject("scope_to",LocalDate.class),r.getTimestamp("observed_at").toInstant(),
                r.getTimestamp("source_available_at")==null?null:r.getTimestamp("source_available_at").toInstant(),
                r.getString("content_hash"),List.of()), key.code(),key.market(),Timestamp.from(instant),Timestamp.from(instant));
        Map<Long,List<Component>> components = new HashMap<>();
        jdbc.query("""
                SELECT e.* FROM stock_dividend_snapshot_event e JOIN stock_dividend_snapshot s ON s.id=e.snapshot_id
                WHERE s.stock_code=? AND s.market=? AND s.normalizer_version=2 ORDER BY e.id
                """, r -> { components.computeIfAbsent(r.getLong("snapshot_id"), x -> new ArrayList<>()).add(
                new Component(r.getString("upstream_component_id"),r.getObject("year",Integer.class),
                r.getObject("ex_dividend_date",LocalDate.class),r.getObject("ex_rights_date",LocalDate.class),
                r.getBigDecimal("cash_dividend"),r.getBigDecimal("stock_dividend"),
                r.getObject("cash_payment_date",LocalDate.class),r.getObject("stock_payment_date",LocalDate.class),
                r.getTimestamp("source_available_at")==null?null:r.getTimestamp("source_available_at").toInstant(),false));
                },key.code(),key.market());
        evidence = evidence.stream().map(e -> new Evidence(e.snapshotId(),e.observationId(),e.provider(),e.version(),e.basis(),
                e.groupsComplete(),e.status(),e.complete(),e.scopeFrom(),e.scopeTo(),e.observedAt(),e.availableAt(),
                e.contentHash(),components.getOrDefault(e.snapshotId(),List.of()))).toList();
        return new Facts(key,evidence,current);
    }
    private static boolean amount(BigDecimal a,BigDecimal b) { return a==null?b==null:b!=null&&a.compareTo(b)==0; }
    private static boolean belongs(Current r,LocalDate d) {
        return r.canonicalDate()!=null?d.equals(r.canonicalDate()):d.equals(r.cashDate())||d.equals(r.stockDate());
    }
    private static boolean equal(Current r, Day d) {
        LocalDate cashDate=d.cash()!=null&&d.cash().signum()>0?d.date():null;
        LocalDate stockDate=d.stock()!=null&&d.stock().signum()>0?d.date():null;
        if (cashDate==null&&stockDate==null) cashDate=d.date();
        return Objects.equals(r.canonicalDate(),d.date())&&Objects.equals(r.cashDate(),cashDate)
                &&Objects.equals(r.stockDate(),stockDate)&&amount(r.cash(),d.cash())&&amount(r.stock(),d.stock())
                &&Objects.equals(r.cashPayment(),d.cashPayment())&&Objects.equals(r.stockPayment(),d.stockPayment())
                &&Objects.equals(r.source(),d.source())&&Objects.equals(r.eventKey(),d.eventKey())
                &&Objects.equals(r.snapshotId(),d.snapshotId())&&Objects.equals(r.resolution(),d.resolution())
                &&Objects.equals(r.reason(),d.reason())&&Objects.equals(r.year(),d.date().getYear());
    }
    @Override public boolean matches(Facts facts,List<Day> target) {
        for (Day day:target) {
            List<Current> active=facts.current().stream().filter(r->"ACTIVE".equals(r.status())&&belongs(r,day.date())).toList();
            if(day.withdrawn()) {
                if(!active.isEmpty())return false;
                if("CANCELLED".equals(day.resolution())&&facts.current().stream().noneMatch(r->"CANCELLED".equals(r.status())&&day.eventKey().equals(r.eventKey())))return false;
            }
            else if(active.size()!=1||!equal(active.getFirst(),day))return false;
        }
        return facts.current().stream().noneMatch(r->"ACTIVE".equals(r.status())&&r.cashDate()==null&&r.stockDate()==null
                &&r.canonicalDate()==null&&!"UNRESOLVED".equals(r.resolution()));
    }
    @Override public int apply(Facts facts,List<Day> target) {
        int changes=0;
        for(Day day:target) {
            List<Current> active=facts.current().stream().filter(r->"ACTIVE".equals(r.status())&&belongs(r,day.date())).toList();
            if(day.withdrawn()) {
                for(Current row:active) changes+=jdbc.update("UPDATE stock_dividend_history SET event_status='SUPERSEDED',updated_at=NOW() WHERE id=? AND event_status='ACTIVE'",row.id());
                if("CANCELLED".equals(day.resolution())&&facts.current().stream().noneMatch(row->"CANCELLED".equals(row.status())&&day.eventKey().equals(row.eventKey())))
                    changes+=jdbc.update("""
                        INSERT INTO stock_dividend_history(stock_code,market,year,ex_dividend_date,cash_dividend,stock_dividend,
                        source,event_key,event_status,canonical_date,source_snapshot_id,resolution_status,resolution_reason,updated_at)
                        VALUES(?,?,?,?,NULL,NULL,?,?,'CANCELLED',?,?,'RESOLVED',?,NOW())
                        """,facts.key().code(),facts.key().market(),day.date().getYear(),day.date(),day.source(),day.eventKey(),day.date(),day.snapshotId(),day.reason());
                continue;
            }
            Current keeper=active.stream().filter(r->day.date().equals(r.canonicalDate())&&day.eventKey().equals(r.eventKey())).findFirst().orElse(null);
            for(Current row:active) if(keeper==null||row.id()!=keeper.id())
                changes+=jdbc.update("UPDATE stock_dividend_history SET event_status='SUPERSEDED',updated_at=NOW() WHERE id=? AND event_status='ACTIVE'",row.id());
            LocalDate cashDate=day.cash()!=null&&day.cash().signum()>0?day.date():null;
            LocalDate stockDate=day.stock()!=null&&day.stock().signum()>0?day.date():null;
            if(cashDate==null&&stockDate==null)cashDate=day.date();
            if(keeper!=null&&equal(keeper,day))continue;
            if(keeper==null) changes+=jdbc.update("""
                INSERT INTO stock_dividend_history(stock_code,market,year,ex_dividend_date,ex_rights_date,cash_dividend,
                stock_dividend,cash_payment_date,stock_payment_date,source,event_key,event_status,canonical_date,
                source_snapshot_id,resolution_status,resolution_reason,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,NOW())
                """,facts.key().code(),facts.key().market(),day.date().getYear(),cashDate,stockDate,day.cash(),day.stock(),
                day.cashPayment(),day.stockPayment(),day.source(),day.eventKey(),day.date(),day.snapshotId(),day.resolution(),day.reason());
            else changes+=jdbc.update("""
                UPDATE stock_dividend_history SET year=?,ex_dividend_date=?,ex_rights_date=?,cash_dividend=?,stock_dividend=?,
                cash_payment_date=?,stock_payment_date=?,source=?,event_key=?,canonical_date=?,source_snapshot_id=?,
                resolution_status=?,resolution_reason=?,yield_pct=NULL,previous_close=NULL,fill_days=NULL,updated_at=NOW()
                WHERE id=? AND event_status='ACTIVE'
                """,day.date().getYear(),cashDate,stockDate,day.cash(),day.stock(),day.cashPayment(),day.stockPayment(),
                day.source(),day.eventKey(),day.date(),day.snapshotId(),day.resolution(),day.reason(),keeper.id());
        }
        for(Current row:facts.current()) if("ACTIVE".equals(row.status())&&row.cashDate()==null&&row.stockDate()==null
                &&row.canonicalDate()==null&&!"UNRESOLVED".equals(row.resolution()))
            changes+=jdbc.update("UPDATE stock_dividend_history SET resolution_status='UNRESOLVED',resolution_reason=?,updated_at=NOW() WHERE id=? AND event_status='ACTIVE'",
                    "缺少可驗作用日期，保留原始欄位供查證但不可採用",row.id());
        return changes;
    }
    @Override public ObservationBatch observationsAfter(long watermark,int limit) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
                WITH first_seen AS (
                    SELECT s.stock_code,s.market,MIN(o.id) first_id
                    FROM stock_dividend_fetch_observation o JOIN stock_dividend_snapshot s ON s.id=o.snapshot_id
                    WHERE o.id>? GROUP BY s.stock_code,s.market ORDER BY first_id LIMIT ?
                ), boundary AS (
                    SELECT COALESCE((SELECT MIN(o.id) FROM stock_dividend_fetch_observation o
                    JOIN stock_dividend_snapshot s ON s.id=o.snapshot_id WHERE o.id>?
                    AND NOT EXISTS(SELECT 1 FROM first_seen f WHERE f.stock_code=s.stock_code AND f.market=s.market)),
                    (SELECT COALESCE(MAX(id),?)+1 FROM stock_dividend_fetch_observation)) next_id
                )
                SELECT f.stock_code,f.market,b.next_id-1 id FROM first_seen f CROSS JOIN boundary b ORDER BY f.first_id
                """,watermark,Math.max(1,Math.min(100,limit)),watermark,watermark);
        Set<Key> keys=new LinkedHashSet<>();long through=watermark;
        for(Map<String,Object> row:rows){through=((Number)row.get("id")).longValue();keys.add(new Key((String)row.get("stock_code"),(String)row.get("market")));}
        return new ObservationBatch(through,List.copyOf(keys));
    }
}
