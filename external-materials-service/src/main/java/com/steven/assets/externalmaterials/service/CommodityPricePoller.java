package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 油價／金價排程（Requirement 77，延伸自既有 Requirement 40 / Task 202 的每日回補）。
 *
 * <p>三個排程共用同一組 {@code commodity.enabled} 開關：
 * <ul>
 *   <li>{@link #dailyCommodityUpdate()} —— 每日 06:30（台北）增量補前一交易日收盤，
 *       作為 17:05 收盤校正整輪失敗時的安全網（不動、語意不變）。</li>
 *   <li>{@link #liveQuoteTick()} —— CME Globex 交易時段內每分鐘更新 WTI／布蘭特／COMEX 黃金
 *       即時價至 Redis（非交易時段不外呼）。</li>
 *   <li>{@link #closingSettlementUpdate()} —— 收盤後 5 分鐘（17:05 America/New_York）取回
 *       該盤收盤價寫入日線表，並回看 {@link #SETTLEMENT_LOOKBACK_DAYS} 天讓交易所結算價修訂落地。</li>
 * </ul>
 */
@Slf4j
@Service
public class CommodityPricePoller {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    /**
     * 回看窗天數：保守預設值、不是實證結論。17:05 抓到的是最後成交價，交易所結算價（settlement）
     * 要更晚才由來源回頭取代原本的最後成交價（見任務檔 337.6 實測表）；本任務只證明「存在修訂」，
     * 沒有證明修訂在幾天後落地，須靠落地後的回歸校準調整（見任務檔驗證段「回看窗是否足夠」）。
     */
    static final int SETTLEMENT_LOOKBACK_DAYS = 5;

    private final HistoricalBackfillService backfill;
    private final CommodityFetchClient fetchClient;
    private final CommoditySpotCacheWriter cacheWriter;
    private final CommodityTradingSessionPolicy sessionPolicy;
    private final StockSourceQuery store;
    private final Clock clock;
    private final AtomicBoolean liveUpdateInFlight = new AtomicBoolean(false);

    @Value("${commodity.enabled:true}")
    private boolean enabled;

    @Autowired
    public CommodityPricePoller(
            HistoricalBackfillService backfill,
            CommodityFetchClient fetchClient,
            CommoditySpotCacheWriter cacheWriter,
            CommodityTradingSessionPolicy sessionPolicy,
            StockSourceQuery store) {
        this(backfill, fetchClient, cacheWriter, sessionPolicy, store, Clock.system(NY));
    }

    CommodityPricePoller(
            HistoricalBackfillService backfill,
            CommodityFetchClient fetchClient,
            CommoditySpotCacheWriter cacheWriter,
            CommodityTradingSessionPolicy sessionPolicy,
            StockSourceQuery store,
            Clock clock) {
        this.backfill = backfill;
        this.fetchClient = fetchClient;
        this.cacheWriter = cacheWriter;
        this.sessionPolicy = sessionPolicy;
        this.store = store;
        this.clock = clock.withZone(NY);
    }

    /** 每日 06:30（台北）增量補油金價收盤；紐約收盤已落在前一夜。既有語意不變，不受本任務改動。 */
    @Scheduled(cron = "0 30 6 * * MON-SAT", zone = "Asia/Taipei")
    public void dailyCommodityUpdate() {
        if (!enabled) return;
        log.info("排程：每日回補油價／金價收盤");
        // 增量起點：無資料時才用得到 since，正常情況由 max(price_date)+1 決定
        LocalDate since = LocalDate.now().minusYears(10);
        for (String code : CommodityFetchClient.SYMBOLS.keySet()) {
            try {
                int n = backfill.backfillCommodity(code, since);
                if (n > 0) log.info("油金價回補 {} 共 {} 筆", code, n);
            } catch (Exception e) {
                log.warn("油金價回補 {} 失敗: {}", code, e.getMessage());
            }
        }
    }

    /**
     * CME Globex 交易時段內每分鐘 tick：非交易時段直接 return，不外呼。
     * 先寫 {@code commodity:session} 心跳，再以 {@link AtomicBoolean} in-flight 守門
     * （前一輪未結束則 skip，同 {@link ExchangeRatePoller#liveUpdateInFlight} 寫法），
     * 三個標的並行抓取（{@link Executors#newVirtualThreadPerTaskExecutor()} try-with-resources，
     * 同 {@link PricePoller#updatePrices} 寫法）。單一標的失敗只 log.warn，不影響另外兩個。
     */
    @Scheduled(cron = "0 * * * * *", zone = "America/New_York")
    public void liveQuoteTick() {
        if (!enabled) return;
        if (!sessionPolicy.inSession()) return;
        if (!liveUpdateInFlight.compareAndSet(false, true)) {
            log.debug("油金價即時報價前一輪仍在執行，本輪 skip");
            return;
        }
        try {
            runLiveQuoteRound();
        } finally {
            liveUpdateInFlight.set(false);
        }
    }

    /**
     * 手動觸發一輪即時報價（{@code POST /internal/commodity/live-refresh}），與 {@link #liveQuoteTick()}
     * 共用同一段抓取邏輯。仍受交易時段判定約束：非交易時段回 {@code inSession=false} 且不改 Redis。
     */
    public LiveRefreshResult liveRefreshNow() {
        if (!sessionPolicy.inSession()) {
            return new LiveRefreshResult(false, List.of());
        }
        if (!liveUpdateInFlight.compareAndSet(false, true)) {
            log.debug("油金價即時報價前一輪仍在執行，手動 live-refresh 本輪 skip");
            return new LiveRefreshResult(true, List.of());
        }
        try {
            return new LiveRefreshResult(true, runLiveQuoteRound());
        } finally {
            liveUpdateInFlight.set(false);
        }
    }

    private List<String> runLiveQuoteRound() {
        Instant polledAt = clock.instant();
        cacheWriter.writeSessionHeartbeat(polledAt);
        List<String> updated = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String code : CommodityFetchClient.SYMBOLS.keySet()) {
                pool.submit(() -> {
                    if (updateLiveQuote(code, polledAt)) updated.add(code);
                });
            }
        } // pool.close() 等所有 task 完成
        return List.copyOf(updated);
    }

    private boolean updateLiveQuote(String code, Instant polledAt) {
        try {
            Optional<CommodityFetchClient.LiveQuote> quote = fetchClient.fetchLiveQuote(code);
            if (quote.isEmpty()) {
                log.warn("油金價即時報價 {} 本輪查無資料", code);
                return false;
            }
            return cacheWriter.applyLiveTick(quote.get(), polledAt);
        } catch (Exception e) {
            log.warn("油金價即時報價 {} 更新失敗: {}", code, e.getMessage());
            return false;
        }
    }

    /** {@code POST /internal/commodity/live-refresh} 回傳摘要。 */
    public record LiveRefreshResult(boolean inSession, List<String> updated) {
    }

    /**
     * 收盤後 5 分鐘（17:05 America/New_York）取回當盤收盤價寫入日線表，並回看
     * {@link #SETTLEMENT_LOOKBACK_DAYS} 天強制覆寫（不得走 {@code backfillCommodity} 的
     * {@code max(price_date)+1} 增量路徑，那正是「寫完不回頭」的成因）。單一標的失敗只
     * log.warn，不影響另外兩個。
     */
    @Scheduled(cron = "0 5 17 * * MON-FRI", zone = "America/New_York")
    public void closingSettlementUpdate() {
        if (!enabled) return;
        LocalDate today = LocalDate.now(NY);
        LocalDate since = today.minusDays(SETTLEMENT_LOOKBACK_DAYS);
        for (String code : CommodityFetchClient.SYMBOLS.keySet()) {
            try {
                applyClosingSettlement(code, since, today);
            } catch (Exception e) {
                log.warn("油金價收盤校正 {} 失敗: {}", code, e.getMessage());
            }
        }
    }

    /** package-private：供測試直接呼叫，繞開 cron 與 enabled 開關（同 EtfNavPoller#persist 測試寫法）。 */
    void applyClosingSettlement(String code, LocalDate since, LocalDate today) {
        List<CommodityFetchClient.CommodityBar> bars = fetchClient.fetchRange(code, since, today);
        if (bars.isEmpty()) {
            log.warn("油金價收盤校正 {} 查無 {}~{} 區間資料（CME 假日／提前收盤／抓取失敗），維持上一個值",
                    code, since, today);
            return;
        }
        // 區間內每一根 bar 都強制覆寫，讓交易所結算價修訂落地（見任務檔 337.6）。
        for (CommodityFetchClient.CommodityBar bar : bars) {
            store.upsertCommodityPrice(code, bar.priceDate(), bar.closePrice(),
                    bar.provider(), bar.sourceUrl(), bar.sourceAvailableAt(), bar.fetchedAt());
        }
        CommodityFetchClient.CommodityBar latestBar = bars.stream()
                .max(Comparator.comparing(CommodityFetchClient.CommodityBar::priceDate))
                .orElseThrow();

        // quoteTime 不得取「本次校正的抓取時刻」，必須是來源自帶的時間戳——額外呼叫一次
        // fetchLiveQuote 取得 meta.regularMarketTime（任務檔 337.6 第 159 行）。
        Optional<CommodityFetchClient.LiveQuote> liveQuote = fetchClient.fetchLiveQuote(code);
        if (liveQuote.isEmpty()) {
            log.warn("油金價收盤校正 {} 已寫入 DB，但取即時報價時間戳失敗，commodity:spot 本輪不更新", code);
            return;
        }
        Instant polledAt = clock.instant();
        cacheWriter.applySettlement(
                code,
                latestBar.closePrice(),
                latestBar.priceDate(),
                liveQuote.get().quoteTime(),
                liveQuote.get().sourcePreviousClose(),
                liveQuote.get().dayHigh(),
                liveQuote.get().dayLow(),
                liveQuote.get().provider(),
                liveQuote.get().sourceUrl(),
                polledAt);
    }
}
