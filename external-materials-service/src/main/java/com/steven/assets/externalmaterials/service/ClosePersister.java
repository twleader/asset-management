package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import com.steven.assets.externalmaterials.client.TwOfficialCloseClient;
import com.steven.assets.externalmaterials.client.TwOfficialCloseClient.OfficialClose;
import com.steven.assets.externalmaterials.client.TwOfficialCloseClient.OfficialCloseBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 盤後收盤價持久化（台股）：
 * - 14:05、15:35、17:35 以 TWSE／TPEx 官方全市場日收盤對帳並保存來源
 * - 16:00 僅以 FinMind 補官方來源仍缺漏的標的
 * - 舊 13:32 Redis dump 已停用，最後成交不得再冒充官方收盤
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
    private final TwOfficialCloseClient twOfficialCloseClient;
    /** Captured once when a source row/bar is accepted; package-visible for deterministic tests. */
    Clock timeSource = Clock.systemUTC();
    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    Sleeper sleeper = Thread::sleep;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String FINMIND_US_CLOSE = "FINMIND_US_CLOSE";
    static final String YAHOO_UK_CLOSE = "YAHOO_UK_CLOSE";
    static final String NASDAQ_REDIS_CLOSE = "NASDAQ_REDIS_CLOSE";
    static final String YAHOO_REDIS_CLOSE = "YAHOO_REDIS_CLOSE";

    public record TwCloseReconciliation(int expected, int verified, List<String> missingCodes) {
        public TwCloseReconciliation {
            missingCodes = List.copyOf(missingCodes);
        }
    }

    /**
     * 海外市場收盤 dump 可接受的 payload 陳舊上限（Task 258）。兩個 dump 皆排在收盤後 2 分鐘
     * （16:02 ET／16:32 LON），而盤中 cron 每 2 分鐘一輪，故合法值的 {@code updatedAt}
     * 必落在收盤前最後幾輪。改小會擋掉正常路徑，改大會放進盤中 tick。
     */
    static final Duration MAX_PAYLOAD_STALENESS = Duration.ofMinutes(12);

    /**
     * 收盤 dump 的逐檔守門（Task 258）。回 {@code true} 才允許把該 payload 寫成 {@code targetDate} 的收盤。
     *
     * <p><b>為什麼需要：</b>{@link #dumpRedisToDb} 原本只要 Redis payload 有 {@code price} 就寫進
     * {@code stock_price_history} 當日列，既不檢查 payload 的 {@code tradingDate}、也不檢查該值有多舊。
     * 代號集合來自 Redis SET {@code price:index:{market}}，其中含開機 {@code warmCacheOnStartup} 以
     * {@code collectAllStockCodes} 寫入（實測台股 34 檔）、之後再也不被盤中 cron（Task 257 後 19 檔）
     * 刷新的「曾持有／曾觀察」標的；加上 {@code PriceCacheWriter.syncClosedFromDb} 會從 DB 最近收盤
     * 寫回 Redis，錯誤值遂透過 Redis 洗一圈回到 DB、每個交易日自我延續一列。實測 2026 年已累積
     * 台股 122 列、美股 3 列、英股 3 列假收盤（例 {@code 2002} 中鋼 2026-07-16～07-29 共 8 個交易日
     * 收盤全記 19.1000、O/H/L 為 null、volume 0）。</p>
     *
     * <p><b>兩條規則皆須成立：</b></p>
     * <ol>
     *   <li>{@code tradingDate} 必須等於 {@code targetDate}——擋掉上述迴路（陳舊值帶的是舊日期，
     *       或 {@code syncClosedFromDb} 寫入的 DB {@code maxTradingDate}）。</li>
     *   <li>{@code updatedAt} 距 {@code now} 不得超過 {@link #MAX_PAYLOAD_STALENESS}——擋掉「同日但
     *       早於收盤數小時」的盤中 tick（實測 2026-07-29 的 {@code 1301} 10:40／{@code 2409} 10:55／
     *       {@code 2882} 11:30 就是被當成收盤寫入的盤中值）。</li>
     * </ol>
     *
     * <p><b>{@code now} 必須是台北牆鐘。</b>{@code PriceCacheWriter} 三處寫 {@code updatedAt} 都是
     * {@code LocalDateTime.now(MarketClock.TW_ZONE)}，即所有市場的 {@code updatedAt} 都是台北牆鐘；
     * 拿它去比美股／英股的當地收盤時刻會分別位移 12／7 小時。改成比「距本次執行時刻」則兩端同為
     * 台北牆鐘，三個市場同一段程式碼即正確。</p>
     *
     * <p><b>刻意不用 payload 的 {@code closed} 當守門條件。</b>13:32 dump 要取的正是 13:28～13:30
     * 那輪盤中 cron 寫入的值，而 {@code PricePoller.scheduledTwIntradayUpdate} 呼叫的是
     * {@code updatePrices(tw, "台股", false)} → {@code closed} 為 {@code false}。以
     * {@code closed == true} 守門會把正常路徑整個擋掉、當日一列都寫不進去。</p>
     */
    static boolean shouldDumpPayload(JsonNode payload, LocalDate targetDate, LocalDateTime now) {
        if (payload == null || targetDate == null || now == null) return false;

        JsonNode td = payload.get("tradingDate");
        if (td == null || td.isNull()) return false;
        LocalDate payloadDate;
        try {
            payloadDate = LocalDate.parse(td.asText());
        } catch (Exception e) {
            return false;
        }
        if (!payloadDate.equals(targetDate)) return false;

        JsonNode ua = payload.get("updatedAt");
        if (ua == null || ua.isNull()) return false;
        LocalDateTime updatedAt;
        try {
            updatedAt = LocalDateTime.parse(ua.asText());
        } catch (Exception e) {
            return false;
        }
        Duration age = Duration.between(updatedAt, now);
        // updatedAt 晚於 now（容器時鐘微幅倒退）視為 0 分鐘，不得因負值被誤擋
        if (age.isNegative()) return true;
        return age.compareTo(MAX_PAYLOAD_STALENESS) <= 0;
    }

    /**
     * 開機自我修復：若今天該跑的 close 邏輯已過排程時間但 DB 沒當日資料，補跑一次。
     * 處理「price-service 在 close 排程時點間沒在跑（restart / crash）」場景。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealMissedClose() {
        new Thread(() -> {
            try {
                ZonedDateTime nowTw = ZonedDateTime.now(MarketClock.TW_ZONE);
                selfHealTwClose(nowTw);
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
     * 舊的台股 Redis 收盤 dump 已停用。盤中最後成交不保證等於集合競價後的官方收盤，
     * 因此這個方法保留相容性但永遠不再寫 DB。
     */
    @Deprecated(forRemoval = false)
    public void dumpTwCloseFromRedis() {
        log.warn("台股 Redis 收盤 dump 已停用；等待 TWSE／TPEx 官方收盤對帳");
    }

    /**
     * TWSE／TPEx 官方日收盤全市場對帳。14:05 先跑，15:35、17:35 再補來源短暫失敗或晚發的標的。
     */
    @Scheduled(cron = "0 5 14 * * MON-FRI", zone = "Asia/Taipei")
    @Scheduled(cron = "0 35 15,17 * * MON-FRI", zone = "Asia/Taipei")
    public void reconcileTwOfficialCloseScheduled() {
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        if (!calendar.isTwTradingDay(today)) {
            log.info("假日休市，略過台股官方收盤對帳 ({})", today);
            return;
        }
        reconcileTwOfficialClose(today);
    }

    public TwCloseReconciliation reconcileTwOfficialClose(LocalDate targetDate) {
        return reconcileTwOfficialClose(targetDate, true);
    }

    TwCloseReconciliation reconcileTwOfficialClose(LocalDate targetDate, boolean publishLatest) {
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        tw.remove("0000");
        OfficialCloseBatch batch = twOfficialCloseClient.fetch(targetDate);

        for (String code : tw) {
            OfficialClose row = batch.rows().get(code);
            if (row == null || !targetDate.equals(row.tradingDate())) continue;
            boolean wrote = source.upsertVerifiedHistory(
                    code, "台股", targetDate,
                    row.open(), row.high(), row.low(), row.close(), row.volume(), row.source());
            if (wrote) {
                PriceResult verified = new PriceResult(
                        code, "台股", row.close(), null, null, row.source(),
                        row.name(), null, null,
                        row.open(), null, row.high(), row.low(), row.volume(),
                        targetDate, timeSource.instant());
                if (publishLatest) cacheWriter.writeVerifiedClose(verified);
            }
        }

        List<String> missing = tw.stream()
                .filter(code -> !source.hasTrustedTwClose(code, targetDate))
                .sorted()
                .toList();
        log.info("台股官方收盤對帳完成 target={} expected={} verified={} missing={} sourceFailures={}",
                targetDate, tw.size(), tw.size() - missing.size(), missing, batch.sourceFailures());
        return new TwCloseReconciliation(tw.size(), tw.size() - missing.size(), missing);
    }

    /** 16:00 僅以 FinMind 補官方來源仍缺漏的標的，不覆寫已驗證完成列。 */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Taipei")
    public int verifyTwCloseWithFinMind() {
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        if (!calendar.isTwTradingDay(today)) {
            log.info("假日休市，略過台股 FinMind 收盤校正 ({})", today);
            return 0;
        }
        return verifyTwCloseWithFinMind(today);
    }

    int verifyTwCloseWithFinMind(LocalDate targetDate) {
        return verifyTwCloseWithFinMind(targetDate, true);
    }

    int verifyTwCloseWithFinMind(LocalDate targetDate, boolean publishLatest) {
        log.info("FinMind 補台股官方收盤缺漏 ({})", targetDate);
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectHeldStockCodes(tw, us, uk);
        tw.remove("0000");
        int ok = 0, miss = 0;
        for (String code : tw) {
            if (source.hasTrustedTwClose(code, targetDate)) continue;
            try {
                Optional<PriceResult> r = client.getTwClosingPriceFromFinMind(code, targetDate);
                if (r.isEmpty()) { miss++; continue; }
                PriceResult pr = withSource(r.get(), StockSourceQuery.FINMIND_TW_CLOSE);
                boolean wrote = source.upsertVerifiedHistory(code, "台股", targetDate,
                        pr.openPrice(), pr.highPrice(), pr.lowPrice(),
                        pr.price(), pr.volume(), StockSourceQuery.FINMIND_TW_CLOSE);
                if (wrote) {
                    if (publishLatest) cacheWriter.writeVerifiedClose(pr);
                    ok++;
                }
                sleeper.sleep(300);
            } catch (Exception e) {
                log.warn("FinMind 校正台股 {} 收盤失敗: {}", code, e.getMessage());
                miss++;
            }
        }
        log.info("FinMind 校正台股收盤完成：成功覆寫 {} 檔，缺漏 {} 檔", ok, miss);
        return ok;
    }

    void selfHealTwClose(ZonedDateTime nowTw) {
        Optional<LocalDate> target = latestCompletedTwTarget(nowTw);
        if (target.isEmpty()) return;
        LocalDate tradingDate = target.get();
        ZonedDateTime officialReady = tradingDate.atTime(14, 5).atZone(MarketClock.TW_ZONE);
        if (nowTw.isBefore(officialReady)) return;

        boolean publishLatest = shouldPublishHistoricalCloseToLatest(
                tradingDate, nowTw, calendar.isTwTradingDay(nowTw.toLocalDate()));
        TwCloseReconciliation result = reconcileTwOfficialClose(tradingDate, publishLatest);
        ZonedDateTime fallbackReady = tradingDate.atTime(16, 0).atZone(MarketClock.TW_ZONE);
        if (result.verified() < result.expected() && !nowTw.isBefore(fallbackReady)) {
            verifyTwCloseWithFinMind(tradingDate, publishLatest);
        }
    }

    static boolean shouldPublishHistoricalCloseToLatest(
            LocalDate targetDate, ZonedDateTime nowTw, boolean todayIsTradingDay) {
        if (targetDate == null || nowTw == null) return false;
        LocalDate today = nowTw.withZoneSameInstant(MarketClock.TW_ZONE).toLocalDate();
        LocalTime time = nowTw.withZoneSameInstant(MarketClock.TW_ZONE).toLocalTime();
        return !targetDate.isBefore(today)
                || !todayIsTradingDay
                || time.isBefore(LocalTime.of(9, 0));
    }

    Optional<LocalDate> latestCompletedTwTarget(ZonedDateTime nowTw) {
        LocalDate today = nowTw.toLocalDate();
        LocalDate candidate = calendar.isTwTradingDay(today)
                && nowTw.toLocalTime().isBefore(LocalTime.of(14, 5))
                ? today.minusDays(1)
                : today;
        for (int i = 0; i < 14; i++, candidate = candidate.minusDays(1)) {
            if (calendar.isTwTradingDay(candidate)) return Optional.of(candidate);
        }
        log.warn("14 日內找不到台股已完成交易日，略過收盤自我修復 now={}", nowTw);
        return Optional.empty();
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
                PriceResult pr = withSource(r.get(), FINMIND_US_CLOSE);
                boolean wrote = source.upsertVerifiedHistory(code, "美股", today,
                        pr.openPrice(), pr.highPrice(), pr.lowPrice(),
                        pr.price(), pr.volume(), FINMIND_US_CLOSE);
                // FinMind 為權威收盤，同步覆寫 Redis live cache 以與 DB 一致
                cacheWriter.writeVerifiedClose(pr);
                if (wrote) ok++;   // 同台股：被拒的列不計入（Task 279）
                sleeper.sleep(300);
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
                boolean wrote = source.upsertVerifiedHistory(code, "英股", today,
                        bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(), YAHOO_UK_CLOSE);
                // 同步覆寫 Redis live cache 以與 DB 一致
                PriceResult pr = new PriceResult(code, "英股", bar.close(), null, null, YAHOO_UK_CLOSE,
                        null, null, null,
                        bar.open(), null, bar.high(), bar.low(), bar.volume(),
                        bar.tradingDate(), timeSource.instant());
                cacheWriter.writeVerifiedClose(pr);
                if (wrote) ok++;   // 同台股：被拒的列不計入（Task 279）
                sleeper.sleep(500);
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
        LocalDateTime now = LocalDateTime.now(MarketClock.TW_ZONE);
        int n = 0;
        List<String> skipped = new ArrayList<>();
        for (String code : codes) {
            String json = redis.opsForValue().get("price:" + market + ":" + code);
            if (json == null) continue;
            try {
                JsonNode r = MAPPER.readTree(json);
                BigDecimal price = bd(r, "price");
                if (price == null) continue;
                // Task 258：陳舊值 / 盤中 tick 不得被冠上今日日期寫成收盤
                if (!shouldDumpPayload(r, tradingDate, now)) {
                    skipped.add(code);
                    continue;
                }
                String closeSource = "美股".equals(market) ? NASDAQ_REDIS_CLOSE : YAHOO_REDIS_CLOSE;
                if (source.upsertVerifiedHistory(code, market, tradingDate,
                        bd(r, "openPrice"), bd(r, "highPrice"), bd(r, "lowPrice"),
                        price,
                        r.hasNonNull("volume") ? r.get("volume").asLong() : null,
                        closeSource)) {
                    n++;   // 被拒的非正收盤列不得算成已寫入（Task 279）
                }
            } catch (Exception e) {
                log.warn("dump Redis {} {} 失敗: {}", market, code, e.getMessage());
            }
        }
        // 不得靜默截斷：被守門跳過的檔要看得見，否則「dump 完成：N 檔」讀起來像全部成功
        if (!skipped.isEmpty()) {
            log.info("{} 收盤 dump 寫入 {} 檔、守門跳過 {} 檔"
                            + "（payload 非本交易日 {} 或距今超過 {} 分鐘）：{}",
                    market, n, skipped.size(), tradingDate,
                    MAX_PAYLOAD_STALENESS.toMinutes(), skipped);
        }
        return n;
    }

    private static BigDecimal bd(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
    }

    private static PriceResult withSource(PriceResult result, String stableSource) {
        return result.withSource(stableSource);
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
