package com.steven.assets.controller;

import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.DividendHistoryService;
import com.steven.assets.service.HistoricalDataService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.service.PriceStreamService;
import com.steven.assets.service.StockPriceService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
@Validated
public class MarketDataController {

    // 資安（Requirement 29）：code / market / currency 會被外部行情 client 串進外部 API 的 URL query / path。
    // 以白名單格式驗證，阻擋以 & ? # / % 等 metacharacter 注入外部請求參數（污染共用行情資料）。
    // 違規由 GlobalExceptionHandler 的 ConstraintViolationException 對映為 400。
    private static final String CODE_PATTERN = "^[A-Za-z0-9.\\-]{1,12}$";     // 台股數字 / 美股 ticker（含 . -）
    private static final String MARKET_PATTERN = "^[\\p{L}0-9]{1,10}$";        // 台股 / 美股…（允許 Unicode 文字，禁 URL metachar）
    private static final String CURRENCY_PATTERN = "^[A-Za-z]{3,4}$";          // ISO 4217（USD/JPY…）

    private final MarketDataService marketDataService;
    private final StockPriceService stockPriceService;
    private final HistoricalDataService historicalDataService;
    private final PriceStreamService priceStreamService;
    private final DividendHistoryService dividendHistoryService;
    private final com.steven.assets.service.ExcelExportService excelExportService;
    private final com.steven.assets.service.TechnicalIndicatorService technicalIndicatorService;
    private final com.steven.assets.service.CommodityLiveQuoteService commodityLiveQuoteService;

    /**
     * 取得交易日曆假日（台股：TWSE 優先、完整 DGPA 行事曆暫行；美股：NYSE 規則計算）
     * GET /api/market-data/holidays?year=2026
     */
    @GetMapping("/holidays")
    public Map<String, Object> getHolidays(@RequestParam int year) {
        return Map.of(
            "tw", marketDataService.getTwHolidays(year),
            "us", marketDataService.getUsHolidays(year),
            "uk", marketDataService.getUkHolidays(year)
        );
    }

    /**
     * 查詢股票配息率
     * GET /api/market-data/dividend-rate?code=0050&market=台股
     */
    @GetMapping("/dividend-rate")
    public ResponseEntity<MarketDataService.DividendRateResult> getDividendRate(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market) {
        return ResponseEntity.ok(marketDataService.getDividendRate(code, market));
    }

    /** 行情五檔展示的富邦十秒 cached snapshot；controller 僅驗參數並代理。 */
    @GetMapping("/quote-detail")
    public ResponseEntity<QuoteDetailDto.Response> getQuoteDetail(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market) {
        return ResponseEntity.ok(marketDataService.getQuoteDetail(code, market));
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
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market) {
        return ResponseEntity.ok(marketDataService.getEtfHoldings(code, market));
    }

    /**
     * 最近 N 年股利（由 DB 讀取，每日 17:00 TW cron 同步；DB 空則一次性 fallback 抓 + 寫）
     * GET /api/market-data/dividends?code=0050&market=台股&years=10
     */
    @GetMapping("/dividends")
    public ResponseEntity<MarketDataService.DividendHistoryResult> getDividendHistory(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam(defaultValue = "10") int years) {
        return ResponseEntity.ok(dividendHistoryService.findFromDb(code, market, years));
    }

    /**
     * 純讀最近 N 年股利（績效比較頁 Requirement 33）：只讀 stock_dividend_history、回「裸陣列」，
     * DB 空即回 []，**絕不觸發 cold-cache 抓取寫入**（與 /dividends 的差別）。
     * GET /api/market-data/dividends-readonly?code=0050&market=台股&years=10
     */
    @GetMapping("/dividends-readonly")
    public List<MarketDataService.DividendRow> getDividendHistoryReadOnly(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam(defaultValue = "10") int years) {
        return dividendHistoryService.findFromDbReadOnly(code, market, years).rows();
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
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
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
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return historicalDataService.getStockHistory(code, market, start, end);
    }

    /**
     * 走勢圖技術指標整段序列（Task 261；StockAnalysisDialog 走勢圖用，經 BFF chart-series 聚合）
     * GET /api/market-data/indicators/series?code=0050&market=台股&start=2016-07-31&end=2026-07-31
     *
     * 與單點 /api/settings 系列無關；此序列與 TechnicalIndicatorService.computeAll() 同源，
     * end 已到該市場今日時尾筆逐位等於 computeAll()（走勢圖與觀察清單表格同源的機械判準）。
     */
    @GetMapping("/indicators/series")
    public List<com.steven.assets.service.TechnicalIndicatorService.IndicatorPoint> getIndicatorSeries(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return technicalIndicatorService.indicatorSeries(code, market, start, end);
    }

    /**
     * 「當日」走勢圖分時 tick 序列（StockAnalysisDialog 走勢圖「當日」期間用）。
     * GET /api/market-data/intraday-ticks?code=0050&market=台股&date=2026-06-05
     * Proxy 至 external-materials-service /internal/intraday-ticks。
     * Redis LIST `price:ticks:{market}:{code}:{date}`，盤中 polling 累積 + 盤後外部源覆寫。
     */
    @GetMapping("/intraday-ticks")
    public List<HistoricalDataService.IntradayTick> getIntradayTicks(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return historicalDataService.fetchIntradayTicks(code, market, date);
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
            @RequestParam(defaultValue = "USD") @Pattern(regexp = CURRENCY_PATTERN, message = "幣別格式不合法") String currency,
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
            @RequestParam(defaultValue = "USD") @Pattern(regexp = CURRENCY_PATTERN, message = "幣別格式不合法") String currency,
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
            @RequestParam(defaultValue = "USD") @Pattern(regexp = CURRENCY_PATTERN, message = "幣別格式不合法") String currency) {
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
            @RequestParam(defaultValue = "USD") @Pattern(regexp = CURRENCY_PATTERN, message = "幣別格式不合法") String currency) {
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
            @RequestParam(defaultValue = "USD") @Pattern(regexp = CURRENCY_PATTERN, message = "幣別格式不合法") String currency,
            @RequestParam(required = false) LocalDate since) {
        if (since == null) since = LocalDate.now().minusYears(10);
        int count = historicalDataService.backfillExchangeRateFrom(currency, since);
        return Map.of("backfilled", count, "currency", currency, "since", since.toString());
    }

    /**
     * 匯率區間匯出成單一 .xlsx（日期／即期買入／即期賣出／中間價四欄）（Requirement 42 / Task 204）
     * GET /api/market-data/exchange-rate/export?currency=USD&start=2020-01-01&end=2026-07-17
     * 預設回近 10 年。全域公開資料，無 owner 過濾。
     */
    @GetMapping("/exchange-rate/export")
    public ResponseEntity<org.springframework.core.io.ByteArrayResource> exportExchangeRates(
            @RequestParam(defaultValue = "USD") @Pattern(regexp = CURRENCY_PATTERN, message = "幣別格式不合法") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end)
            throws java.io.IOException {
        LocalDate[] range = normalizeTenYearRange(start, end);
        byte[] data = excelExportService.exportExchangeRates(currency, range[0], range[1]);
        java.time.format.DateTimeFormatter fileFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        String filename = com.steven.assets.service.ExcelExportService.exchangeRateLabel(currency)
                + "_" + fileFmt.format(range[0]) + "_" + fileFmt.format(range[1]) + ".xlsx";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new org.springframework.core.io.ByteArrayResource(data));
    }

    // ===== 油價金價（Requirement 40 / Task 202）=====

    /**
     * 三標的（WTI / BRENT / GOLD）區間每日收盤價
     * GET /api/market-data/commodity?start=2016-07-18&end=2026-07-17
     * 預設回近 10 年。全域公開行情，無 owner 過濾。
     */
    @GetMapping("/commodity")
    public Map<String, List<CommodityPriceHistory>> getCommodityHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LocalDate[] range = normalizeTenYearRange(start, end);
        return historicalDataService.getCommodityHistory(range[0], range[1]);
    }

    /**
     * 手動觸發油金價刷新：三標的增量回補 + 清理 10 年前資料
     * POST /api/market-data/commodity/refresh
     */
    @PostMapping("/commodity/refresh")
    public Map<String, Object> refreshCommodities() {
        Map<String, Object> backfilled = historicalDataService.refreshCommodities();
        return Map.of("backfilled", backfilled);
    }

    /**
     * 三標的盤中即時報價（Requirement 77 / Task 337）：唯讀 Redis cache ＋ DB 前收聚合出漲跌。
     * GET /api/market-data/commodity/live
     * 非交易時段或缺 key 時 marketOpen=false／該標的為 null，不代表錯誤。
     */
    @GetMapping("/commodity/live")
    public com.steven.assets.service.CommodityLiveQuoteService.LiveQuotesResponse getCommodityLive() {
        return commodityLiveQuoteService.getLiveQuotes();
    }

    /**
     * 手動觸發油金價「即時報價」刷新（與既有 {@code /commodity/refresh} 分開，見 337.12 理由）：
     * proxy 至 ext-materials-service {@code POST /internal/commodity/live-refresh} 跑一輪即時抓取。
     * POST /api/market-data/commodity/live-refresh
     */
    @PostMapping("/commodity/live-refresh")
    public com.steven.assets.service.HistoricalDataService.LiveRefreshResult liveRefreshCommodities() {
        return historicalDataService.liveRefreshCommodities();
    }

    /**
     * 油金價區間匯出成單一 .xlsx（日期／WTI／Brent／黃金四欄）
     * GET /api/market-data/commodity/export?start=2020-01-01&end=2026-07-17
     */
    @GetMapping("/commodity/export")
    public ResponseEntity<org.springframework.core.io.ByteArrayResource> exportCommodities(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end)
            throws java.io.IOException {
        LocalDate[] range = normalizeTenYearRange(start, end);
        byte[] data = excelExportService.exportCommodityPrices(range[0], range[1]);
        java.time.format.DateTimeFormatter fileFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        String filename = "油價金價_" + fileFmt.format(range[0]) + "_" + fileFmt.format(range[1]) + ".xlsx";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new org.springframework.core.io.ByteArrayResource(data));
    }

    /** 補預設值（近 10 年）並驗證區間；start 晚於 end 直接擋為 400。 */
    private LocalDate[] normalizeTenYearRange(LocalDate start, LocalDate end) {
        if (start == null) start = LocalDate.now().minusYears(10);
        if (end == null) end = LocalDate.now();
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日期不可晚於結束日期：" + start + " > " + end);
        }
        return new LocalDate[] { start, end };
    }
}
