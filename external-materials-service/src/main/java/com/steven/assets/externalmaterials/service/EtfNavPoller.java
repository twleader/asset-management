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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ETF 淨值／折溢價排程抓取（Task 214／349）：抓外部 → 寫 Redis，供資產總覽匯出與公開 quote API 取用。
 *
 * <p><b>台股</b>：每 2 分鐘（09-13 時、交易時段 guard）打證交所 {@code all_etf.txt}。該檔一次回全市場 350 檔，
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

    /** Package-visible deterministic time source for observation-boundary tests. */
    Clock timeSource = Clock.systemUTC();

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

    /** 台股盤中每 2 分鐘；交易雷達台股價格 producer 已改為每 10 秒，不再依賴奇偶分鐘錯開。 */
    @Scheduled(cron = "0 1/2 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTwUpdate() {
        if (!enabled || !clock.isTwMarketOpen()) return;
        refreshTw();
    }

    /**
     * 台股收盤後補一次（17:30）：投信約 17:00 更新當日淨值，此時抓到的即為當日最終值。
     *
     * <p>入庫是 upsert，盤中每輪都會覆寫同一列，故這一輪的意義是<b>讓當日最後一次寫入落在收盤後</b>——
     * 也就是 DB 內該日的值＝收盤折溢價，而非停在 13:30 前某個盤中瞬間。
     */
    @Scheduled(cron = "0 30 17 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTwCloseUpdate() {
        if (!enabled || !clock.isTradingDay("台股", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")))) return;
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
            persist(nav);
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
            persist(nav);
            n++;
        }
        log.info("ETF 淨值更新（美股）：持股 {} 檔中 {} 檔取得淨值", us.size(), n);
        return n;
    }

    /**
     * 寫入 {@code etf_nav_history}（Task 215）：Redis 只留最新一筆（TTL 96h）供即時匯出，
     * 長期折溢價走勢靠這張表留存。
     *
     * <p>以來源自帶的資料日為主鍵之一，故同一天多次抓取只覆寫同一列（冪等）；
     * 入庫失敗只記 log，不影響 Redis 寫入與整輪排程（歷史留存不該拖垮即時功能）。
     */
    private void persist(EtfNav nav) {
        persist(nav, timeSource.instant());
    }

    /** Same persistence path with an explicit observed-at for deterministic tests. */
    void persist(EtfNav nav, Instant observedAt) {
        LocalDate navDate = parseNavDate(nav.navAsOf());
        if (navDate == null || nav.nav() == null || observedAt == null) return;
        PremiumResult premium;
        try {
            premium = resolvePremium(nav, navDate,
                    (code, date) -> source.findCloseOn(code, nav.market(), date));
        } catch (Exception e) {
            log.warn("ETF 折溢價解析失敗 {} {} {}: {}",
                    nav.market(), nav.stockCode(), navDate, e.getMessage());
            return;
        }
        try {
            source.upsertEtfNav(nav.stockCode(), nav.market(), navDate,
                    nav.nav(), premium.pct(), premium.origin(), nav.source());
        } catch (Exception e) {
            log.warn("ETF 淨值入庫失敗 {} {} {}: {}",
                    nav.market(), nav.stockCode(), navDate, e.getMessage());
        }
        try {
            // Source feeds expose no independently reliable publication instant.
            // First observation is therefore the conservative effective known-at.
            source.appendEtfNavObservation(nav.stockCode(), nav.market(), navDate,
                    nav.nav(), premium.pct(), premium.origin(), nav.source(),
                    observedAt, observedAt, "OBSERVED_AT_NO_PUBLISHED_TIMESTAMP");
        } catch (Exception e) {
            // Append-only audit failure must be visible, but must not undo the
            // compatible daily-current write or Redis refresh.
            log.warn("ETF NAV observation append 失敗 {} {} {}: {}",
                    nav.market(), nav.stockCode(), navDate, e.getMessage());
        }
    }

    /** 折溢價值與其來源標記（Task 259）：{@code origin} 為 {@code OFFICIAL}／{@code RECONSTRUCTED}／{@code null}。 */
    record PremiumResult(java.math.BigDecimal pct, String origin) {}

    /**
     * 入庫用的折溢價（Task 215，Task 259 起依 market 分流並標記來源）：
     * 來源有權威值就用（{@code OFFICIAL}）；沒有時，僅美股以<b>同一交易日的收盤價</b>與淨值反推
     * （{@code RECONSTRUCTED}）——台股<b>不反推</b>，因其淨值欄在股票型 ETF 已四捨五入至小數 2 位，
     * 反推誤差達 0.07 個百分點，與證交所公告值對不上（Requirement 34）。
     *
     * <p>美股反推刻意不用即時價或前一日收盤：前者會讓歷史列的值隨抓取時點漂移、後者是實測會把 VOO
     * 真實 +0.003% 溢價放大成 +1.02% 的錯配。查無同日收盤價或淨值為 0 時回 {@code PremiumResult(null, null)}
     * （該列折溢價留空），不退而求其次用別日價格湊數；下一輪抓取會再試一次，屆時收盤價多半已入庫。
     *
     * <p>注意與匯出欄位的語意差異：Excel 的折溢價用「該列當下的即時價」（＝現在買貴了沒），
     * 本表用「該交易日收盤價」（＝當日收盤折溢價，供日後比較常態區間）。兩者本就不是同一個問題。
     *
     * @param closeLookup 取代直接呼叫 {@link StockSourceQuery#findCloseOn}，使本方法可脫離 Spring context 單元測試
     */
    static PremiumResult resolvePremium(EtfNav nav, LocalDate navDate,
            java.util.function.BiFunction<String, LocalDate, java.util.Optional<java.math.BigDecimal>> closeLookup) {
        if (nav.premiumDiscountPct() != null) {
            return new PremiumResult(nav.premiumDiscountPct(), "OFFICIAL");
        }
        if ("台股".equals(nav.market())) {
            return new PremiumResult(null, null); // 台股折溢價欄留白：不反推
        }
        java.math.BigDecimal close = closeLookup.apply(nav.stockCode(), navDate).orElse(null);
        if (close == null || nav.nav().compareTo(java.math.BigDecimal.ZERO) == 0) {
            return new PremiumResult(null, null);
        }
        java.math.BigDecimal pct = close.subtract(nav.nav())
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(nav.nav(), 4, java.math.RoundingMode.HALF_UP);
        return new PremiumResult(pct, "RECONSTRUCTED");
    }

    /**
     * 解析來源的資料時點為日期：台股為 {@code yyyyMMdd HH:mm:ss}、美股為 {@code yyyy-MM-dd}。
     * 解析不出來一律回 null（不以「今天」代入——那會在跨日或休市抓取時把資料掛到錯誤的日期）。
     */
    private static LocalDate parseNavDate(String navAsOf) {
        if (navAsOf == null || navAsOf.isBlank()) return null;
        String head = navAsOf.trim().split("\\s+")[0];
        try {
            if (head.length() == 8 && head.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(head, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(head);
        } catch (Exception e) {
            return null;
        }
    }
}
