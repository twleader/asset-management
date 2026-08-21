package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 盤中股價輪詢：
 * - 台股：週一～五 09:00–13:30 Asia/Taipei，每 2 分鐘
 * - 美股：週一～五 09:30–16:00 America/New_York，每 2 分鐘
 *
 * 啟動時補抓主檔中尚無 Redis cache 的股票（盤外則僅抓一次當作 cold start）。
 * 盤後 close cron 由 ClosePersister 處理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricePoller {

    private final PriceFetchClient client;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final MarketClock clock;
    /** Row-retrieval timestamp source; package-visible for deterministic unit tests. */
    Clock timeSource = Clock.systemUTC();

    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        Set<String> ukCodes = new LinkedHashSet<>();
        source.collectAllStockCodes(twCodes, usCodes, ukCodes);
        if (twCodes.isEmpty() && usCodes.isEmpty() && ukCodes.isEmpty()) return;
        log.info("price-service 啟動，背景補抓 cold cache：台股 {} 檔，美股 {} 檔，英股 {} 檔",
                twCodes.size(), usCodes.size(), ukCodes.size());
        new Thread(() -> {
            // 開盤中：外部抓即時價。休市：改以 DB 收盤同步 Redis（不重抓 last-tick 覆寫，
            // 否則會蓋掉 FinMind 權威收盤 → Redis↔DB 不一致，spec Task 111）。
            if (clock.isTwMarketOpen()) updatePrices(twCodes, "台股", false); else syncClosedFromDb(twCodes, "台股");
            if (clock.isUsMarketOpen()) updatePrices(usCodes, "美股", false); else syncClosedFromDb(usCodes, "美股");
            if (clock.isUkMarketOpen()) updatePrices(ukCodes, "英股", false); else syncClosedFromDb(ukCodes, "英股");
        }, "price-cache-warmup").start();
    }

    @Scheduled(cron = "0 0/2 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTwIntradayUpdate() {
        if (!clock.isTwMarketOpen()) return;
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        // collectHeldStockCodes 的 alert SQL 已排除 0000，但 snapshot 持股側未排除；
        // 台股大盤不是個股，不能交給個股 MIS/Redis writer。
        tw.remove("0000");
        if (tw.isEmpty()) return;
        log.info("更新台股即時價格 ({} 檔)", tw.size());
        updatePrices(tw, "台股", false);
    }

    @Scheduled(cron = "0 0/2 9-16 * * MON-FRI", zone = "America/New_York")
    public void scheduledUsIntradayUpdate() {
        if (!clock.isUsMarketOpen()) return;
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (us.isEmpty()) return;
        log.info("更新美股即時價格 ({} 檔)", us.size());
        updatePrices(us, "美股", false);
    }

    @Scheduled(cron = "0 0/2 8-16 * * MON-FRI", zone = "Europe/London")
    public void scheduledUkIntradayUpdate() {
        if (!clock.isUkMarketOpen()) return;
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (uk.isEmpty()) return;
        log.info("更新英股即時價格 ({} 檔)", uk.size());
        updatePrices(uk, "英股", false);
    }

    /**
     * 同步抓所有持股價格寫 Redis（不限交易時段）。供 /internal/refresh 端點使用。
     */
    public RefreshSummary refreshAll() {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        boolean twOpen = clock.isTwMarketOpen();
        boolean usOpen = clock.isUsMarketOpen();
        boolean ukOpen = clock.isUkMarketOpen();
        // 開盤中：外部抓即時價。休市 / 國定假日：改以 DB 收盤同步 Redis，不重抓 last-tick 覆寫
        //（否則會把 FinMind 權威收盤蓋成盤前 last-tick → Dashboard/歷年 與 SnapshotForm 不一致，spec Task 111）。
        if (twOpen) updatePrices(tw, "台股", false); else syncClosedFromDb(tw, "台股");
        if (usOpen) updatePrices(us, "美股", false); else syncClosedFromDb(us, "美股");
        if (ukOpen) updatePrices(uk, "英股", false); else syncClosedFromDb(uk, "英股");
        return new RefreshSummary(tw.size(), us.size(), uk.size(), twOpen, usOpen, ukOpen);
    }

    public record RefreshSummary(int twUpdated, int usUpdated, int ukUpdated,
                                 boolean twMarketOpen, boolean usMarketOpen, boolean ukMarketOpen) {}

    /**
     * 並行抓價：每檔一個 virtual thread。
     * 對 23 檔股票（每檔 HTTP RTT 約 300ms~10s 含 fallback）總時間從 sequential 數十秒降到 ≈ 最慢一檔的耗時。
     * 用 try-with-resources 的 ExecutorService 在區塊結束時等待所有任務完成。
     */
    /**
     * 休市時以 DB（stock_price_history）最近一筆收盤同步 Redis，取代外部重抓。
     * 確保 Redis 收盤 == DB 收盤（FinMind 權威），避免盤外刷新 / 假日抓到的 last-tick
     * 蓋掉已校正的官方收盤（spec Task 111）。DB 無收盤者略過（getLive 自然 fallback）。
     */
    void syncClosedFromDb(Set<String> codes, String market) {
        if (codes.isEmpty()) return;
        for (String code : codes) {
            try {
                source.findLatestDatedClose(code, market)
                        .ifPresent(close -> writer.syncClosedFromDb(
                                code, market, close, timeSource.instant()));
            } catch (Exception e) {
                log.warn("休市 DB→Redis 同步 {} {} 失敗: {}", market, code, e.getMessage());
            }
        }
    }

    void updatePrices(Set<String> codes, String market, boolean markClosed) {
        if (codes.isEmpty()) return;
        if ("台股".equals(market)) {
            updateTwPrices(codes, markClosed);
            return;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String code : codes) {
                pool.submit(() -> {
                    try {
                        // empty 代表「本輪不更新」：台股 z='-'（兩 tick 之間無成交）或外部 API 查無資料；
                        // 略過寫入 Redis 以保留上一輪成功 poll 的當日 intraday 成交價（spec Task 79）。
                        Optional<PriceResult> opt = client.getStockPrice(code, market);
                        if (opt.isEmpty()) return;
                        PriceResult r = opt.get();
                        if (r.price() == null) return;
                        PriceCacheWriter.CacheWriteOutcome outcome = writer.write(r, markClosed);
                        if (outcome == PriceCacheWriter.CacheWriteOutcome.WRITTEN
                                && r.stockName() != null && !r.stockName().isBlank()) {
                            source.upsertStockName(code, market, r.stockName());
                        }
                    } catch (Exception e) {
                        log.warn("更新 {} {} 股價失敗: {}", market, code, e.getMessage());
                    }
                });
            }
        } // executor.close() 等所有 task 完成
    }

    private void updateTwPrices(Set<String> codes, boolean markClosed) {
        PriceFetchClient.TwQuoteBatchSummary summary = client.fetchTwBatch(codes);
        int written = 0;
        int staleRejected = 0;
        int writeFailures = 0;
        for (var entry : summary.resolved().entrySet()) {
            PriceResult result = entry.getValue();
            PriceCacheWriter.CacheWriteOutcome outcome = writer.write(result, markClosed);
            switch (outcome) {
                case WRITTEN -> {
                    written++;
                    if (result.stockName() != null && !result.stockName().isBlank()) {
                        source.upsertStockName(entry.getKey(), "台股", result.stockName());
                    }
                }
                case REJECTED_STALE -> staleRejected++;
                case FAILED, SKIPPED_INVALID_PRICE -> writeFailures++;
            }
        }
        log.info("台股MIS batch requested={} resolved={} noTrade={} missing={} invalid={} "
                        + "httpRequests={} requestFailures={} written={} staleRejected={} writeFailures={} "
                        + "foreignCodes={} schemaAnomalies={} sourceTimeAnomalyCodes={} capacityRejectedCodes={}",
                summary.requestedCount(), summary.resolved().size(), summary.noTradeCodes().size(),
                summary.missingCodes().size(), summary.invalidCodes().size(),
                summary.httpRequests(), summary.requestFailures(), written, staleRejected, writeFailures,
                summary.foreignCodes(), summary.schemaAnomalies(), summary.sourceTimeAnomalyCodes(),
                summary.capacityRejectedCodes());
    }
}
