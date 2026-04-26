package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockPriceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MarketDataService {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final StockPriceRepository stockPriceRepo;
    private final StockPriceHistoryRepository stockPriceHistoryRepo;

    /** Yahoo Finance crumb（session 期間有效） */
    private volatile String yahooCrumb = null;

    public MarketDataService(StockPriceRepository stockPriceRepo,
                             StockPriceHistoryRepository stockPriceHistoryRepo) {
        this.stockPriceRepo = stockPriceRepo;
        this.stockPriceHistoryRepo = stockPriceHistoryRepo;
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cm)
                .build();
        this.mapper = new ObjectMapper();
    }

    public record DividendRateResult(
            String stockCode,
            String market,
            BigDecimal dividendRate,
            String source,
            String description,
            String stockName
    ) {
        /** 向後相容：不帶 stockName 的建構 */
        public DividendRateResult(String stockCode, String market, BigDecimal dividendRate,
                                  String source, String description) {
            this(stockCode, market, dividendRate, source, description, null);
        }
    }

    public record EtfHolding(
            String stockCode,
            String stockName,
            BigDecimal weight,        // 持股占比（%）
            BigDecimal shares         // 持股數（原始資料常無此欄位，可為 null）
    ) {}

    public record EtfHoldingsResult(
            String stockCode,
            String market,
            boolean supported,
            String source,
            String asOfDate,
            String message,
            List<EtfHolding> holdings
    ) {}

    public record DividendRow(
            Integer year,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            String exDividendDate,         // 除息日 YYYY-MM-DD
            BigDecimal yieldPct,           // 當年殖利率（%），可為 null
            String cashPaymentDate,        // 現金股利發放日
            String stockPaymentDate,       // 股票股利發放日
            Integer fillDays,              // 填息天數（尚未填息為 null）
            BigDecimal previousClose       // 除息日前一交易日收盤價（無資料為 null）
    ) {
        /** 向後相容：舊建構（無 payment dates / fillDays / previousClose） */
        public DividendRow(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                           String exDividendDate, BigDecimal yieldPct) {
            this(year, cashDividend, stockDividend, exDividendDate, yieldPct, null, null, null, null);
        }
    }

    /** 除息日參考資料：前一交易日收盤價 + 填息天數 */
    private record DividendBasis(BigDecimal previousClose, Integer fillDays) {
        static final DividendBasis EMPTY = new DividendBasis(null, null);
    }

    public record DividendHistoryResult(
            String stockCode,
            String market,
            String source,
            String message,
            List<DividendRow> rows
    ) {}

    public record PriceResult(
            String stockCode,
            String market,
            BigDecimal price,
            BigDecimal change,
            BigDecimal changePct,
            String source,
            String stockName,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal previousClose,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume
    ) {
        /** 向後相容：不帶 stockName 的建構 */
        public PriceResult(String stockCode, String market, BigDecimal price,
                           BigDecimal change, BigDecimal changePct, String source) {
            this(stockCode, market, price, change, changePct, source, null,
                    null, null, null, null, null, null, null);
        }

        /** 向後相容：帶 stockName 但不帶詳細報價的建構 */
        public PriceResult(String stockCode, String market, BigDecimal price,
                           BigDecimal change, BigDecimal changePct, String source, String stockName) {
            this(stockCode, market, price, change, changePct, source, stockName,
                    null, null, null, null, null, null, null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 股票最新股價（含漲跌）
    // 台股：TWSE mis API → Yahoo Finance .TW → .TWO
    // 美股：NASDAQ info API → Yahoo Finance
    // ─────────────────────────────────────────────────────────────────────────
    public PriceResult getStockPrice(String stockCode, String market) {
        if ("台股".equals(market)) {
            Optional<PriceResult> twse = getTwseRealTimePrice(stockCode);
            if (twse.isPresent()) return twse.get();

            Optional<PriceResult> tw = getYahooPrice(stockCode, stockCode + ".TW", market);
            if (tw.isPresent()) return tw.get();

            return getYahooPrice(stockCode, stockCode + ".TWO", market)
                    .orElseThrow(() -> new RuntimeException("查無股價：" + stockCode));
        } else {
            // 美股：NASDAQ 優先，Yahoo 備用
            Optional<PriceResult> nasdaq = getNasdaqPrice(stockCode);
            if (nasdaq.isPresent()) {
                PriceResult r = nasdaq.get();
                // NASDAQ 沒有五檔（buy/sell）也常缺 stockName，必要時呼叫 Yahoo 補齊
                boolean needName = r.stockName() == null || r.stockName().isBlank();
                boolean needBidAsk = r.buyPrice() == null || r.sellPrice() == null;
                if (needName || needBidAsk) {
                    Optional<PriceResult> y = getYahooPrice(stockCode, stockCode, market);
                    String name = needName
                            ? y.map(PriceResult::stockName).filter(n -> n != null && !n.isBlank()).orElse(null)
                            : r.stockName();
                    BigDecimal bid = needBidAsk ? y.map(PriceResult::buyPrice).orElse(null)  : r.buyPrice();
                    BigDecimal ask = needBidAsk ? y.map(PriceResult::sellPrice).orElse(null) : r.sellPrice();
                    return new PriceResult(r.stockCode(), r.market(), r.price(), r.change(), r.changePct(),
                            r.source(), name,
                            bid, ask, r.openPrice(), r.previousClose(),
                            r.highPrice(), r.lowPrice(), r.volume());
                }
                return r;
            }

            return getYahooPrice(stockCode, stockCode, market)
                    .orElseThrow(() -> new RuntimeException("查無股價：" + stockCode));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NASDAQ quote info API：美股即時報價
    // GET https://api.nasdaq.com/api/quote/{symbol}/info?assetClass=stocks
    // primaryData.lastSalePrice  "$182.63"
    // primaryData.netChange      "+1.23" / "-1.23"
    // primaryData.percentageChange "0.68%"
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<PriceResult> getNasdaqPrice(String stockCode) {
        for (String assetClass : new String[]{"stocks", "etf"}) {
        try {
            String url = "https://api.nasdaq.com/api/quote/" + stockCode + "/info?assetClass=" + assetClass;
            String body = get(url, UA);
            JsonNode root = mapper.readTree(body);
            JsonNode pd = root.path("data").path("primaryData");

            String lastSale = pd.path("lastSalePrice").asText("").replace("$", "").replace(",", "").trim();
            if (lastSale.isEmpty() || lastSale.equals("N/A")) continue;

            BigDecimal price = new BigDecimal(lastSale);

            String changeStr = pd.path("netChange").asText("0").replace(",", "").trim();
            BigDecimal change = new BigDecimal(changeStr.startsWith("+") ? changeStr.substring(1) : changeStr);

            String pctStr = pd.path("percentageChange").asText("0%")
                    .replace("%", "").replace("+", "").replace(",", "").trim();
            BigDecimal changePct = new BigDecimal(pctStr).setScale(6, RoundingMode.HALF_UP);
            // netChange 可能是負值，pct 跟著調整符號
            if (change.compareTo(BigDecimal.ZERO) < 0 && changePct.compareTo(BigDecimal.ZERO) > 0) {
                changePct = changePct.negate();
            }

            String companyName = root.path("data").path("companyName").asText("");
            if (companyName.isBlank()) companyName = null;

            // 解析 keyStats 的開/昨/高低/量
            JsonNode keyStats = root.path("data").path("keyStats");
            BigDecimal openPrice    = parseDollar(keyStats.path("OpenPrice").path("value").asText(""));
            BigDecimal previousClose = parseDollar(keyStats.path("PreviousClose").path("value").asText(""));
            BigDecimal[] hl         = parseRange(keyStats.path("DayrangeHigh").path("value").asText(""));
            if (hl[0] == null && hl[1] == null) {
                hl = parseRange(keyStats.path("Dayrange").path("value").asText(""));
            }
            Long volume = parseLong(keyStats.path("Volume").path("value").asText("").replace(",", ""));

            // bid/ask 在 NASDAQ info API 通常無提供，留 null
            return Optional.of(new PriceResult(stockCode, "美股", price, change, changePct, "NASDAQ",
                    companyName,
                    null, null, openPrice, previousClose, hl[0], hl[1], volume));
        } catch (Exception e) {
            log.warn("NASDAQ price 查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
        }
        } // end for assetClass
        return Optional.empty();
    }

    /**
     * TWSE mis 即時報價 API
     * GET https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_{code}.tw
     * 欄位: z=成交價, y=昨收, c=代號
     */
    private Optional<PriceResult> getTwseRealTimePrice(String stockCode) {
        for (String ex : new String[]{"tse", "otc"}) {
            try {
                String url = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch="
                        + ex + "_" + stockCode + ".tw&json=1&delay=0";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", UA)
                        .header("Referer", "https://mis.twse.com.tw/stock/index.jsp")
                        .header("Accept", "application/json, */*")
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;

                JsonNode root = mapper.readTree(resp.body());
                JsonNode arr = root.path("msgArray");
                if (!arr.isArray() || arr.isEmpty()) continue;

                JsonNode item = arr.get(0);
                String codeCheck = item.path("c").asText("").trim();
                if (codeCheck.isEmpty() || !stockCode.equals(codeCheck)) continue;

                String priceStr = item.path("z").asText("").trim();
                String prevStr  = item.path("y").asText("").trim();
                String name     = item.path("n").asText("").trim();

                BigDecimal prevClose = (prevStr.isEmpty() || prevStr.startsWith("-"))
                        ? null : new BigDecimal(prevStr);

                // 解析買賣五檔（取最佳價）、開高低、成交量（張）
                BigDecimal buyPrice  = parseFirstQuote(item.path("b").asText(""));
                BigDecimal sellPrice = parseFirstQuote(item.path("a").asText(""));
                BigDecimal openPrice = parseDecimal(item.path("o").asText(""));
                BigDecimal highPrice = parseDecimal(item.path("h").asText(""));
                BigDecimal lowPrice  = parseDecimal(item.path("l").asText(""));
                Long volumeLots      = parseLong(item.path("v").asText("")); // TWSE 已以「張」為單位

                // z = "--" 表示未成交（收盤後或開盤前），改用昨收
                if (priceStr.isEmpty() || priceStr.startsWith("-")) {
                    if (prevClose != null) {
                        return Optional.of(new PriceResult(
                                stockCode, "台股", prevClose,
                                BigDecimal.ZERO, BigDecimal.ZERO, "TWSE(前收)",
                                name.isEmpty() ? null : name,
                                buyPrice, sellPrice, openPrice, prevClose, highPrice, lowPrice, volumeLots));
                    }
                    continue;
                }

                BigDecimal price = new BigDecimal(priceStr);
                BigDecimal change = prevClose != null ? price.subtract(prevClose) : BigDecimal.ZERO;
                BigDecimal changePct = (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) != 0)
                        ? change.multiply(BigDecimal.valueOf(100)).divide(prevClose, 6, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                return Optional.of(new PriceResult(stockCode, "台股", price, change, changePct, "TWSE",
                        name.isEmpty() ? null : name,
                        buyPrice, sellPrice, openPrice, prevClose, highPrice, lowPrice, volumeLots));

            } catch (Exception e) {
                log.warn("TWSE mis 查詢失敗 {} ({}): {}", stockCode, ex, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Yahoo Finance quoteSummary price module
     * 適用台股（.TW / .TWO）與美股（純 ticker）
     */
    private Optional<PriceResult> getYahooPrice(String stockCode, String symbol, String market) {
        try {
            String crumb = getYahooCrumb();
            String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/"
                    + symbol + "?modules=price,summaryDetail&crumb="
                    + java.net.URLEncoder.encode(crumb, java.nio.charset.StandardCharsets.UTF_8);
            String body = get(url, UA);

            JsonNode root = mapper.readTree(body);
            JsonNode result = root.path("quoteSummary").path("result");
            if (!result.isArray() || result.isEmpty()) return Optional.empty();

            JsonNode priceNode = result.get(0).path("price");
            double currentPrice = priceNode.path("regularMarketPrice").path("raw").asDouble(-1);
            if (currentPrice <= 0) return Optional.empty();

            double change    = priceNode.path("regularMarketChange").path("raw").asDouble(0);
            double changePct = priceNode.path("regularMarketChangePercent").path("raw").asDouble(0);
            String name = priceNode.path("shortName").asText("");
            if (name.isEmpty()) name = priceNode.path("longName").asText("");

            JsonNode sd = result.get(0).path("summaryDetail");
            BigDecimal bid    = yahooDecimal(sd.path("bid"));
            BigDecimal ask    = yahooDecimal(sd.path("ask"));
            BigDecimal open   = yahooDecimal(sd.path("open").has("raw") ? sd.path("open") : priceNode.path("regularMarketOpen"));
            BigDecimal prev   = yahooDecimal(sd.path("previousClose").has("raw") ? sd.path("previousClose") : priceNode.path("regularMarketPreviousClose"));
            BigDecimal high   = yahooDecimal(sd.path("dayHigh").has("raw") ? sd.path("dayHigh") : priceNode.path("regularMarketDayHigh"));
            BigDecimal low    = yahooDecimal(sd.path("dayLow").has("raw") ? sd.path("dayLow") : priceNode.path("regularMarketDayLow"));
            long volRaw = sd.path("regularMarketVolume").path("raw").asLong(
                    priceNode.path("regularMarketVolume").path("raw").asLong(0));
            // 台股 Yahoo 回傳成交量為「股」，換算為「張」
            Long volume = volRaw > 0 ? ("台股".equals(market) ? volRaw / 1000 : volRaw) : null;

            return Optional.of(new PriceResult(
                    stockCode, market,
                    BigDecimal.valueOf(currentPrice).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(change).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(changePct).setScale(6, RoundingMode.HALF_UP),
                    "Yahoo Finance",
                    name.isEmpty() ? null : name,
                    bid, ask, open, prev, high, low, volume));
        } catch (Exception e) {
            log.warn("Yahoo Finance 股價查詢失敗 {}: {}", symbol, e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("429"))) {
                yahooCrumb = null;
            }
            return Optional.empty();
        }
    }

    /**
     * 查詢配息率：
     * - 台股：TWSE BWIBBU_ALL → FinMind 5 年平均 → TWSE BWIBBU 歷史 5 年
     * - 美股：NASDAQ（可連），不使用 Yahoo Finance（Docker 環境被擋）
     */
    public DividendRateResult getDividendRate(String stockCode, String market) {
        if ("台股".equals(market)) {
            // 一律使用「最近 3 年平均殖利率」。優先序：
            // 1. FinMind 近 3 年平均（ETF/個股皆適用，免認證）
            Optional<DividendRateResult> finmind = getFinMindDividendRate(stockCode);
            if (finmind.isPresent()) return finmind.get();

            // 2. TWSE BWIBBU 近 3 年平均（FinMind 失敗時的備用）
            Optional<DividendRateResult> threeYear = getTwseThreeYearAvgDividendRate(stockCode);
            if (threeYear.isPresent()) return threeYear.get();

            // 3. TWSE OpenAPI 當日殖利率（最後備援，個股才有值）
            Optional<DividendRateResult> twse = getTwseDividendRate(stockCode);
            if (twse.isPresent()) return twse.get();

            return new DividendRateResult(stockCode, market, null, "N/A", "查無配息資料", null);
        } else {
            // 美股：NASDAQ API 優先
            Optional<DividendRateResult> nasdaq = getNasdaqDividendRateAny(stockCode);
            if (nasdaq.isPresent()) return nasdaq.get();

            // Fallback：常見美股 ETF 已知殖利率（Vanguard ETF 等 NASDAQ API 查不到）
            Optional<DividendRateResult> known = getKnownUsEtfDividendRate(stockCode);
            if (known.isPresent()) return known.get();

            return new DividendRateResult(stockCode, market, null, "N/A", "查無配息資料", null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TWSE OpenAPI：取當日全市場殖利率（僅涵蓋上市個股，不含 ETF）
    // Endpoint: https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL
    // Fields: Code, Name, DividendYield, PEratio, PBratio, Date
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<DividendRateResult> getTwseDividendRate(String stockCode) {
        try {
            String url = "https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL";
            String body = get(url, UA);

            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return Optional.empty();

            for (JsonNode item : arr) {
                if (!stockCode.equals(item.path("Code").asText())) continue;

                String rateStr = item.path("DividendYield").asText("").trim();
                if (rateStr.isEmpty() || rateStr.equals("-")) return Optional.empty();

                BigDecimal rate = new BigDecimal(rateStr)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

                String stockName = item.path("Name").asText("");
                return Optional.of(new DividendRateResult(
                        stockCode, "台股", rate,
                        "TWSE",
                        "當日殖利率（%s %s）".formatted(item.path("Date").asText(), stockName),
                        stockName.isBlank() ? null : stockName
                ));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("TWSE 查詢失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TWSE BWIBBU 近 3 年平均殖利率
    // Endpoint: https://www.twse.com.tw/exchangeReport/BWIBBU?response=json&date={YEAR}1201&stockNo={stockCode}
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<DividendRateResult> getTwseThreeYearAvgDividendRate(String stockCode) {
        try {
            int currentYear = LocalDate.now().getYear();
            List<Double> yields = new ArrayList<>();

            // 取最近 3 個完整年度（不含當年度）
            for (int y = currentYear - 1; y >= currentYear - 3; y--) {
                try {
                    String url = "https://www.twse.com.tw/exchangeReport/BWIBBU?response=json&date="
                            + y + "1201&stockNo=" + stockCode;
                    // 每年使用較短 timeout（5 秒），避免整體阻塞過久
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("User-Agent", UA)
                            .header("Accept", "application/json, */*")
                            .header("Accept-Encoding", "identity")
                            .GET().build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) continue;
                    String body = resp.body();
                    JsonNode root = mapper.readTree(body);

                    // Find index of the column whose name contains "殖利率"
                    JsonNode fields = root.path("fields");
                    if (!fields.isArray()) continue;
                    int yieldIdx = -1;
                    for (int i = 0; i < fields.size(); i++) {
                        if (fields.get(i).asText("").contains("殖利率")) {
                            yieldIdx = i;
                            break;
                        }
                    }
                    if (yieldIdx < 0) continue;

                    // Take the last row in data[] for December of each year
                    JsonNode data = root.path("data");
                    if (!data.isArray() || data.isEmpty()) continue;
                    JsonNode lastRow = data.get(data.size() - 1);
                    if (!lastRow.isArray() || lastRow.size() <= yieldIdx) continue;

                    String valStr = lastRow.get(yieldIdx).asText("").replace(",", "").trim();
                    if (valStr.isEmpty() || valStr.equals("-") || valStr.equals("N/A")) continue;

                    double val = Double.parseDouble(valStr);
                    if (val > 0) yields.add(val);
                } catch (Exception e) {
                    log.warn("TWSE BWIBBU 查詢失敗 {} 年 {}: {}", y, stockCode, e.getMessage());
                }
            }

            if (yields.isEmpty()) return Optional.empty();

            double avg = yields.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            BigDecimal rate = BigDecimal.valueOf(avg / 100).setScale(6, RoundingMode.HALF_UP);
            String pctStr = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP).toPlainString();

            return Optional.of(new DividendRateResult(
                    stockCode, "台股", rate,
                    "TWSE(3Y平均)",
                    "近 %d 年平均殖利率（%s%%）".formatted(yields.size(), pctStr)
            ));
        } catch (Exception e) {
            log.warn("TWSE 5 年平均殖利率查詢失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FinMind API：台股/台灣 ETF 近 5 年現金股利殖利率
    // 1. 先查 TaiwanStockDividend（股票/股票型 ETF 有資料）
    // 2. 查無則改用 TaiwanStockDividendResult（債券 ETF 的除息紀錄）
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<DividendRateResult> getFinMindDividendRate(String stockCode) {
        // 1. TaiwanStockDividend（CashEarningsDistribution）
        Optional<DividendRateResult> r1 = getFinMindFromDataset(stockCode,
                "TaiwanStockDividend", "CashEarningsDistribution");
        if (r1.isPresent()) return r1;

        // 2. TaiwanStockDividendResult（stock_and_cache_dividend，適用債券 ETF）
        return getFinMindFromDataset(stockCode,
                "TaiwanStockDividendResult", "stock_and_cache_dividend");
    }

    private Optional<DividendRateResult> getFinMindFromDataset(
            String stockCode, String dataset, String dividendField) {
        try {
            // 抓 4 年資料，確保能涵蓋完整的 3 個歷史年度（避開當年度不完整資料）
            String startDate = LocalDate.now().minusYears(4).toString();
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=" + dataset
                    + "&data_id=" + stockCode
                    + "&start_date=" + startDate;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Optional.empty();

            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();

            // 依年份彙總配息金額
            java.util.Map<Integer, Double> annualDividend = new java.util.TreeMap<>();
            for (JsonNode item : data) {
                String dateStr = item.path("date").asText("");
                if (dateStr.length() < 4) continue;
                int year;
                try { year = Integer.parseInt(dateStr.substring(0, 4)); }
                catch (NumberFormatException e) { continue; }

                double cash;
                if ("CashEarningsDistribution".equals(dividendField)) {
                    // TaiwanStockDividend: CashEarningsDistribution + CashStatutorySurplus
                    cash = item.path("CashEarningsDistribution").asDouble(0)
                         + item.path("CashStatutorySurplus").asDouble(0);
                } else {
                    // TaiwanStockDividendResult: stock_and_cache_dividend
                    cash = item.path("stock_and_cache_dividend").asDouble(0);
                }
                if (cash <= 0) continue;

                annualDividend.merge(year, cash, Double::sum);
            }

            if (annualDividend.isEmpty()) return Optional.empty();

            // 排除當年度（資料不完整會壓低平均），取最近 3 個完整年度
            int currentYear = LocalDate.now().getYear();
            annualDividend.remove(currentYear);
            if (annualDividend.isEmpty()) return Optional.empty();

            List<Double> yearlyDividends = new ArrayList<>(annualDividend.values());
            int fromIdx = Math.max(0, yearlyDividends.size() - 3);
            List<Double> recent = yearlyDividends.subList(fromIdx, yearlyDividends.size());
            double avgAnnualDividend = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (avgAnnualDividend <= 0) return Optional.empty();

            // 取當前股價：優先即時 API，若抓不到改用 DB 快取
            double price = 0;
            Optional<PriceResult> priceOpt = getTwseRealTimePrice(stockCode);
            if (priceOpt.isPresent() && priceOpt.get().price().compareTo(BigDecimal.ZERO) > 0) {
                price = priceOpt.get().price().doubleValue();
            } else {
                var cached = stockPriceRepo.findByStockCodeAndMarket(stockCode, "台股");
                if (cached.isPresent() && cached.get().getPrice() != null) {
                    price = cached.get().getPrice().doubleValue();
                    log.info("FinMind({}) {} 即時股價不可用，改用 DB 快取價格 {}", dataset, stockCode, price);
                }
            }
            if (price <= 0) return Optional.empty();

            double yieldRate = avgAnnualDividend / price;
            BigDecimal rate = BigDecimal.valueOf(yieldRate).setScale(6, RoundingMode.HALF_UP);
            String pctStr = BigDecimal.valueOf(yieldRate * 100).setScale(2, RoundingMode.HALF_UP).toPlainString();
            String divStr = BigDecimal.valueOf(avgAnnualDividend).setScale(2, RoundingMode.HALF_UP).toPlainString();

            log.info("FinMind({}) {} 近{}年平均配息={}, 現價={}, 殖利率={}%",
                    dataset, stockCode, recent.size(), divStr, price, pctStr);

            return Optional.of(new DividendRateResult(
                    stockCode, "台股", rate,
                    "FinMind(3Y平均)",
                    "近 %d 年平均配息 %s 元，殖利率 %s%%".formatted(recent.size(), divStr, pctStr)
            ));
        } catch (Exception e) {
            log.warn("FinMind({}) 查詢失敗 {}: {}", dataset, stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 常見美股 ETF 已知殖利率 Fallback（Vanguard 等 NASDAQ API 查不到的）
    // 資料來源：Vanguard 官網、Morningstar（定期手動更新）
    // ─────────────────────────────────────────────────────────────────────────
    private static final java.util.Map<String, double[]> KNOWN_US_ETF_YIELDS = java.util.Map.of(
            "VOO",  new double[]{0.0125, 1.25},  // Vanguard S&P 500 ETF ~1.25%
            "VT",   new double[]{0.0194, 1.94},  // Vanguard Total World Stock ETF ~1.94%
            "VTI",  new double[]{0.0130, 1.30},  // Vanguard Total Stock Market ETF ~1.30%
            "VXUS", new double[]{0.0290, 2.90},  // Vanguard International Stock ETF ~2.90%
            "BND",  new double[]{0.0370, 3.70},  // Vanguard Total Bond Market ETF ~3.70%
            "QQQ",  new double[]{0.0058, 0.58}   // Invesco QQQ ~0.58%
    );

    private Optional<DividendRateResult> getKnownUsEtfDividendRate(String stockCode) {
        double[] vals = KNOWN_US_ETF_YIELDS.get(stockCode);
        if (vals == null) return Optional.empty();
        BigDecimal rate = BigDecimal.valueOf(vals[0]).setScale(6, RoundingMode.HALF_UP);
        return Optional.of(new DividendRateResult(
                stockCode, "美股", rate,
                "預設值",
                "常見 ETF 參考殖利率 %.2f%%（資料來源：基金公司官網）".formatted(vals[1])
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NASDAQ API：美股殖利率
    // Endpoint: https://api.nasdaq.com/api/quote/{symbol}/dividends?assetClass=stocks
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<DividendRateResult> getNasdaqDividendRate(String stockCode) {
        try {
            String url = "https://api.nasdaq.com/api/quote/" + stockCode
                    + "/dividends?assetClass=stocks";
            String body = get(url, UA);

            JsonNode root = mapper.readTree(body);
            String yieldStr = root.path("data").path("yield").asText("").trim();

            if (yieldStr.isEmpty() || yieldStr.equals("N/A") || yieldStr.equals("--")) {
                return Optional.empty();
            }

            // yield 格式為 "0.41%"
            String numStr = yieldStr.replace("%", "").trim();
            BigDecimal rate = new BigDecimal(numStr)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            if (rate.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            String annualized = root.path("data").path("annualizedDividend").asText("");

            return Optional.of(new DividendRateResult(
                    stockCode, "美股", rate,
                    "NASDAQ",
                    "年化股息率（年化股利 %s）".formatted(annualized)
            ));
        } catch (Exception e) {
            log.warn("NASDAQ 查詢失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NASDAQ API：美股殖利率（涵蓋 stocks 與 etf 兩種資產類別）
    // Endpoint: https://api.nasdaq.com/api/quote/{symbol}/dividends?assetClass={stocks|etf}
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<DividendRateResult> getNasdaqDividendRateAny(String stockCode) {
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetClass=" + assetClass;
                String body = get(url, UA);

                JsonNode root = mapper.readTree(body);
                String yieldStr = root.path("data").path("yield").asText("").trim();

                if (yieldStr.isEmpty() || yieldStr.equals("N/A") || yieldStr.equals("--")) continue;

                // yield 格式為 "0.41%"
                String numStr = yieldStr.replace("%", "").trim();
                BigDecimal rate = new BigDecimal(numStr)
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                if (rate.compareTo(BigDecimal.ZERO) <= 0) continue;

                String annualized = root.path("data").path("annualizedDividend").asText("");
                String companyName = getNasdaqPrice(stockCode)
                        .map(PriceResult::stockName)
                        .filter(n -> n != null && !n.isBlank())
                        .orElse(null);

                return Optional.of(new DividendRateResult(
                        stockCode, "美股", rate,
                        "NASDAQ",
                        "年化股息率（年化股利 %s）".formatted(annualized),
                        companyName
                ));
            } catch (Exception e) {
                log.warn("NASDAQ 查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        return Optional.empty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Yahoo Finance quoteSummary API（支援 crumb 認證，供台灣 ETF 等備用）
    // ─────────────────────────────────────────────────────────────────────────
    private static final Pattern CRUMB_PATTERN =
            Pattern.compile("\"crumb\":\"([^\"]{5,30})\"");

    private Optional<DividendRateResult> getYahooDividendRate(String stockCode, String symbol, String market) {
        try {
            String crumb = getYahooCrumb();
            String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/"
                    + symbol + "?modules=summaryDetail,defaultKeyStatistics&crumb="
                    + java.net.URLEncoder.encode(crumb, java.nio.charset.StandardCharsets.UTF_8);
            String body = get(url, UA);

            JsonNode root = mapper.readTree(body);
            JsonNode summary = root.path("quoteSummary").path("result").get(0);
            if (summary == null || summary.isMissingNode()) return Optional.empty();

            JsonNode detail = summary.path("summaryDetail");
            JsonNode keyStats = summary.path("defaultKeyStatistics");

            BigDecimal rate = null;
            String desc = "";

            // 1. fiveYearAvgDividendYield（美股 ETF/個股常有）
            double v1 = detail.path("fiveYearAvgDividendYield").path("raw").asDouble(-1);
            if (v1 > 0) {
                rate = BigDecimal.valueOf(v1 / 100).setScale(6, RoundingMode.HALF_UP);
                desc = "近 5 年平均殖利率";
            }

            // 2. dividendYield
            if (rate == null) {
                double v2 = detail.path("dividendYield").path("raw").asDouble(-1);
                if (v2 > 0) {
                    rate = BigDecimal.valueOf(v2).setScale(6, RoundingMode.HALF_UP);
                    desc = "當前殖利率";
                }
            }

            // 3. trailingAnnualDividendYield
            if (rate == null) {
                double v3 = detail.path("trailingAnnualDividendYield").path("raw").asDouble(-1);
                if (v3 > 0) {
                    rate = BigDecimal.valueOf(v3).setScale(6, RoundingMode.HALF_UP);
                    desc = "過去 12 個月殖利率";
                }
            }

            // 4. defaultKeyStatistics.yield（台灣 ETF 如 0050 使用此欄）
            if (rate == null) {
                double v4 = keyStats.path("yield").path("raw").asDouble(-1);
                if (v4 > 0) {
                    rate = BigDecimal.valueOf(v4).setScale(6, RoundingMode.HALF_UP);
                    desc = "ETF 配息率";
                }
            }

            if (rate == null) return Optional.empty();
            return Optional.of(new DividendRateResult(stockCode, market, rate, "Yahoo Finance", desc));

        } catch (Exception e) {
            log.warn("Yahoo Finance 查詢失敗 {}: {}", symbol, e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("429"))) {
                yahooCrumb = null;
            }
            return Optional.empty();
        }
    }

    /**
     * 取得 Yahoo Finance crumb：
     * 1. 建立 cookie session（訪問 finance.yahoo.com）
     * 2. 嘗試從 HTML 擷取內嵌 crumb
     * 3. 備用：帶 cookie 呼叫 getcrumb API（含重試，應對 429）
     */
    private synchronized String getYahooCrumb() throws Exception {
        if (yahooCrumb != null) return yahooCrumb;

        log.info("初始化 Yahoo Finance session...");

        // Step 1: 建立 session（取得 Yahoo cookies）
        HttpResponse<String> resp = send(buildRequest("https://finance.yahoo.com", UA));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Yahoo Finance 首頁回應 HTTP " + resp.statusCode());
        }

        // Step 2a: 嘗試從 HTML 擷取 crumb（登入態頁面含有 crumb）
        Matcher m = CRUMB_PATTERN.matcher(resp.body());
        if (m.find()) {
            yahooCrumb = m.group(1);
            log.info("Yahoo Finance crumb 從 HTML 取得：{}", yahooCrumb);
            return yahooCrumb;
        }

        // Step 2b: 呼叫 getcrumb API，帶重試（應對 429 rate limit）
        String[] crumbUrls = {
            "https://query1.finance.yahoo.com/v1/test/getcrumb",
            "https://query2.finance.yahoo.com/v1/test/getcrumb"
        };

        for (String crumbUrl : crumbUrls) {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) {
                    log.info("等待後重試 Yahoo crumb（第 {} 次）...", attempt + 1);
                    Thread.sleep(2000L * attempt);
                }
                HttpResponse<String> crumbResp = send(buildRequest(crumbUrl, UA));
                if (crumbResp.statusCode() == 200) {
                    String crumb = crumbResp.body().trim();
                    if (!crumb.isBlank() && !crumb.contains(" ") && crumb.length() <= 50) {
                        yahooCrumb = crumb;
                        log.info("Yahoo Finance crumb 取得：{} (from {})", yahooCrumb, crumbUrl);
                        return yahooCrumb;
                    }
                }
                if (crumbResp.statusCode() != 429) break; // 非 rate limit 錯誤不重試
            }
        }

        throw new RuntimeException("無法取得 Yahoo Finance crumb（已重試多次）");
    }

    private String get(String url, String userAgent) throws Exception {
        HttpResponse<String> resp = send(buildRequest(url, userAgent));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest buildRequest(String url, String userAgent) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/html, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "identity")   // 避免 gzip 壓縮導致解析失敗
                .header("Referer", "https://finance.yahoo.com/")
                .GET()
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 交易日曆：台股假日（TWSE Open API）、美股假日（NYSE 計算）
    // ─────────────────────────────────────────────────────────────────────────

    private final Map<Integer, Map<String, String>> twHolidayCache = new ConcurrentHashMap<>();

    /**
     * 取得台股非交易日（從 TWSE Open API 抓取，依年份快取）
     * key = "YYYY-MM-DD", value = 假日名稱
     */
    public Map<String, String> getTwHolidays(int year) {
        return twHolidayCache.computeIfAbsent(year, this::fetchTwHolidaysFromTwse);
    }

    private Map<String, String> fetchTwHolidaysFromTwse(int year) {
        int rocYear = year - 1911;
        String rocYearStr = String.valueOf(rocYear);
        String url = "https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            Map<String, String> holidays = new LinkedHashMap<>();
            for (JsonNode item : root) {
                String date = item.path("Date").asText();
                String name = item.path("Name").asText();
                // 跳過「交易日標記」（開始/最後交易日）
                if (name.contains("開始交易日") || name.contains("最後交易日")) continue;
                // 只取指定年份
                if (!date.startsWith(rocYearStr)) continue;
                // 民國日期 → 西元日期
                String mmdd = date.substring(rocYearStr.length()); // e.g. "0403"
                int month = Integer.parseInt(mmdd.substring(0, 2));
                int day = Integer.parseInt(mmdd.substring(2, 4));
                String gregorian = String.format("%04d-%02d-%02d", year, month, day);
                holidays.put(gregorian, name);
            }
            log.info("TWSE holidays fetched for {}: {} entries", year, holidays.size());
            return holidays;
        } catch (Exception e) {
            log.warn("Failed to fetch TWSE holidays for {}: {}", year, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 取得美股非交易日（NYSE 規則計算，含週末遞移）
     * key = "YYYY-MM-DD", value = 假日名稱（英文）
     */
    public Map<String, String> getUsHolidays(int year) {
        Map<String, String> h = new LinkedHashMap<>();
        // New Year's Day (Jan 1, observed)
        addObserved(h, year, 1, 1, "New Year's Day");
        // MLK Day: 1月第3個週一
        h.put(nthWeekday(year, 1, DayOfWeek.MONDAY, 3), "MLK Day");
        // Presidents' Day: 2月第3個週一
        h.put(nthWeekday(year, 2, DayOfWeek.MONDAY, 3), "Presidents' Day");
        // Good Friday (NYSE 休市)
        h.put(goodFriday(year), "Good Friday");
        // Memorial Day: 5月最後一個週一
        h.put(lastWeekday(year, 5, DayOfWeek.MONDAY), "Memorial Day");
        // Juneteenth (Jun 19, observed)
        addObserved(h, year, 6, 19, "Juneteenth");
        // Independence Day (Jul 4, observed)
        addObserved(h, year, 7, 4, "Independence Day");
        // Labor Day: 9月第1個週一
        h.put(nthWeekday(year, 9, DayOfWeek.MONDAY, 1), "Labor Day");
        // Thanksgiving: 11月第4個週四
        h.put(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4), "Thanksgiving");
        // Christmas (Dec 25, observed)
        addObserved(h, year, 12, 25, "Christmas");
        return h;
    }

    /** 該日是否為台股交易日（非週末且不在 TWSE 假日表） */
    public boolean isTwTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !getTwHolidays(date.getYear()).containsKey(date.toString());
    }

    /** 該日是否為美股交易日（非週末且不在 NYSE 假日表） */
    public boolean isUsTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !getUsHolidays(date.getYear()).containsKey(date.toString());
    }

    private void addObserved(Map<String, String> h, int year, int month, int day, String name) {
        LocalDate date = LocalDate.of(year, month, day);
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) date = date.minusDays(1); // observed Friday
        else if (dow == DayOfWeek.SUNDAY) date = date.plusDays(1); // observed Monday
        h.put(date.toString(), name);
    }

    private String nthWeekday(int year, int month, DayOfWeek dow, int n) {
        LocalDate d = LocalDate.of(year, month, 1);
        int count = 0;
        while (true) {
            if (d.getDayOfWeek() == dow && ++count == n) return d.toString();
            d = d.plusDays(1);
        }
    }

    private String lastWeekday(int year, int month, DayOfWeek dow) {
        LocalDate d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (d.getDayOfWeek() != dow) d = d.minusDays(1);
        return d.toString();
    }

    /** Good Friday = Easter Sunday - 2 days (Anonymous Gregorian algorithm) */
    private String goodFriday(int year) {
        int a = year % 19, b = year / 100, c = year % 100;
        int d = b / 4, e = b % 4, f = (b + 8) / 25;
        int g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day).minusDays(2).toString();
    }

    // =====================================================================
    // 詳細報價解析輔助
    // =====================================================================

    /** 解析 TWSE 五檔字串（"650.0000_651.0000_..."），取最佳檔（第一個） */
    private static BigDecimal parseFirstQuote(String s) {
        if (s == null || s.isBlank() || s.startsWith("-")) return null;
        String first = s.split("_")[0].trim();
        return parseDecimal(first);
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String trim = s.trim();
        if (trim.isEmpty() || "-".equals(trim) || "--".equals(trim) || "N/A".equalsIgnoreCase(trim)) return null;
        try { return new BigDecimal(trim.replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        String trim = s.trim();
        if (trim.isEmpty() || "-".equals(trim) || "--".equals(trim) || "N/A".equalsIgnoreCase(trim)) return null;
        try { return Long.parseLong(trim.replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    /** "$182.63" / "182.63" → BigDecimal */
    private static BigDecimal parseDollar(String s) {
        if (s == null) return null;
        String t = s.replace("$", "").replace(",", "").trim();
        return parseDecimal(t);
    }

    /** NASDAQ keyStats 的 dayrange 欄位通常為 "180.00 - 185.00"，回傳 [high, low] */
    private static BigDecimal[] parseRange(String s) {
        if (s == null || s.isBlank()) return new BigDecimal[]{null, null};
        String[] parts = s.replace("$", "").split("-");
        if (parts.length < 2) return new BigDecimal[]{null, null};
        BigDecimal a = parseDecimal(parts[0]);
        BigDecimal b = parseDecimal(parts[1]);
        if (a == null || b == null) return new BigDecimal[]{a, b};
        return a.compareTo(b) >= 0
                ? new BigDecimal[]{a, b}
                : new BigDecimal[]{b, a};
    }

    /**
     * 計算填息天數：除息日當天股價 vs 除息日前一交易日的收盤價（基準價）。
     * 從除息日（含）起，找第一筆收盤 >= 基準價的交易日，回傳之間的交易日數。
     * 尚未填息或資料不足回傳 null。
     */
    private Integer calcFillDays(String stockCode, String market, String exDate) {
        return calcDividendBasis(stockCode, market, exDate).fillDays();
    }

    /** 一次查詢除息日前後價格序列，回傳前一交易日收盤價與填息天數。 */
    private DividendBasis calcDividendBasis(String stockCode, String market, String exDate) {
        if (exDate == null || exDate.length() < 10) return DividendBasis.EMPTY;
        try {
            LocalDate ex = LocalDate.parse(exDate);
            // 取除息日前後各 1 年的歷史，足夠多數情況下找到填息日
            LocalDate from = ex.minusDays(20);
            LocalDate to   = ex.plusDays(400);
            List<StockPriceHistory> series = stockPriceHistoryRepo
                    .findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                            stockCode, market, from, to);
            if (series.size() < 2) return DividendBasis.EMPTY;

            // 基準價 = 除息日前一個交易日的收盤
            int exIdx = -1;
            for (int i = 0; i < series.size(); i++) {
                if (!series.get(i).getTradingDate().isBefore(ex)) { exIdx = i; break; }
            }
            if (exIdx <= 0) return DividendBasis.EMPTY;
            BigDecimal basis = series.get(exIdx - 1).getClosePrice();
            if (basis == null) return DividendBasis.EMPTY;

            // 從除息日（含）起，找第一筆收盤 >= basis
            Integer fillDays = null;
            for (int i = exIdx; i < series.size(); i++) {
                BigDecimal close = series.get(i).getClosePrice();
                if (close != null && close.compareTo(basis) >= 0) {
                    fillDays = i - exIdx;   // 0 表示除息日當天即填息
                    break;
                }
            }
            return new DividendBasis(basis, fillDays);
        } catch (Exception e) {
            log.debug("calcDividendBasis failed for {} {} ex={}: {}", stockCode, market, exDate, e.getMessage());
            return DividendBasis.EMPTY;
        }
    }

    private static BigDecimal yahooDecimal(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        if (!node.has("raw")) return null;
        double v = node.path("raw").asDouble(Double.NaN);
        if (Double.isNaN(v)) return null;
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    // =====================================================================
    // ETF 判斷 / 持股明細 / 股利歷史 （Requirement 13）
    // =====================================================================

    private static final java.util.Set<String> US_ETF_WHITELIST = java.util.Set.of(
            "VOO", "VT", "VTI", "VGT", "VYM", "VNQ", "VXUS",
            "SPY", "QQQ", "DIA", "IVV", "IWM",
            "AVGO",   // 保留：使用者持倉白名單擴充用
            "SCHD", "JEPI", "JEPQ"
    );

    /** 判斷是否為 ETF。 台股：00 開頭；美股：白名單 */
    public boolean isEtf(String stockCode, String market) {
        if (stockCode == null) return false;
        if ("台股".equals(market)) return stockCode.startsWith("00");
        if ("美股".equals(market)) return US_ETF_WHITELIST.contains(stockCode.toUpperCase());
        return false;
    }

    /**
     * ETF 成分持股。台股用 FinMind TaiwanETFHoldings（免認證）；美股暫不支援。
     */
    public EtfHoldingsResult getEtfHoldings(String stockCode, String market) {
        if (!isEtf(stockCode, market)) {
            return new EtfHoldingsResult(stockCode, market, false, null, null,
                    "此股票非 ETF 或未在支援清單", List.of());
        }
        // 優先嘗試 Yahoo Finance topHoldings（美股 ETF 效果最佳；台股通常只回前 10 大）
        try {
            EtfHoldingsResult y = getYahooEtfHoldings(stockCode, market);
            if (y != null && !y.holdings().isEmpty()) return y;
        } catch (Exception ignore) {}

        if ("美股".equals(market)) {
            return new EtfHoldingsResult(stockCode, market, false, null, null,
                    "Yahoo Finance 未提供此 ETF 的成分股資料", List.of());
        }
        // 台股 ETF：FinMind 的 ETF 持股資料集需付費方案；保留呼叫以便未來升級
        try {
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=TaiwanETFHoldings"
                    + "&data_id=" + stockCode;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new EtfHoldingsResult(stockCode, market, true, "FinMind", null,
                        "FinMind 回應 " + resp.statusCode(), List.of());
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return new EtfHoldingsResult(stockCode, market, true, "FinMind", null,
                        "查無成分股資料", List.of());
            }

            // 取最新日期的資料
            String latestDate = "";
            for (JsonNode n : data) {
                String d = n.path("date").asText("");
                if (d.compareTo(latestDate) > 0) latestDate = d;
            }
            List<EtfHolding> holdings = new ArrayList<>();
            for (JsonNode n : data) {
                if (!latestDate.equals(n.path("date").asText(""))) continue;
                String code = n.path("stock_id").asText("");
                String name = n.path("stock_name").asText("");
                double weight = n.path("weight").asDouble(0);
                if (code.isEmpty() && name.isEmpty()) continue;
                holdings.add(new EtfHolding(
                        code, name,
                        BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP),
                        null
                ));
            }
            // 依權重由大到小
            holdings.sort((a, b) -> b.weight().compareTo(a.weight()));
            return new EtfHoldingsResult(stockCode, market, true, "FinMind",
                    latestDate, null, holdings);
        } catch (Exception e) {
            log.warn("ETF 持股查詢失敗 {}: {}", stockCode, e.getMessage());
            return new EtfHoldingsResult(stockCode, market, true, "FinMind", null,
                    "查詢失敗：" + e.getMessage(), List.of());
        }
    }

    /**
     * 透過 Yahoo Finance quoteSummary topHoldings 取得 ETF 前十大成分股
     */
    private EtfHoldingsResult getYahooEtfHoldings(String stockCode, String market) {
        try {
            String symbol = "台股".equals(market) ? stockCode + ".TW" : stockCode;
            String crumb = getYahooCrumb();
            String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/"
                    + symbol + "?modules=topHoldings&crumb="
                    + java.net.URLEncoder.encode(crumb, java.nio.charset.StandardCharsets.UTF_8);
            String body = get(url, UA);
            JsonNode root = mapper.readTree(body);
            JsonNode result = root.path("quoteSummary").path("result");
            if (!result.isArray() || result.isEmpty()) return null;

            JsonNode top = result.get(0).path("topHoldings");
            JsonNode arr = top.path("holdings");
            if (!arr.isArray() || arr.isEmpty()) {
                // 試 .TWO（上櫃）
                if ("台股".equals(market)) {
                    symbol = stockCode + ".TWO";
                    url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/"
                            + symbol + "?modules=topHoldings&crumb="
                            + java.net.URLEncoder.encode(crumb, java.nio.charset.StandardCharsets.UTF_8);
                    body = get(url, UA);
                    root = mapper.readTree(body);
                    arr = root.path("quoteSummary").path("result").get(0)
                            .path("topHoldings").path("holdings");
                }
            }
            if (!arr.isArray() || arr.isEmpty()) return null;

            List<EtfHolding> list = new ArrayList<>();
            for (JsonNode h : arr) {
                String code = h.path("symbol").asText("");
                String name = h.path("holdingName").asText("");
                double weight = h.path("holdingPercent").path("raw").asDouble(0) * 100.0;
                list.add(new EtfHolding(
                        code, name,
                        BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP),
                        null
                ));
            }
            list.sort((a, b) -> b.weight().compareTo(a.weight()));
            return new EtfHoldingsResult(stockCode, market, true, "Yahoo Finance (前 10 大)",
                    null, null, list);
        } catch (Exception e) {
            log.warn("Yahoo topHoldings 查詢失敗 {}: {}", stockCode, e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("429"))) {
                yahooCrumb = null;
            }
            return null;
        }
    }

    /**
     * 最近 N 年股利。台股：FinMind TaiwanStockDividend；美股：NASDAQ dividends API
     */
    public DividendHistoryResult getDividendHistory(String stockCode, String market, int years) {
        int n = Math.max(1, Math.min(years, 20));
        if ("台股".equals(market)) {
            return getTwDividendHistory(stockCode, n);
        } else if ("美股".equals(market)) {
            return getUsDividendHistory(stockCode, n);
        }
        return new DividendHistoryResult(stockCode, market, null, "不支援的市場", List.of());
    }

    private DividendHistoryResult getTwDividendHistory(String stockCode, int years) {
        try {
            String startDate = LocalDate.now().minusYears(years).toString();
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=TaiwanStockDividend"
                    + "&data_id=" + stockCode
                    + "&start_date=" + startDate;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new DividendHistoryResult(stockCode, "台股", "FinMind",
                        "FinMind 回應 " + resp.statusCode(), List.of());
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return new DividendHistoryResult(stockCode, "台股", "FinMind",
                        "查無股利資料", List.of());
            }

            // 一筆配息事件 = 一列（不再依年度彙總，因 0050、台積電等每年多次配息）
            List<DividendRow> rows = new ArrayList<>();
            for (JsonNode item : data) {
                double cash = item.path("CashEarningsDistribution").asDouble(0)
                            + item.path("CashStatutorySurplus").asDouble(0);
                double stock = item.path("StockEarningsDistribution").asDouble(0)
                             + item.path("StockStatutorySurplus").asDouble(0);
                if (cash == 0 && stock == 0) continue;

                String exDate = item.path("CashExDividendTradingDate").asText("");
                if (exDate.isEmpty()) exDate = item.path("StockExDividendTradingDate").asText("");

                Integer year = null;
                if (exDate.length() >= 4) {
                    try { year = Integer.parseInt(exDate.substring(0, 4)); }
                    catch (NumberFormatException ignore) {}
                }
                if (year == null) {
                    String date = item.path("date").asText("");
                    if (date.length() < 4) continue;
                    try { year = Integer.parseInt(date.substring(0, 4)); }
                    catch (NumberFormatException e) { continue; }
                }

                String cashPay  = item.path("CashDividendPaymentDate").asText("");
                String stockPay = item.path("StockDividendPaymentDate").asText("");

                DividendBasis basis = calcDividendBasis(stockCode, "台股", exDate);
                rows.add(new DividendRow(
                        year,
                        BigDecimal.valueOf(cash).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(stock).setScale(4, RoundingMode.HALF_UP),
                        exDate.isEmpty() ? null : exDate,
                        null,
                        cashPay.isEmpty() ? null : cashPay,
                        stockPay.isEmpty() ? null : stockPay,
                        basis.fillDays(),
                        basis.previousClose()
                ));
            }
            // 除息日新→舊
            rows.sort((a, b) -> {
                String ea = a.exDividendDate() != null ? a.exDividendDate() : "";
                String eb = b.exDividendDate() != null ? b.exDividendDate() : "";
                return eb.compareTo(ea);
            });
            return new DividendHistoryResult(stockCode, "台股", "FinMind", null, rows);
        } catch (Exception e) {
            log.warn("台股股利歷史查詢失敗 {}: {}", stockCode, e.getMessage());
            return new DividendHistoryResult(stockCode, "台股", "FinMind",
                    "查詢失敗：" + e.getMessage(), List.of());
        }
    }

    private DividendHistoryResult getUsDividendHistory(String stockCode, int years) {
        int fromYear = LocalDate.now().getYear() - years + 1;
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetclass=" + assetClass;
                String body = get(url, UA);
                JsonNode root = mapper.readTree(body);
                JsonNode rowsNode = root.path("data").path("dividends").path("rows");
                if (!rowsNode.isArray() || rowsNode.isEmpty()) continue;

                List<DividendRow> rows = new ArrayList<>();
                for (JsonNode r : rowsNode) {
                    String exDate = r.path("exOrEffDate").asText("");
                    if (exDate.length() < 10) continue;
                    int year;
                    String exIso;
                    try {
                        // NASDAQ 日期格式 MM/DD/YYYY → 轉 ISO
                        String[] p = exDate.split("/");
                        year = Integer.parseInt(p[2]);
                        exIso = String.format("%04d-%02d-%02d", year,
                                Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                    } catch (Exception e) { continue; }
                    if (year < fromYear) continue;

                    String amtStr = r.path("amount").asText("").replace("$", "").trim();
                    if (amtStr.isEmpty() || amtStr.equals("N/A")) continue;
                    double amt;
                    try { amt = Double.parseDouble(amtStr); }
                    catch (NumberFormatException e) { continue; }

                    String payDate = r.path("paymentDate").asText("");
                    String payIso = null;
                    if (payDate.length() >= 10) {
                        try {
                            String[] pp = payDate.split("/");
                            payIso = String.format("%04d-%02d-%02d",
                                    Integer.parseInt(pp[2]), Integer.parseInt(pp[0]), Integer.parseInt(pp[1]));
                        } catch (Exception ignore) {}
                    }

                    DividendBasis basis = calcDividendBasis(stockCode, "美股", exIso);
                    rows.add(new DividendRow(
                            year,
                            BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.ZERO,
                            exIso,
                            null,
                            payIso,
                            null,
                            basis.fillDays(),
                            basis.previousClose()
                    ));
                }
                if (rows.isEmpty()) continue;
                rows.sort((a, b) -> {
                    String ea = a.exDividendDate() != null ? a.exDividendDate() : "";
                    String eb = b.exDividendDate() != null ? b.exDividendDate() : "";
                    return eb.compareTo(ea);
                });
                return new DividendHistoryResult(stockCode, "美股", "NASDAQ", null, rows);
            } catch (Exception e) {
                log.warn("美股股利歷史查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        return new DividendHistoryResult(stockCode, "美股", "NASDAQ",
                "查無股利資料", List.of());
    }
}
