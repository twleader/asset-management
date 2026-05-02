package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalDataService {

    private final StockPriceHistoryRepository priceHistRepo;
    private final PriceQueryService priceQuery;
    private final ExchangeRateHistoryRepository rateHistRepo;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockRepository stockMasterRepo;
    private final com.steven.assets.repository.FundMasterRepository fundMasterRepo;

    /** FinMind API token（免費註冊，未設定時走匿名額度，超過會回 402） */
    @Value("${finmind.token:${FINMIND_TOKEN:}}")
    private String finmindToken;

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // Yahoo Finance crumb 認證
    private volatile String yahooCrumb = null;
    private volatile boolean yahooCookieInitialized = false;

    // ═══════════════════════════════════════════════════════════════════════
    //  台股歷史收盤價 — FinMind TaiwanStockPrice
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional
    public int backfillTwStock(String stockCode, LocalDate since) {
        return backfillTwStock(stockCode, since, null);
    }

    @Transactional
    public int backfillTwStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate maxDate = priceHistRepo.findMaxTradingDate(stockCode, "台股").orElse(null);
        LocalDate minDate = priceHistRepo.findMinTradingDate(stockCode, "台股").orElse(null);
        // 若 since 早於目前最早紀錄（向前缺漏），從 since 開始；否則從 maxDate+1 向後補齊
        LocalDate start;
        if (maxDate == null) {
            start = since;
        } else if (minDate != null && since.isBefore(minDate)) {
            start = since; // 有向前缺漏，從 since 重新拉（重複資料由 findBy... 判斷跳過）
        } else {
            start = maxDate.plusDays(1); // 只需向後補齊
        }
        LocalDate end = (until != null) ? until : LocalDate.now();
        if (!start.isBefore(end)) return 0;

        log.info("回補台股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        try {
            // 部分特殊 ETF 在 FinMind 需要帶後綴（如 00642U、00637L、00638R）
            // 若用原始代號查不到資料，依序嘗試常見後綴
            List<String> candidates = new ArrayList<>();
            candidates.add(stockCode);
            if (stockCode.matches("\\d{5}")) { // 5位數才嘗試後綴
                candidates.add(stockCode + "U");
                candidates.add(stockCode + "L");
                candidates.add(stockCode + "R");
                candidates.add(stockCode + "B");
            }

            JsonNode data = null;
            String resolvedCode = stockCode;
            for (String candidate : candidates) {
                String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice"
                        + "&data_id=" + candidate + "&start_date=" + start + "&end_date=" + end;
                String body = httpGet(url);
                JsonNode d = mapper.readTree(body).path("data");
                if (d.isArray() && d.size() > 0) {
                    data = d;
                    resolvedCode = candidate;
                    if (!candidate.equals(stockCode)) {
                        log.info("台股 {} 在 FinMind 的完整代號為 {}", stockCode, resolvedCode);
                    }
                    break;
                }
            }
            if (data == null || !data.isArray()) return 0;

            int count = 0;
            for (JsonNode row : data) {
                LocalDate date = LocalDate.parse(row.path("date").asText());
                // 以使用者輸入的原始代號存入 DB（保持一致性）
                if (priceHistRepo.findByStockCodeAndMarketAndTradingDate(stockCode, "台股", date).isPresent())
                    continue;

                priceHistRepo.save(StockPriceHistory.builder()
                        .stockCode(stockCode)
                        .market("台股")
                        .tradingDate(date)
                        .openPrice(decimal(row, "open"))
                        .highPrice(decimal(row, "max"))
                        .lowPrice(decimal(row, "min"))
                        .closePrice(decimal(row, "close"))
                        .volume(row.path("Trading_Volume").asLong(0))
                        .build());
                count++;
            }
            log.info("台股 {} 匯入 {} 筆", stockCode, count);
            return count;
        } catch (Exception e) {
            log.warn("回補台股 {} 失敗: {}", stockCode, e.getMessage());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  美股歷史收盤價 — Yahoo Finance Chart API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 用 curl 呼叫外部 API（避免 Java HttpClient 被 Yahoo 擋）
     * 支援重試：遇到非 JSON 回應（如 429 "Too Many Requests"）會等待後重試
     */
    private String curlGetWithRetry(String url, int maxRetries) throws Exception {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 使用精簡 User-Agent 避免被 Yahoo 限速（長 UA 會觸發 429）
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0",
                    url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            // 檢查是否為有效 JSON（以 { 開頭）
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return body;
            }

            // 非 JSON 回應（可能是 429 "Too Many Requests"）
            if (attempt < maxRetries) {
                long waitMs = (attempt + 1) * 10000L; // 10s, 20s, 30s...
                log.info("Yahoo Finance 回傳非 JSON（可能 429），等待 {}s 後重試 ({}/{})",
                        waitMs / 1000, attempt + 1, maxRetries);
                sleep(waitMs);
            }
        }
        throw new RuntimeException("Yahoo Finance 重試 " + maxRetries + " 次仍失敗");
    }

    /** 盤中分鐘級資料 bar：開盤後某 5 分鐘的 OHLC。 */
    public record IntradayBar(LocalDateTime time, BigDecimal open, BigDecimal high,
                               BigDecimal low, BigDecimal close) {}

    /**
     * 抓取指定股票最近 N 個交易日的 5 分鐘 K 線（用 Yahoo Finance chart API）。
     * 用於警示盤中觸發補抓 — 從 5 分鐘 bar 找出條件第一次成立的精確時點。
     * 台股需 .TW 後綴；美股直接用 ticker。
     */
    public List<IntradayBar> fetchIntraday5m(String stockCode, String market, int daysBack) {
        try {
            String ticker = "美股".equals(market) ? stockCode : stockCode + ".TW";
            String range = Math.max(1, daysBack) + "d";
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + ticker
                    + "?interval=5m&range=" + range;
            String body = curlGetWithRetry(url, 2);

            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            String tz = chart.path("meta").path("exchangeTimezoneName").asText("Asia/Taipei");
            ZoneId zone = ZoneId.of(tz);
            if (!timestamps.isArray()) return List.of();

            List<IntradayBar> bars = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                long ts = timestamps.get(i).asLong();
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                LocalDateTime time = Instant.ofEpochSecond(ts).atZone(zone).toLocalDateTime();
                bars.add(new IntradayBar(time,
                        jsonDecimal(quotes.path("open").path(i)),
                        jsonDecimal(quotes.path("high").path(i)),
                        jsonDecimal(quotes.path("low").path(i)),
                        jsonDecimal(close)));
            }
            return bars;
        } catch (Exception e) {
            log.warn("抓取 {} {} 盤中分鐘資料失敗: {}", market, stockCode, e.getMessage());
            return List.of();
        }
    }

    @Transactional
    public int backfillUsStock(String stockCode, LocalDate since) {
        return backfillUsStock(stockCode, since, null);
    }

    @Transactional
    public int backfillUsStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate maxDate = priceHistRepo.findMaxTradingDate(stockCode, "美股").orElse(null);
        LocalDate minDate = priceHistRepo.findMinTradingDate(stockCode, "美股").orElse(null);
        // 若 since 早於目前最早紀錄（向前缺漏），從 since 開始；否則從 maxDate+1 向後補齊
        LocalDate start;
        if (maxDate == null) {
            start = since;
        } else if (minDate != null && since.isBefore(minDate)) {
            start = since;
        } else {
            start = maxDate.plusDays(1);
        }
        LocalDate end = (until != null) ? until : LocalDate.now();
        if (!start.isBefore(end)) return 0;

        log.info("回補美股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        try {
            // 使用 period1/period2 精確指定範圍（比 range 更精準）
            long period1 = start.atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond();
            long period2 = end.plusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond();
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + stockCode
                    + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d";

            String body = curlGetWithRetry(url, 2);

            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo Finance {} 無資料: {}", stockCode, err.isEmpty() ? "timestamps not found" : err);
                return 0;
            }

            int count = 0;
            for (int i = 0; i < timestamps.size(); i++) {
                long ts = timestamps.get(i).asLong();
                LocalDate date = Instant.ofEpochSecond(ts).atZone(ZoneId.of("America/New_York")).toLocalDate();
                if (date.isBefore(start)) continue;

                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;

                if (priceHistRepo.findByStockCodeAndMarketAndTradingDate(stockCode, "美股", date).isPresent())
                    continue;

                priceHistRepo.save(StockPriceHistory.builder()
                        .stockCode(stockCode)
                        .market("美股")
                        .tradingDate(date)
                        .openPrice(jsonDecimal(quotes.path("open").path(i)))
                        .highPrice(jsonDecimal(quotes.path("high").path(i)))
                        .lowPrice(jsonDecimal(quotes.path("low").path(i)))
                        .closePrice(jsonDecimal(close))
                        .volume(quotes.path("volume").path(i).asLong(0))
                        .build());
                count++;
            }
            log.info("美股 {} 匯入 {} 筆", stockCode, count);
            return count;
        } catch (Exception e) {
            log.warn("回補美股 {} 失敗: {}", stockCode, e.getMessage());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  匯率歷史 — FinMind TaiwanExchangeRate
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 強制從指定日期補齊歷史匯率（忽略現有 maxDate，補齊中間缺漏的區段）
     */
    @Transactional
    public int backfillExchangeRateFrom(String currency, LocalDate since) {
        if (!since.isBefore(LocalDate.now())) return 0;
        log.info("強制回補 {} 匯率: {} ~ today", currency, since);
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate"
                    + "&data_id=" + currency + "&start_date=" + since;
            String body = httpGet(url);
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isArray()) return 0;

            int count = 0;
            for (JsonNode row : data) {
                LocalDate date = LocalDate.parse(row.path("date").asText());
                if (rateHistRepo.findByCurrencyAndRateDate(currency, date).isPresent()) continue;

                BigDecimal buy = decimal(row, "cash_buy");
                BigDecimal sell = decimal(row, "cash_sell");
                // FinMind 對非現金交易幣別（如 ZAR / EUR）cash_buy/cash_sell 為 0（近年）或 -1（舊資料）；fallback 到 spot
                if (buy == null || buy.compareTo(BigDecimal.ZERO) <= 0) buy = decimal(row, "spot_buy");
                if (sell == null || sell.compareTo(BigDecimal.ZERO) <= 0) sell = decimal(row, "spot_sell");
                if (buy == null && sell == null) continue;

                rateHistRepo.save(ExchangeRateHistory.builder()
                        .currency(currency).rateDate(date)
                        .buyRate(buy).sellRate(sell)
                        .build());
                count++;
            }
            log.info("{} 匯率強制補齊 {} 筆", currency, count);
            return count;
        } catch (Exception e) {
            log.warn("強制回補 {} 匯率失敗: {}", currency, e.getMessage());
            return 0;
        }
    }

    @Transactional
    public int backfillExchangeRate(String currency, LocalDate since) {
        LocalDate maxDate = rateHistRepo.findMaxRateDate(currency).orElse(null);
        LocalDate start = maxDate != null ? maxDate.plusDays(1) : since;
        if (!start.isBefore(LocalDate.now())) return 0;

        log.info("回補 {} 匯率: {} ~ today", currency, start);
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate"
                    + "&data_id=" + currency + "&start_date=" + start;
            String body = httpGet(url);
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isArray()) return 0;

            int count = 0;
            for (JsonNode row : data) {
                LocalDate date = LocalDate.parse(row.path("date").asText());
                if (rateHistRepo.findByCurrencyAndRateDate(currency, date).isPresent()) continue;

                BigDecimal buy = decimal(row, "cash_buy");
                BigDecimal sell = decimal(row, "cash_sell");
                // 如果沒有現金買賣，用即期
                if (buy == null) buy = decimal(row, "spot_buy");
                if (sell == null) sell = decimal(row, "spot_sell");
                if (buy == null && sell == null) continue;

                rateHistRepo.save(ExchangeRateHistory.builder()
                        .currency(currency)
                        .rateDate(date)
                        .buyRate(buy)
                        .sellRate(sell)
                        .build());
                count++;
            }
            log.info("{} 匯率匯入 {} 筆", currency, count);
            return count;
        } catch (Exception e) {
            log.warn("回補 {} 匯率失敗: {}", currency, e.getMessage());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  批次回補：所有持股 + 匯率
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 回補單一股票的歷史收盤價（供前端即時補齊用）
     * since：起始日期（含）；until：截止日期（含），null 表示到今日
     */
    public Map<String, Object> backfillSingleStock(String stockCode, String market, LocalDate since, LocalDate until) {
        int count = 0;
        if ("台股".equals(market)) {
            count = backfillTwStock(stockCode, since, until);
        } else {
            count = backfillUsStock(stockCode, since, until);
        }
        return Map.of("stockCode", stockCode, "market", market, "records", count, "since", since.toString());
    }

    /** 向後相容的無 until 版本（供 backfillAll 使用） */
    public Map<String, Object> backfillSingleStock(String stockCode, String market, LocalDate since) {
        return backfillSingleStock(stockCode, market, since, null);
    }

    public Map<String, Object> backfillAll() {
        LocalDate twoYearsAgo = LocalDate.now().minusYears(10);
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        collectAllHeldCodes(twCodes, usCodes);

        int twTotal = 0, usTotal = 0;
        for (String code : twCodes) {
            twTotal += backfillTwStock(code, twoYearsAgo);
            sleep(600); // 避免被限速
        }
        for (String code : usCodes) {
            usTotal += backfillUsStock(code, twoYearsAgo);
            sleep(2000); // Yahoo Finance 需要較長間隔避免 429
        }

        int rateTotal = backfillExchangeRate("USD", LocalDate.now().minusYears(10));

        return Map.of("twRecords", twTotal, "usRecords", usTotal,
                "exchangeRateRecords", rateTotal,
                "twStocks", twCodes.size(), "usStocks", usCodes.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  每日排程：收盤後更新當日收盤價 + 匯率
    //  台股：每日 14:00 (收盤後 30 分鐘)
    //  美股：每日 06:00 台灣時間 (收盤後)
    //  匯率：每日 17:00
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Taipei")
    public void dailyTwStockUpdate() {
        log.info("排程：更新台股當日收盤價");
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        collectAllHeldCodes(twCodes, usCodes);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        for (String code : twCodes) {
            backfillTwStock(code, today.minusDays(5));
            sleep(500);
        }
    }

    @Scheduled(cron = "0 0 6 * * MON-FRI", zone = "Asia/Taipei")
    public void dailyUsStockUpdate() {
        log.info("排程：更新美股當日收盤價");
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        collectAllHeldCodes(twCodes, usCodes);

        LocalDate recent = LocalDate.now().minusDays(5);
        for (String code : usCodes) {
            backfillUsStock(code, recent);
            sleep(2000); // Yahoo Finance 需要較長間隔避免 429
        }
    }

    /**
     * 啟動時自動補齊：App 重啟後在背景執行，填滿所有持股的歷史缺漏
     * - 若有股票無資料或資料超過 7 天未更新，從 10 年前開始補齊
     * - 已有最新資料的股票，backfillTwStock/backfillUsStock 會自動從 maxDate+1 開始，幾乎不耗時
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startupBackfill() {
        Thread.ofVirtual().name("startup-backfill").start(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
            log.info("啟動補齊：開始檢查所有持股歷史價格缺漏...");
            LocalDate since = LocalDate.now().minusYears(10);
            LocalDate staleThreshold = LocalDate.now().minusDays(7);

            Set<String> twCodes = new LinkedHashSet<>();
            Set<String> usCodes = new LinkedHashSet<>();
            collectAllHeldCodes(twCodes, usCodes);

            for (String code : twCodes) {
                LocalDate maxDate = priceHistRepo.findMaxTradingDate(code, "台股").orElse(null);
                LocalDate minDate = priceHistRepo.findMinTradingDate(code, "台股").orElse(null);
                boolean needsFill = maxDate == null
                        || maxDate.isBefore(staleThreshold)
                        || (minDate != null && since.isBefore(minDate));
                if (needsFill) {
                    log.info("啟動補齊台股 {} (maxDate={}, minDate={})", code, maxDate, minDate);
                    backfillTwStock(code, since);
                    sleep(600);
                }
            }
            for (String code : usCodes) {
                LocalDate maxDate = priceHistRepo.findMaxTradingDate(code, "美股").orElse(null);
                LocalDate minDate = priceHistRepo.findMinTradingDate(code, "美股").orElse(null);
                boolean needsFill = maxDate == null
                        || maxDate.isBefore(staleThreshold)
                        || (minDate != null && since.isBefore(minDate));
                if (needsFill) {
                    log.info("啟動補齊美股 {} (maxDate={}, minDate={})", code, maxDate, minDate);
                    backfillUsStock(code, since);
                    sleep(2000);
                }
            }

            // 匯率：對所有需追蹤幣別（USD + fund_master 上的非 TWD 幣別）若最早資料晚於 10 年前，強制從 10 年前回補
            for (String currency : currenciesToTrack()) {
                LocalDate rateMinDate = rateHistRepo.findMinDate(currency).orElse(null);
                if (rateMinDate == null || since.isBefore(rateMinDate)) {
                    log.info("啟動補齊 {} 匯率 (minDate={}，補齊至 {})", currency, rateMinDate, since);
                    backfillExchangeRateFrom(currency, since);
                }
            }
            log.info("啟動補齊完成");
        });
    }

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
    public void dailyExchangeRateUpdate() {
        log.info("排程：更新匯率（收盤後，FinMind 回補 + 清理舊資料）");
        for (String currency : currenciesToTrack()) {
            backfillExchangeRate(currency, LocalDate.now().minusDays(5));
            purgeOldExchangeRates(currency, 10);
        }
        purgeOldStockPriceHistory(10);
    }

    /**
     * 系統需追蹤的非 TWD 計價幣別集合：股票美股 USD + fund_master 上所有非 TWD 幣別 (Requirement 19)。
     */
    private java.util.Set<String> currenciesToTrack() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        set.add("USD");
        for (com.steven.assets.model.FundMaster fm : fundMasterRepo.findByActiveTrue()) {
            String c = fm.getCurrency();
            if (c != null && !c.isBlank() && !"TWD".equalsIgnoreCase(c)) {
                set.add(c.toUpperCase());
            }
        }
        return set;
    }

    /**
     * 清除超過指定年數的股價歷史資料（所有市場）
     */
    @Transactional
    public void purgeOldStockPriceHistory(int keepYears) {
        LocalDate cutoff = LocalDate.now().minusYears(keepYears);
        long deleted = priceHistRepo.deleteByTradingDateBefore(cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆超過 {} 年的股價歷史資料", deleted, keepYears);
        }
    }

    /**
     * 盤中匯率更新：每 5 分鐘從台灣銀行即時牌告抓取 USD 匯率
     * 台灣外匯市場交易時間：週一～五 09:00 ~ 16:00 (台北時間)
     * Cron 在 09:05 ~ 15:55 每 5 分鐘執行（09:00 市場尚未開盤跳過）
     */
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")
    public void intradayExchangeRateUpdate() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Taipei"));
        int hour = now.getHour();
        int minute = now.getMinute();
        // 09:00 整點跳過（市場尚未開盤）
        if (hour == 9 && minute < 5) return;
        log.info("排程：盤中更新匯率（台灣銀行） ({}:{})", hour, String.format("%02d", minute));
        for (String currency : currenciesToTrack()) {
            fetchBotExchangeRate(currency);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  台灣銀行即時匯率 — BOT CSV endpoint
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 從台灣銀行牌告匯率 CSV 取得即時匯率並寫入 ExchangeRateHistory
     * CSV endpoint: https://rate.bot.com.tw/xrt/flcsv/0/day
     * 注意：BOT CSV 回傳 UTF-8 with BOM + CRLF 換行
     * 買入區欄位: 0=幣別, 1=匯率, 2=現金買入, 3=即期買入
     * 賣出區欄位: 11=匯率, 12=現金賣出, 13=即期賣出
     * 使用 curl 避免 Java HttpClient 被 BOT 擋
     */
    @Transactional
    public void fetchBotExchangeRate(String currency) {
        try {
            String url = "https://rate.bot.com.tw/xrt/flcsv/0/day";
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0",
                    url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String csvBody = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            if (csvBody == null || csvBody.trim().isEmpty()) {
                log.warn("台灣銀行 CSV 回傳空白");
                return;
            }

            // 移除 UTF-8 BOM，統一換行符
            csvBody = csvBody.replace("\uFEFF", "");
            String[] lines = csvBody.split("\\r?\\n");

            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith(currency + ",")) continue;

                String[] cols = trimmed.split(",");
                if (cols.length < 14) {
                    log.warn("台灣銀行 CSV {} 欄位不足: {} 欄", currency, cols.length);
                    return;
                }

                // 即期買入 = index 3, 即期賣出 = index 13
                BigDecimal spotBuy = parseBotDecimal(cols[3]);
                BigDecimal spotSell = parseBotDecimal(cols[13]);

                if (spotBuy == null || spotSell == null) {
                    log.warn("台灣銀行 CSV {} 即期匯率解析失敗: buy=[{}], sell=[{}]", currency, cols[3], cols[13]);
                    return;
                }

                LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
                ExchangeRateHistory record = rateHistRepo.findByCurrencyAndRateDate(currency, today)
                        .orElse(ExchangeRateHistory.builder().currency(currency).rateDate(today).build());

                record.setBuyRate(spotBuy);
                record.setSellRate(spotSell);
                rateHistRepo.save(record);
                log.info("台灣銀行 {} 匯率: buy={}, sell={}, mid={} ({})",
                        currency, spotBuy, spotSell, record.getMidRate(), today);
                return;
            }
            log.warn("台灣銀行 CSV 找不到 {} 的匯率資料", currency);
        } catch (Exception e) {
            log.warn("台灣銀行匯率抓取失敗 ({}): {}", currency, e.getMessage());
        }
    }

    private BigDecimal parseBotDecimal(String value) {
        if (value == null) return null;
        String trimmed = value.trim().replaceAll("[^0-9.]", "");
        if (trimmed.isEmpty()) return null;
        try {
            return new BigDecimal(trimmed).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 清除超過指定年數的匯率歷史資料
     */
    @Transactional
    public void purgeOldExchangeRates(String currency, int keepYears) {
        LocalDate cutoff = LocalDate.now().minusYears(keepYears);
        long deleted = rateHistRepo.deleteByCurrencyAndRateDateBefore(currency, cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆超過 {} 年的 {} 匯率資料", deleted, keepYears, currency);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  查詢 API
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<StockPriceHistory> getStockHistory(String stockCode, String market, LocalDate start, LocalDate end) {
        List<StockPriceHistory> history = new ArrayList<>(
            priceHistRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(stockCode, market, start, end)
        );

        // 若查詢範圍包含今天，從 StockPrice 快取補上今日即時價
        ZoneId tz = "美股".equals(market) ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Taipei");
        LocalDate today = LocalDate.now(tz);
        if (!end.isBefore(today) && !today.isBefore(start)) {
            boolean alreadyHasToday = history.stream().anyMatch(h -> h.getTradingDate().equals(today));
            if (!alreadyHasToday) {
                priceQuery.getLive(stockCode, market).ifPresent(sp -> {
                    if (sp.price() != null && sp.tradingDate() != null && today.toString().equals(sp.tradingDate())) {
                        history.add(StockPriceHistory.builder()
                            .stockCode(stockCode)
                            .market(market)
                            .tradingDate(today)
                            .closePrice(sp.price())
                            .build());
                    }
                });
            }
        }

        return history;
    }

    /**
     * 批次查詢多支股票在指定日期（或最近之前）的收盤價
     * 回傳 Map: "市場_代號" -> 收盤價
     */
    public record SnapshotPriceDto(String stockCode, String market, BigDecimal price, String tradingDate) {}

    @Transactional(readOnly = true)
    public List<SnapshotPriceDto> getPricesOnDate(List<Map<String, String>> stocks, LocalDate date) {
        List<SnapshotPriceDto> result = new ArrayList<>();
        for (Map<String, String> s : stocks) {
            String code = s.get("code");
            String mktStr = s.get("market");
            priceHistRepo.findClosestPrice(code, mktStr, date).ifPresent(h ->
                result.add(new SnapshotPriceDto(
                    code, mktStr,
                    h.getClosePrice(),
                    h.getTradingDate().toString()
                ))
            );
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateHistory> getExchangeRateHistory(String currency, LocalDate start, LocalDate end) {
        return rateHistRepo.findByCurrencyAndRateDateBetweenOrderByRateDateAsc(currency, start, end);
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateHistory> getAllExchangeRates(String currency) {
        return rateHistRepo.findByCurrencyOrderByRateDateAsc(currency);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ExchangeRateHistory> getLatestExchangeRate(String currency) {
        return rateHistRepo.findClosestRate(currency, LocalDate.now());
    }

    /** 取得指定日期或之前最近一筆匯率（用於歷史交易日匯率查詢） */
    @Transactional(readOnly = true)
    public java.util.Optional<ExchangeRateHistory> getExchangeRateOnDate(String currency, LocalDate date) {
        return rateHistRepo.findClosestRate(currency, date);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 收集所有需要回補歷史價格的股票代號：
     * 來源 = stock 主檔（含曾持有、觀察清單、警示）∪ 歷史快照中的持股（保險用）
     */
    private void collectAllHeldCodes(Set<String> twCodes, Set<String> usCodes) {
        for (Stock s : stockMasterRepo.findAll()) {
            if ("美股".equals(s.getMarket())) usCodes.add(s.getCode());
            else twCodes.add(s.getCode());
        }
        // 保險：歷史快照中的持股（避免主檔被誤刪時資料消失）
        List<AssetSnapshot> snapshots = snapshotRepo.findAllWithStocksOrderByDateAsc();
        for (AssetSnapshot s : snapshots) {
            for (StockHolding sh : s.getStocks()) {
                if ("美股".equals(sh.getMarket())) usCodes.add(sh.getStockCode());
                else twCodes.add(sh.getStockCode());
            }
        }
    }

    private String httpGet(String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20));
        if (url.contains("api.finmindtrade.com") && finmindToken != null && !finmindToken.isBlank()) {
            b.header("Authorization", "Bearer " + finmindToken.trim());
        }
        HttpResponse<String> resp = httpClient.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  股票名稱查詢（FinMind TaiwanStockInfo / Yahoo Finance）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 查詢台股名稱（使用 FinMind TaiwanStockInfo）
     * 回傳空字串表示查不到
     */
    public String fetchTwStockName(String code) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockInfo&data_id="
                    + code.trim().toUpperCase();
            String body = httpGet(url);
            JsonNode data = mapper.readTree(body).path("data");
            if (data.isArray() && data.size() > 0) {
                String name = data.get(0).path("stock_name").asText("").trim();
                if (!name.isEmpty() && !name.equalsIgnoreCase(code)) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.warn("FinMind 查詢台股名稱失敗 {}: {}", code, e.getMessage());
        }
        return "";
    }

    /**
     * 查詢美股名稱（使用 Yahoo Finance chart meta）
     * 回傳空字串表示查不到
     */
    public String fetchUsStockName(String code) {
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + code.trim().toUpperCase()
                    + "?interval=1d&range=1d";
            String body = curlGetWithRetry(url, 1);
            JsonNode meta = mapper.readTree(body)
                    .path("chart").path("result").path(0).path("meta");
            String name = meta.path("shortName").asText("").trim();
            if (name.isEmpty()) name = meta.path("longName").asText("").trim();
            if (!name.isEmpty() && !name.equalsIgnoreCase(code)) {
                return name;
            }
        } catch (Exception e) {
            log.warn("Yahoo 查詢美股名稱失敗 {}: {}", code, e.getMessage());
        }
        return "";
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            return new BigDecimal(v.asText()).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal jsonDecimal(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        return BigDecimal.valueOf(v.asDouble()).setScale(4, RoundingMode.HALF_UP);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
