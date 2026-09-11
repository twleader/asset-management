package com.steven.assets.externalmaterials.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Task425 post-close completed-daily-candle producer.  Facts commit before guarded local projection. */
@Service
public class FubonHistoricalDailyCandleSyncService {
    private final String enabled;
    private final FubonMarketRunGate gate;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final FubonHistoricalDailyCandleStore facts;
    private final StockSourceQuery stockHistory;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Result lastResult = new Result("DISABLED", "NOT_RUN", 0, 0, 0, 0);

    @Autowired
    public FubonHistoricalDailyCandleSyncService(@Value("${fubon.historical-daily-candle-sync-enabled:false}") String enabled,
                                                 FubonMarketRunGate gate, FubonRadarScope radar, FubonMarketDataPort client,
                                                 FubonHistoricalDailyCandleStore facts, StockSourceQuery stockHistory) {
        this.enabled = enabled; this.gate = gate; this.radar = radar; this.client = client; this.facts = facts; this.stockHistory = stockHistory;
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduled() { lastResult = sync(); }
    public Result lastResult() { return lastResult; }

    public Result sync() {
        String reason = gate.reason(enabled, "HISTORICAL_DAILY_CANDLE_SYNC_DISABLED", true);
        if (reason != null) return finish(reason, 0, 0, 0, 0);
        if (!inFlight.compareAndSet(false, true)) return finish("IN_FLIGHT", 0, 0, 0, 0);
        try {
            List<String> codes;
            try { codes = radar.current(30); }
            catch (Unavailable unavailable) { return finish(unavailable.reason(), 0, 0, 0, 0); }
            if (codes.isEmpty()) return finish("NO_SYMBOLS", 0, 0, 0, 0);
            LocalDate to = gate.today(), from = to.minusDays(365);
            int requested = 0, factCount = 0, projected = 0, failed = 0;
            for (String code : codes) {
                requested++;
                try {
                    HistoricalDailyCandlesRead response = client.historicalDailyCandles(code, from, to);
                    if (!response.usableSnapshot()) { failed++; continue; }
                    for (HistoricalDailyCandle candle : response.candles()) {
                        FubonHistoricalDailyCandleStore.Result fact = facts.persist(response, candle);
                        if (fact.status() == FubonHistoricalDailyCandleStore.Status.FAILED
                                || fact.status() == FubonHistoricalDailyCandleStore.Status.CONFLICT_NO_SOURCE_REVISION) { failed++; continue; }
                        factCount++;
                        // This happens only after the REQUIRES_NEW immutable fact transaction has returned.
                        if (stockHistory.upsertFubonHistoricalDailyCandle(response.symbol(), candle.tradingDate(), candle.open(),
                                candle.high(), candle.low(), candle.close(), candle.volume())) projected++;
                    }
                } catch (Unavailable unavailable) {
                    failed++;
                    if (unavailable.stopRun()) return finish(unavailable.reason(), requested, factCount, projected, failed);
                }
            }
            return finish(failed == 0 ? "SUCCESS" : factCount > 0 ? "PARTIAL" : "FAILED", requested, factCount, projected, failed);
        } finally { inFlight.set(false); }
    }
    private Result finish(String reason, int requested, int facts, int projected, int failed) {
        Result result = new Result("SUCCESS".equals(reason) ? "SUCCESS" : "PARTIAL".equals(reason) ? "PARTIAL" :
                "FAILED".equals(reason) ? "FAILED" : reason, reason, requested, facts, projected, failed);
        lastResult = result; return result;
    }
    public record Result(String outcome, String reason, int requested, int facts, int projected, int failed) {}
}
