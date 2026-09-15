package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PostgreSQL is the sole canonical authority for a Taiwan five-level snapshot.
 *
 * <p>The conditional UPSERT performs the complete two-source comparator and increments the
 * database-assigned revision atomically. A stale writer re-reads the locked canonical row; it
 * never compares source/time in JVM memory or returns its raw candidate to Redis.</p>
 */
@Slf4j
@Component
public class IntradayOrderBookSnapshotStore {

    private static final String TAIWAN = "台股";
    private static final String FUBON = "FUBON_BOOKS";
    private static final String YAHOO = "YAHOO_TW";
    private static final Pattern CODE = Pattern.compile("^[0-9]{4,6}[A-Z]?$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public IntradayOrderBookSnapshotStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        // Header and levels are read by separate SQL statements. PostgreSQL READ_COMMITTED could
        // otherwise expose an old header followed by a newer five-level set if a writer commits
        // between them. One repeatable-read transaction makes the returned canonical snapshot
        // immutable at one database revision.
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public enum PersistStatus { APPLIED, STALE_OR_EQUAL, FAILED }
    public enum ReadStatus { FOUND, NOT_FOUND, FAILED }

    /** A typed full snapshot paired only with the revision assigned by PostgreSQL. */
    public record CanonicalSnapshot(TwQuoteDetailFetchClient.QuoteDetailResult snapshot, long canonicalRevision) {}

    public record PersistResult(PersistStatus status, CanonicalSnapshot canonical) {
        static PersistResult applied(CanonicalSnapshot value) {
            return new PersistResult(PersistStatus.APPLIED, value);
        }

        static PersistResult stale(CanonicalSnapshot value) {
            return new PersistResult(PersistStatus.STALE_OR_EQUAL, value);
        }

        static PersistResult failed() {
            return new PersistResult(PersistStatus.FAILED, null);
        }
    }

    /** Header-only pure-read result; it distinguishes a DB failure from a normal miss. */
    public record RevisionLookup(ReadStatus status, long canonicalRevision) {
        static RevisionLookup found(long revision) { return new RevisionLookup(ReadStatus.FOUND, revision); }
        static RevisionLookup missing() { return new RevisionLookup(ReadStatus.NOT_FOUND, 0L); }
        static RevisionLookup failed() { return new RevisionLookup(ReadStatus.FAILED, 0L); }
    }

    /** Full pure-read result; callers must not treat a failed DB read as a cache hit. */
    public record CanonicalLookup(ReadStatus status, CanonicalSnapshot canonical) {
        static CanonicalLookup found(CanonicalSnapshot canonical) { return new CanonicalLookup(ReadStatus.FOUND, canonical); }
        static CanonicalLookup missing() { return new CanonicalLookup(ReadStatus.NOT_FOUND, null); }
        static CanonicalLookup failed() { return new CanonicalLookup(ReadStatus.FAILED, null); }
    }

    /** Validates every value before it can reach the canonical write transaction. */
    public boolean isPersistable(TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {
        if (snapshot == null || !snapshot.supported() || !snapshot.available()
                || !TAIWAN.equals(snapshot.market()) || !allowedSource(snapshot.source())
                || !"OPEN".equals(snapshot.marketStatus()) || snapshot.stockCode() == null
                || !CODE.matcher(snapshot.stockCode()).matches() || "0000".equals(snapshot.stockCode())
                || snapshot.stockName() == null || snapshot.stockName().isBlank()
                || snapshot.stockName().trim().equalsIgnoreCase(snapshot.stockCode())
                || snapshot.sourceTime() == null || snapshot.sourceTime().isBefore(Instant.EPOCH)
                || !hasMicrosecondPrecision(snapshot.sourceTime()) || snapshot.fetchedAt() == null
                || !validPositive(snapshot.price()) || !validPositive(snapshot.previousClose())
                || !validPositiveOrNull(snapshot.openPrice()) || !validPositiveOrNull(snapshot.highPrice())
                || !validPositiveOrNull(snapshot.lowPrice()) || !validPositiveOrNull(snapshot.averagePrice())
                || !validNonNegativeOrNull(snapshot.turnoverYi()) || !validNonNegativeOrNull(snapshot.volumeLots())
                || !validNonNegativeOrNull(snapshot.previousVolumeLots())
                || !validNonNegativeOrNull(snapshot.innerVolumeLots())
                || !validNonNegativeOrNull(snapshot.outerVolumeLots())
                || snapshot.levels() == null || snapshot.levels().size() != 5) {
            return false;
        }
        if (snapshot.highPrice() != null && snapshot.lowPrice() != null
                && snapshot.highPrice().compareTo(snapshot.lowPrice()) < 0) return false;
        Set<BigDecimal> bidPrices = new HashSet<>();
        Set<BigDecimal> askPrices = new HashSet<>();
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        for (int index = 0; index < 5; index++) {
            TwQuoteDetailFetchClient.OrderBookLevel level = snapshot.levels().get(index);
            if (level == null || level.level() != index + 1
                    || !validCompleteSide(level.bidPrice(), level.bidVolumeLots(), bidPrices)
                    || !validCompleteSide(level.askPrice(), level.askVolumeLots(), askPrices)
                    || (previousBid != null && previousBid.compareTo(level.bidPrice()) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(level.askPrice()) >= 0)) {
                return false;
            }
            previousBid = level.bidPrice();
            previousAsk = level.askPrice();
        }
        return true;
    }

    /**
     * Applies a candidate atomically and returns the committed canonical row plus its revision.
     * A valid existing stock master is required; header and all five levels then commit together.
     */
    public PersistResult persist(TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {
        if (!isPersistable(snapshot)) return PersistResult.failed();
        try {
            PersistResult result = writeTransaction.execute(ignored -> persistWithinTransaction(snapshot));
            return result == null ? PersistResult.failed() : result;
        } catch (Exception failure) {
            log.warn("五檔 canonical DB 寫入失敗 market={} code={}", snapshot.market(), snapshot.stockCode());
            return PersistResult.failed();
        }
    }

    /** Read the current header revision without changing any persistence or cache state. */
    public RevisionLookup findRevision(String code, String market) {
        if (!isSupportedIdentity(code, market)) return RevisionLookup.missing();
        try {
            RevisionLookup result = readTransaction.execute(ignored -> {
                List<Long> revisions = jdbc.query("""
                        SELECT canonical_revision FROM stock_intraday_order_book
                        WHERE stock_code=? AND market=?
                        """, (rs, rowNum) -> rs.getLong("canonical_revision"), code, market);
                return revisions.isEmpty() ? RevisionLookup.missing() : RevisionLookup.found(revisions.getFirst());
            });
            return result == null ? RevisionLookup.failed() : result;
        } catch (Exception failure) {
            log.warn("五檔 canonical DB revision 讀取失敗 market={} code={}", market, code);
            return RevisionLookup.failed();
        }
    }

    /** Read the full canonical snapshot and its revision without touching Redis. */
    public CanonicalLookup findCanonical(String code, String market) {
        if (!isSupportedIdentity(code, market)) return CanonicalLookup.missing();
        try {
            CanonicalLookup result = readTransaction.execute(ignored -> readCanonicalLookup(code, market));
            return result == null ? CanonicalLookup.failed() : result;
        } catch (Exception failure) {
            log.warn("五檔 canonical DB 讀取失敗 market={} code={}", market, code);
            return CanonicalLookup.failed();
        }
    }

    /** Backward-compatible convenience for old internal tests; new readers use {@link #findCanonical}. */
    public Optional<TwQuoteDetailFetchClient.QuoteDetailResult> find(String code, String market) {
        CanonicalLookup lookup = findCanonical(code, market);
        return lookup.status() == ReadStatus.FOUND ? Optional.of(lookup.canonical().snapshot()) : Optional.empty();
    }

    private PersistResult persistWithinTransaction(TwQuoteDetailFetchClient.QuoteDetailResult snapshot) {
        List<String> masterNames = jdbc.query("SELECT name FROM stock WHERE code=? AND market=?",
                (rs, rowNum) -> rs.getString("name"), snapshot.stockCode(), snapshot.market());
        if (masterNames.size() != 1 || invalidMasterName(masterNames.getFirst(), snapshot.stockCode())) {
            return PersistResult.failed();
        }
        LocalDate tradingDate = snapshot.sourceTime().atZone(MarketClock.TW_ZONE).toLocalDate();
        List<Long> revisions = jdbc.query("""
                INSERT INTO stock_intraday_order_book
                  (stock_code, market, trading_date, source_updated_at, fetched_at, source, market_status,
                   actual_price, previous_close, open_price, high_price, low_price, average_price, turnover_yi,
                   volume_lots, previous_volume_lots, inner_volume_lots, outer_volume_lots, canonical_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (stock_code, market) DO UPDATE SET
                  trading_date=EXCLUDED.trading_date, source_updated_at=EXCLUDED.source_updated_at,
                  fetched_at=EXCLUDED.fetched_at, source=EXCLUDED.source, market_status=EXCLUDED.market_status,
                  actual_price=EXCLUDED.actual_price, previous_close=EXCLUDED.previous_close,
                  open_price=EXCLUDED.open_price, high_price=EXCLUDED.high_price, low_price=EXCLUDED.low_price,
                  average_price=EXCLUDED.average_price, turnover_yi=EXCLUDED.turnover_yi,
                  volume_lots=EXCLUDED.volume_lots, previous_volume_lots=EXCLUDED.previous_volume_lots,
                  inner_volume_lots=EXCLUDED.inner_volume_lots, outer_volume_lots=EXCLUDED.outer_volume_lots,
                  canonical_revision=stock_intraday_order_book.canonical_revision + 1
                WHERE
                  (EXCLUDED.source = stock_intraday_order_book.source
                   AND EXCLUDED.source_updated_at > stock_intraday_order_book.source_updated_at)
                  OR (EXCLUDED.source = 'FUBON_BOOKS' AND stock_intraday_order_book.source = 'YAHOO_TW')
                  OR (EXCLUDED.source = 'YAHOO_TW' AND stock_intraday_order_book.source = 'FUBON_BOOKS'
                      AND EXCLUDED.source_updated_at > stock_intraday_order_book.source_updated_at)
                RETURNING canonical_revision
                """, (rs, rowNum) -> rs.getLong("canonical_revision"),
                snapshot.stockCode(), snapshot.market(), tradingDate, Timestamp.from(snapshot.sourceTime()),
                Timestamp.from(snapshot.fetchedAt()), snapshot.source(), snapshot.marketStatus(), snapshot.price(),
                snapshot.previousClose(), snapshot.openPrice(), snapshot.highPrice(), snapshot.lowPrice(),
                snapshot.averagePrice(), snapshot.turnoverYi(), snapshot.volumeLots(), snapshot.previousVolumeLots(),
                snapshot.innerVolumeLots(), snapshot.outerVolumeLots());
        if (revisions.isEmpty()) {
            return readCanonical(snapshot.stockCode(), snapshot.market())
                    .map(PersistResult::stale)
                    .orElseThrow(() -> new IllegalStateException("canonical order book missing after stale conflict"));
        }

        jdbc.update("DELETE FROM stock_intraday_order_book_level WHERE stock_code=? AND market=?",
                snapshot.stockCode(), snapshot.market());
        for (TwQuoteDetailFetchClient.OrderBookLevel level : snapshot.levels()) {
            jdbc.update("""
                    INSERT INTO stock_intraday_order_book_level
                      (stock_code, market, level, bid_price, bid_volume_lots, ask_price, ask_volume_lots)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.stockCode(), snapshot.market(), level.level(), level.bidPrice(),
                    level.bidVolumeLots(), level.askPrice(), level.askVolumeLots());
        }
        return readCanonical(snapshot.stockCode(), snapshot.market())
                .map(PersistResult::applied)
                .orElseThrow(() -> new IllegalStateException("canonical order book unreadable after applied write"));
    }

    private CanonicalLookup readCanonicalLookup(String code, String market) {
        return readCanonical(code, market).map(CanonicalLookup::found).orElseGet(CanonicalLookup::missing);
    }

    private Optional<CanonicalSnapshot> readCanonical(String code, String market) {
        List<Header> headers = jdbc.query("""
                SELECT h.stock_code, h.market, h.source_updated_at, h.fetched_at, h.source, h.market_status,
                       h.actual_price, h.previous_close, h.open_price, h.high_price, h.low_price, h.average_price,
                       h.turnover_yi, h.volume_lots, h.previous_volume_lots, h.inner_volume_lots,
                       h.outer_volume_lots, h.canonical_revision, s.name AS stock_name
                FROM stock_intraday_order_book h
                LEFT JOIN stock s ON s.code=h.stock_code AND s.market=h.market
                WHERE h.stock_code=? AND h.market=?
                """, (rs, rowNum) -> new Header(
                rs.getString("stock_code"), rs.getString("market"),
                rs.getTimestamp("source_updated_at").toInstant(), rs.getTimestamp("fetched_at").toInstant(),
                rs.getString("source"), rs.getString("market_status"), rs.getBigDecimal("actual_price"),
                rs.getBigDecimal("previous_close"), rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
                rs.getBigDecimal("low_price"), rs.getBigDecimal("average_price"), rs.getBigDecimal("turnover_yi"),
                rs.getObject("volume_lots", Long.class), rs.getObject("previous_volume_lots", Long.class),
                rs.getObject("inner_volume_lots", Long.class), rs.getObject("outer_volume_lots", Long.class),
                rs.getLong("canonical_revision"), rs.getString("stock_name")), code, market);
        if (headers.isEmpty()) return Optional.empty();
        Header header = headers.getFirst();
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = jdbc.query("""
                SELECT level, bid_price, bid_volume_lots, ask_price, ask_volume_lots
                FROM stock_intraday_order_book_level
                WHERE stock_code=? AND market=?
                ORDER BY level
                """, (rs, rowNum) -> new TwQuoteDetailFetchClient.OrderBookLevel(
                rs.getInt("level"), rs.getBigDecimal("bid_price"), rs.getObject("bid_volume_lots", Long.class),
                rs.getBigDecimal("ask_price"), rs.getObject("ask_volume_lots", Long.class)), code, market);
        if (levels.size() != 5) return Optional.empty();

        BigDecimal change = header.actualPrice.subtract(header.previousClose);
        BigDecimal changePercent = percentage(change, header.previousClose);
        BigDecimal amplitude = header.highPrice == null || header.lowPrice == null
                ? null : percentage(header.highPrice.subtract(header.lowPrice), header.previousClose);
        TwQuoteDetailFetchClient.QuoteDetailResult snapshot = new TwQuoteDetailFetchClient.QuoteDetailResult(
                header.stockCode, header.stockName, header.market, true, true, header.source, null,
                header.sourceUpdatedAt, header.fetchedAt, header.marketStatus, header.actualPrice,
                header.previousClose, header.openPrice, header.highPrice, header.lowPrice, header.averagePrice,
                change, changePercent, header.turnoverYi, header.volumeLots, header.previousVolumeLots, amplitude,
                header.innerVolumeLots, header.outerVolumeLots,
                sharePercentage(header.innerVolumeLots, header.outerVolumeLots, true),
                sharePercentage(header.innerVolumeLots, header.outerVolumeLots, false),
                total(levels, true), total(levels, false), List.copyOf(levels));
        return isPersistable(snapshot) && header.canonicalRevision > 0
                ? Optional.of(new CanonicalSnapshot(snapshot, header.canonicalRevision)) : Optional.empty();
    }

    private static boolean isSupportedIdentity(String code, String market) {
        return TAIWAN.equals(market) && code != null && CODE.matcher(code).matches() && !"0000".equals(code);
    }

    private static boolean invalidMasterName(String name, String code) {
        return name == null || name.isBlank() || name.trim().equalsIgnoreCase(code);
    }

    private static boolean allowedSource(String source) {
        return FUBON.equals(source) || YAHOO.equals(source);
    }

    private static boolean hasMicrosecondPrecision(Instant value) {
        return value.getNano() % 1_000 == 0;
    }

    private static boolean validPositive(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10;
    }

    private static boolean validPositiveOrNull(BigDecimal value) {
        return value == null || validPositive(value);
    }

    private static boolean validNonNegativeOrNull(BigDecimal value) {
        return value == null || (value.signum() >= 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10);
    }

    private static boolean validNonNegativeOrNull(Long value) {
        return value == null || value >= 0;
    }

    private static boolean validCompleteSide(BigDecimal price, Long lots, Set<BigDecimal> seen) {
        return validPositive(price) && lots != null && lots > 0 && seen.add(price.stripTrailingZeros());
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() <= 0
                ? null : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sharePercentage(Long inner, Long outer, boolean useInner) {
        if (inner == null || outer == null) return null;
        try {
            long total = Math.addExact(inner, outer);
            if (total <= 0) return null;
            BigDecimal innerPercent = BigDecimal.valueOf(inner).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            return useInner ? innerPercent : BigDecimal.valueOf(100).setScale(2).subtract(innerPercent);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static Long total(List<TwQuoteDetailFetchClient.OrderBookLevel> levels, boolean bid) {
        try {
            long total = 0;
            for (TwQuoteDetailFetchClient.OrderBookLevel level : levels) {
                total = Math.addExact(total, bid ? level.bidVolumeLots() : level.askVolumeLots());
            }
            return total;
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private record Header(
            String stockCode, String market, Instant sourceUpdatedAt, Instant fetchedAt, String source,
            String marketStatus, BigDecimal actualPrice, BigDecimal previousClose, BigDecimal openPrice,
            BigDecimal highPrice, BigDecimal lowPrice, BigDecimal averagePrice, BigDecimal turnoverYi,
            Long volumeLots, Long previousVolumeLots, Long innerVolumeLots, Long outerVolumeLots,
            long canonicalRevision, String stockName) {}
}
