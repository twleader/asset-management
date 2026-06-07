package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.IntradayBar;
import com.steven.assets.externalmaterials.client.PriceFetchClient.TickBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 盤後外部資料源覆寫 Redis tick LIST。
 *
 * 動機：盤中 {@link PricePoller} 每 2 分鐘輪詢只在「真實成交」時寫 tick；遇 z='-'（兩 tick 之間
 * 無新成交的 5 秒視窗）會 skip，原始累積 LIST 可能跳號。盤後抓 FinMind / Yahoo 全天完整 K 線
 * 覆寫，補回所有時間點，前端「當日」走勢圖即顯示權威來源的完整曲線。
 *
 * Cron 時點安排（較 {@link ClosePersister} dump 晚 3 分鐘，避免與 dump 競爭 Redis IO）：
 *  - 台股 13:35 TW（dump 13:32 之後）→ FinMind TaiwanStockKBar 5m
 *  - 美股 16:05 ET（dump 16:02 之後）→ Yahoo chart interval=5m
 *  - 英股 16:35 LON（dump 16:32 之後）→ Yahoo chart interval=5m
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntradayTickRefresher {

    private final PriceFetchClient client;
    private final IntradayTickStore tickStore;
    private final StockSourceQuery source;

    @Scheduled(cron = "0 35 13 * * MON-FRI", zone = "Asia/Taipei")
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

    @Scheduled(cron = "0 35 16 * * MON-FRI", zone = "Europe/London")
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
