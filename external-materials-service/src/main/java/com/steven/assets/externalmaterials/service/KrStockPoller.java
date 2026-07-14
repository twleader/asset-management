package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.HistoricalBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 海外參考個股（韓股）每日收盤排程：抓三星電子（005930）、SK 海力士（000660）的日收盤，
 * upsert 至 {@code foreign_stock_daily_history}，供 {@link MarketSnapshotFetchClient} 組韓股公開資訊快照
 * （category=kr-market，併同 KOSPI 大盤與美股／匯率快照進入每輪爬蟲）。
 *
 * <p>韓股 15:30 KST（≈14:30 Asia/Taipei）收盤，故排程 16:00（Asia/Taipei）抓，確保次日早上 08:20
 * 爬蟲已有前一交易日收盤。另有開機 warmup（{@link ApplicationReadyEvent}），部署後立即有資料。
 * 資料源為 Yahoo Finance 美國站 chart API（005930.KS／000660.KS，非中港澳）。逐檔／逐則 graceful：
 * 任一失敗只 log warn、不影響其他，比照 {@link TwseIndexPoller} 等既有 producer 慣例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KrStockPoller {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    /** 韓股參考個股：三星電子、SK 海力士（Yahoo symbol 加 .KS 由 PriceFetchClient 處理）。 */
    private static final List<String> KR_CODES = List.of("005930", "000660");

    private final PriceFetchClient priceFetch;
    private final StockSourceQuery store;

    @Value("${kr-stock.enabled:true}")
    private boolean enabled;

    /** 每日 16:00（Asia/Taipei）：韓股收盤後抓當週收盤補齊。 */
    @Scheduled(cron = "0 0 16 * * *", zone = "Asia/Taipei")
    public void scheduled() {
        if (!enabled) return;
        refresh("scheduled");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        if (!enabled) {
            log.info("韓股參考個股抓取已停用（kr-stock.enabled=false），略過 warmup");
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                refresh("warmup");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("韓股參考個股 warmup 失敗：{}", e.getMessage());
            }
        }, "kr-stock-warmup").start();
    }

    private void refresh(String trigger) {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalDate start = today.minusDays(10);   // 抓近日一小段，覆蓋連假 / 補漏，upsert 天然去重
        int ok = 0, fail = 0;
        for (String code : KR_CODES) {
            try {
                List<HistoricalBar> bars = priceFetch.fetchKrHistoricalRange(code, start, today);
                for (HistoricalBar b : bars) {
                    if (b.tradingDate() == null || b.close() == null) continue;
                    store.upsertForeignStockDaily(code, b.tradingDate(), b.close());
                    ok++;
                }
            } catch (Exception e) {
                fail++;
                log.warn("韓股參考個股抓取失敗（{}）：{}", code, e.getMessage());
            }
        }
        log.info("韓股參考個股抓取（{}）：upsert {} 筆、失敗 {} 檔", trigger, ok, fail);
    }
}
