package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 股利 evidence 抓取排程。只 append snapshot/event/observation；不維護
 * {@code stock_dividend_history} current-state，也不做 ACTIVE/CANCELLED 決策。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendPersister {

    private static final int RETAIN_YEARS = 10;

    private final DividendFetchClient client;
    private final StockSourceQuery source;
    private final DividendSnapshotStore snapshots;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        new Thread(() -> {
            Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
            source.collectAllStockCodes(tw, us, uk);
            // 英股 UCITS ETF 配息由 MarketDataFetchService.getDividendRate 即時走 Yahoo chart?events=div，
            // 不在這裡寫 stock_dividend_history（Yahoo TTM 計算每次查詢都精確，無需快取）
            int count = 0;
            for (String code : tw) {
                try { syncOne(code, "台股"); count++; Thread.sleep(300); }
                catch (Exception e) { log.warn("dividend warmup TW {}: {}", code, e.getMessage()); }
            }
            for (String code : us) {
                try { syncOne(code, "美股"); count++; Thread.sleep(500); }
                catch (Exception e) { log.warn("dividend warmup US {}: {}", code, e.getMessage()); }
            }
            log.info("dividend 啟動 warmup 完成：{} 檔", count);
        }, "dividend-warmup").start();
    }

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledSyncAll() {
        log.info("排程：同步股利歷史");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectAllStockCodes(tw, us, uk);
        int ok = 0, fail = 0;
        for (String code : tw) {
            try { syncOne(code, "台股"); ok++; Thread.sleep(300); }
            catch (Exception e) { fail++; log.warn("dividend sync TW {}: {}", code, e.getMessage()); }
        }
        for (String code : us) {
            try { syncOne(code, "美股"); ok++; Thread.sleep(500); }
            catch (Exception e) { fail++; log.warn("dividend sync US {}: {}", code, e.getMessage()); }
        }
        log.info("股利歷史同步完成：成功 {} 檔、失敗 {} 檔", ok, fail);
    }

    /** 抓單檔並 append immutable evidence；回傳本次所有 observation 的事件筆數。 */
    public int syncOne(String code, String market) {
        List<DividendFetchClient.DividendFetchResult> observations =
                client.fetchObservations(code, market, RETAIN_YEARS);
        if (observations == null || observations.isEmpty()) {
            log.warn("股利 evidence 未回任何 observation：{} {}", market, code);
            return 0;
        }
        Instant observedAt = Instant.now();
        int eventCount = 0;
        for (DividendFetchClient.DividendFetchResult fetched : observations) {
            DividendSnapshotStore.PersistResult persisted = snapshots.record(
                    code, market, fetched, observedAt);
            if (persisted.snapshotId() <= 0 || fetched == null) {
                log.warn("股利 evidence 未落地：{} {} status={} reason={}", market, code,
                        fetched == null ? null : fetched.status(),
                        fetched == null ? null : fetched.errorReason());
                continue;
            }
            eventCount += fetched.events().size();
        }
        return eventCount;
    }
}
