package com.steven.assets.price.service;

import com.steven.assets.price.client.PriceFetchClient;
import com.steven.assets.price.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 盤後收盤價持久化：
 * - 台股 13:35 Asia/Taipei：FinMind TaiwanStockPrice → stock_price_history
 * - 美股 16:05 America/New_York：NASDAQ live 價當作收盤 → stock_price_history
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClosePersister {

    private final PriceFetchClient client;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;

    @Scheduled(cron = "0 35 13 * * MON-FRI", zone = "Asia/Taipei")
    public void recordTwClosingPrice() {
        log.info("排程：記錄台股當日收盤價（FinMind）");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us);
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        int ok = 0, miss = 0;
        for (String code : tw) {
            try {
                Optional<PriceResult> r = client.getTwClosingPriceFromFinMind(code, today);
                if (r.isEmpty()) { miss++; continue; }
                PriceResult pr = r.get();
                writer.write(pr, true);
                source.upsertHistory(code, "台股", today,
                        pr.openPrice(), pr.highPrice(), pr.lowPrice(),
                        pr.price(), pr.volume());
                ok++;
                Thread.sleep(300);
            } catch (Exception e) {
                log.warn("FinMind 記錄台股 {} 收盤失敗: {}", code, e.getMessage());
                miss++;
            }
        }
        log.info("台股收盤價紀錄完成：成功 {} 檔，缺漏 {} 檔", ok, miss);
    }

    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "America/New_York")
    public void recordUsClosingPrice() {
        log.info("排程：記錄美股當日收盤價（NASDAQ）");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us);
        LocalDate today = LocalDate.now(MarketClock.US_ZONE);
        int ok = 0, miss = 0;
        for (String code : us) {
            try {
                PriceResult pr = client.getStockPrice(code, "美股");
                if (pr.price() == null) { miss++; continue; }
                writer.write(pr, true);
                source.upsertHistory(code, "美股", today,
                        pr.openPrice(), pr.highPrice(), pr.lowPrice(),
                        pr.price(), pr.volume());
                ok++;
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("NASDAQ 記錄美股 {} 收盤失敗: {}", code, e.getMessage());
                miss++;
            }
        }
        log.info("美股收盤價紀錄完成：成功 {} 檔，缺漏 {} 檔", ok, miss);
    }
}
