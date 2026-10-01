package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Explicit, serial, auditable Task466 invocation. It never participates in normal service startup. */
@Component
public class FubonHistoricalBackfillRunner implements ApplicationRunner, ExitCodeGenerator {
    public static final String COMMAND = "fubon-historical-backfill";
    private static final long HISTORY_REQUEST_PACING_MILLIS = 1_250;
    private final String enabled;
    private final String oneShotOnly;
    private final FubonMarketConfigState access;
    private final FubonRadarScope radar;
    private final MarketCalendar calendar;
    private final FubonMarketDataPort client;
    private final FubonHistoricalDailyCandleStore dailyFacts;
    private final FubonMarketDataHistoryStore minuteFacts;
    private final StockSourceQuery stockHistory;
    private final FubonHistoricalBackfillReceiptStore receipts;
    private final FubonHistoricalBackfillCampaignLock campaignLock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile int exitCode;

    public FubonHistoricalBackfillRunner(@Value("${fubon.historical-backfill-enabled:false}") String enabled,
            @Value("${fubon.historical-backfill-only:false}") String oneShotOnly,
            FubonMarketConfigState access, FubonRadarScope radar, MarketCalendar calendar,
            FubonMarketDataPort client, FubonHistoricalDailyCandleStore dailyFacts,
            FubonMarketDataHistoryStore minuteFacts, StockSourceQuery stockHistory,
            FubonHistoricalBackfillReceiptStore receipts, FubonHistoricalBackfillCampaignLock campaignLock,
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.enabled = enabled; this.oneShotOnly = oneShotOnly;
        this.access = access; this.radar = radar; this.calendar = calendar; this.client = client;
        this.dailyFacts = dailyFacts; this.minuteFacts = minuteFacts; this.stockHistory = stockHistory;
        this.receipts = receipts; this.campaignLock = campaignLock; this.jdbc = jdbc; this.mapper = mapper;
    }

    @Override public void run(ApplicationArguments args) {
        if (!args.containsOption(COMMAND)) {
            if (Boolean.parseBoolean(oneShotOnly)) {
                System.err.println("{\"campaignOutcome\":\"REJECTED\",\"errorCode\":\"HISTORICAL_BACKFILL_COMMAND_REQUIRED\"}");
                exitCode = 2;
            }
            return;
        }
        if (!List.of("run").equals(args.getOptionValues(COMMAND)) || !featureEnabled()) {
            throw new IllegalArgumentException("HISTORICAL_BACKFILL_COMMAND_OR_FEATURE_GATE_INVALID");
        }
        try { exitCode = execute(args); }
        catch (RuntimeException failure) {
            System.err.println("{\"campaignOutcome\":\"PARTIAL\",\"errorCode\":\"" + errorCode(failure) + "\"}");
            exitCode = 2;
        }
    }

    private int execute(ApplicationArguments args) {
        boolean create = args.containsOption("create");
        boolean resume = args.containsOption("resume");
        boolean retryFailed = args.containsOption("retry-failed");
        List<String> profiles = args.getOptionValues("spring.profiles.active");
        boolean validProfile = profiles == null || List.of("postgres").equals(profiles);
        if (create == resume || args.getNonOptionArgs().size() != 0 || !validProfile
                || args.getOptionNames().stream().anyMatch(name -> !Set.of(COMMAND, "create", "resume", "retry-failed", "spring.profiles.active").contains(name)))
            throw new Unavailable("INVALID_COMMAND");
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        if (!featureEnabled()) throw new Unavailable("HISTORICAL_BACKFILL_DISABLED", true);
        String accessReason = access.unavailableReason();
        if (accessReason != null) throw new Unavailable(accessReason, true);
        LocalDate latest = latestCompletedBefore(today);

        FubonHistoricalBackfillReceiptStore.Campaign campaign;
        FubonHistoricalBackfillCampaignLock.Lease lease = null;
        try {
            if (create) {
                List<String> symbols = validatedScope(radar.current(30));
                LocalDate from = latest.minusYears(10);
                campaign = new FubonHistoricalBackfillReceiptStore.Campaign(UUID.randomUUID(), from, latest, latest,
                        symbols, scopeHash(symbols), "RUNNING", Instant.now());
                receipts.create(campaign);
                lease = campaignLock.tryAcquire(campaign.id()).orElseThrow(() -> new Unavailable("CAMPAIGN_BUSY", true));
            } else {
                UUID id = parseId(args.getOptionValues("resume"));
                lease = campaignLock.tryAcquire(id).orElseThrow(() -> new Unavailable("CAMPAIGN_BUSY", true));
                campaign = receipts.find(id).orElseThrow(() -> new Unavailable("CAMPAIGN_NOT_FOUND", true));
                if (!scopeHash(campaign.symbols()).equals(campaign.scopeHash()) || campaign.symbols().isEmpty()
                        || campaign.symbols().size() > 30 || !campaign.symbols().equals(validatedScope(campaign.symbols())))
                    throw new Unavailable("CAMPAIGN_MANIFEST_INVALID", true);
                validatedScope(radar.current(30));
                if ("SUCCESS".equals(campaign.status())) throw new Unavailable("CAMPAIGN_ALREADY_COMPLETE", true);
                if ("PARTIAL".equals(campaign.status())) receipts.beginResume(id);
            }
        Map<String, Object> before = factCoverage(campaign.symbols());
        Map<FubonHistoricalBackfillReceiptStore.WindowKey, FubonHistoricalBackfillReceiptStore.Attempt> latestAttempts = receipts.latestAttempts(campaign.id());
        List<FubonHistoricalBackfillPlanner.Window> windows = FubonHistoricalBackfillPlanner.windows(campaign.symbols(), campaign.from(), campaign.to());
        String blocking = null;
        for (var window : windows) {
            var key = new FubonHistoricalBackfillReceiptStore.WindowKey(window.dataset(), window.symbol(), window.from(), window.to());
            var prior = latestAttempts.get(key);
            if (prior != null) {
                if (Set.of("COMPLETE", "NO_DATA").contains(prior.status())) continue;
                if ("STARTED".equals(prior.status())) {
                    receipts.finish(prior, new FubonHistoricalBackfillReceiptStore.AttemptResult("FAILED", null, 0, 0, 0, 0, "INTERRUPTED"));
                    blocking = "INTERRUPTED"; break;
                }
                if (Set.of("CONFLICT", "SCOPE_CHANGED").contains(prior.status())) { blocking = prior.status(); break; }
                if ("FAILED".equals(prior.status()) && !retryFailed) { blocking = "RETRY_FAILED_REQUIRED"; break; }
            }
            String gate = recheckAccessAndScope(window.symbol(), campaign.to());
            if (gate != null && !"SCOPE_CHANGED".equals(gate)) { blocking = gate; break; }
            var attempt = receipts.start(campaign.id(), key);
            if ("SCOPE_CHANGED".equals(gate)) {
                receipts.finish(attempt, new FubonHistoricalBackfillReceiptStore.AttemptResult("SCOPE_CHANGED", null, 0, 0, 0, 0, gate));
                blocking = gate; break;
            }
            try {
                var outcome = process(window);
                receipts.finish(attempt, outcome);
                latestAttempts.put(key, new FubonHistoricalBackfillReceiptStore.Attempt(attempt.id(), key, attempt.number(), outcome.status()));
                if (Set.of("FAILED", "CONFLICT", "SCOPE_CHANGED").contains(outcome.status())) { blocking = outcome.errorCode() == null ? outcome.status() : outcome.errorCode(); break; }
                if (!paceHistoryRequest()) { blocking = "INTERRUPTED"; break; }
            } catch (RuntimeException failure) {
                String code = errorCode(failure);
                receipts.finish(attempt, new FubonHistoricalBackfillReceiptStore.AttemptResult(
                        "FAILED", null, 0, 0, 0, 0, code));
                latestAttempts.put(key, new FubonHistoricalBackfillReceiptStore.Attempt(attempt.id(), key, attempt.number(), "FAILED"));
                blocking = code; break;
            }
        }
        Map<String, Object> after = factCoverage(campaign.symbols());
        Map<String, Object> report = report(campaign, before, after, latestAttempts, blocking);
        boolean allPlannedWindowsResolved = allPlannedWindowsResolved(windows, latestAttempts);
        boolean success = blocking == null && allPlannedWindowsResolved
                && latestAttempts.values().stream().noneMatch(a -> Set.of("FAILED", "CONFLICT", "SCOPE_CHANGED", "STARTED").contains(a.status()));
        String json;
        try { json = mapper.writeValueAsString(report); } catch (Exception e) { json = "{}"; success = false; }
        receipts.finishCampaign(campaign.id(), success ? "SUCCESS" : "PARTIAL", json);
        System.out.println(json);
        return success ? 0 : 2;
        } finally {
            if (lease != null) lease.close();
        }
    }

    FubonHistoricalBackfillReceiptStore.AttemptResult process(FubonHistoricalBackfillPlanner.Window w) {
        if ("DAILY_CANDLE".equals(w.dataset())) {
            HistoricalDailyCandlesRead read = client.historicalDailyCandles(w.symbol(), w.from(), w.to());
            if (!read.usableSnapshot()) throw new Unavailable("INVALID_DAILY_RESPONSE", true);
            int inserted = 0, unchanged = 0, conflicts = 0;
            for (HistoricalDailyCandle candle : read.candles()) {
                FubonHistoricalDailyCandleStore.Result result;
                try { result = dailyFacts.persist(read, candle); }
                catch (RuntimeException failure) {
                    return failedDailyAttempt(read, inserted, unchanged, conflicts, "DAILY_PERSISTENCE_FAILED");
                }
                if (result == null || result.status() == null) {
                    return failedDailyAttempt(read, inserted, unchanged, conflicts, "DAILY_PERSISTENCE_FAILED");
                }
                switch (result.status()) {
                    case WRITTEN -> inserted++;
                    case UNCHANGED -> unchanged++;
                    case CONFLICT_NO_SOURCE_REVISION -> {
                        conflicts++;
                        return new FubonHistoricalBackfillReceiptStore.AttemptResult("CONFLICT", read.observedAt(),
                                read.candles().size(), inserted, unchanged, conflicts, "CONFLICT_NO_SOURCE_REVISION");
                    }
                    case FAILED -> {
                        return failedDailyAttempt(read, inserted, unchanged, conflicts, "DAILY_PERSISTENCE_FAILED");
                    }
                }
                if (result.status() == FubonHistoricalDailyCandleStore.Status.WRITTEN
                        || result.status() == FubonHistoricalDailyCandleStore.Status.UNCHANGED) {
                    try {
                        stockHistory.upsertFubonHistoricalDailyCandle(read.symbol(), candle.tradingDate(), candle.open(),
                                candle.high(), candle.low(), candle.close(), candle.volume());
                    } catch (RuntimeException failure) {
                        return failedDailyAttempt(read, inserted, unchanged, conflicts, "DAILY_PROJECTION_FAILED");
                    }
                }
            }
            if (conflicts > 0) return new FubonHistoricalBackfillReceiptStore.AttemptResult("CONFLICT", read.observedAt(), read.candles().size(), inserted, unchanged, conflicts, "CONFLICT_NO_SOURCE_REVISION");
            return new FubonHistoricalBackfillReceiptStore.AttemptResult(read.candles().isEmpty() ? "NO_DATA" : "COMPLETE",
                    read.observedAt(), read.candles().size(), inserted, unchanged, 0, null);
        }
        HistoricalIntradayCandlesRead read = client.historicalIntradayCandles(w.symbol(), w.from(), w.to());
        if ("NO_DATA".equals(read.status())) return new FubonHistoricalBackfillReceiptStore.AttemptResult("NO_DATA", read.observedAt(), 0, 0, 0, 0, null);
        var persisted = minuteFacts.persistHistoricalCandles(read);
        if (persisted.status() == FubonMarketDataHistoryStore.Status.FAILED) throw new Unavailable("MINUTE_PERSISTENCE_FAILED", true);
        if (persisted.conflicts() > 0) return new FubonHistoricalBackfillReceiptStore.AttemptResult("CONFLICT", read.observedAt(), read.candles().size(), persisted.written(), persisted.unchanged(), persisted.conflicts(), "CONFLICT_NO_SOURCE_REVISION");
        return new FubonHistoricalBackfillReceiptStore.AttemptResult("COMPLETE", read.observedAt(), read.candles().size(), persisted.written(), persisted.unchanged(), 0, null);
    }

    private static FubonHistoricalBackfillReceiptStore.AttemptResult failedDailyAttempt(
            HistoricalDailyCandlesRead read, int inserted, int unchanged, int conflicts, String errorCode) {
        return new FubonHistoricalBackfillReceiptStore.AttemptResult("FAILED", read.observedAt(),
                read.candles().size(), inserted, unchanged, conflicts, errorCode);
    }

    private String recheckAccessAndScope(String symbol, LocalDate campaignTo) {
        String reason = access.unavailableReason();
        if (reason != null) return reason;
        try {
            LocalDate completed = latestCompletedBefore(LocalDate.now(MarketClock.TW_ZONE));
            if (completed.isBefore(campaignTo)) return "CALENDAR_CHANGED";
        } catch (RuntimeException failure) { return "CALENDAR_UNKNOWN"; }
        try { return validatedScope(radar.current(30)).contains(symbol) ? null : "SCOPE_CHANGED"; }
        catch (RuntimeException failure) { return "RADAR_UNAVAILABLE"; }
    }
    /** Keep the one-shot campaign below Fubon's shared 60 history requests/minute ceiling. */
    private static boolean paceHistoryRequest() {
        try {
            Thread.sleep(HISTORY_REQUEST_PACING_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    LocalDate latestCompletedBefore(LocalDate today) {
        // The 366-day bound only protects against a calendar authority that reports every date closed forever.
        // Unknown calendar state is still an immediate fail-closed result.
        for (int days = 1; days <= 366; days++) {
            LocalDate candidate = today.minusDays(days);
            Optional<Boolean> known;
            try { known = calendar.isTwTradingDayKnown(candidate); }
            catch (RuntimeException failure) { throw new Unavailable("CALENDAR_UNKNOWN", true); }
            if (known.isEmpty()) throw new Unavailable("CALENDAR_UNKNOWN", true);
            if (known.get()) return candidate;
        }
        throw new Unavailable("CALENDAR_UNKNOWN", true);
    }
    static boolean allPlannedWindowsResolved(List<FubonHistoricalBackfillPlanner.Window> windows,
            Map<FubonHistoricalBackfillReceiptStore.WindowKey, FubonHistoricalBackfillReceiptStore.Attempt> attempts) {
        return windows.stream().allMatch(window -> {
            var key = new FubonHistoricalBackfillReceiptStore.WindowKey(window.dataset(), window.symbol(), window.from(), window.to());
            var attempt = attempts.get(key);
            return attempt != null && Set.of("COMPLETE", "NO_DATA").contains(attempt.status());
        });
    }
    private static List<String> validatedScope(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > 30 || !codes.equals(codes.stream().distinct().sorted().toList())
                || codes.stream().anyMatch(code -> !StockSourceQuery.isTaiwanRadarCode(code) || !validSymbol(code)))
            throw new Unavailable("RADAR_INVALID", true);
        return List.copyOf(codes);
    }
    private static String scopeHash(List<String> symbols) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", symbols).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static UUID parseId(List<String> values) {
        try { if (values == null || values.size() != 1) throw new IllegalArgumentException(); UUID id = UUID.fromString(values.getFirst()); if (!id.toString().equals(values.getFirst())) throw new IllegalArgumentException(); return id; }
        catch (RuntimeException invalid) { throw new Unavailable("INVALID_CAMPAIGN_ID", true); }
    }
    Map<String, Object> factCoverage(List<String> symbols) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object[] codeArray = {symbols.toArray(String[]::new)};
        result.put("daily", coverageFor("DAILY_CANDLE", "fubon_historical_daily_candle", "trading_date", codeArray));
        result.put("minute", coverageFor("INTRADAY_CANDLE_1M", "fubon_intraday_candle", "source_date", codeArray));
        return result;
    }
    List<Map<String, Object>> coverageFor(String dataset, String table, String dateColumn, Object[] codeArray) {
        // Identifiers are selected only from these fixed internal call sites; request data stays a bound array parameter.
        String sql = "SELECT requested.symbol, count(f.stock_code) AS row_count, min(f." + dateColumn + ") AS earliest, "
                + "max(f." + dateColumn + ") AS latest FROM unnest(?::varchar[]) AS requested(symbol) "
                + "LEFT JOIN " + table + " f ON f.stock_code=requested.symbol AND f.market='台股' "
                + "GROUP BY requested.symbol ORDER BY requested.symbol";
        return jdbc.query(sql, (rs, row) -> {
            Map<String, Object> coverage = new LinkedHashMap<>();
            coverage.put("dataset", dataset);
            coverage.put("symbol", rs.getString("symbol"));
            coverage.put("rows", rs.getLong("row_count"));
            coverage.put("earliest", Objects.toString(rs.getObject("earliest"), null));
            coverage.put("latest", Objects.toString(rs.getObject("latest"), null));
            return coverage;
        }, codeArray);
    }
    private Map<String, Object> report(FubonHistoricalBackfillReceiptStore.Campaign campaign, Map<String, Object> before,
            Map<String, Object> after, Map<FubonHistoricalBackfillReceiptStore.WindowKey, FubonHistoricalBackfillReceiptStore.Attempt> attempts,
            String blocking) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("campaignId", campaign.id()); result.put("fromDate", campaign.from()); result.put("toDate", campaign.to());
        result.put("latestCompletedDate", campaign.latestCompleted()); result.put("symbols", campaign.symbols().size());
        result.put("before", before); result.put("after", after);
        result.put("unsupported", Map.of(
                "CUMULATIVE_INTRADAY_QUOTE_VOLUME", Map.of("status", "UNSUPPORTED", "reason", "No historical vendor endpoint or persistent source"),
                "INTRADAY_PRICE_VOLUME_DISTRIBUTION", Map.of("status", "UNSUPPORTED", "reason", "Current-day Redis snapshot only; no historical source")));
        Map<String, Long> statusCounts = new TreeMap<>();
        jdbc.query("SELECT status, count(*) AS n FROM fubon_historical_backfill_window_attempt WHERE campaign_id=? GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> { statusCounts.put(rs.getString(1), rs.getLong(2)); }, campaign.id());
        result.put("attemptCounts", statusCounts); result.put("logicalWindows", attempts.size()); result.put("blockingError", blocking);
        Map<String, Integer> totals = jdbc.queryForObject("SELECT COALESCE(sum(provider_row_count),0)::integer, COALESCE(sum(inserted_count),0)::integer, COALESCE(sum(unchanged_count),0)::integer, COALESCE(sum(conflict_count),0)::integer FROM fubon_historical_backfill_window_attempt WHERE campaign_id=?",
                (rs, row) -> Map.of("providerRows", rs.getInt(1), "inserted", rs.getInt(2), "unchanged", rs.getInt(3), "conflicts", rs.getInt(4)), campaign.id());
        result.put("attemptTotals", totals == null ? Map.of() : totals);
        result.put("outcome", blocking == null ? "SUCCESS" : "PARTIAL");
        return result;
    }
    private static String errorCode(Throwable error) {
        if (error instanceof Unavailable unavailable) return sanitize(unavailable.reason());
        return "BACKFILL_FAILURE";
    }
    private static String sanitize(String code) { return code != null && code.matches("[A-Z0-9_]{1,64}") ? code : "BACKFILL_FAILURE"; }
    @Override public int getExitCode() { return exitCode; }
    boolean featureEnabled() { return "true".equalsIgnoreCase(enabled); }
}
