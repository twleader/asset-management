package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.EtfNavFetchClient;
import com.steven.assets.externalmaterials.client.EtfNavFetchClient.EtfNav;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ETF 淨值／折溢價排程抓取（Task 210）：抓外部 → 寫 Redis，供 business-services 的資產總覽匯出取用。
 *
 * <p><b>台股</b>：每 5 分鐘（09-13 時、交易時段 guard）打證交所 {@code all_etf.txt}。該檔一次回全市場 350 檔，
 * 故不論持有幾檔都只是<b>一個 request</b>——刻意不逐檔查詢，對來源最友善。
 *
 * <p><b>美股</b>：淨值一天只公告一次（收盤後），故只在美東 18:30 抓一次，逐檔打 Yahoo quoteSummary。
 * 逐檔是必要的（無全市場彙整檔），但檔數少（僅持股中的 ETF）且一天一次。
 *
 * <p><b>不做 ETF 判定</b>：台股以「代號是否出現在證交所 ETF 名冊」判定、美股以「Yahoo 是否回 navPrice」判定，
 * 兩者都是資料驅動。既有 {@code isEtf()} 白名單誤把個股 AVGO 當 ETF、又漏掉使用者持有的 SGOV，刻意不複用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtfNavPoller {

    private final EtfNavFetchClient client;
    private final MarketDataFetchService marketDataFetchService;
    private final EtfNavCacheWriter writer;
    private final StockSourceQuery source;
    private final MarketClock clock;

    @Value("${etf-nav.enabled:true}")
    private boolean enabled;

    /** 開機補抓（不阻塞啟動）：重啟後若無此步，Redis 冷啟動要等到下一個交易時段才有淨值。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!enabled) return;
        new Thread(() -> {
            try {
                refreshAll();
            } catch (Exception e) {
                log.warn("ETF 淨值開機補抓失敗：{}", e.getMessage());
            }
        }, "etf-nav-warmup").start();
    }

    /** 台股盤中每 5 分鐘：證交所 iNAV 每 15 秒更新，5 分鐘一次足夠且對來源友善。 */
    @Scheduled(cron = "0 2/5 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTwUpdate() {
        if (!enabled || !clock.isTwMarketOpen()) return;
        refreshTw();
    }

    /** 美股收盤後（美東 18:30）：發行商當日淨值多於 17:00-18:00 ET 間公告，此時抓可拿到 T 日 NAV。 */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "America/New_York")
    public void scheduledUsUpdate() {
        if (!enabled) return;
        refreshUs();
    }

    /** 手動觸發（{@code POST /internal/etf-nav/refresh}）：不限交易時段，供驗證與補救。 */
    public RefreshSummary refreshAll() {
        int tw = refreshTw();
        int us = refreshUs();
        return new RefreshSummary(tw, us);
    }

    public record RefreshSummary(int twUpdated, int usUpdated) {}

    /** 台股：一次抓全市場，只寫入「持股／觀察清單中有的代號」，避免把 350 檔全灌進 Redis。 */
    private int refreshTw() {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (tw.isEmpty()) return 0;
        Map<String, EtfNav> all = client.fetchTwAll();
        if (all.isEmpty()) return 0; // 抓取失敗：保留 Redis 上一輪的值，不覆寫、不清空
        int n = 0;
        for (String code : tw) {
            EtfNav nav = all.get(code);
            if (nav == null) continue; // 不在 ETF 名冊＝個股，正常情形
            writer.write(nav);
            n++;
        }
        log.info("ETF 淨值更新（台股）：持股 {} 檔中 {} 檔為 ETF 並已寫入", tw.size(), n);
        return n;
    }

    /** 美股：逐檔問 Yahoo；個股沒有 navPrice 會回 null，即為 ETF 與否的資料驅動判定。 */
    private int refreshUs() {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        if (us.isEmpty()) return 0;
        int n = 0;
        for (String code : us) {
            EtfNav nav = marketDataFetchService.getUsEtfNav(code);
            if (nav == null) continue; // 個股或抓取失敗
            writer.write(nav);
            n++;
        }
        log.info("ETF 淨值更新（美股）：持股 {} 檔中 {} 檔取得淨值", us.size(), n);
        return n;
    }
}
