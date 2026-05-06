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

    /** TWSE 假日表（依年份快取）。 */
    @GetMapping("/tw-holidays")
    public Map<String, String> twHolidays(@RequestParam int year) {
        return marketData.getTwHolidays(year);
    }

    /** 股票名稱查詢（台股 FinMind / 美股 Yahoo）。 */
    @GetMapping("/stock-name")
    public Map<String, String> stockName(
            @RequestParam String code, @RequestParam String market) {
        String name = "台股".equals(market) ? marketData.fetchTwStockName(code)
                : marketData.fetchUsStockName(code);
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
