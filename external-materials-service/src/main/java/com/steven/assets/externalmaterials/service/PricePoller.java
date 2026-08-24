package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 盤中股價輪詢：
 * - 台股：週一～五 09:00–13:30 Asia/Taipei，每 10 秒
 * - 美股：週一～五 09:30–16:00 America/New_York，每 2 分鐘
 *
 * <p>台股四個入口都經同一 known-open dispatcher。Disabled mode 使用 Task 350 的 MIS
 * 批次 provider；enabled mode 使用 Fubon provider。其他市場維持逐檔 virtual thread。</p>
 */
@Slf4j
@Service
public class PricePoller {

    private final PriceFetchClient client;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final MarketClock clock;
    private final TwLiveQuoteDispatcher twLiveDispatcher;
    private final Consumer<Runnable> warmupLauncher;

    /** Row-retrieval timestamp source; package-visible for deterministic unit tests. */
    Clock timeSource = Clock.systemUTC();

    @Autowired
    public PricePoller(
            PriceFetchClient client,
            PriceCacheWriter writer,
            StockSourceQuery source,
            MarketClock clock,
            TwLiveQuoteDispatcher twLiveDispatcher) {
        this(client, writer, source, clock, twLiveDispatcher, task -> {
            Thread thread = new Thread(task, "price-cache-warmup");
            thread.start();
        });
    }

    PricePoller(
            PriceFetchClient client,
            PriceCacheWriter writer,
            StockSourceQuery source,
            MarketClock clock,
            TwLiveQuoteDispatcher twLiveDispatcher,
            Consumer<Runnable> warmupLauncher) {
        this.client = client;
        this.writer = writer;
        this.source = source;
        this.clock = clock;
        this.twLiveDispatcher = twLiveDispatcher;
        this.warmupLauncher = warmupLauncher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        Set<String> ukCodes = new LinkedHashSet<>();
        source.collectAllStockCodes(twCodes, usCodes, ukCodes);
        if (twCodes.isEmpty() && usCodes.isEmpty() && ukCodes.isEmpty()) return;
        log.info("price-service 啟動，背景補抓 cold cache：台股 {} 檔，美股 {} 檔，英股 {} 檔",
                twCodes.size(), usCodes.size(), ukCodes.size());
        warmupLauncher.accept(() -> {
            TwLiveQuoteBatchResult twResult = updatePrices(twCodes, "台股", false);
            if (twResult.marketState() == TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED) {
                syncClosedFromDb(twCodes, "台股");
            }
            if (clock.isUsMarketOpen()) updatePrices(usCodes, "美股", false);
            else syncClosedFromDb(usCodes, "美股");
            if (clock.isUkMarketOpen()) updatePrices(ukCodes, "英股", false);
            else syncClosedFromDb(ukCodes, "英股");
        });
    }

    @Scheduled(cron = "*/10 * 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTwIntradayUpdate() {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        tw.remove("0000");
        if (!tw.isEmpty()) log.info("更新台股即時價格 ({} 檔)", tw.size());
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

    /** 同步抓所有持股價格寫 Redis。供 /internal/refresh 端點使用。 */
    public RefreshSummary refreshAll() {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        boolean usOpen = clock.isUsMarketOpen();
        boolean ukOpen = clock.isUkMarketOpen();
        TwLiveQuoteBatchResult twResult = updatePrices(tw, "台股", false);
        boolean twOpen = twResult.marketOpen();
        if (twResult.marketState() == TwLiveQuoteBatchResult.MarketState.MARKET_CLOSED) {
            syncClosedFromDb(tw, "台股");
        }
        if (usOpen) updatePrices(us, "美股", false); else syncClosedFromDb(us, "美股");
        if (ukOpen) updatePrices(uk, "英股", false); else syncClosedFromDb(uk, "英股");
        return new RefreshSummary(tw.size(), us.size(), uk.size(), twOpen, usOpen, ukOpen);
    }

    public record RefreshSummary(int twUpdated, int usUpdated, int ukUpdated,
                                 boolean twMarketOpen, boolean usMarketOpen, boolean ukMarketOpen) {}

    /** DB row price/date are fetched together; one Instant is captured only after that row returns. */
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

    TwLiveQuoteBatchResult updatePrices(Set<String> codes, String market, boolean markClosed) {
        if ("台股".equals(market) && !markClosed) {
            return twLiveDispatcher.refresh(codes);
        }
        if (codes.isEmpty()) return TwLiveQuoteBatchResult.open(0, 0, 0, 0);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String code : codes) {
                pool.submit(() -> {
                    try {
                        Optional<PriceResult> optional = client.getStockPrice(code, market);
                        if (optional.isEmpty() || optional.get().price() == null) return;
                        PriceResult result = optional.get();
                        succeeded.incrementAndGet();
                        PriceCacheWriter.CacheWriteOutcome outcome = writer.write(result, markClosed);
                        if (outcome == PriceCacheWriter.CacheWriteOutcome.WRITTEN) {
                            written.incrementAndGet();
                            if (result.stockName() != null && !result.stockName().isBlank()) {
                                source.upsertStockName(code, market, result.stockName());
                            }
                        } else if (outcome == PriceCacheWriter.CacheWriteOutcome.FAILED
                                || outcome == PriceCacheWriter.CacheWriteOutcome.SKIPPED_INVALID_PRICE) {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log.warn("更新 {} {} 股價失敗: {}", market, code, e.getMessage());
                    }
                });
            }
        }
        return TwLiveQuoteBatchResult.open(
                codes.size(), succeeded.get(), written.get(), failed.get());
    }

    TwLiveQuoteDispatcher.Authorization authorizeTwLive() {
        return twLiveDispatcher.authorize();
    }

    TwLiveQuoteBatchResult updateTwPrices(
            Set<String> codes,
            TwLiveQuoteDispatcher.Authorization authorization) {
        return twLiveDispatcher.refresh(codes, authorization);
    }
}
