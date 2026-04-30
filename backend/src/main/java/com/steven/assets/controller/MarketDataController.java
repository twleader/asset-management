package com.steven.assets.controller;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.DividendHistoryService;
import com.steven.assets.service.HistoricalDataService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PriceStreamService;
import com.steven.assets.service.StockPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final StockPriceService stockPriceService;
    private final HistoricalDataService historicalDataService;
    private final PriceStreamService priceStreamService;
    private final DividendHistoryService dividendHistoryService;

    /**
     * 取得交易日曆假日（台股：TWSE Open API；美股：NYSE 規則計算）
     * GET /api/market-data/holidays?year=2026
     */
    @GetMapping("/holidays")
    public Map<String, Object> getHolidays(@RequestParam int year) {
        return Map.of(
            "tw", marketDataService.getTwHolidays(year),
            "us", marketDataService.getUsHolidays(year)
        );
    }

    /**
     * 查詢股票配息率
     * GET /api/market-data/dividend-rate?code=0050&market=台股
     */
    @GetMapping("/dividend-rate")
    public ResponseEntity<MarketDataService.DividendRateResult> getDividendRate(
            @RequestParam String code,
            @RequestParam String market) {
        return ResponseEntity.ok(marketDataService.getDividendRate(code, market));
    }

    /**
     * 取得所有持股的最新價格（自 Redis live cache 讀取，由 price-service 排程更新；miss 則 fallback 至歷史表）
     * GET /api/market-data/prices
     */
    @GetMapping("/prices")
    public List<StockPriceService.StockPriceDto> getAllPrices() {
        return stockPriceService.getAllPrices();
    }

    /**
     * 即時推送股價更新（SSE）。
     * 訂閱來源：Redis pub/sub channel `price-update`，由 price-service 寫 Redis 時 publish。
     * 連線後不主動推 snapshot；前端須先打 /prices 拿初始狀態，之後 onmessage 增量更新。
     * 每 30 秒送一筆 keep-alive heartbeat（comment SSE event）避免中介 proxy 切連線。
     */
    @GetMapping(value = "/prices/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamPrices() {
        Flux<ServerSentEvent<String>> updates = priceStreamService.stream()
                .map(json -> ServerSentEvent.<String>builder()
                        .event("price-update")
                        .data(json)
                        .build());
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(i -> ServerSentEvent.<String>builder().comment("keep-alive").build());
        return Flux.merge(updates, heartbeat);
    }

    /**
     * 取得台股/美股市場狀態（是否開盤中）
     * GET /api/market-data/market-status
     */
    @GetMapping("/market-status")
    public Map<String, Object> getMarketStatus() {
        return stockPriceService.getMarketStatus();
    }

    /**
     * 即時資產估算：最新快照持倉 × 當前快取股價
     * GET /api/market-data/live-assets
     */
    @GetMapping("/live-assets")
    public ResponseEntity<StockPriceService.LiveAssetsResponse> getLiveAssets() {
        StockPriceService.LiveAssetsResponse response = stockPriceService.getLiveAssets();
        if (response == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(response);
    }

    /**
     * 手動觸發股價更新（不限交易時間）
     * POST /api/market-data/prices/refresh
     */
    @PostMapping("/prices/refresh")
    public Map<String, Object> refreshPrices() {
        return stockPriceService.manualRefresh();
    }

    /**
     * ETF 成分持股
     * GET /api/market-data/etf-holdings?code=0050&market=台股
     */
    @GetMapping("/etf-holdings")
    public ResponseEntity<MarketDataService.EtfHoldingsResult> getEtfHoldings(
            @RequestParam String code,
            @RequestParam String market) {
        return ResponseEntity.ok(marketDataService.getEtfHoldings(code, market));
    }

    /**
     * 最近 N 年股利（由 DB 讀取，每日 17:00 TW cron 同步；DB 空則一次性 fallback 抓 + 寫）
     * GET /api/market-data/dividends?code=0050&market=台股&years=10
     */
    @GetMapping("/dividends")
    public ResponseEntity<MarketDataService.DividendHistoryResult> getDividendHistory(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam(defaultValue = "10") int years) {
        return ResponseEntity.ok(dividendHistoryService.findFromDb(code, market, years));
    }

    // ===== 歷史收盤價 =====

    /**
     * 回補所有持股歷史收盤價 + USD 匯率
     * POST /api/market-data/history/backfill
     */
    @PostMapping("/history/backfill")
    public Map<String, Object> backfillAll() {
        return historicalDataService.backfillAll();
    }

    /**
     * 回補單一股票歷史收盤價（供前端輸入代號後即時補齊）
     * POST /api/market-data/history/backfill-stock?code=0050&market=台股&since=2020-04-01&until=2020-05-25
     */
    @PostMapping("/history/backfill-stock")
    public Map<String, Object> backfillSingleStock(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until) {
        if (since == null) since = LocalDate.now().minusYears(10);
        return historicalDataService.backfillSingleStock(code, market, since, until);
    }

    /**
     * 查詢單一股票歷史收盤價
     * GET /api/market-data/history/stock?code=0050&market=台股&start=2024-01-01&end=2026-04-06
     */
    @GetMapping("/history/stock")
    public List<StockPriceHistory> getStockHistory(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return historicalDataService.getStockHistory(code, market, start, end);
    }

    /**
     * 批次查詢多支股票在指定快照日期的收盤價
     * POST /api/market-data/history/prices-on-date?date=2026-04-09
     * Body: [{"code":"0050","market":"台股"}, ...]
     */
    @PostMapping("/history/prices-on-date")
    public List<HistoricalDataService.SnapshotPriceDto> getPricesOnDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody List<Map<String, String>> stocks) {
        return historicalDataService.getPricesOnDate(stocks, date);
    }

    // ===== 匯率 =====

    /**
     * 查詢匯率歷史 (預設 USD)
     * GET /api/market-data/exchange-rate?currency=USD&start=2020-01-01&end=2026-04-06
     */
    @GetMapping("/exchange-rate")
    public List<ExchangeRateHistory> getExchangeRateHistory(
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        if (start == null && end == null) {
            return historicalDataService.getAllExchangeRates(currency);
        }
        if (start == null) start = LocalDate.now().minusYears(10);
        if (end == null) end = LocalDate.now();
        return historicalDataService.getExchangeRateHistory(currency, start, end);
    }

    /**
     * 取得指定日期（或之前最近）的歷史匯率
     * GET /api/market-data/exchange-rate/on-date?currency=USD&date=2025-01-15
     */
    @GetMapping("/exchange-rate/on-date")
    public ResponseEntity<ExchangeRateHistory> getExchangeRateOnDate(
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return historicalDataService.getExchangeRateOnDate(currency, date)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 取得最新匯率（資料庫中最近一筆）
     * GET /api/market-data/exchange-rate/latest?currency=USD
     */
    @GetMapping("/exchange-rate/latest")
    public ResponseEntity<ExchangeRateHistory> getLatestExchangeRate(
            @RequestParam(defaultValue = "USD") String currency) {
        return historicalDataService.getLatestExchangeRate(currency)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 手動觸發匯率刷新：FinMind 回補近期缺漏 + 台灣銀行即時牌告抓取今日匯率
     * POST /api/market-data/exchange-rate/refresh
     */
    @PostMapping("/exchange-rate/refresh")
    public Map<String, Object> refreshExchangeRate(
            @RequestParam(defaultValue = "USD") String currency) {
        int backfilled = historicalDataService.backfillExchangeRate(currency, LocalDate.now().minusDays(10));
        historicalDataService.fetchBotExchangeRate(currency);
        // 每次刷新時順便清理超過 10 年的舊資料
        historicalDataService.purgeOldExchangeRates(currency, 10);
        return Map.of("backfilled", backfilled, "currency", currency, "botFetched", true);
    }

    /**
     * 強制補齊指定日期之後的歷史匯率（補齊中間缺漏，不受 maxDate 限制）
     * POST /api/market-data/exchange-rate/backfill-history?currency=USD&since=2021-01-01
     */
    @PostMapping("/exchange-rate/backfill-history")
    public Map<String, Object> backfillExchangeRateHistory(
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(required = false) LocalDate since) {
        if (since == null) since = LocalDate.now().minusYears(10);
        int count = historicalDataService.backfillExchangeRateFrom(currency, since);
        return Map.of("backfilled", count, "currency", currency, "since", since.toString());
    }
}
