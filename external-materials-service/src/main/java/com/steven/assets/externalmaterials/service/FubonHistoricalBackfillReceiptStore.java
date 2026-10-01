package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Writes the immutable Task466 campaign manifest and append-only per-window attempts. */
@Repository
public class FubonHistoricalBackfillReceiptStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FubonHistoricalBackfillReceiptStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Campaign(UUID id, LocalDate from, LocalDate to, LocalDate latestCompleted,
                           List<String> symbols, String scopeHash, String status, Instant createdAt) {
        public Campaign { symbols = List.copyOf(symbols); }
    }
    public record WindowKey(String dataset, String symbol, LocalDate from, LocalDate to) {}
    public record Attempt(long id, WindowKey window, int number, String status) {}
    public record AttemptResult(String status, Instant observedAt, int providerRows, int inserted,
                               int unchanged, int conflicts, String errorCode) {}

    public void create(Campaign campaign) {
        String symbols = FubonMarketJson.MAPPER.valueToTree(campaign.symbols()).toString();
        String unsupported = "{\"CUMULATIVE_INTRADAY_QUOTE_VOLUME\":{\"status\":\"UNSUPPORTED\",\"reason\":\"No historical vendor endpoint or persistent source\"},\"INTRADAY_PRICE_VOLUME_DISTRIBUTION\":{\"status\":\"UNSUPPORTED\",\"reason\":\"Current-day Redis snapshot only; no historical source\"}}";
        jdbc.update("""
                INSERT INTO fubon_historical_backfill_campaign
                  (campaign_id, from_date, to_date, latest_completed_date, symbols, scope_sha256,
                   unsupported_coverage, created_at, status)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, 'RUNNING')
                """, campaign.id(), campaign.from(), campaign.to(), campaign.latestCompleted(), symbols,
                campaign.scopeHash(), unsupported, Timestamp.from(campaign.createdAt()));
    }

    public Optional<Campaign> find(UUID id) {
        List<Campaign> values = jdbc.query("""
                SELECT campaign_id, from_date, to_date, latest_completed_date, symbols::text, scope_sha256, status, created_at
                FROM fubon_historical_backfill_campaign WHERE campaign_id=?
                """, (rs, row) -> {
            List<String> symbols = parseSymbols(rs.getString("symbols"));
            return new Campaign(rs.getObject("campaign_id", UUID.class), rs.getObject("from_date", LocalDate.class),
                    rs.getObject("to_date", LocalDate.class), rs.getObject("latest_completed_date", LocalDate.class),
                    symbols, rs.getString("scope_sha256"), rs.getString("status"), rs.getTimestamp("created_at").toInstant());
        }, id);
        return values.stream().findFirst();
    }

    public Map<WindowKey, Attempt> latestAttempts(UUID campaignId) {
        Map<WindowKey, Attempt> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (dataset, symbol, window_from) attempt_id, dataset, symbol, window_from, window_to, attempt_number, status
                FROM fubon_historical_backfill_window_attempt WHERE campaign_id=?
                ORDER BY dataset, symbol, window_from, attempt_number DESC
                """, rs -> {
            WindowKey key = new WindowKey(rs.getString("dataset"), rs.getString("symbol"),
                    rs.getObject("window_from", LocalDate.class), rs.getObject("window_to", LocalDate.class));
            result.put(key, new Attempt(rs.getLong("attempt_id"), key, rs.getInt("attempt_number"), rs.getString("status")));
        }, campaignId);
        return result;
    }

    public Attempt start(UUID campaignId, WindowKey key) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM fubon_historical_backfill_window_attempt
                WHERE campaign_id=? AND dataset=? AND symbol=? AND window_from=?
                """, Integer.class, campaignId, key.dataset(), key.symbol(), key.from());
        int number = next == null ? 1 : next;
        Long id = tx.execute(status -> {
            jdbc.update("""
                    INSERT INTO fubon_historical_backfill_window_attempt
                      (campaign_id, dataset, symbol, window_from, window_to, attempt_number, status, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'STARTED', ?)
                    """, campaignId, key.dataset(), key.symbol(), key.from(), key.to(), number, Timestamp.from(Instant.now()));
            return jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('fubon_historical_backfill_window_attempt','attempt_id'))", Long.class);
        });
        return new Attempt(Objects.requireNonNull(id), key, number, "STARTED");
    }

    public void finish(Attempt attempt, AttemptResult result) {
        if (!Set.of("COMPLETE", "NO_DATA", "FAILED", "CONFLICT", "SCOPE_CHANGED").contains(result.status()))
            throw new IllegalArgumentException("INVALID_ATTEMPT_STATUS");
        int changed = jdbc.update("""
                UPDATE fubon_historical_backfill_window_attempt
                SET status=?, completed_at=?, observed_at=?, provider_row_count=?, inserted_count=?, unchanged_count=?, conflict_count=?, error_code=?
                WHERE attempt_id=? AND status='STARTED'
                """, result.status(), Timestamp.from(Instant.now()), result.observedAt() == null ? null : Timestamp.from(result.observedAt()),
                result.providerRows(), result.inserted(), result.unchanged(), result.conflicts(), sanitize(result.errorCode()), attempt.id());
        if (changed != 1) throw new IllegalStateException("ATTEMPT_TRANSITION_REJECTED");
    }

    public void finishCampaign(UUID id, String status, String summary) {
        if (!Set.of("SUCCESS", "PARTIAL").contains(status)) throw new IllegalArgumentException("INVALID_CAMPAIGN_STATUS");
        jdbc.update("UPDATE fubon_historical_backfill_campaign SET status=?, finished_at=?, summary=?::jsonb WHERE campaign_id=? AND status='RUNNING'",
                status, Timestamp.from(Instant.now()), summary, id);
    }

    public void beginResume(UUID id) {
        int changed = jdbc.update("UPDATE fubon_historical_backfill_campaign SET status='RUNNING', finished_at=NULL, summary=NULL WHERE campaign_id=? AND status='PARTIAL'", id);
        if (changed != 1) throw new IllegalStateException("CAMPAIGN_RESUME_REJECTED");
    }

    private static String sanitize(String code) {
        if (code == null) return null;
        return code.matches("[A-Z0-9_]{1,64}") ? code : "SANITIZED_FAILURE";
    }
    private static List<String> parseSymbols(String json) {
        try { return FubonMarketJson.MAPPER.readValue(json, new TypeReference<>() {}); }
        catch (Exception invalid) { throw new IllegalStateException("CAMPAIGN_MANIFEST_INVALID"); }
    }
}
