package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/**
 * The sole external-materials Jdbc writer for Task408 source facts.  It never
 * receives local radar calculations, and it commits a complete technical
 * capture before any caller is allowed to project it to Redis.
 */
@Component
public class FubonMarketDataHistoryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;

    public FubonMarketDataHistoryStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.writes = new TransactionTemplate(transactionManager);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public enum Status { WRITTEN, UNCHANGED, CONFLICT_NO_SOURCE_REVISION, FAILED }
    public record TechnicalResult(Status facts, boolean completeCaptureCommitted, int factWritten,
                                  int factUnchanged, int conflicts, UUID captureId) {}
    public record BasicResult(Status status) {}
    public record CandlesResult(Status status, int written, int unchanged, int conflicts) {}

    public TechnicalResult persistTechnical(TechnicalBundle bundle) {
        if (bundle == null || !validSymbol(bundle.symbol()) || !validProfileManifest(bundle)) return failedTechnical(bundle);
        try {
            TechnicalResult value = writes.execute(ignored -> persistTechnicalInTransaction(bundle));
            return value == null ? failedTechnical(bundle) : value;
        } catch (RuntimeException failure) { return failedTechnical(bundle); }
    }

    private TechnicalResult persistTechnicalInTransaction(TechnicalBundle bundle) {
        int written = 0, unchanged = 0, conflicts = 0;
        Map<String, FactRef> candidateFacts = new LinkedHashMap<>();
        Map<String, FactRef> previousFacts = new HashMap<>();
        for (TechnicalProfileRead response : bundle.profiles()) {
            TechnicalProfile profile = profile(response.profileId());
            if (!profile.parameters().equals(response.parameters())) throw new IllegalArgumentException("INVALID_PROFILE");
            if (!response.available()) continue;
            for (TechnicalHistory history : response.history()) {
                String hash = FubonCanonicalHash.technical(profile, history.sourceDate(), history.payload());
                Status result = insertFact(bundle, profile, history, hash);
                if (result == Status.WRITTEN) written++;
                else if (result == Status.UNCHANGED) unchanged++;
                else if (result == Status.CONFLICT_NO_SOURCE_REVISION) conflicts++;
                if (history == response.candidate()) candidateFacts.put(profile.profileId(), new FactRef(history.sourceDate(), hash));
                if (history == response.previous()) previousFacts.put(profile.profileId(), new FactRef(history.sourceDate(), hash));
            }
        }
        boolean complete = bundle.complete() && conflicts == 0 && candidateFacts.size() == TECHNICAL_PROFILES.size()
                && candidatesPointToFacts(bundle, candidateFacts);
        if (complete) {
            for (TechnicalProfileRead response : bundle.profiles()) {
                TechnicalProfile profile = profile(response.profileId());
                FactRef candidate = candidateFacts.get(profile.profileId());
                FactRef previous = previousFacts.get(profile.profileId());
                insertMember(bundle, response, profile, candidate, previous);
            }
        }
        Status status = conflicts > 0 ? Status.CONFLICT_NO_SOURCE_REVISION
                : written > 0 ? Status.WRITTEN : Status.UNCHANGED;
        return new TechnicalResult(status, complete, written, unchanged, conflicts, bundle.captureId());
    }

    /**
     * The wire parser already enforces this, but the persistence boundary must
     * not rely on a particular HTTP client.  Otherwise a unit adapter could
     * hand us duplicate IDs and create a partial capture that looked complete.
     */
    private static boolean validProfileManifest(TechnicalBundle bundle) {
        if (bundle.captureId() == null || bundle.queryFrom() == null || bundle.queryTo() == null
                || bundle.queryFrom().isAfter(bundle.queryTo())
                || bundle.profiles().size() != TECHNICAL_PROFILES.size()) return false;
        for (int index = 0; index < TECHNICAL_PROFILES.size(); index++) {
            TechnicalProfile expected = TECHNICAL_PROFILES.get(index);
            TechnicalProfileRead actual = bundle.profiles().get(index);
            if (actual == null || !expected.profileId().equals(actual.profileId())
                    || !expected.parameters().equals(actual.parameters()) || actual.observedAt() == null)
                return false;
            if (actual.available() && (actual.history().isEmpty() || actual.history().size() > 421
                    || !strictHistory(actual.history(), bundle.queryFrom(), bundle.queryTo()))) return false;
            if (!actual.available() && !actual.history().isEmpty()) return false;
        }
        return true;
    }

    private static boolean strictHistory(List<TechnicalHistory> rows, LocalDate from, LocalDate to) {
        LocalDate previous = null;
        for (TechnicalHistory row : rows) {
            if (row == null || row.sourceDate() == null || row.sourceDate().isBefore(from)
                    || row.sourceDate().isAfter(to) || previous != null && !row.sourceDate().isAfter(previous)
                    || row.sourceTimestamp() != null || row.payload() == null) return false;
            previous = row.sourceDate();
        }
        return true;
    }

    private Status insertFact(TechnicalBundle bundle, TechnicalProfile profile, TechnicalHistory history, String hash) {
        String parameters = FubonCanonicalHash.canonical(profile.parameters());
        String payload = FubonCanonicalHash.canonical(history.payload());
        int created = jdbc.update("""
                INSERT INTO stock_technical_indicator
                  (stock_code, market, provider, timeframe, profile_id, source_date, indicator_kind, parameters, payload,
                   source_timestamp, capture_id, observed_at, first_observed_at, content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_code, market, provider, timeframe, profile_id, source_date) DO NOTHING
                """, bundle.symbol(), MARKET, PROVIDER, profile.timeframe(), profile.profileId(), history.sourceDate(), profile.kind(),
                parameters, payload, null, bundle.captureId(), Timestamp.from(observedFor(bundle, profile.profileId())),
                Timestamp.from(observedFor(bundle, profile.profileId())), hash);
        if (created == 1) return Status.WRITTEN;
        List<String> existing = jdbc.query("""
                SELECT content_hash FROM stock_technical_indicator
                WHERE stock_code=? AND market=? AND provider=? AND timeframe=? AND profile_id=? AND source_date=?
                """, (rs, row) -> rs.getString(1), bundle.symbol(), MARKET, PROVIDER, profile.timeframe(),
                profile.profileId(), history.sourceDate());
        if (existing.size() != 1) throw new IllegalStateException("technical fact disappeared");
        return hash.equals(existing.getFirst()) ? Status.UNCHANGED : Status.CONFLICT_NO_SOURCE_REVISION;
    }

    private boolean candidatesPointToFacts(TechnicalBundle bundle, Map<String, FactRef> candidates) {
        for (TechnicalProfile profile : TECHNICAL_PROFILES) {
            FactRef fact = candidates.get(profile.profileId());
            Integer matches = jdbc.queryForObject("""
                    SELECT count(*) FROM stock_technical_indicator
                    WHERE stock_code=? AND market=? AND provider=? AND timeframe=? AND profile_id=? AND source_date=? AND content_hash=?
                    """, Integer.class, bundle.symbol(), MARKET, PROVIDER, profile.timeframe(), profile.profileId(),
                    fact.sourceDate(), fact.hash());
            if (matches == null || matches != 1) return false;
        }
        return true;
    }

    private void insertMember(TechnicalBundle bundle, TechnicalProfileRead response, TechnicalProfile profile,
                              FactRef candidate, FactRef previous) {
        // A KDJ previous pair is only used if it came from this response's adjacent row.
        boolean allowedPrevious = Set.of("kdj_d_9_3_3", "kdj_w_9_3_3").contains(profile.profileId()) && previous != null;
        jdbc.update("""
                INSERT INTO fubon_technical_capture_member
                  (capture_id, profile_id, stock_code, market, provider, timeframe, source_date, content_hash, observed_at,
                   previous_source_date, previous_content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (capture_id, profile_id) DO NOTHING
                """, bundle.captureId(), profile.profileId(), bundle.symbol(), MARKET, PROVIDER, profile.timeframe(),
                candidate.sourceDate(), candidate.hash(), Timestamp.from(response.observedAt()),
                allowedPrevious ? previous.sourceDate() : null, allowedPrevious ? previous.hash() : null);
    }

    public BasicResult persistBasic(StockBasicRead basic) {
        if (basic == null || !validSymbol(basic.symbol())) return new BasicResult(Status.FAILED);
        try {
            BasicResult result = writes.execute(ignored -> persistBasicInTransaction(basic));
            return result == null ? new BasicResult(Status.FAILED) : result;
        } catch (RuntimeException failure) { return new BasicResult(Status.FAILED); }
    }

    private BasicResult persistBasicInTransaction(StockBasicRead basic) {
        String hash = FubonCanonicalHash.basic(basicCanonicalDocument(basic));
        List<BasicExisting> existing = jdbc.query("""
                SELECT source_date, content_hash FROM fubon_stock_basic_info WHERE stock_code=? AND market=? AND provider=?
                """, (rs, row) -> new BasicExisting(rs.getObject(1, LocalDate.class), rs.getString(2)),
                basic.symbol(), MARKET, PROVIDER);
        if (!existing.isEmpty()) {
            BasicExisting previous = existing.getFirst();
            if (basic.sourceDate().isBefore(previous.sourceDate())) return new BasicResult(Status.CONFLICT_NO_SOURCE_REVISION);
            if (basic.sourceDate().equals(previous.sourceDate()) && !hash.equals(previous.hash()))
                return new BasicResult(Status.CONFLICT_NO_SOURCE_REVISION);
            if (hash.equals(previous.hash())) return new BasicResult(Status.UNCHANGED);
        }
        jdbc.update("""
                INSERT INTO fubon_stock_basic_info
                  (stock_code, market, provider, source_date, exchange, instrument_type, source_name, industry, security_type,
                   source_market, price_limit_up, price_limit_down, trading_eligible, trading_status, matching_interval, board_lot,
                   currency, observed_at, content_hash)
                VALUES (?, ?, ?, ?, ?, 'EQUITY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_code, market, provider) DO UPDATE SET
                  source_date=EXCLUDED.source_date, exchange=EXCLUDED.exchange, instrument_type=EXCLUDED.instrument_type,
                  source_name=EXCLUDED.source_name, industry=EXCLUDED.industry, security_type=EXCLUDED.security_type,
                  source_market=EXCLUDED.source_market, price_limit_up=EXCLUDED.price_limit_up, price_limit_down=EXCLUDED.price_limit_down,
                  trading_eligible=EXCLUDED.trading_eligible, trading_status=EXCLUDED.trading_status,
                  matching_interval=EXCLUDED.matching_interval, board_lot=EXCLUDED.board_lot, currency=EXCLUDED.currency,
                  observed_at=EXCLUDED.observed_at, content_hash=EXCLUDED.content_hash
                """, basic.symbol(), MARKET, PROVIDER, basic.sourceDate(), basic.exchange(), basic.sourceName(), basic.industry(),
                basic.securityType(), basic.sourceMarket(), basic.limitUpPrice(), basic.limitDownPrice(), basic.tradingEligible(),
                basic.tradingStatus(), basic.matchingInterval(), basic.boardLot(), basic.currency(), Timestamp.from(basic.observedAt()), hash);
        return new BasicResult(Status.WRITTEN);
    }

    public CandlesResult persistCandles(IntradayCandlesRead read) {
        if (read == null || !"AVAILABLE".equals(read.status()) || !validSymbol(read.symbol()))
            return new CandlesResult(Status.FAILED, 0, 0, 0);
        try {
            CandlesResult result = writes.execute(ignored -> persistCandlesInTransaction(read));
            return result == null ? new CandlesResult(Status.FAILED, 0, 0, 0) : result;
        } catch (RuntimeException failure) { return new CandlesResult(Status.FAILED, 0, 0, 0); }
    }

    private CandlesResult persistCandlesInTransaction(IntradayCandlesRead read) {
        int written = 0, unchanged = 0, conflicts = 0;
        for (IntradayCandle candle : read.candles()) {
            String hash = FubonCanonicalHash.candle(candleCanonicalDocument(read, candle));
            int inserted = jdbc.update("""
                    INSERT INTO fubon_intraday_candle
                      (stock_code, market, provider, timeframe, candle_at, source_date, exchange, open, high, low, close, average,
                       volume, observed_at, content_hash)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (stock_code, market, provider, timeframe, candle_at) DO NOTHING
                    """, read.symbol(), MARKET, PROVIDER, Timestamp.from(candle.candleAt()), read.sourceDate(), read.exchange(),
                    candle.open(), candle.high(), candle.low(), candle.close(), candle.average(), candle.volume(),
                    Timestamp.from(read.observedAt()), hash);
            if (inserted == 1) { written++; continue; }
            List<String> existing = jdbc.query("""
                    SELECT content_hash FROM fubon_intraday_candle
                    WHERE stock_code=? AND market=? AND provider=? AND timeframe=1 AND candle_at=?
                    """, (rs, row) -> rs.getString(1), read.symbol(), MARKET, PROVIDER, Timestamp.from(candle.candleAt()));
            if (existing.size() != 1) throw new IllegalStateException("minute fact disappeared");
            if (hash.equals(existing.getFirst())) unchanged++; else conflicts++;
        }
        Status status = conflicts > 0 ? Status.CONFLICT_NO_SOURCE_REVISION : written > 0 ? Status.WRITTEN : Status.UNCHANGED;
        return new CandlesResult(status, written, unchanged, conflicts);
    }

    private static Instant observedFor(TechnicalBundle bundle, String profileId) {
        return bundle.profiles().stream().filter(value -> value.profileId().equals(profileId)).findFirst()
                .map(TechnicalProfileRead::observedAt).orElseThrow(() -> new IllegalArgumentException("profile missing"));
    }
    private static TechnicalResult failedTechnical(TechnicalBundle bundle) {
        return new TechnicalResult(Status.FAILED, false, 0, 0, 0, bundle == null ? null : bundle.captureId());
    }
    private static Map<String, Object> basicCanonicalDocument(StockBasicRead value) {
        Map<String, Object> result = new TreeMap<>();
        result.put("schemaVersion", 1); result.put("symbol", value.symbol()); result.put("market", MARKET); result.put("provider", PROVIDER);
        result.put("sourceDate", value.sourceDate().toString()); result.put("instrumentType", "EQUITY"); result.put("exchange", value.exchange());
        result.put("sourceMarket", value.sourceMarket()); result.put("sourceName", value.sourceName()); result.put("industry", value.industry());
        result.put("securityType", value.securityType()); result.put("limitUpPrice", value.limitUpPrice() == null ? null : FubonCanonicalHash.decimal(value.limitUpPrice()));
        result.put("limitDownPrice", value.limitDownPrice() == null ? null : FubonCanonicalHash.decimal(value.limitDownPrice()));
        result.put("tradingEligible", value.tradingEligible()); result.put("tradingStatus", value.tradingStatus());
        result.put("matchingInterval", value.matchingInterval()); result.put("boardLot", value.boardLot()); result.put("currency", value.currency());
        return result;
    }
    private static Map<String, Object> candleCanonicalDocument(IntradayCandlesRead read, IntradayCandle value) {
        Map<String, Object> candle = new TreeMap<>();
        candle.put("candleAt", value.candleAt().toString()); candle.put("open", FubonCanonicalHash.decimal(value.open()));
        candle.put("high", FubonCanonicalHash.decimal(value.high())); candle.put("low", FubonCanonicalHash.decimal(value.low()));
        candle.put("close", FubonCanonicalHash.decimal(value.close())); candle.put("volume", Long.toString(value.volume()));
        candle.put("average", FubonCanonicalHash.decimal(value.average()));
        Map<String, Object> root = new TreeMap<>();
        root.put("symbol", read.symbol()); root.put("market", MARKET); root.put("provider", PROVIDER);
        root.put("sourceDate", read.sourceDate().toString()); root.put("instrumentType", "EQUITY"); root.put("exchange", read.exchange());
        root.put("sourceMarket", read.sourceMarket()); root.put("timeframe", 1); root.put("candle", candle);
        return root;
    }
    private record FactRef(LocalDate sourceDate, String hash) {}
    private record BasicExisting(LocalDate sourceDate, String hash) {}
}
