package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 今日交易雷達「重新整理」按鈕專用的台股行情回補（Task 249，Requirement 43 修訂）。
 *
 * <p>只抓本頁真正用得到的東西：台股個股（最新快照持股 ∪ 觀察清單）＋大盤 {@code 0000}。
 * <b>刻意不重用 {@link PricePoller#refreshAll()}</b>——後者連美股／英股一起抓，本頁不評分，
 * 多抓只是把使用者的等待時間拉長到含逐檔 Yahoo 查詢的長度。</p>
 *
 * <p>本類別必須位於 {@code ...externalmaterials.service} 套件：它呼叫的
 * {@link PricePoller#updatePrices}／{@link PricePoller#syncClosedFromDb}／
 * {@link TaiexIndexPoller#updateOnce()} 皆為 package-private，刻意不為此改成 public。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwRadarRefreshService {

    private static final String TW_MARKET = "台股";
    private static final String TAIEX_CODE = "0000";
    /** 台股收盤時刻；休市守門的時間下界（見 refresh() 的休市分支）。 */
    private static final LocalTime TW_CLOSE = LocalTime.of(13, 30);

    private final PricePoller poller;
    private final TaiexIndexPoller taiex;
    private final StockSourceQuery source;
    private final MarketClock clock;

    /** 單一併發：已有一輪在跑就立即回 busy，不排隊等待。 */
    private final Semaphore gate = new Semaphore(1);

    /**
     * bean 生命週期的 executor。
     *
     * <p><b>絕不可改成方法內 try-with-resources。</b> Java 19+ 的 {@code ExecutorService.close()}
     * 是 {@code shutdown()} 後 {@code awaitTermination(1 DAY)}，離開 try 區塊會一路等到大盤任務結束，
     * 把下方的 12 秒上限整個作廢（{@link PricePoller#updatePrices} 的
     * {@code // executor.close() 等所有 task 完成} 註解即此語意）。</p>
     */
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    /** package-private 非 final：測試需調小。生產不覆寫。 */
    long taiexWaitSeconds = 12;

    /**
     * package-private 非 final：休市守門的 13:30 分界必須可測，不得讓測試結果取決於真實時鐘。
     * 生產不覆寫。
     */
    java.time.Clock timeSource = java.time.Clock.system(MarketClock.TW_ZONE);

    /**
     * @param performed           是否真的執行了一輪（busy 時為 false）
     * @param skippedPendingClose 今日收盤尚未落 DB 的空窗，刻意跳過同步（見休市分支）
     */
    public record Summary(boolean performed, boolean busy, boolean twMarketOpen,
                          boolean twMarketUnknown, boolean skippedPendingClose,
                          int twStocks, int indexUpdated) {

        static Summary busy(boolean twMarketOpen, boolean twMarketUnknown) {
            return new Summary(false, true, twMarketOpen, twMarketUnknown, false, 0, 0);
        }
    }

    public Summary refresh() {
        TwLiveQuoteDispatcher.Authorization authorization = poller.authorizeTwLive();
        boolean open = authorization.marketOpen();
        boolean unknown = authorization.marketUnknown();
        if (!gate.tryAcquire()) {
            log.info("[tw-radar-refresh] 已有一輪進行中，直接回 busy");
            return Summary.busy(open, unknown);
        }

        long t0 = System.nanoTime();
        try {
            // 用雷達專用收集器（每位 owner 各自最新快照 ∪ 觀察清單，已排除 0000）。
            // 不可改回 collectHeldStockCodes：兩者自 Task 257 起雖共用同一個 DISTINCT ON (owner_user_id)
            // 子查詢，但後者不做 SQL 層 market='台股' 過濾、也不無條件 remove("0000")——它靠 classify()
            // 的 else 分支把非美股非英股的一切 market 值歸入台股，本頁只評台股，換過去會多抓且含大盤。
            Set<String> tw = new LinkedHashSet<>();
            source.collectTwRadarCodes(tw);
            tw.remove(TAIEX_CODE);   // 防禦性：收集器已排除，這裡不倚賴它
            log.info("[tw-radar-refresh] 開始：台股 {} 檔，開盤中={}", tw.size(), open);

            int indexUpdated = open ? refreshOpenMarket(tw, authorization) : 0;
            boolean skippedPendingClose = open || unknown ? false : syncClosedGuarded(tw);

            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[tw-radar-refresh] 完成：台股 {} 檔，大盤更新={}，跳過待收盤={}，耗時 {} ms",
                    tw.size(), indexUpdated, skippedPendingClose, ms);
            return new Summary(true, false, open, unknown, skippedPendingClose, tw.size(), indexUpdated);
        } finally {
            gate.release();
        }
    }

    /**
     * 開盤中：個股與大盤併行抓。
     *
     * <p>先等大盤（12 秒上限）再等個股，順序不可顛倒——反過來會讓大盤的 12 秒疊在個股的
     * 約 20 秒之後。大盤逾時直接放棄本輪（Redis 保留上一輪真實點位，比照
     * {@link TaiexIndexPoller#updateOnce()} 既有「查無有效點位不寫」的慣例）：其底層
     * {@code MacroDataFetchClient.curlGetWithRetry(url, 2)} 的退避是 10s+20s，且 curl 子程序
     * 沒有 {@code -m}、{@code waitFor()} 也無逾時，理論上無上界；business 端的 WebClient 只有
     * 30 秒，序列執行必然被打穿。{@code cancel(true)} 救不回來（阻塞在 pipe 讀取時對中斷無反應），
     * 唯一正確的做法就是不等它。
     *
     * @return 大盤是否更新成功（1／0）
     */
    private int refreshOpenMarket(
            Set<String> tw,
            TwLiveQuoteDispatcher.Authorization authorization) {
        Future<?> stocks = pool.submit(() -> poller.updateTwPrices(tw, authorization));
        Future<?> index = pool.submit(taiex::updateOnce);

        int indexUpdated = 0;
        try {
            index.get(taiexWaitSeconds, TimeUnit.SECONDS);
            indexUpdated = 1;
        } catch (TimeoutException e) {
            index.cancel(true);
            log.warn("[tw-radar-refresh] 大盤逾時（{} 秒），放棄本輪", taiexWaitSeconds);
        } catch (Exception e) {
            log.warn("[tw-radar-refresh] 大盤更新失敗: {}", e.toString());
        }

        try {
            stocks.get();
        } catch (Exception e) {
            log.warn("[tw-radar-refresh] 個股更新失敗: {}", e.toString());
        }
        return indexUpdated;
    }

    /**
     * 休市：以 DB 收盤同步 Redis，但必須逐檔守門。
     *
     * <p>守門只在「交易日且已過 13:30」時生效。{@code MarketClock.isTwMarketOpen()} 的上界是
     * 13:30，而今日收盤要到 {@code ClosePersister.dumpTwCloseFromRedis()}（cron {@code 0 32 13}）
     * 才由 Redis 落進 {@code stock_price_history}；這兩分鐘內 {@code findRecentClose} 取回的是
     * <b>昨日</b>收盤，{@code PriceCacheWriter.syncClosedFromDb} 會無條件覆寫 Redis 銷毀當日真實收盤，
     * 接著 13:32 的 {@code dumpRedisToDb}（不檢查 payload 的 tradingDate）會把該昨收當成今日收盤
     * upsert 進 DB，污染收盤價的唯一權威來源。</p>
     *
     * <p>時間下界不可省：少了它，交易日盤前 00:00–09:00 整段也會落進守門（同樣「休市＋是交易日＋
     * 今日收盤未落 DB」），而守門理由在盤前是反過來的——DB 有 16:00 FinMind 校正過的權威收盤，
     * Redis 才是該被同步的一方。</p>
     *
     * @return 是否因空窗而整批跳過（{@code tw} 為空時恆為 false，不得回出假訊息）
     */
    private boolean syncClosedGuarded(Set<String> tw) {
        LocalDate today = LocalDate.now(timeSource);
        boolean guard = clock.isTradingDay(TW_MARKET, today)
                && LocalTime.now(timeSource).isAfter(TW_CLOSE);

        // Optional.empty()（該檔無任何歷史列）視為不納入同步；不可寫成 .orElse(today)，
        // 那會把「無歷史」翻轉成「等於今日 → 納入同步」，與守門意圖相反。
        Set<String> syncable = guard
                ? tw.stream()
                        .filter(c -> source.findMaxTradingDate(c, TW_MARKET).filter(today::equals).isPresent())
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                : tw;

        if (!syncable.isEmpty()) {
            poller.syncClosedFromDb(syncable, TW_MARKET);
            return false;
        }
        boolean skipped = guard && !tw.isEmpty();
        if (skipped) {
            log.info("[tw-radar-refresh] 今日收盤尚未落 DB，跳過同步以免昨收覆寫 Redis");
        }
        return skipped;
    }
}
