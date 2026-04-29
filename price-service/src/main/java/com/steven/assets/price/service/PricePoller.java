package com.steven.assets.price.service;

import com.steven.assets.price.client.PriceFetchClient;
import com.steven.assets.price.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

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

    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        source.collectAllStockCodes(twCodes, usCodes);
        if (twCodes.isEmpty() && usCodes.isEmpty()) return;
        log.info("price-service 啟動，背景補抓 cold cache：台股 {} 檔，美股 {} 檔", twCodes.size(), usCodes.size());
        new Thread(() -> {
            updatePrices(twCodes, "台股", !clock.isTwMarketOpen());
            updatePrices(usCodes, "美股", !clock.isUsMarketOpen());
        }, "price-cache-warmup").start();
    }

    @Scheduled(cron = "0 0/2 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTwIntradayUpdate() {
        if (!clock.isTwMarketOpen()) return;
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us);
        if (tw.isEmpty()) return;
        log.info("更新台股即時價格 ({} 檔)", tw.size());
        updatePrices(tw, "台股", false);
    }

    @Scheduled(cron = "0 0/2 9-16 * * MON-FRI", zone = "America/New_York")
    public void scheduledUsIntradayUpdate() {
        if (!clock.isUsMarketOpen()) return;
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us);
        if (us.isEmpty()) return;
        log.info("更新美股即時價格 ({} 檔)", us.size());
        updatePrices(us, "美股", false);
    }

    /**
     * 同步抓所有持股價格寫 Redis（不限交易時段）。供 /internal/refresh 端點使用。
     */
    public RefreshSummary refreshAll() {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us);
        updatePrices(tw, "台股", false);
        updatePrices(us, "美股", false);
        return new RefreshSummary(tw.size(), us.size(), clock.isTwMarketOpen(), clock.isUsMarketOpen());
    }

    public record RefreshSummary(int twUpdated, int usUpdated, boolean twMarketOpen, boolean usMarketOpen) {}

    void updatePrices(Set<String> codes, String market, boolean markClosed) {
        for (String code : codes) {
            try {
                PriceResult r = client.getStockPrice(code, market);
                if (r.price() == null) continue;
                writer.write(r, markClosed);
                if (r.stockName() != null && !r.stockName().isBlank()) {
                    source.upsertStockName(code, market, r.stockName());
                }
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("更新 {} {} 股價失敗: {}", market, code, e.getMessage());
            }
        }
    }

    // 不再使用，僅為 compatibility 預留（避免 IDE 警告）
    @SuppressWarnings("unused")
    private LocalDate today() { return LocalDate.now(); }
}
