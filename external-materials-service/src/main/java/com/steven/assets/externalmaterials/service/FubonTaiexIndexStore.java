package com.steven.assets.externalmaterials.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-canonical one-row TAIEX realtime state.
 *
 * <p>The strict source-time comparison is deliberately in one SQL upsert.  The write transaction
 * uses {@code REQUIRES_NEW}; callers receive its row only after {@link TransactionTemplate#execute}
 * has successfully committed, and can therefore safely project it to Redis outside this class.</p>
 */
@Slf4j
@Component
public class FubonTaiexIndexStore {

    private static final String INDEX_CODE = "0000";
    private static final String EXCHANGE = "TWSE";
    private static final String SOURCE = "FUBON_INDICES";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public FubonTaiexIndexStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public enum PersistStatus { APPLIED, STALE_OR_EQUAL, FAILED }
    public enum ReadStatus { FOUND, NOT_FOUND, FAILED }

    /** Returned values came from PostgreSQL, never from an incoming raw SSE frame. */
    public record CanonicalIndex(
            String indexCode,
            String providerSymbol,
            String exchange,
            LocalDate tradingDate,
            Instant providerUpdatedAt,
            BigDecimal indexPoint,
            String source) {
    }

    public record Candidate(
            String providerSymbol,
            LocalDate tradingDate,
            Instant providerUpdatedAt,
            BigDecimal indexPoint) {
    }

    public record PersistResult(PersistStatus status, CanonicalIndex canonical) {
        static PersistResult applied(CanonicalIndex row) {
            return new PersistResult(PersistStatus.APPLIED, row);
        }

        static PersistResult stale(CanonicalIndex row) {
            return new PersistResult(PersistStatus.STALE_OR_EQUAL, row);
        }

        static PersistResult failed() {
            return new PersistResult(PersistStatus.FAILED, null);
        }
    }

    public record FindResult(ReadStatus status, CanonicalIndex canonical) {
        static FindResult found(CanonicalIndex row) {
            return new FindResult(ReadStatus.FOUND, row);
        }

        static FindResult missing() {
            return new FindResult(ReadStatus.NOT_FOUND, null);
        }

        static FindResult failed() {
            return new FindResult(ReadStatus.FAILED, null);
        }
    }

    public PersistResult persist(Candidate candidate) {
        if (!validCandidate(candidate)) return PersistResult.failed();
        try {
            PersistResult result = writeTransaction.execute(ignored -> persistWithinTransaction(candidate));
            return result == null ? PersistResult.failed() : result;
        } catch (Exception failure) {
            log.warn("Fubon TAIEX canonical DB 寫入失敗 reason=DB_WRITE_FAILED");
            return PersistResult.failed();
        }
    }

    /** Pure read used by the Yahoo fallback; no cache repair happens here. */
    public FindResult findForTradingDate(LocalDate tradingDate) {
        if (tradingDate == null) return FindResult.missing();
        try {
            FindResult result = readTransaction.execute(ignored -> readForDate(tradingDate)
                    .map(FindResult::found).orElseGet(FindResult::missing));
            return result == null ? FindResult.failed() : result;
        } catch (Exception failure) {
            log.warn("Fubon TAIEX canonical DB 讀取失敗 reason=DB_READ_FAILED");
            return FindResult.failed();
        }
    }

    private PersistResult persistWithinTransaction(Candidate candidate) {
        List<CanonicalIndex> applied = jdbc.query("""
                INSERT INTO fubon_taiex_index_latest
                  (index_code, provider_symbol, exchange, trading_date, provider_updated_at, index_point, source)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (index_code) DO UPDATE SET
                  provider_symbol=EXCLUDED.provider_symbol,
                  exchange=EXCLUDED.exchange,
                  trading_date=EXCLUDED.trading_date,
                  provider_updated_at=EXCLUDED.provider_updated_at,
                  index_point=EXCLUDED.index_point,
                  source=EXCLUDED.source
                WHERE EXCLUDED.provider_updated_at > fubon_taiex_index_latest.provider_updated_at
                RETURNING index_code, provider_symbol, exchange, trading_date, provider_updated_at, index_point, source
                """, (rs, rowNum) -> readRow(rs),
                INDEX_CODE, candidate.providerSymbol(), EXCHANGE, candidate.tradingDate(),
                Timestamp.from(candidate.providerUpdatedAt()), candidate.indexPoint(), SOURCE);
        if (!applied.isEmpty()) return PersistResult.applied(applied.getFirst());

        CanonicalIndex canonical = readCurrent().orElseThrow(
                () -> new IllegalStateException("canonical TAIEX row missing after stale conflict"));
        return PersistResult.stale(canonical);
    }

    private Optional<CanonicalIndex> readForDate(LocalDate tradingDate) {
        List<CanonicalIndex> rows = jdbc.query("""
                SELECT index_code, provider_symbol, exchange, trading_date, provider_updated_at, index_point, source
                FROM fubon_taiex_index_latest WHERE index_code=? AND trading_date=?
                """, (rs, rowNum) -> readRow(rs), INDEX_CODE, tradingDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private Optional<CanonicalIndex> readCurrent() {
        List<CanonicalIndex> rows = jdbc.query("""
                SELECT index_code, provider_symbol, exchange, trading_date, provider_updated_at, index_point, source
                FROM fubon_taiex_index_latest WHERE index_code=?
                """, (rs, rowNum) -> readRow(rs), INDEX_CODE);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static CanonicalIndex readRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CanonicalIndex(rs.getString("index_code"), rs.getString("provider_symbol"),
                rs.getString("exchange"), rs.getObject("trading_date", LocalDate.class),
                rs.getTimestamp("provider_updated_at").toInstant(), rs.getBigDecimal("index_point"),
                rs.getString("source"));
    }

    private static boolean validCandidate(Candidate candidate) {
        if (candidate == null || !FubonTaiexIndexContract.validSymbol(candidate.providerSymbol())
                || candidate.tradingDate() == null || candidate.providerUpdatedAt() == null
                || candidate.indexPoint() == null || candidate.indexPoint().signum() <= 0
                || candidate.indexPoint().precision() > 20 || candidate.indexPoint().scale() < 0
                || candidate.indexPoint().scale() > 10) return false;
        return candidate.tradingDate().equals(
                candidate.providerUpdatedAt().atZone(MarketClock.TW_ZONE).toLocalDate());
    }
}
