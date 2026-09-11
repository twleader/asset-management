package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Immutable Task425 source-fact writer.  Projection is deliberately owned by the caller after commit. */
@Repository
public class FubonHistoricalDailyCandleStore {
    public enum Status { WRITTEN, UNCHANGED, CONFLICT_NO_SOURCE_REVISION, FAILED }
    public record Result(Status status) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate facts;

    public FubonHistoricalDailyCandleStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.facts = new TransactionTemplate(transactionManager);
        this.facts.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Each invocation is its own committed fact transaction before any price-history projection can begin. */
    public Result persist(HistoricalDailyCandlesRead read, HistoricalDailyCandle candle) {
        if (!valid(read, candle)) return new Result(Status.FAILED);
        try {
            Result result = facts.execute(ignored -> persistInNewTransaction(read, candle));
            return result == null ? new Result(Status.FAILED) : result;
        } catch (RuntimeException failure) { return new Result(Status.FAILED); }
    }

    private Result persistInNewTransaction(HistoricalDailyCandlesRead read, HistoricalDailyCandle candle) {
        Map<String, Object> document = canonicalDocument(read, candle);
        String payload = FubonCanonicalHash.canonical(document);
        String hash = FubonCanonicalHash.dailyCandle(document);
        int created = jdbc.update("""
                INSERT INTO fubon_historical_daily_candle
                  (stock_code, market, provider, trading_date, exchange, source_market, open, high, low, close, volume, turnover,
                   price_change, observed_at, schema_version, canonical_payload, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?::jsonb, ?)
                ON CONFLICT (stock_code, market, trading_date) DO NOTHING
                """, read.symbol(), MARKET, PROVIDER, candle.tradingDate(), read.exchange(), read.sourceMarket(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume(), candle.turnover(), candle.priceChange(),
                Timestamp.from(read.observedAt()), payload, hash);
        if (created == 1) return new Result(Status.WRITTEN);
        List<String> hashes = jdbc.query("""
                SELECT payload_hash FROM fubon_historical_daily_candle
                WHERE stock_code=? AND market=? AND trading_date=?
                """, (rs, row) -> rs.getString(1), read.symbol(), MARKET, candle.tradingDate());
        if (hashes.size() != 1) throw new IllegalStateException("daily source fact disappeared");
        return new Result(hash.equals(hashes.getFirst()) ? Status.UNCHANGED : Status.CONFLICT_NO_SOURCE_REVISION);
    }

    static Map<String, Object> canonicalDocument(HistoricalDailyCandlesRead read, HistoricalDailyCandle candle) {
        Map<String, Object> value = new java.util.TreeMap<>();
        value.put("schemaVersion", 1); value.put("symbol", read.symbol()); value.put("market", MARKET); value.put("provider", PROVIDER);
        value.put("tradingDate", candle.tradingDate().toString()); value.put("exchange", read.exchange()); value.put("sourceMarket", read.sourceMarket());
        value.put("open", canonical(candle.open())); value.put("high", canonical(candle.high())); value.put("low", canonical(candle.low()));
        value.put("close", canonical(candle.close())); value.put("volume", Long.toString(candle.volume()));
        value.put("turnover", canonical(candle.turnover())); value.put("change", candle.priceChange() == null ? null : canonical(candle.priceChange()));
        return value;
    }

    private static boolean valid(HistoricalDailyCandlesRead read, HistoricalDailyCandle candle) {
        if (read == null || candle == null || !validSymbol(read.symbol()) || read.queryFrom() == null || read.queryTo() == null
                || read.observedAt() == null || candle.tradingDate() == null || candle.tradingDate().isBefore(read.queryFrom())
                || candle.tradingDate().isAfter(read.queryTo()) || !"OK".equals(read.status()) || read.reason() != null
                || !exchange(read.exchange()) || !positive(candle.open()) || !positive(candle.high()) || !positive(candle.low())
                || !positive(candle.close()) || candle.volume() < 0 || !nonnegative(candle.turnover())
                || read.sourceMarket() != null && !read.sourceMarket().matches("[\\x20-\\x7e]{1,20}")) return false;
        return candle.high().compareTo(candle.open()) >= 0 && candle.high().compareTo(candle.close()) >= 0
                && candle.open().compareTo(candle.low()) >= 0 && candle.close().compareTo(candle.low()) >= 0;
    }
    private static boolean exchange(String value) { return "TWSE".equals(value) || "TPEx".equals(value) || "ESB".equals(value); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0 && within(value); }
    private static boolean nonnegative(BigDecimal value) { return value != null && value.signum() >= 0 && within(value); }
    private static boolean within(BigDecimal value) { return value.precision() <= 20 && Math.max(value.scale(), 0) <= 10; }
}
