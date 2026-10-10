package com.steven.assets.externalmaterials.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Canonical minutes remain immutable; a capture receipt records observation, never source revision. */
@Repository
public class FubonIntradayCandleRepository {
    private final JdbcTemplate jdbc;
    private final MarketClock clock;
    private final TransactionTemplate writes;

    public FubonIntradayCandleRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, MarketClock clock) {
        this.jdbc = jdbc; this.clock = clock;
        this.writes = new TransactionTemplate(manager);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writes.setTimeout(10);
    }

    public record WriteResult(String status, int inserted, int unchanged) {}

    /** The Java HTTP start precedes the SDK start and supplies conservative completion evidence. */
    public WriteResult capture(IntradayCandlesRead read, Instant requestStartedAt) {
        if (read == null || requestStartedAt == null || !validSymbol(read.symbol())
                || read.observedAt() == null || read.observedAt().isBefore(requestStartedAt)
                || !read.sourceDate().equals(requestStartedAt.atZone(MarketClock.TW_ZONE).toLocalDate()))
            throw new IllegalArgumentException("INVALID_CAPTURE");
        List<IntradayCandle> completed = completed(read, requestStartedAt);
        try {
            return writes.execute(tx -> {
                timeouts();
                LocalDate floor = FubonMinuteRetentionFloor.at(clock.instant());
                if (read.sourceDate().isBefore(floor)) throw new IllegalArgumentException("EXPIRED_CAPTURE");
                int inserted = 0, unchanged = 0;
                for (IntradayCandle candle : completed) {
                    String hash = FubonCanonicalHash.candle(FubonMarketDataHistoryStore.candleCanonicalDocument(read, candle));
                    int count = jdbc.update("""
                            INSERT INTO fubon_intraday_candle
                              (stock_code,market,provider,timeframe,candle_at,source_date,exchange,
                               open,high,low,close,average,volume,observed_at,content_hash)
                            VALUES (?,'台股','FUBON_SDK',1,?,?,?,?,?,?,?,?,?,?,?)
                            ON CONFLICT (stock_code,market,provider,timeframe,candle_at) DO NOTHING
                            """, read.symbol(), Timestamp.from(candle.candleAt()), read.sourceDate(), read.exchange(),
                            candle.open(), candle.high(), candle.low(), candle.close(), candle.average(), candle.volume(),
                            Timestamp.from(read.observedAt()), hash);
                    if (count == 1) inserted++;
                    else {
                        String previous = jdbc.queryForObject("""
                                SELECT content_hash FROM fubon_intraday_candle
                                WHERE stock_code=? AND market='台股' AND provider='FUBON_SDK'
                                  AND timeframe=1 AND candle_at=?
                                """, String.class, read.symbol(), Timestamp.from(candle.candleAt()));
                        if (!hash.equals(previous)) throw new SourceConflict();
                        unchanged++;
                    }
                }
                Instant lastCompleted = completed.isEmpty() ? null : completed.getLast().candleAt().plusSeconds(60);
                receipt(read.symbol(), read.sourceDate(), requestStartedAt, read.observedAt(), lastCompleted,
                        lastCompleted == null ? "UNAVAILABLE" : "AVAILABLE",
                        lastCompleted == null ? "INSUFFICIENT_COMPLETED_BARS" : null);
                return new WriteResult(lastCompleted == null ? "UNAVAILABLE" : "AVAILABLE", inserted, unchanged);
            });
        } catch (SourceConflict conflict) {
            // The first transaction rolled back every insert. Publish the failure separately.
            unavailable(read.symbol(), read.sourceDate(), requestStartedAt, read.observedAt(),
                    "CONFLICT", "CONFLICT_NO_SOURCE_REVISION");
            return new WriteResult("CONFLICT", 0, 0);
        }
    }

    static List<IntradayCandle> completed(IntradayCandlesRead read, Instant started) {
        if (!"AVAILABLE".equals(read.status()) || read.candles().isEmpty()) return List.of();
        Instant tail = read.candles().getLast().candleAt();
        return read.candles().stream().filter(c -> c.candleAt().isBefore(tail)
                && !c.candleAt().plusSeconds(60).isAfter(started.minusSeconds(60))
                && c.candleAt().atZone(MarketClock.TW_ZONE).toLocalTime().isBefore(LocalTime.of(13, 30)))
                .toList();
    }

    public void unavailable(String code, LocalDate date, Instant started, Instant captured,
                            String status, String reason) {
        writes.executeWithoutResult(tx -> {
            timeouts();
            if (date.isBefore(FubonMinuteRetentionFloor.at(clock.instant()))) return;
            receipt(code, date, started, captured.isBefore(started) ? started : captured,
                    null, status, reason);
        });
    }

    private void receipt(String code, LocalDate date, Instant started, Instant captured,
                         Instant completed, String status, String reason) {
        jdbc.update("""
                INSERT INTO fubon_intraday_candle_capture
                  (stock_code,market,provider,source_date,request_started_at,captured_at,latest_completed_at,status,reason)
                VALUES (?,'台股','FUBON_SDK',?,?,?,?,?,?)
                ON CONFLICT (stock_code,market,provider) DO UPDATE SET
                  source_date=EXCLUDED.source_date,request_started_at=EXCLUDED.request_started_at,
                  captured_at=EXCLUDED.captured_at,latest_completed_at=EXCLUDED.latest_completed_at,
                  status=EXCLUDED.status,reason=EXCLUDED.reason
                WHERE EXCLUDED.captured_at>fubon_intraday_candle_capture.captured_at
                """, code, date, Timestamp.from(started), Timestamp.from(captured),
                completed == null ? null : Timestamp.from(completed), status, reason);
    }

    public record Snapshot(String stockCode, LocalDate sourceDate, Instant requestStartedAt, Instant capturedAt,
                           Instant lastCompletedAt, String status, String reason,
                           List<FubonIntradayCandleBatch.Candle> candles) {}

    /** One statement = one MVCC snapshot, including the receipt and each first-observed fact. */
    public List<Snapshot> read(List<String> codes, LocalDate date, Instant asOf, LocalDate floor) {
        var builders = new LinkedHashMap<String, SnapshotBuilder>();
        jdbc.query("""
                WITH requested AS (
                  SELECT * FROM unnest(?::varchar[]) WITH ORDINALITY AS r(stock_code,ord)
                )
                SELECT r.stock_code,c.source_date,c.request_started_at,c.captured_at,c.latest_completed_at,
                       c.status,c.reason,f.candle_at,f.open,f.high,f.low,f.close,f.volume
                FROM requested r
                LEFT JOIN fubon_intraday_candle_capture c
                  ON c.stock_code=r.stock_code AND c.market='台股' AND c.provider='FUBON_SDK'
                 AND c.source_date=? AND c.source_date>=? AND c.captured_at<=?
                LEFT JOIN LATERAL (
                  SELECT candle_at,open,high,low,close,volume FROM fubon_intraday_candle f
                  WHERE f.stock_code=r.stock_code AND f.market='台股' AND f.provider='FUBON_SDK'
                    AND f.timeframe=1 AND f.source_date=? AND f.source_date>=?
                    AND c.status='AVAILABLE' AND f.observed_at<=c.captured_at
                    AND c.request_started_at<=? AND c.latest_completed_at<=c.request_started_at-INTERVAL '60 seconds'
                    AND f.candle_at+INTERVAL '60 seconds'<=c.latest_completed_at
                    AND f.candle_at+INTERVAL '60 seconds'<=c.request_started_at-INTERVAL '60 seconds'
                    AND (f.candle_at AT TIME ZONE 'Asia/Taipei')::time>='09:00'::time
                    AND (f.candle_at AT TIME ZONE 'Asia/Taipei')::time<'13:30'::time
                  ORDER BY f.candle_at DESC LIMIT 30
                ) f ON true
                ORDER BY r.ord,f.candle_at ASC
                """, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("varchar", codes.toArray(String[]::new)));
                    ps.setObject(2, date); ps.setObject(3, floor); ps.setTimestamp(4, Timestamp.from(asOf));
                    ps.setObject(5, date); ps.setObject(6, floor); ps.setTimestamp(7, Timestamp.from(asOf));
                }, rs -> {
                    String code = rs.getString(1);
                    SnapshotBuilder b = builders.get(code);
                    if (b == null) {
                        b = new SnapshotBuilder(code, rs.getObject(2, LocalDate.class), instant(rs.getTimestamp(3)),
                                instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), rs.getString(6), rs.getString(7));
                        builders.put(code, b);
                    }
                    Timestamp candleAt = rs.getTimestamp(8);
                    if (candleAt != null) b.candles.add(new FubonIntradayCandleBatch.Candle(candleAt.toInstant(),
                            FubonCanonicalHash.decimal(rs.getBigDecimal(9)), FubonCanonicalHash.decimal(rs.getBigDecimal(10)),
                            FubonCanonicalHash.decimal(rs.getBigDecimal(11)), FubonCanonicalHash.decimal(rs.getBigDecimal(12)),
                            rs.getLong(13)));
                });
        return builders.values().stream().map(SnapshotBuilder::build).toList();
    }

    /** Independent bounded transaction: no table rewrite and no cloud backup mutation. */
    public int deleteExpiredBatch(LocalDate floor, boolean captures) {
        Integer count = writes.execute(tx -> {
            timeouts();
            String table = captures ? "fubon_intraday_candle_capture" : "fubon_intraday_candle";
            return jdbc.update("DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table
                    + " WHERE source_date<? ORDER BY source_date LIMIT 10000 FOR UPDATE SKIP LOCKED)", floor);
        });
        return count == null ? 0 : count;
    }

    public boolean hasExpired(LocalDate floor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fubon_intraday_candle WHERE source_date<?)
                    OR EXISTS(SELECT 1 FROM fubon_intraday_candle_capture WHERE source_date<?)
                """,Boolean.class,floor,floor));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL statement_timeout='10s'");
        jdbc.execute("SET LOCAL lock_timeout='2s'");
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static final class SourceConflict extends RuntimeException {}
    private static final class SnapshotBuilder {
        final String code; final LocalDate date; final Instant started, captured, completed;
        final String status, reason; final List<FubonIntradayCandleBatch.Candle> candles = new ArrayList<>();
        SnapshotBuilder(String code, LocalDate date, Instant started, Instant captured, Instant completed,
                        String status, String reason) {
            this.code=code; this.date=date; this.started=started; this.captured=captured;
            this.completed=completed; this.status=status; this.reason=reason;
        }
        Snapshot build() { return new Snapshot(code,date,started,captured,completed,status,reason,List.copyOf(candles)); }
    }
}
