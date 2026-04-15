package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Yahoo Finance crumb（session 期間有效） */
    private volatile String yahooCrumb = null;

    public MarketDataService(StockPriceRepository stockPriceRepo) {
        this.stockPriceRepo = stockPriceRepo;
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

    public record PriceResult(
            String stockCode,
            String market,
            BigDecimal price,
            BigDecimal change,
            BigDecimal changePct,
            String source,
            String stockName
    ) {
        /** 向後相容：不帶 stockName 的建構 */
        public PriceResult(String stockCode, String market, BigDecimal price,
                           BigDecimal change, BigDecimal changePct, String source) {
            this(stockCode, market, price, change, changePct, source, null);
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
                // NASDAQ 拿到價格但無公司名稱時，去 Yahoo 補名稱
                if (r.stockName() == null || r.stockName().isBlank()) {
                    String name = getYahooPrice(stockCode, stockCode, market)
                            .map(PriceResult::stockName)
                            .filter(n -> n != null && !n.isBlank())
                            .orElse(null);
                    return new PriceResult(r.stockCode(), r.market(), r.price(), r.change(), r.changePct(), r.source(), name);
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
            BigDecimal changePct = new BigDecimal(pctStr)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            // netChange 可能是負值，pct 跟著調整符號
            if (change.compareTo(BigDecimal.ZERO) < 0 && changePct.compareTo(BigDecimal.ZERO) > 0) {
                changePct = changePct.negate();
            }

            String companyName = root.path("data").path("companyName").asText("");
            if (companyName.isBlank()) companyName = null;

            return Optional.of(new PriceResult(stockCode, "美股", price, change, changePct, "NASDAQ", companyName));
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

                // z = "--" 表示未成交（收盤後或開盤前），改用昨收
                if (priceStr.isEmpty() || priceStr.startsWith("-")) {
                    if (prevClose != null) {
                        return Optional.of(new PriceResult(
                                stockCode, "台股", prevClose,
                                BigDecimal.ZERO, BigDecimal.ZERO, "TWSE(前收)", name.isEmpty() ? null : name));
                    }
                    continue;
                }

                BigDecimal price = new BigDecimal(priceStr);
                BigDecimal change = prevClose != null ? price.subtract(prevClose) : BigDecimal.ZERO;
                BigDecimal changePct = (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) != 0)
                        ? change.divide(prevClose, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;

                return Optional.of(new PriceResult(stockCode, "台股", price, change, changePct, "TWSE",
                        name.isEmpty() ? null : name));

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
                    + symbol + "?modules=price&crumb="
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

            return Optional.of(new PriceResult(
                    stockCode, market,
                    BigDecimal.valueOf(currentPrice).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(change).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(changePct).setScale(6, RoundingMode.HALF_UP),
                    "Yahoo Finance",
                    name.isEmpty() ? null : name));
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
            // 1. TWSE OpenAPI 當日殖利率（快速、從 Docker 可連；個股有值，ETF 通常為 "-"）
            Optional<DividendRateResult> twse = getTwseDividendRate(stockCode);
            if (twse.isPresent()) return twse.get();

            // 2. FinMind 近 5 年平均現金股利殖利率（ETF 適用，免認證，Docker 可連）
            Optional<DividendRateResult> finmind = getFinMindDividendRate(stockCode);
            if (finmind.isPresent()) return finmind.get();

            // 3. TWSE BWIBBU 歷史 5 年平均殖利率（備用，較慢）
            Optional<DividendRateResult> fiveYear = getTwseFiveYearAvgDividendRate(stockCode);
            if (fiveYear.isPresent()) return fiveYear.get();

            throw new RuntimeException("查無配息資料：" + stockCode);
        } else {
            // 美股：NASDAQ API 優先
            Optional<DividendRateResult> nasdaq = getNasdaqDividendRateAny(stockCode);
            if (nasdaq.isPresent()) return nasdaq.get();

            // Fallback：常見美股 ETF 已知殖利率（Vanguard ETF 等 NASDAQ API 查不到）
            Optional<DividendRateResult> known = getKnownUsEtfDividendRate(stockCode);
            if (known.isPresent()) return known.get();

            throw new RuntimeException("查無配息資料：" + stockCode);
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
    // TWSE BWIBBU 近 5 年平均殖利率
    // Endpoint: https://www.twse.com.tw/exchangeReport/BWIBBU?response=json&date={YEAR}1201&stockNo={stockCode}
    // ─────────────────────────────────────────────────────────────────────────
    private Optional<DividendRateResult> getTwseFiveYearAvgDividendRate(String stockCode) {
        try {
            int currentYear = LocalDate.now().getYear();
            List<Double> yields = new ArrayList<>();

            for (int y = currentYear; y >= currentYear - 4; y--) {
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
                    "TWSE(5Y平均)",
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
            String startDate = LocalDate.now().minusYears(5).toString();
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

            List<Double> yearlyDividends = new ArrayList<>(annualDividend.values());
            int fromIdx = Math.max(0, yearlyDividends.size() - 5);
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
                    "FinMind(5Y平均)",
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
}
