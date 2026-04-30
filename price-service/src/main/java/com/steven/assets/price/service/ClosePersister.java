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
import java.time.LocalTime;
import java.time.ZonedDateTime;
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

    /**
     * 開機自我修復：若今天該跑的 close cron 已過排程時間但 DB 沒當日資料，補跑一次。
     * 處理「price-service 在 close 排程時點間沒在跑（restart / crash）」場景。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealMissedClose() {
        new Thread(() -> {
            try {
                ZonedDateTime nowTw = ZonedDateTime.now(MarketClock.TW_ZONE);
                LocalDate today = nowTw.toLocalDate();
                if (isWeekday(nowTw) && nowTw.toLocalTime().isAfter(LocalTime.of(16, 0))
                        && !hasAnyHistoryFor(today, "台股")) {
                    log.info("self-heal: 偵測到台股今日 ({}) close cron 未產出資料，補跑一次", today);
                    recordTwClosingPrice();
                }
                ZonedDateTime nowUs = ZonedDateTime.now(MarketClock.US_ZONE);
                LocalDate todayUs = nowUs.toLocalDate();
                if (isWeekday(nowUs) && nowUs.toLocalTime().isAfter(LocalTime.of(16, 5))
                        && !hasAnyHistoryFor(todayUs, "美股")) {
                    log.info("self-heal: 偵測到美股今日 ({}) close cron 未產出資料，補跑一次", todayUs);
                    recordUsClosingPrice();
                }
            } catch (Exception e) {
                log.warn("close self-heal 失敗: {}", e.getMessage());
            }
        }, "close-self-heal").start();
    }

    private boolean isWeekday(ZonedDateTime t) {
        var d = t.getDayOfWeek();
        return d != java.time.DayOfWeek.SATURDAY && d != java.time.DayOfWeek.SUNDAY;
    }

    private boolean hasAnyHistoryFor(LocalDate date, String market) {
        // 任意一檔有紀錄就視為 cron 跑過；都沒有才補跑
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us);
        Set<String> codes = "美股".equals(market) ? us : tw;
        for (String code : codes) {
            if (source.findMaxTradingDate(code, market).filter(d -> !d.isBefore(date)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 台股盤後收盤紀錄。
     * 排在 16:00 TW（盤後 2.5 小時）— FinMind TaiwanStockPrice 通常盤後 1–2 小時才發佈當日資料，
     * 13:35 太早會全部回空。
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Taipei")
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
