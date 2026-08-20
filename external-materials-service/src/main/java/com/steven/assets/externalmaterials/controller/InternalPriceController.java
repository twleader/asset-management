package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.ClosePersister;
import com.steven.assets.externalmaterials.service.DividendPersister;
import com.steven.assets.externalmaterials.service.ExchangeRatePoller;
import com.steven.assets.externalmaterials.service.FundDividendBackfillService;
import com.steven.assets.externalmaterials.service.FundDividendPoller;
import com.steven.assets.externalmaterials.service.FundNavBackfillService;
import com.steven.assets.externalmaterials.service.FundNavPoller;
import com.steven.assets.externalmaterials.service.HistoricalBackfillService;
import com.steven.assets.externalmaterials.service.MarketDataFetchService;
import com.steven.assets.externalmaterials.service.MarketClock;
import com.steven.assets.externalmaterials.service.PricePoller;
import com.steven.assets.externalmaterials.service.PricePoller.RefreshSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 僅 docker network 內 business-services 呼叫；不對外暴露。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPriceController {

    private final PricePoller poller;
    private final MarketClock clock;
    private final DividendPersister dividendPersister;
    private final FundNavPoller fundNavPoller;
    private final FundDividendPoller fundDividendPoller;
    private final FundNavBackfillService fundNavBackfillService;
    private final FundDividendBackfillService fundDividendBackfillService;
    private final ClosePersister closePersister;
    private final HistoricalBackfillService historicalBackfill;
    private final ExchangeRatePoller exchangeRatePoller;
    private final com.steven.assets.externalmaterials.client.PriceFetchClient priceFetch;
    private final MarketDataFetchService marketData;
    private final com.steven.assets.externalmaterials.client.MacroDataFetchClient macro;
    private final com.steven.assets.externalmaterials.service.IntradayTickStore tickStore;
    private final com.steven.assets.externalmaterials.service.IntradayTickRefresher tickRefresher;
    private final com.steven.assets.externalmaterials.service.StockSourceQuery stockSource;
    private final com.steven.assets.externalmaterials.service.TwTyphoonClosureService typhoonClosure;
    private final com.steven.assets.externalmaterials.service.EtfNavPoller etfNavPoller;
    private final com.steven.assets.externalmaterials.service.TwRadarRefreshService twRadarRefresh;
    private final com.steven.assets.externalmaterials.service.NewsPoller newsPoller;
    private final com.steven.assets.externalmaterials.service.StockFundamentalPoller stockFundamentalPoller;
    private final com.steven.assets.externalmaterials.service.CommodityPricePoller commodityPricePoller;

    /**
     * 同步抓所有持股報價、寫 Redis 後回傳統計。
     * 對應原 backend POST /api/market-data/prices/refresh 的內部觸發。
     */
    @PostMapping("/refresh")
    public RefreshSummary refresh() {
        return poller.refreshAll();
    }

    /**
     * 今日交易雷達手動「重新整理」專用：只抓台股個股 ＋ 大盤 0000（Task 249）。
     *
     * <p>與上方 {@code /refresh} 分開的理由：後者是 {@code refreshAll()}，會連美股／英股一起抓，
     * 而交易雷達只評台股，多抓只會拉長使用者按下按鈕後的等待時間。</p>
     */
    @PostMapping("/refresh/tw-radar")
    public com.steven.assets.externalmaterials.service.TwRadarRefreshService.Summary refreshTwRadar() {
        return twRadarRefresh.refresh();
    }

    /**
     * 抓單檔股利並 append immutable snapshot evidence，回傳 observation 事件筆數。
     */
    @PostMapping("/dividend/sync")
    public Map<String, Object> syncDividend(@RequestParam String code, @RequestParam String market) {
        int n = dividendPersister.syncOne(code, market);
        return Map.of("written", n);
    }

    /**
     * 同步全抓信託基金 NAV 寫入 fund_nav 表（Requirement 19）。
     * Backend / BFF 「刷新最新淨值」按鈕對應內部觸發。
     */
    @PostMapping("/fund-nav/refresh")
    public FundNavPoller.RefreshSummary refreshFundNav() {
        return fundNavPoller.refreshAll();
    }

    /**
     * 同步全抓信託基金配息歷史 (Requirement 20)。
     */
    @PostMapping("/fund-dividend/refresh")
    public FundDividendPoller.RefreshSummary refreshFundDividend() {
        return fundDividendPoller.refreshAll();
    }

    /** 信託基金 NAV 歷史回補 (Requirement 21)。 */
    @PostMapping("/fund-nav/backfill")
    public FundNavBackfillService.BackfillSummary backfillFundNav(
            @RequestParam(defaultValue = "10") int years) {
        return fundNavBackfillService.backfillAll(years);
    }

    /** 信託基金配息歷史回補 (Requirement 21)。 */
    @PostMapping("/fund-dividend/backfill")
    public FundDividendBackfillService.BackfillSummary backfillFundDividend(
            @RequestParam(defaultValue = "10") int years) {
        return fundDividendBackfillService.backfillAll(years);
    }

    /**
     * 公開資訊爬蟲手動「只重產檔案」（Requirement 63 / Task 280）：<b>不抓取、不寫 news_headline</b>，
     * 直接由 DB 產出 {@code public_info_<今日>.json} ＋ {@code .xlsx} 兩份並（啟用時）同步 Drive，
     * {@code trigger} 標為 {@code manual-export}。business 端 {@code POST /api/crawler-export-path/run-now}
     * （限 ADMIN）proxy 至此。
     *
     * <p>與排程輪共用 {@code NewsPoller.running} 旗標：已在跑就回 {@code status=BUSY}、不啟第二輪。
     */
    @PostMapping("/news-poller/export-now")
    public com.steven.assets.externalmaterials.service.NewsPoller.ManualRunResult exportPublicInfoNow() {
        return newsPoller.exportNow();
    }

    /**
     * 公開資訊爬蟲手動「完整跑一輪」（Requirement 63 / Task 280）：抓取 → upsert news_headline →
     * 清理保留期外舊聞 → 產出兩份檔案 → Drive 同步，{@code trigger} 標為 {@code manual}。
     * business 端 {@code POST /api/crawler-export-path/fetch-and-run-now}（限 ADMIN）proxy 至此。
     *
     * <p><b>路徑刻意不叫 {@code run-now}</b>：全庫既有的八個 {@code POST .../run-now} 一律是
     * 「立即匯出、不重新抓資料」，同一字串在兩層反義時接反是靜默的（兩支都產出同名的兩份檔，只差有沒有抓）。
     *
     * <p>典型一輪 3~5 秒，但十餘個來源序列抓取（各 15 秒 request timeout）最壞可達數分鐘；
     * <b>本端點刻意不設上限</b>——上游放棄等待不影響這一輪跑完與寫檔（逾時由 business 端負責回報 RUNNING）。
     */
    @PostMapping("/news-poller/fetch-and-export-now")
    public com.steven.assets.externalmaterials.service.NewsPoller.ManualRunResult fetchAndExportPublicInfoNow() {
        return newsPoller.fetchAndExportNow();
    }

    /**
     * 公開觸發「重新搜尋」（Requirement 71 / Task 329）：{@code fetch-and-export-now} 的免登入版本，
     * 由 business 端 {@code POST /api/crawler-export-path/public-rescan}（免驗證、30 秒全域 Redis
     * 冷卻節流）proxy 呼叫。本端點仍只能經 docker network 呼叫，不映射 host port、不經 Nginx 9090
     * 直接暴露——gateway 只轉送到 business 這一層公開端點。
     */
    @PostMapping("/news-poller/public-rescan")
    public com.steven.assets.externalmaterials.service.NewsPoller.ManualRunResult publicRescanNews() {
        return newsPoller.publicRescan();
    }

    /** 手動觸發個股基本面：public_info 證據由既有新聞路徑提供，本端點只刷新結構化來源與 fallback。 */
    @PostMapping("/fundamentals/refresh")
    public com.steven.assets.externalmaterials.service.StockFundamentalPoller.RefreshSummary refreshFundamentals() {
        return stockFundamentalPoller.refreshNow();
    }

    /** 手動觸發 FinMind 校正當日台股收盤價（同 16:00 排程），覆寫 stock_price_history。 */
    @PostMapping("/close/verify-tw")
    public Map<String, Object> verifyTwClose() {
        int ok = closePersister.verifyTwCloseWithFinMind();
        return Map.of("verified", ok);
    }

    /** 手動觸發 FinMind 校正當日美股收盤價（同 18:00 ET 排程）。 */
    @PostMapping("/close/verify-us")
    public Map<String, Object> verifyUsClose() {
        int ok = closePersister.verifyUsCloseWithFinMind();
        return Map.of("verified", ok);
    }

    /** 回補單支股票歷史收盤價（FinMind 台股 / Yahoo 美股）。 */
    @PostMapping("/backfill/stock")
    public Map<String, Object> backfillStock(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until) {
        return historicalBackfill.backfillSingleStock(code, market, since, until);
    }

    /**
     * 歷史修復（Task 258）：對指定市場與日期區間重抓權威日線並<b>覆寫</b>既有列。
     *
     * <p>與 {@code /backfill/stock} 的差別：後者是增量（起點 {@code maxDate+1}）＋ skip-if-exists，
     * 修不了區間中段已存在的錯誤列。本端點一律覆寫，但仍跳過該市場當日列（今日列獨佔給 ClosePersister）、
     * 且來源查無某日時保留既有列不動。冪等，可重複執行。</p>
     *
     * <p>維護用入口，不做成使用者可按的按鈕；business-services 與 BFF 端刻意不加 proxy。</p>
     */
    @PostMapping("/repair/history")
    public HistoricalBackfillService.RepairSummary repairHistory(
            @RequestParam String market,
            @RequestParam(required = false) String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return historicalBackfill.repairRange(market, code, from, to);
    }

    /** 全持股 + USD 匯率 10 年回補（耗時操作）。 */
    @PostMapping("/backfill/all")
    public Map<String, Object> backfillAll() {
        return historicalBackfill.backfillAll();
    }

    /** 增量補匯率：從 max(rate_date)+1 至今。 */
    @PostMapping("/backfill/exchange-rate")
    public Map<String, Object> backfillExchangeRate(
            @RequestParam String currency,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        int n = historicalBackfill.backfillExchangeRate(currency, since);
        return Map.of("currency", currency, "records", n);
    }

    /** 強制從 since 補匯率（補中間缺漏）。 */
    @PostMapping("/backfill/exchange-rate-from")
    public Map<String, Object> backfillExchangeRateFrom(
            @RequestParam String currency,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        int n = historicalBackfill.backfillExchangeRateFrom(currency, since);
        return Map.of("currency", currency, "records", n);
    }

    /**
     * 手動重抓 ETF 淨值／折溢價寫入 Redis（Task 214）。不限交易時段，供部署後驗證與抓取失敗時補救。
     * 台股一次打證交所全市場彙整檔、美股逐檔問 Yahoo；個股不會有值（資料驅動判定，非白名單）。
     */
    @PostMapping("/etf-nav/refresh")
    public com.steven.assets.externalmaterials.service.EtfNavPoller.RefreshSummary refreshEtfNav() {
        return etfNavPoller.refreshAll();
    }

    /** 增量補油金價：從 max(price_date)+1 至今（Requirement 40）。 */
    @PostMapping("/backfill/commodity")
    public Map<String, Object> backfillCommodity(
            @RequestParam String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        int n = historicalBackfill.backfillCommodity(code, since);
        return Map.of("code", code, "records", n);
    }

    /** 強制從 since 補油金價（首次補滿十年／補中間缺漏，Requirement 40）。 */
    @PostMapping("/backfill/commodity-from")
    public Map<String, Object> backfillCommodityFrom(
            @RequestParam String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        int n = historicalBackfill.backfillCommodityFrom(code, since);
        return Map.of("code", code, "records", n);
    }

    /**
     * 手動觸發一輪油金價即時報價（Requirement 77），與每分鐘排程共用同一段抓取邏輯。
     * 仍受交易時段判定約束：非交易時段回 {@code inSession=false} 且不改 Redis。
     * business 端 {@code POST /api/market-data/commodity/live-refresh} proxy 至此。
     */
    @PostMapping("/commodity/live-refresh")
    public com.steven.assets.externalmaterials.service.CommodityPricePoller.LiveRefreshResult
            liveRefreshCommodity() {
        return commodityPricePoller.liveRefreshNow();
    }

    /** IMF DataMapper 指標查詢（NGDPDPC 人均 GDP / NGDP_RPCH GDP 成長率）。 */
    @GetMapping("/macro/imf")
    public Map<Integer, java.math.BigDecimal> imf(
            @RequestParam String indicator,
            @RequestParam String country,
            @RequestParam(defaultValue = "2") int scale) throws Exception {
        return macro.fetchImf(indicator, country, scale);
    }

    /** 主計總處 DGBAS 國民所得常用資料（台灣官方）：經濟成長率 + 平均每人GDP(美元)，優先於 IMF。 */
    @GetMapping("/macro/dgbas")
    public Map<String, Map<Integer, java.math.BigDecimal>> dgbas() {
        return macro.fetchDgbasNationalIncome();
    }

    /** TWSE 加權指數月線 OHLC（月報整月）。 */
    @GetMapping("/macro/twse-monthly")
    public java.util.List<com.steven.assets.externalmaterials.client.MacroDataFetchClient.DailyOhlc>
        twseMonthly(@RequestParam int year, @RequestParam int month) {
        return macro.fetchTwseMonthlyDaily(year, month);
    }

    /**
     * 除 TWSE 外的 code-keyed 指數近十年每日 OHLC。code ∈ {TPEX,DJI,SPX,SP500TR,IXIC,SOX,FTSE,DAX,KOSPI,N225}；
     * TPEX 為 TPEx 官方逐月 OHLC／成交量例外，其餘代碼走 Yahoo v8 chart（range=10y）。
     */
    @GetMapping("/macro/us-index")
    public java.util.List<com.steven.assets.externalmaterials.client.MacroDataFetchClient.DailyOhlc>
        usIndex(@RequestParam String code) {
        return macro.fetchUsIndexDaily(code);
    }

    /** TWSE 發行量加權股價報酬指數（含息）單日收盤；非交易日 / 查無回 204。 */
    @GetMapping("/macro/twse-return-index")
    public org.springframework.http.ResponseEntity<com.steven.assets.externalmaterials.client.MacroDataFetchClient.TwseReturnIndexPoint>
        twseReturnIndex(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        com.steven.assets.externalmaterials.client.MacroDataFetchClient.TwseReturnIndexPoint point =
                macro.fetchTwseReturnIndexDaily(date);
        return point == null
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(point);
    }

    /**
     * 指數「當日」分時（最新交易日）。market ∈ {TWSE,TPEX,DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}；
     * TPEX 走官方 MIS，其餘市場走 Yahoo 5m。
     */
    @GetMapping("/macro/index-intraday")
    public java.util.List<com.steven.assets.externalmaterials.client.MacroDataFetchClient.IndexIntradayPoint>
        indexIntraday(@RequestParam String market) {
        return macro.fetchIndexIntraday(market);
    }

    /** 殖利率（TWSE / FinMind / NASDAQ 級聯）。 */
    @GetMapping("/dividend-rate")
    public MarketDataFetchService.DividendRateResult dividendRate(
            @RequestParam String code, @RequestParam String market) {
        return marketData.getDividendRate(code, market);
    }

    /** ETF 持股（Yahoo topHoldings + 台股 FinMind fallback）。 */
    @GetMapping("/etf-holdings")
    public MarketDataFetchService.EtfHoldingsResult etfHoldings(
            @RequestParam String code, @RequestParam String market) {
        return marketData.getEtfHoldings(code, market);
    }

    /** 股利歷史。 */
    @GetMapping("/dividend-history")
    public MarketDataFetchService.DividendHistoryResult dividendHistory(
            @RequestParam String code, @RequestParam String market,
            @RequestParam(defaultValue = "10") int years) {
        return marketData.getDividendHistory(code, market, years);
    }

    /** TWSE 假日表（依年份快取，已 union 颱風假 / 臨時休市）。 */
    @GetMapping("/tw-holidays")
    public Map<String, String> twHolidays(@RequestParam int year) {
        return marketData.getTwHolidays(year);
    }

    /** 手動 / 驗證即時偵測台股颱風假（DGPA 停班公告），命中即寫入 tw_market_closure。回傳今日是否休市。 */
    @PostMapping("/tw-closure/detect")
    public Map<String, Object> detectTwClosure() {
        boolean closed = typhoonClosure.detectAndPersistToday();
        return Map.of("closedToday", closed);
    }

    /** 股票名稱查詢（台股 FinMind / 美股 Yahoo / 英股 Yahoo `.L`）。 */
    @GetMapping("/stock-name")
    public Map<String, String> stockName(
            @RequestParam String code, @RequestParam String market) {
        String name;
        if ("台股".equals(market)) name = marketData.fetchTwStockName(code);
        else if ("英股".equals(market)) name = marketData.fetchUkStockName(code);
        else name = marketData.fetchUsStockName(code);
        return Map.of("name", name == null ? "" : name);
    }

    /** 盤中 5 分鐘 K 線（StockAlertService 警示觸發補抓用）。 */
    @GetMapping("/intraday-5m")
    public java.util.List<com.steven.assets.externalmaterials.client.PriceFetchClient.IntradayBar> intraday5m(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam(defaultValue = "5") int daysBack) {
        return priceFetch.fetchIntraday5m(code, market, daysBack);
    }

    /**
     * 「當日」走勢圖分時 tick 序列（盤中 polling 累積 + 盤後外部源覆寫）。
     * date 未指定 → 預設當日交易日：盤中 / 盤後 tick 都寫在「今天」bucket
     * （key {@code price:ticks:{market}:{code}:{今天}}），但今日收盤價要收盤後才進
     * stock_price_history，故盤中直接用 {@code findMaxTradingDate} 會停在前一交易日、
     * 落在空 bucket（前端顯示「無當日分時資料」）。→ 今天是該市場交易日且今日 tick 已有資料
     * 就用今天；否則（週末 / 假日 / 盤前尚無資料）退回最近一個有收盤的交易日。
     * Redis LIST 空且服務側已有完整資料源 → 同步觸發一次 refreshOne 作為 cold-start，回填後再回傳。
     */
    @GetMapping("/intraday-ticks")
    public java.util.List<com.steven.assets.externalmaterials.service.IntradayTickStore.TickPoint> intradayTicks(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate today = LocalDate.now(
                com.steven.assets.externalmaterials.service.MarketClock.zoneOf(market));
        if (date != null) {
            if (date.equals(today) && clock.isTradingDay(market, today)) {
                return todayTicksWithSelfHeal(code, market, today);
            }
            return ticksWithColdStart(code, market, date);
        }
        if (clock.isTradingDay(market, today)) {
            var todayTicks = todayTicksWithSelfHeal(code, market, today);
            if (!todayTicks.isEmpty()) return todayTicks;
        }
        return ticksWithColdStart(code, market,
                stockSource.findMaxTradingDate(code, market).orElse(today));
    }

    private java.util.List<com.steven.assets.externalmaterials.service.IntradayTickStore.TickPoint>
            todayTicksWithSelfHeal(String code, String market, LocalDate today) {
        var ticks = tickStore.getTicks(code, market, today);
        if (com.steven.assets.externalmaterials.service.IntradayTickStore
                .isIncompleteForSession(ticks, market, today)) {
            tickRefresher.refreshOneGuarded(code, market, today, true);
            ticks = tickStore.getTicks(code, market, today);
        }
        return ticks;
    }

    /**
     * 讀當日 tick LIST；空且服務側有完整資料源時同步 cold-start refresh 一次後再讀。
     * target 非交易日（週末 / 國定假日 / 颱風假）時**不** cold-start——該日本無盤，refresh 只會抓到
     * 昨收平盤幻影再度污染（颱風假一體休市，Task 161）；此時退回空序列（前端顯示「無當日分時資料」）。
     * 保護 {@code findMaxTradingDate} 空（該檔無任何歷史）→ {@code .orElse(today)} 落在颱風日的邊角。
     */
    private java.util.List<com.steven.assets.externalmaterials.service.IntradayTickStore.TickPoint>
            ticksWithColdStart(String code, String market, LocalDate target) {
        var ticks = tickStore.getTicks(code, market, target);
        if (ticks.isEmpty() && clock.isTradingDay(market, target)) {
            tickRefresher.refreshOne(code, market, target);
            ticks = tickStore.getTicks(code, market, target);
        }
        return ticks;
    }

    /** 手動觸發 BOT 即期匯率抓取（同盤中 5 分鐘排程）。 */
    @PostMapping("/exchange-rate/refresh-bot")
    public Map<String, Object> refreshBotFx(@RequestParam String currency) {
        boolean ok = exchangeRatePoller.refreshBotNow(currency);
        return Map.of("currency", currency, "refreshed", ok);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "twMarketOpen", clock.isTwMarketOpen(),
                "usMarketOpen", clock.isUsMarketOpen()
        );
    }
}
