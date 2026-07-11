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

    /**
     * 同步抓所有持股報價、寫 Redis 後回傳統計。
     * 對應原 backend POST /api/market-data/prices/refresh 的內部觸發。
     */
    @PostMapping("/refresh")
    public RefreshSummary refresh() {
        return poller.refreshAll();
    }

    /**
     * Backend cold-cache fallback：抓單檔股利 + 寫 stock_dividend_history，回傳寫入筆數。
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

    /** 海外指數近 10 年每日 OHLC（Yahoo v8 chart，range=10y）。code ∈ {DJI,SPX,SP500TR,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
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

    /** 指數「當日」分時（Yahoo 5m，最新交易日）。market ∈ {TWSE,DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
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
        if (date != null) {
            return ticksWithColdStart(code, market, date);
        }
        LocalDate today = LocalDate.now(
                com.steven.assets.externalmaterials.service.MarketClock.zoneOf(market));
        if (clock.isTradingDay(market, today)) {
            var todayTicks = tickStore.getTicks(code, market, today);
            if (!todayTicks.isEmpty()) return todayTicks;
        }
        return ticksWithColdStart(code, market,
                stockSource.findMaxTradingDate(code, market).orElse(today));
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
