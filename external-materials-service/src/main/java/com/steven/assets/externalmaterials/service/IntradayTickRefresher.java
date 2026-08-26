package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.IntradayBar;
import com.steven.assets.externalmaterials.client.PriceFetchClient.TickBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 盤後外部資料源覆寫 Redis tick LIST。
 *
 * 動機：盤中交易雷達台股由 {@link TwLiveQuoteDispatcher} 每 10 秒、美／英股由 {@link PricePoller}
 * 每 2 分鐘輪詢，且只在「真實成交」時寫 tick；遇 z='-'（兩 tick 之間
 * 無新成交的 5 秒視窗）會 skip，原始累積 LIST 可能跳號。盤後抓 FinMind / Yahoo 全天完整 K 線
 * 覆寫，補回所有時間點，前端「當日」走勢圖即顯示權威來源的完整曲線。
 *
 * Cron 時點安排：
 *  - 台股 14:00 TW → FinMind TaiwanStockKBar 5m（FinMind 無 token 時 fallback Yahoo）
 *  - 美股 16:05 ET（dump 16:02 之後）→ Yahoo chart interval=5m
 *  - 英股 17:00 LON → Yahoo chart interval=5m
 *
 * 台股 / 英股排在收盤後 ~30 分（非收盤後 5 分）：Yahoo 對 TWSE(~25min) / LSE(~15min) 的 5m feed
 * 有延遲，收盤後 5 分抓會截斷（缺收盤前數根，如台股停在 13:10 而非 13:30、英股停在 ~16:20）。
 * 後延讓 feed 追上收盤取得完整全日；另搭配 {@link IntradayTickStore#replaceTicks} 的 never-shrink
 * 防截斷 guard（新資料末刻早於既有就不覆寫）雙保險。美股 Yahoo 5m 無延遲，16:05 即抓到完整 09:30-16:00。
 */
@Slf4j
@Service
public class IntradayTickRefresher {

    private final PriceFetchClient client;
    private final IntradayTickStore tickStore;
    private final StockSourceQuery source;
    private final MarketClock clock;

    private final Duration selfHealCooldown;
    private final Duration selfHealWait;
    private final ConcurrentHashMap<String, HealAttempt> healAttempts = new ConcurrentHashMap<>();
    private final ExecutorService healExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public IntradayTickRefresher(
            PriceFetchClient client, IntradayTickStore tickStore,
            StockSourceQuery source, MarketClock clock) {
        this(client, tickStore, source, clock, Duration.ofSeconds(60), Duration.ofSeconds(8));
    }

    IntradayTickRefresher(
            PriceFetchClient client, IntradayTickStore tickStore,
            StockSourceQuery source, MarketClock clock,
            Duration selfHealCooldown, Duration selfHealWait) {
        this.client = client;
        this.tickStore = tickStore;
        this.source = source;
        this.clock = clock;
        this.selfHealCooldown = selfHealCooldown;
        this.selfHealWait = selfHealWait;
    }

    private static final class HealAttempt {
        private final CompletableFuture<Void> future;
        private volatile long cooldownUntilNanos;

        private HealAttempt(CompletableFuture<Void> future) {
            this.future = future;
            this.cooldownUntilNanos = Long.MAX_VALUE;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void healOpenMarketsOnStartup() {
        new Thread(() -> {
            Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
            source.collectHeldStockCodes(tw, us, uk);
            if (clock.isTwMarketOpen()) healOnStartup(tw, "台股", LocalDate.now(MarketClock.TW_ZONE));
            if (clock.isUsMarketOpen()) healOnStartup(us, "美股", LocalDate.now(MarketClock.US_ZONE));
            if (clock.isUkMarketOpen()) healOnStartup(uk, "英股", LocalDate.now(MarketClock.LON_ZONE));
        }, "intraday-tick-self-heal").start();
    }

    private void healOnStartup(Set<String> codes, String market, LocalDate date) {
        if (codes.isEmpty()) return;
        log.info("啟動自癒 {} {} 檔當日分時 tick", market, codes.size());
        for (String code : codes) refreshOneGuarded(code, market, date, false);
    }

    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Taipei")
    public void refreshTwTicks() {
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (tw.isEmpty()) return;
        log.info("盤後覆寫台股 {} 檔分時 tick LIST（FinMind TaiwanStockKBar 5m）", tw.size());
        refreshTw(tw, today);
    }

    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "America/New_York")
    public void refreshUsTicks() {
        LocalDate today = LocalDate.now(MarketClock.US_ZONE);
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (us.isEmpty()) return;
        log.info("盤後覆寫美股 {} 檔分時 tick LIST（Yahoo 5m）", us.size());
        refreshYahoo(us, "美股", today);
    }

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Europe/London")
    public void refreshUkTicks() {
        LocalDate today = LocalDate.now(MarketClock.LON_ZONE);
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (uk.isEmpty()) return;
        log.info("盤後覆寫英股 {} 檔分時 tick LIST（Yahoo 5m）", uk.size());
        refreshYahoo(uk, "英股", today);
    }

    /**
     * 對外觸發單檔即時 refresh：StockAnalysisDialog 切到「當日」如 Redis tick LIST 為空
     * 視同 cold start，立刻抓一次外部源寫入。供 InternalPriceController endpoint 用。
     */
    public void refreshOne(String code, String market, LocalDate date) {
        if ("美股".equals(market) || "英股".equals(market)) {
            refreshYahooOne(code, market, date);
        } else {
            refreshTwOne(code, date);
        }
    }

    /**
     * 讀取路徑的 single-flight 自癒：同一 market/code/date 只允許一個外呼，
     * 完成後成功失敗皆冷卻 60 秒；呼叫端最多等待 8 秒，逾時後背景工作繼續。
     */
    public void refreshOneGuarded(String code, String market, LocalDate date, boolean wait) {
        String key = market + "_" + code + "_" + date;
        long now = System.nanoTime();
        HealAttempt attempt = healAttempts.compute(key, (ignored, existing) -> {
            if (existing != null && (existing.future.isDone()
                    ? now < existing.cooldownUntilNanos
                    : true)) {
                return existing;
            }
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> refreshOne(code, market, date), healExecutor);
            HealAttempt created = new HealAttempt(future);
            future.whenComplete((ok, error) -> created.cooldownUntilNanos =
                    System.nanoTime() + selfHealCooldown.toNanos());
            return created;
        });
        purgeExpiredAttempts(now);
        if (!wait) return;
        try {
            attempt.future.get(selfHealWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.info("分時自癒等待逾時，先回既有資料 {} {} {}", market, code, date);
        } catch (Exception e) {
            log.warn("分時自癒失敗 {} {} {}: {}", market, code, date, e.getMessage());
        }
    }

    private void purgeExpiredAttempts(long now) {
        if (healAttempts.size() < 128) return;
        healAttempts.entrySet().removeIf(e ->
                e.getValue().future.isDone() && now >= e.getValue().cooldownUntilNanos);
    }

    private void refreshTw(Set<String> codes, LocalDate date) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String code : codes) {
                pool.submit(() -> refreshTwOne(code, date));
            }
        }
    }

    private void refreshTwOne(String code, LocalDate date) {
        try {
            // 主來源 FinMind TaiwanStockKBar 需 sponsor token，未設定時回 400 → 直接 fallback。
            List<TickBar> bars = client.fetchTwKBar5m(code, date);
            if (!bars.isEmpty()) {
                tickStore.replaceTicks(code, "台股", date, toTickPoints(bars));
                return;
            }
            // Fallback：Yahoo chart interval=5m（與美/英股同一資料源）。
            refreshYahooOne(code, "台股", date);
        } catch (Exception e) {
            log.warn("覆寫台股 {} ticks 失敗: {}", code, e.getMessage());
        }
    }

    private void refreshYahoo(Set<String> codes, String market, LocalDate date) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String code : codes) {
                pool.submit(() -> refreshYahooOne(code, market, date));
            }
        }
    }

    private void refreshYahooOne(String code, String market, LocalDate date) {
        try {
            List<IntradayBar> bars = client.fetchIntraday5m(code, market, 1);
            List<IntradayTickStore.TickPoint> ticks = new ArrayList<>();
            for (IntradayBar b : bars) {
                if (b.close() == null) continue;
                // 只保留 date 當天的 bar（Yahoo range=1d 偶爾跨日，避免污染）
                String t = b.time();
                if (t == null || !t.startsWith(date.toString())) continue;
                ticks.add(new IntradayTickStore.TickPoint(t, b.close()));
            }
            tickStore.replaceTicks(code, market, date, ticks);
        } catch (Exception e) {
            log.warn("覆寫 {} {} ticks 失敗: {}", market, code, e.getMessage());
        }
    }

    private static List<IntradayTickStore.TickPoint> toTickPoints(List<TickBar> bars) {
        List<IntradayTickStore.TickPoint> out = new ArrayList<>(bars.size());
        for (TickBar b : bars) {
            if (b.price() == null) continue;
            out.add(new IntradayTickStore.TickPoint(b.time(), b.price()));
        }
        return out;
    }
}
