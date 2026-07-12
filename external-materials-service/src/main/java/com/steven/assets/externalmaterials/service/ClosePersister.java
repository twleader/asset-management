package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 盤後收盤價持久化（兩段式，台股）：
 * - 13:32 TW {@link #dumpTwCloseFromRedis()}：盤中最後一輪 cron（13:30）寫進 Redis 的價即為當日收盤
 *   （TWSE mis 的最後一筆成交），秒到資料庫，不依賴 FinMind 即時性
 * - 16:00 TW {@link #verifyTwCloseWithFinMind()}：FinMind TaiwanStockPrice 盤後 1-2 小時才發佈，
 *   此時呼叫驗證／覆寫；FinMind 有回值就以 FinMind 為權威。**同時覆寫 Redis** 以避免 Dashboard /
 *   SnapshotForm 讀 Redis 仍看到盤中 last tick（13:28 那輪）。
 *
 * 美股：16:02 ET {@link #dumpUsCloseFromRedis()} dump Redis（盤中最後一輪 cron 16:00 寫的 NASDAQ 收盤），
 *   18:00 ET {@link #verifyUsCloseWithFinMind()} 以 FinMind USStockPrice 校正並同步覆寫 Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClosePersister {

    private final PriceFetchClient client;
    private final StockSourceQuery source;
    private final StringRedisTemplate redis;
    private final PriceCacheWriter cacheWriter;
    private final MarketCalendar calendar;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 開機自我修復：若今天該跑的 close 邏輯已過排程時間但 DB 沒當日資料，補跑一次。
     * 處理「price-service 在 close 排程時點間沒在跑（restart / crash）」場景。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealMissedClose() {
        new Thread(() -> {
            try {
                ZonedDateTime nowTw = ZonedDateTime.now(MarketClock.TW_ZONE);
                LocalDate today = nowTw.toLocalDate();
                if (calendar.isTwTradingDay(today)) {
                    if (nowTw.toLocalTime().isAfter(LocalTime.of(16, 0))) {
                        // 16:00 後：若 DB 無當日資料，先試 FinMind；FinMind 失敗 fallback Redis dump
                        if (!hasAnyHistoryFor(today, "台股")) {
                            log.info("self-heal: 台股今日 ({}) DB 無資料，跑 FinMind 校正", today);
                            int finmindOk = verifyTwCloseWithFinMind();
                            if (finmindOk == 0) {
                                log.info("self-heal: FinMind 全空，改用 Redis dump");
                                dumpTwCloseFromRedis();
                            }
                        }
                    } else if (nowTw.toLocalTime().isAfter(LocalTime.of(13, 32))
                            && !hasAnyHistoryFor(today, "台股")) {
                        log.info("self-heal: 台股今日 ({}) DB 無資料，dump Redis 補一次", today);
                        dumpTwCloseFromRedis();
                    }
                }
                ZonedDateTime nowUs = ZonedDateTime.now(MarketClock.US_ZONE);
                LocalDate todayUs = nowUs.toLocalDate();
                if (calendar.isUsTradingDay(todayUs)) {
                    if (nowUs.toLocalTime().isAfter(LocalTime.of(18, 0))) {
                        if (!hasAnyHistoryFor(todayUs, "美股")) {
                            log.info("self-heal: 美股今日 ({}) DB 無資料，跑 FinMind 校正", todayUs);
                            int finmindOk = verifyUsCloseWithFinMind();
                            if (finmindOk == 0) {
                                log.info("self-heal: 美股 FinMind 全空，改用 Redis dump");
                                dumpUsCloseFromRedis();
                            }
                        }
                    } else if (nowUs.toLocalTime().isAfter(LocalTime.of(16, 2))
                            && !hasAnyHistoryFor(todayUs, "美股")) {
                        log.info("self-heal: 美股今日 ({}) DB 無資料，dump Redis 補一次", todayUs);
                        dumpUsCloseFromRedis();
                    }
                }
                ZonedDateTime nowUk = ZonedDateTime.now(MarketClock.LON_ZONE);
                LocalDate todayUk = nowUk.toLocalDate();
                if (calendar.isUkTradingDay(todayUk)) {
                    if (nowUk.toLocalTime().isAfter(LocalTime.of(17, 0))) {
                        if (!hasAnyHistoryFor(todayUk, "英股")) {
                            log.info("self-heal: 英股今日 ({}) DB 無資料，跑 Yahoo 校正", todayUk);
                            int yahooOk = verifyUkCloseWithYahoo();
                            if (yahooOk == 0) {
                                log.info("self-heal: 英股 Yahoo 全空，改用 Redis dump");
                                dumpUkCloseFromRedis();
                            }
                        }
                    } else if (nowUk.toLocalTime().isAfter(LocalTime.of(16, 32))
                            && !hasAnyHistoryFor(todayUk, "英股")) {
                        log.info("self-heal: 英股今日 ({}) DB 無資料，dump Redis 補一次", todayUk);
                        dumpUkCloseFromRedis();
                    }
                }
            } catch (Exception e) {
                log.warn("close self-heal 失敗: {}", e.getMessage());
            }
        }, "close-self-heal").start();
    }

    /**
     * 13:32 TW：把 13:30 那輪 cron 已寫進 Redis 的價（= TWSE mis 最後成交）dump 到 stock_price_history。
     * Redis TTL 24h，13:32 還在窗口內。
     */
    @Scheduled(cron = "0 32 13 * * MON-FRI", zone = "Asia/Taipei")
    public void dumpTwCloseFromRedis() {
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        if (!calendar.isTwTradingDay(today)) {
            log.info("假日休市，略過台股 Redis 收盤 dump ({})", today);
            return;
        }
        log.info("排程：dump 台股 Redis 收盤價到 DB ({})", today);
        int n = dumpRedisToDb("台股", today);
        log.info("台股 Redis dump 完成：{} 檔", n);
    }

    /**
     * 16:00 TW：用 FinMind 校正當日收盤價。FinMind 有回值即以其為權威覆寫 DB；
     * 沒回值（still pending or 假日）保留 13:32 dump 結果。
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Taipei")
    public int verifyTwCloseWithFinMind() {
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        if (!calendar.isTwTradingDay(today)) {
            log.info("假日休市，略過台股 FinMind 收盤校正 ({})", today);
            return 0;
        }
        log.info("排程：FinMind 校正台股當日收盤價");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        int ok = 0, miss = 0;
        for (String code : tw) {
            try {
                Optional<PriceResult> r = client.getTwClosingPriceFromFinMind(code, today);
                if (r.isEmpty()) { miss++; continue; }
                PriceResult pr = r.get();
                source.upsertHistory(code, "台股", today,
                        pr.openPrice(), pr.highPrice(), pr.lowPrice(),
                        pr.price(), pr.volume());
                // FinMind 為權威收盤，同步覆寫 Redis live cache 以與 DB 一致
                // （避免 Dashboard / SnapshotForm 讀 Redis 仍看到盤中 last tick）
                cacheWriter.writeVerifiedClose(pr);
                ok++;
                Thread.sleep(300);
            } catch (Exception e) {
                log.warn("FinMind 校正台股 {} 收盤失敗: {}", code, e.getMessage());
                miss++;
            }
        }
        log.info("FinMind 校正台股收盤完成：成功覆寫 {} 檔，缺漏 {} 檔", ok, miss);
        return ok;
    }

    /**
     * 16:02 ET：dump 美股 Redis 收盤價（16:00 ET 那輪 cron 已寫好）到 DB。
     */
    @Scheduled(cron = "0 2 16 * * MON-FRI", zone = "America/New_York")
    public void dumpUsCloseFromRedis() {
        LocalDate today = LocalDate.now(MarketClock.US_ZONE);
        if (!calendar.isUsTradingDay(today)) {
            log.info("假日休市，略過美股 Redis 收盤 dump ({})", today);
            return;
        }
        log.info("排程：dump 美股 Redis 收盤價到 DB ({})", today);
        int n = dumpRedisToDb("美股", today);
        log.info("美股 Redis dump 完成：{} 檔", n);
    }

    /**
     * 18:00 ET：用 FinMind USStockPrice 校正美股當日收盤。FinMind 有回值即覆寫 16:02 dump 值。
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "America/New_York")
    public int verifyUsCloseWithFinMind() {
        LocalDate today = LocalDate.now(MarketClock.US_ZONE);
        if (!calendar.isUsTradingDay(today)) {
            log.info("假日休市，略過美股 FinMind 收盤校正 ({})", today);
            return 0;
        }
        log.info("排程：FinMind 校正美股當日收盤價");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        int ok = 0, miss = 0;
        for (String code : us) {
            try {
                Optional<PriceResult> r = client.getUsClosingPriceFromFinMind(code, today);
                if (r.isEmpty()) { miss++; continue; }
                PriceResult pr = r.get();
                source.upsertHistory(code, "美股", today,
                        pr.openPrice(), pr.highPrice(), pr.lowPrice(),
                        pr.price(), pr.volume());
                // FinMind 為權威收盤，同步覆寫 Redis live cache 以與 DB 一致
                cacheWriter.writeVerifiedClose(pr);
                ok++;
                Thread.sleep(300);
            } catch (Exception e) {
                log.warn("FinMind 校正美股 {} 收盤失敗: {}", code, e.getMessage());
                miss++;
            }
        }
        log.info("FinMind 校正美股收盤完成：成功覆寫 {} 檔，缺漏 {} 檔", ok, miss);
        return ok;
    }

    /**
     * 16:32 LON：dump 英股 Redis 收盤價（16:30 LON 那輪 cron 已寫好）到 DB。
     */
    @Scheduled(cron = "0 32 16 * * MON-FRI", zone = "Europe/London")
    public void dumpUkCloseFromRedis() {
        LocalDate today = LocalDate.now(MarketClock.LON_ZONE);
        if (!calendar.isUkTradingDay(today)) {
            log.info("假日休市，略過英股 Redis 收盤 dump ({})", today);
            return;
        }
        log.info("排程：dump 英股 Redis 收盤價到 DB ({})", today);
        int n = dumpRedisToDb("英股", today);
        log.info("英股 Redis dump 完成：{} 檔", n);
    }

    /**
     * 17:00 LON：用 Yahoo Finance 校正英股當日收盤價。英股無 FinMind 對應 dataset，
     * 改走 Yahoo chart historical（單日 range = today~today）作為權威。
     */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Europe/London")
    public int verifyUkCloseWithYahoo() {
        LocalDate today = LocalDate.now(MarketClock.LON_ZONE);
        if (!calendar.isUkTradingDay(today)) {
            log.info("假日休市，略過英股 Yahoo 收盤校正 ({})", today);
            return 0;
        }
        log.info("排程：Yahoo 校正英股當日收盤價");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        int ok = 0, miss = 0;
        for (String code : uk) {
            try {
                java.util.List<PriceFetchClient.HistoricalBar> bars =
                        client.fetchUkHistoricalRange(code, today, today);
                if (bars.isEmpty()) { miss++; continue; }
                PriceFetchClient.HistoricalBar bar = bars.get(bars.size() - 1);
                if (!bar.tradingDate().equals(today)) { miss++; continue; }
                source.upsertHistory(code, "英股", today,
                        bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
                // 同步覆寫 Redis live cache 以與 DB 一致
                PriceResult pr = new PriceResult(code, "英股", bar.close(), null, null, "Yahoo",
                        null, null, null,
                        bar.open(), null, bar.high(), bar.low(), bar.volume());
                cacheWriter.writeVerifiedClose(pr);
                ok++;
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("Yahoo 校正英股 {} 收盤失敗: {}", code, e.getMessage());
                miss++;
            }
        }
        log.info("Yahoo 校正英股收盤完成：成功覆寫 {} 檔，缺漏 {} 檔", ok, miss);
        return ok;
    }

    /** 共用：遍歷 Redis price:{market}:* 把每筆 upsert 進 stock_price_history。 */
    private int dumpRedisToDb(String market, LocalDate tradingDate) {
        Set<String> codes = redis.opsForSet().members("price:index:" + market);
        if (codes == null || codes.isEmpty()) {
            log.warn("Redis index 為空：price:index:{}（盤中 cron 可能沒寫成功）", market);
            return 0;
        }
        int n = 0;
        for (String code : codes) {
            String json = redis.opsForValue().get("price:" + market + ":" + code);
            if (json == null) continue;
            try {
                JsonNode r = MAPPER.readTree(json);
                BigDecimal price = bd(r, "price");
                if (price == null) continue;
                source.upsertHistory(code, market, tradingDate,
                        bd(r, "openPrice"), bd(r, "highPrice"), bd(r, "lowPrice"),
                        price,
                        r.hasNonNull("volume") ? r.get("volume").asLong() : null);
                n++;
            } catch (Exception e) {
                log.warn("dump Redis {} {} 失敗: {}", market, code, e.getMessage());
            }
        }
        return n;
    }

    private static BigDecimal bd(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
    }

    private boolean hasAnyHistoryFor(LocalDate date, String market) {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        Set<String> codes;
        if ("美股".equals(market)) codes = us;
        else if ("英股".equals(market)) codes = uk;
        else codes = tw;
        for (String code : codes) {
            if (source.findMaxTradingDate(code, market).filter(d -> !d.isBefore(date)).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
