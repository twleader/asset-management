package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import com.steven.assets.externalmaterials.client.DividendFetchClient.DividendEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 股利歷史持久化：每日 17:00 TW 全量同步主檔股票，UI 直接讀 stock_dividend_history。
 * 一次性 sync(code, market) 也透過 InternalDividendController 對外暴露給 backend cold-cache fallback。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendPersister {

    private static final int RETAIN_YEARS = 10;

    private final DividendFetchClient client;
    private final StockSourceQuery source;

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

    /** 抓單檔 + 寫 DB。supply by InternalDividendController for backend cold-cache fallback。 */
    public int syncOne(String code, String market) {
        List<DividendEvent> events = client.fetch(code, market, RETAIN_YEARS);
        int written = 0;
        for (DividendEvent e : events) {
            LocalDate exDate = parseDate(e.exDividendDate());
            BigDecimal previousClose = null;
            Integer fillDays = null;
            BigDecimal yieldPct = null;
            if (exDate != null) {
                StockSourceQuery.DividendBasis basis = source.calcDividendBasis(code, market, exDate);
                previousClose = basis.previousClose();
                fillDays = basis.fillDays();
                if (e.cashDividend() != null && previousClose != null && previousClose.signum() > 0) {
                    yieldPct = e.cashDividend().multiply(BigDecimal.valueOf(100))
                            .divide(previousClose, 4, RoundingMode.HALF_UP);
                }
            }
            source.upsertDividend(code, market, e.year(),
                    e.cashDividend(), e.stockDividend(),
                    exDate,
                    parseDate(e.cashPaymentDate()), parseDate(e.stockPaymentDate()),
                    yieldPct, fillDays, previousClose,
                    client.source(market));
            written++;
        }
        return written;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }
}
