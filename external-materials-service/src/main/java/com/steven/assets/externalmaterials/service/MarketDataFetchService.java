package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 殖利率 / ETF 持股 / 股利歷史 / TWSE 假日 / 股票名稱對外抓取（從 backend MarketDataService 搬遷至此）。
 *
 * 對外 API：
 *  - TWSE OpenAPI: https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL（殖利率）/ holidaySchedule（假日）
 *  - TWSE BWIBBU per stock: https://www.twse.com.tw/exchangeReport/BWIBBU
 *  - FinMind: TaiwanStockDividend / TaiwanStockDividendResult / TaiwanETFHoldings / TaiwanStockInfo
 *  - NASDAQ: /api/quote/{code}/dividends, /api/quote/{code}/info
 *  - Yahoo Finance quoteSummary（含 crumb 認證）
 *
 * 結果記錄與 business-services 端的 record 型別保持同 JSON shape；business-services 直接反序列化。
 */
@Slf4j
@Service
public class MarketDataFetchService {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Yahoo crumb / quoteSummary（topHoldings）的反 bot WAF 對長 Chrome UA 一律回 429（Too Many Requests），
    // 僅短 UA "Mozilla/5.0" 放行（v8/chart 端點則長 UA 可用）。故獨立常數只用於 crumb + quoteSummary。
    private static final String YAHOO_UA = "Mozilla/5.0";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String finmindToken;
    private final StockSourceQuery store;

    private volatile String yahooCrumb = null;
    private volatile long yahooCrumbBlockedUntil = 0L;
    private static final long YAHOO_CRUMB_BLOCK_MS = 5 * 60 * 1000L;

    // Yahoo crumb / quoteSummary 改走 curl 子程序（見 getYahooCrumb），cookie 暫存於此檔。
    private static final String YAHOO_COOKIE_FILE = "/tmp/yahoo-cookies.txt";

    // ETF 成分股 12h cache（成分股每日至多變動一次，避免重複打 Yahoo 觸發 429）
    private record CachedEtfHoldings(EtfHoldingsResult result, long expiresAt) {}
    private final Map<String, CachedEtfHoldings> etfHoldingsCache = new ConcurrentHashMap<>();
    private static final long ETF_HOLDINGS_TTL_MS = 12 * 60 * 60 * 1000L;

    // 台股「股名→代號」字典 24h cache：MoneyDJ 成分股只給股名無代號，用此補上代號。
    // 來源 TWSE STOCK_DAY_ALL（上市）+ TPEX（上櫃）；放記憶體而非 stock 主檔，避免污染抓價排程清單。
    private volatile Map<String, String> twNameToCode = java.util.Collections.emptyMap();
    private volatile long twNameToCodeExpiresAt = 0L;
    private static final long TW_NAME_MAP_TTL_MS = 24 * 60 * 60 * 1000L;

    private final Map<Integer, Map<String, String>> twHolidayCache = new ConcurrentHashMap<>();

    public MarketDataFetchService(StockSourceQuery store,
                                  @Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken) {
        this.store = store;
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record DividendRateResult(
            String stockCode, String market, BigDecimal dividendRate,
            String source, String description, String stockName) {}

    public record EtfHolding(String stockCode, String stockName, BigDecimal weight, BigDecimal shares) {}

    public record EtfHoldingsResult(
            String stockCode, String market, boolean supported, String source,
            String asOfDate, String message, List<EtfHolding> holdings) {}

    public record DividendRow(
            Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
            String exDividendDate, BigDecimal yieldPct,
            String cashPaymentDate, String stockPaymentDate,
            Integer fillDays, BigDecimal previousClose) {}

    public record DividendHistoryResult(
            String stockCode, String market, String source, String message, List<DividendRow> rows) {}

    // ─── 殖利率 ────────────────────────────────────────────────────────────────

    public DividendRateResult getDividendRate(String stockCode, String market) {
        if ("台股".equals(market)) {
            Optional<DividendRateResult> finmind = getFinMindDividendRate(stockCode);
            if (finmind.isPresent()) return finmind.get();
            Optional<DividendRateResult> threeYear = getTwseThreeYearAvgDividendRate(stockCode);
            if (threeYear.isPresent()) return threeYear.get();
            Optional<DividendRateResult> twse = getTwseDividendRate(stockCode);
            if (twse.isPresent()) return twse.get();
            return new DividendRateResult(stockCode, market, null, "N/A", "查無配息資料", null);
        } else if ("英股".equals(market)) {
            // 英股 UCITS ETF：Yahoo chart?events=div 對 {code}.L 取 TTM 殖利率
            Optional<DividendRateResult> yahoo = getYahooDividendRateForTicker(stockCode, stockCode + ".L", "英股");
            if (yahoo.isPresent()) return yahoo.get();
            return new DividendRateResult(stockCode, market, null, "N/A", "查無配息資料", null);
        } else {
            Optional<DividendRateResult> nasdaq = getNasdaqDividendRateAny(stockCode);
            if (nasdaq.isPresent()) return nasdaq.get();
            // Yahoo fallback：NASDAQ API 對非 NASDAQ 上市（NYSE / NYSEARCA）一律回 N/A，
            // 例如 SGOV / BIL / SCHD / JEPI / TLT。改用 Yahoo chart events=div 計算 TTM 殖利率。
            Optional<DividendRateResult> yahoo = getYahooDividendRate(stockCode);
            if (yahoo.isPresent()) return yahoo.get();
            Optional<DividendRateResult> known = getKnownUsEtfDividendRate(stockCode);
            if (known.isPresent()) return known.get();
            return new DividendRateResult(stockCode, market, null, "N/A", "查無配息資料", null);
        }
    }

    private Optional<DividendRateResult> getTwseDividendRate(String stockCode) {
        try {
            String body = get("https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL");
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return Optional.empty();
            for (JsonNode item : arr) {
                if (!stockCode.equals(item.path("Code").asText())) continue;
                String rateStr = item.path("DividendYield").asText("").trim();
                if (rateStr.isEmpty() || rateStr.equals("-")) return Optional.empty();
                BigDecimal rate = new BigDecimal(rateStr).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                String stockName = item.path("Name").asText("");
                return Optional.of(new DividendRateResult(
                        stockCode, "台股", rate, "TWSE",
                        "當日殖利率（%s %s）".formatted(item.path("Date").asText(), stockName),
                        stockName.isBlank() ? null : stockName));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("TWSE 查詢失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<DividendRateResult> getTwseThreeYearAvgDividendRate(String stockCode) {
        try {
            int currentYear = LocalDate.now().getYear();
            List<Double> yields = new ArrayList<>();
            for (int y = currentYear - 1; y >= currentYear - 3; y--) {
                try {
                    String url = "https://www.twse.com.tw/exchangeReport/BWIBBU?response=json&date="
                            + y + "1201&stockNo=" + stockCode;
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("User-Agent", UA)
                            .header("Accept", "application/json, */*")
                            .header("Accept-Encoding", "identity").GET().build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) continue;
                    JsonNode root = mapper.readTree(resp.body());
                    JsonNode fields = root.path("fields");
                    if (!fields.isArray()) continue;
                    int yieldIdx = -1;
                    for (int i = 0; i < fields.size(); i++) {
                        if (fields.get(i).asText("").contains("殖利率")) { yieldIdx = i; break; }
                    }
                    if (yieldIdx < 0) continue;
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
                    stockCode, "台股", rate, "TWSE(3Y平均)",
                    "近 %d 年平均殖利率（%s%%）".formatted(yields.size(), pctStr), null));
        } catch (Exception e) {
            log.warn("TWSE 3 年平均殖利率查詢失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<DividendRateResult> getFinMindDividendRate(String stockCode) {
        Optional<DividendRateResult> r1 = getFinMindFromDataset(stockCode,
                "TaiwanStockDividend", "CashEarningsDistribution");
        if (r1.isPresent()) return r1;
        return getFinMindFromDataset(stockCode,
                "TaiwanStockDividendResult", "stock_and_cache_dividend");
    }

    private Optional<DividendRateResult> getFinMindFromDataset(
            String stockCode, String dataset, String dividendField) {
        try {
            String startDate = LocalDate.now().minusYears(4).toString();
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=" + dataset
                    + "&data_id=" + stockCode + "&start_date=" + startDate;
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 10), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FinMind({}) {} 回應 {}", dataset, stockCode, resp.statusCode());
                return Optional.empty();
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();

            java.util.Map<Integer, Double> annualDividend = new java.util.TreeMap<>();
            for (JsonNode item : data) {
                String dateStr = item.path("date").asText("");
                if (dateStr.length() < 4) continue;
                int year;
                try { year = Integer.parseInt(dateStr.substring(0, 4)); }
                catch (NumberFormatException e) { continue; }
                double cash;
                if ("CashEarningsDistribution".equals(dividendField)) {
                    cash = item.path("CashEarningsDistribution").asDouble(0)
                         + item.path("CashStatutorySurplus").asDouble(0);
                } else {
                    cash = item.path("stock_and_cache_dividend").asDouble(0);
                }
                if (cash <= 0) continue;
                annualDividend.merge(year, cash, Double::sum);
            }
            if (annualDividend.isEmpty()) return Optional.empty();
            int currentYear = LocalDate.now().getYear();
            annualDividend.remove(currentYear);
            if (annualDividend.isEmpty()) return Optional.empty();
            List<Double> yearlyDividends = new ArrayList<>(annualDividend.values());
            int fromIdx = Math.max(0, yearlyDividends.size() - 3);
            List<Double> recent = yearlyDividends.subList(fromIdx, yearlyDividends.size());
            double avgAnnualDividend = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (avgAnnualDividend <= 0) return Optional.empty();

            // 分母：歷史表最近一筆收盤價（簡化：不再走即時 API，避免雙倍依賴）
            double price = store.findRecentClose(stockCode, "台股")
                    .map(BigDecimal::doubleValue).orElse(0.0);
            if (price <= 0) return Optional.empty();

            double yieldRate = avgAnnualDividend / price;
            BigDecimal rate = BigDecimal.valueOf(yieldRate).setScale(6, RoundingMode.HALF_UP);
            String pctStr = BigDecimal.valueOf(yieldRate * 100).setScale(2, RoundingMode.HALF_UP).toPlainString();
            String divStr = BigDecimal.valueOf(avgAnnualDividend).setScale(2, RoundingMode.HALF_UP).toPlainString();
            return Optional.of(new DividendRateResult(
                    stockCode, "台股", rate, "FinMind(3Y平均)",
                    "近 %d 年平均配息 %s 元，殖利率 %s%%".formatted(recent.size(), divStr, pctStr), null));
        } catch (Exception e) {
            log.warn("FinMind({}) 查詢失敗 {}: {}", dataset, stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private static final java.util.Map<String, double[]> KNOWN_US_ETF_YIELDS = java.util.Map.of(
            "VOO", new double[]{0.0125, 1.25}, "VT", new double[]{0.0194, 1.94},
            "VTI", new double[]{0.0130, 1.30}, "VXUS", new double[]{0.0290, 2.90},
            "BND", new double[]{0.0370, 3.70}, "QQQ", new double[]{0.0058, 0.58});

    private Optional<DividendRateResult> getKnownUsEtfDividendRate(String stockCode) {
        double[] vals = KNOWN_US_ETF_YIELDS.get(stockCode);
        if (vals == null) return Optional.empty();
        BigDecimal rate = BigDecimal.valueOf(vals[0]).setScale(6, RoundingMode.HALF_UP);
        return Optional.of(new DividendRateResult(
                stockCode, "美股", rate, "預設值",
                "常見 ETF 參考殖利率 %.2f%%（資料來源：基金公司官網）".formatted(vals[1]), null));
    }

    /**
     * Yahoo Finance fallback：對所有 US-listed（含 NYSE / NYSEARCA）標的，
     * 用 chart endpoint 取最近 1 年的 dividends events，TTM 配息加總 ÷ 現價 = 殖利率。
     * 走 curl 子程序避免 Yahoo 的 fingerprint 偵測（與 fetchUsStockName 同一個模式）。
     */
    private Optional<DividendRateResult> getYahooDividendRate(String stockCode) {
        return getYahooDividendRateForTicker(stockCode, stockCode.trim().toUpperCase(), "美股");
    }

    /**
     * Yahoo chart events=div 取 TTM 殖利率（US / UK 共用）。
     * @param stockCode 存入 result 的代號（不含後綴，如 CSPX）
     * @param yahooTicker 對 Yahoo 查的完整 ticker（如 CSPX.L、JEPI）
     * @param market 存入 result 的市場字串（`美股` / `英股`）
     */
    private Optional<DividendRateResult> getYahooDividendRateForTicker(String stockCode, String yahooTicker, String market) {
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + yahooTicker + "?interval=1d&range=1y&events=div";
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            JsonNode result = mapper.readTree(body).path("chart").path("result").path(0);
            if (result.isMissingNode()) return Optional.empty();
            double price = result.path("meta").path("regularMarketPrice").asDouble(0);
            if (price <= 0) return Optional.empty();
            JsonNode divs = result.path("events").path("dividends");
            if (!divs.isObject() || divs.isEmpty()) return Optional.empty();
            double ttm = 0; int n = 0;
            for (JsonNode d : divs) {
                double amt = d.path("amount").asDouble(0);
                if (amt > 0) { ttm += amt; n++; }
            }
            if (n == 0 || ttm <= 0) return Optional.empty();
            BigDecimal rate = BigDecimal.valueOf(ttm / price).setScale(6, RoundingMode.HALF_UP);
            BigDecimal pct = BigDecimal.valueOf(ttm / price * 100).setScale(2, RoundingMode.HALF_UP);
            return Optional.of(new DividendRateResult(
                    stockCode, market, rate, "Yahoo Finance",
                    "TTM %d 筆配息合計 $%.4f，殖利率 %s%%".formatted(n, ttm, pct.toPlainString()),
                    null));
        } catch (Exception e) {
            log.warn("Yahoo dividend 查詢失敗 {}: {}", yahooTicker, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<DividendRateResult> getNasdaqDividendRateAny(String stockCode) {
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetClass=" + assetClass;
                String body = get(url);
                JsonNode root = mapper.readTree(body);
                String yieldStr = root.path("data").path("yield").asText("").trim();
                if (yieldStr.isEmpty() || yieldStr.equals("N/A") || yieldStr.equals("--")) continue;
                String numStr = yieldStr.replace("%", "").trim();
                BigDecimal rate = new BigDecimal(numStr).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                if (rate.compareTo(BigDecimal.ZERO) <= 0) continue;
                String annualized = root.path("data").path("annualizedDividend").asText("");
                return Optional.of(new DividendRateResult(
                        stockCode, "美股", rate, "NASDAQ",
                        "年化股息率（年化股利 %s）".formatted(annualized), null));
            } catch (Exception e) {
                log.warn("NASDAQ 查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        return Optional.empty();
    }

    // ─── ETF 持股 ───────────────────────────────────────────────────────────────

    private static final java.util.Set<String> US_ETF_WHITELIST = java.util.Set.of(
            "VOO", "VT", "VTI", "VGT", "VYM", "VNQ", "VXUS",
            "SPY", "QQQ", "DIA", "IVV", "IWM", "AVGO", "SCHD", "JEPI", "JEPQ");

    private static final java.util.Set<String> UK_ETF_WHITELIST = java.util.Set.of(
            "CSPX", "VWRA", "VUSA", "EIMI", "IWDA");

    public boolean isEtf(String stockCode, String market) {
        if (stockCode == null) return false;
        if ("台股".equals(market)) return stockCode.startsWith("00");
        if ("美股".equals(market)) return US_ETF_WHITELIST.contains(stockCode.toUpperCase());
        if ("英股".equals(market)) return UK_ETF_WHITELIST.contains(stockCode.toUpperCase());
        return false;
    }

    /**
     * 取得 ETF 成分股（含 12h in-memory cache）。成分股每日至多變動一次，加上 Yahoo 對短時間大量
     * quoteSummary 會 rate limit（429），故快取成功結果可避免 dashboard 每次切 tab 都重打 Yahoo。
     * 僅快取「有成分股」的成功結果；失敗（429 / 查無）不快取，以便稍後重試。
     */
    public EtfHoldingsResult getEtfHoldings(String stockCode, String market) {
        String key = market + "_" + stockCode;
        long now = System.currentTimeMillis();
        CachedEtfHoldings cached = etfHoldingsCache.get(key);
        if (cached != null && cached.expiresAt > now) return cached.result;
        EtfHoldingsResult result = fetchEtfHoldingsUncached(stockCode, market);
        if (result != null && result.holdings() != null && !result.holdings().isEmpty()) {
            etfHoldingsCache.put(key, new CachedEtfHoldings(result, now + ETF_HOLDINGS_TTL_MS));
        }
        return result;
    }

    private EtfHoldingsResult fetchEtfHoldingsUncached(String stockCode, String market) {
        if (!isEtf(stockCode, market)) {
            return new EtfHoldingsResult(stockCode, market, false, null, null,
                    "此股票非 ETF 或未在支援清單", List.of());
        }
        // 台股優先 MoneyDJ（完整成分股、單一來源、不像 Yahoo 會限流；FinMind dataset 已移除）
        if ("台股".equals(market)) {
            try {
                EtfHoldingsResult m = getMoneyDjEtfHoldings(stockCode, market);
                if (m != null && !m.holdings().isEmpty()) return m;
            } catch (Exception ignore) {}
        }
        try {
            EtfHoldingsResult y = getYahooEtfHoldings(stockCode, market);
            if (y != null && !y.holdings().isEmpty()) return y;
        } catch (Exception ignore) {}

        if ("美股".equals(market)) {
            return new EtfHoldingsResult(stockCode, market, false, null, null,
                    "Yahoo Finance 未提供此 ETF 的成分股資料", List.of());
        }
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanETFHoldings"
                    + "&data_id=" + stockCode;
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 15), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new EtfHoldingsResult(stockCode, market, true, "FinMind", null,
                        "FinMind 回應 " + resp.statusCode(), List.of());
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return new EtfHoldingsResult(stockCode, market, true, "FinMind", null,
                        "查無成分股資料", List.of());
            }
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
                holdings.add(new EtfHolding(code, name,
                        BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP), null));
            }
            holdings.sort((a, b) -> b.weight().compareTo(a.weight()));
            return new EtfHoldingsResult(stockCode, market, true, "FinMind", latestDate, null, holdings);
        } catch (Exception e) {
            log.warn("ETF 持股查詢失敗 {}: {}", stockCode, e.getMessage());
            return new EtfHoldingsResult(stockCode, market, true, "FinMind", null,
                    "查詢失敗：" + e.getMessage(), List.of());
        }
    }

    private static final Pattern MDJ_DATE = Pattern.compile("資料日期：([0-9/]+)");
    private static final Pattern MDJ_TR = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern MDJ_TD = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);

    /**
     * MoneyDJ ETF 持股明細（台股主來源）：完整成分股（非僅前 10），單一來源、不需 crumb、不易限流。
     * 頁面 https://www.moneydj.com/ETF/X/Basic/Basic0007a.xdjhtm?etfid={code}.TW
     * 持股明細表欄位：股票名稱 / 持股(千股) / 比例(%) / 增減。MoneyDJ 只揭露名稱（無代號），
     * 故 EtfHolding.stockCode 留空，由下游（BFF lookthrough）以 stockName 為聚合鍵。
     * 以 curl 子程序抓取（與 Yahoo 同理，避開部分站點對 Java HttpClient 的封鎖）。
     */
    private EtfHoldingsResult getMoneyDjEtfHoldings(String stockCode, String market) {
        for (String suffix : new String[]{".TW", ".TWO"}) {
            try {
                String url = "https://www.moneydj.com/ETF/X/Basic/Basic0007a.xdjhtm?etfid="
                        + stockCode + suffix;
                String html = runCurl("-s", "-m", "20", "-A", UA, url);
                EtfHoldingsResult r = parseMoneyDjHoldings(stockCode, market, html);
                if (r != null && !r.holdings().isEmpty()) return r;
            } catch (Exception e) {
                log.warn("MoneyDJ ETF 持股查詢失敗 {}{}: {}", stockCode, suffix, e.getMessage());
            }
        }
        return null;
    }

    /** 啟動後背景預熱「股名→代號」字典，避免重啟後第一筆 ETF 請求同步載入而逾時。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmTwNameToCodeOnStartup() {
        Thread t = new Thread(() -> {
            try {
                int n = twNameToCodeMap().size();
                log.info("股名→代號字典預熱完成：{} 檔", n);
            } catch (Exception e) {
                log.warn("股名→代號字典預熱失敗: {}", e.getMessage());
            }
        }, "namemap-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 台股「股名→代號」字典（24h cache）：TWSE STOCK_DAY_ALL（上市）+ TPEX（上櫃）。
     * 放記憶體而非 stock 主檔 — stock 表是抓價排程的股票清單（StockSourceQuery.collectAllStockCodes
     * 會 SELECT * FROM stock 去抓價），灌入全市場會造成排程爆量。
     */
    private synchronized Map<String, String> twNameToCodeMap() {
        long now = System.currentTimeMillis();
        if (!twNameToCode.isEmpty() && now < twNameToCodeExpiresAt) return twNameToCode;
        Map<String, String> map = new HashMap<>();
        // 上櫃先載、上市後載（同名時上市優先覆蓋）
        try {
            String body = runCurl("-s", "-m", "25", "-A", UA,
                    "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes");
            for (JsonNode n : mapper.readTree(body)) {
                String code = n.path("SecuritiesCompanyCode").asText("").trim();
                String name = n.path("CompanyName").asText("").trim();
                if (!code.isEmpty() && !name.isEmpty()) map.put(name, code);
            }
        } catch (Exception e) { log.warn("TPEX 股名清單載入失敗: {}", e.getMessage()); }
        try {
            String body = runCurl("-s", "-m", "25", "-A", UA,
                    "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL");
            for (JsonNode n : mapper.readTree(body)) {
                String code = n.path("Code").asText("").trim();
                String name = n.path("Name").asText("").trim();
                if (!code.isEmpty() && !name.isEmpty()) map.put(name, code);
            }
        } catch (Exception e) { log.warn("TWSE 股名清單載入失敗: {}", e.getMessage()); }
        if (!map.isEmpty()) {
            twNameToCode = map;
            twNameToCodeExpiresAt = now + TW_NAME_MAP_TTL_MS;
        }
        return twNameToCode;
    }

    private EtfHoldingsResult parseMoneyDjHoldings(String stockCode, String market, String html) {
        if (html == null || html.isEmpty()) return null;
        int idx = html.indexOf("股票名稱");
        if (idx < 0) return null;
        Map<String, String> nameToCode = twNameToCodeMap();
        String asOf = null;
        Matcher dm = MDJ_DATE.matcher(html);
        if (dm.find()) asOf = dm.group(1);
        // 持股明細表：從「股票名稱」表頭到該表結束
        String seg = html.substring(idx);
        int end = seg.indexOf("</table>");
        if (end > 0) seg = seg.substring(0, end);
        List<EtfHolding> holdings = new ArrayList<>();
        Matcher rm = MDJ_TR.matcher(seg);
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = MDJ_TD.matcher(rm.group(1));
            while (cm.find()) {
                cells.add(cm.group(1).replaceAll("<[^>]+>", "").replace("&nbsp;", "").trim());
            }
            if (cells.size() < 3) continue;
            String name = cells.get(0);
            if (name.isEmpty() || name.equals("股票名稱")) continue;
            try {
                BigDecimal weight = new BigDecimal(cells.get(2).replace(",", ""))
                        .setScale(4, RoundingMode.HALF_UP);
                if (weight.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal shares = null;
                try {
                    shares = new BigDecimal(cells.get(1).replace(",", ""))
                            .multiply(BigDecimal.valueOf(1000));
                } catch (NumberFormatException ignore) {}
                String code = nameToCode.getOrDefault(name, "");
                holdings.add(new EtfHolding(code, name, weight, shares));
            } catch (NumberFormatException ignore) {}
        }
        if (holdings.isEmpty()) return null;
        holdings.sort((a, b) -> b.weight().compareTo(a.weight()));
        return new EtfHoldingsResult(stockCode, market, true, "MoneyDJ", asOf, null, holdings);
    }

    private EtfHoldingsResult getYahooEtfHoldings(String stockCode, String market) {
        try {
            String symbol;
            if ("台股".equals(market)) symbol = stockCode + ".TW";
            else if ("英股".equals(market)) symbol = stockCode + ".L";
            else symbol = stockCode;
            String crumb = getYahooCrumb();
            String url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + symbol
                    + "?modules=topHoldings&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8);
            String body = yahooApiGet(url);
            JsonNode root = mapper.readTree(body);
            JsonNode result = root.path("quoteSummary").path("result");
            if (!result.isArray() || result.isEmpty()) return null;
            JsonNode arr = result.get(0).path("topHoldings").path("holdings");
            if ((!arr.isArray() || arr.isEmpty()) && "台股".equals(market)) {
                symbol = stockCode + ".TWO";
                url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + symbol
                        + "?modules=topHoldings&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8);
                body = yahooApiGet(url);
                root = mapper.readTree(body);
                arr = root.path("quoteSummary").path("result").get(0)
                        .path("topHoldings").path("holdings");
            }
            if (!arr.isArray() || arr.isEmpty()) return null;
            List<EtfHolding> list = new ArrayList<>();
            for (JsonNode h : arr) {
                String code = h.path("symbol").asText("");
                String name = h.path("holdingName").asText("");
                double weight = h.path("holdingPercent").path("raw").asDouble(0) * 100.0;
                list.add(new EtfHolding(code, name,
                        BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP), null));
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

    // ─── 股利歷史 ──────────────────────────────────────────────────────────────

    public DividendHistoryResult getDividendHistory(String stockCode, String market, int years) {
        int n = Math.max(1, Math.min(years, 20));
        if ("台股".equals(market)) return getTwDividendHistory(stockCode, n);
        if ("美股".equals(market)) return getUsDividendHistory(stockCode, n);
        if ("英股".equals(market)) return getYahooDividendHistory(stockCode, stockCode + ".L", "英股", n);
        return new DividendHistoryResult(stockCode, market, null, "不支援的市場", List.of());
    }

    /**
     * Yahoo chart events=div 取近 N 年股利歷史（英股 UCITS ETF 用；無 NASDAQ 對應 dataset）。
     */
    private DividendHistoryResult getYahooDividendHistory(String stockCode, String yahooTicker, String market, int years) {
        try {
            int range = Math.max(1, Math.min(years, 20));
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + yahooTicker + "?interval=1d&range=" + range + "y&events=div";
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            JsonNode divs = mapper.readTree(body)
                    .path("chart").path("result").path(0).path("events").path("dividends");
            if (!divs.isObject() || divs.isEmpty()) {
                return new DividendHistoryResult(stockCode, market, "Yahoo Finance", "查無股利資料", List.of());
            }
            List<DividendRow> rows = new ArrayList<>();
            for (JsonNode d : divs) {
                long ts = d.path("date").asLong(0);
                double amt = d.path("amount").asDouble(0);
                if (ts <= 0 || amt <= 0) continue;
                LocalDate exDate = java.time.Instant.ofEpochSecond(ts)
                        .atZone(java.time.ZoneId.of("Europe/London")).toLocalDate();
                rows.add(new DividendRow(exDate.getYear(),
                        BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO, exDate.toString(), null, null, null,
                        null, null));
            }
            rows.sort((a, b) -> {
                String ea = a.exDividendDate() != null ? a.exDividendDate() : "";
                String eb = b.exDividendDate() != null ? b.exDividendDate() : "";
                return eb.compareTo(ea);
            });
            return new DividendHistoryResult(stockCode, market, "Yahoo Finance", null, rows);
        } catch (Exception e) {
            log.warn("Yahoo 英股股利歷史查詢失敗 {}: {}", yahooTicker, e.getMessage());
            return new DividendHistoryResult(stockCode, market, "Yahoo Finance",
                    "查詢失敗：" + e.getMessage(), List.of());
        }
    }

    private DividendHistoryResult getTwDividendHistory(String stockCode, int years) {
        try {
            String startDate = LocalDate.now().minusYears(years).toString();
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockDividend"
                    + "&data_id=" + stockCode + "&start_date=" + startDate;
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 15), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new DividendHistoryResult(stockCode, "台股", "FinMind",
                        "FinMind 回應 " + resp.statusCode(), List.of());
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return new DividendHistoryResult(stockCode, "台股", "FinMind", "查無股利資料", List.of());
            }
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
                String cashPay = item.path("CashDividendPaymentDate").asText("");
                String stockPay = item.path("StockDividendPaymentDate").asText("");
                StockSourceQuery.DividendBasis basis = exDate.isEmpty()
                        ? StockSourceQuery.DividendBasis.EMPTY
                        : store.calcDividendBasis(stockCode, "台股", LocalDate.parse(exDate));
                rows.add(new DividendRow(year,
                        BigDecimal.valueOf(cash).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(stock).setScale(4, RoundingMode.HALF_UP),
                        exDate.isEmpty() ? null : exDate, null,
                        cashPay.isEmpty() ? null : cashPay,
                        stockPay.isEmpty() ? null : stockPay,
                        basis.fillDays(), basis.previousClose()));
            }
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
                String body = get(url);
                JsonNode rowsNode = mapper.readTree(body).path("data").path("dividends").path("rows");
                if (!rowsNode.isArray() || rowsNode.isEmpty()) continue;
                List<DividendRow> rows = new ArrayList<>();
                for (JsonNode r : rowsNode) {
                    String exDate = r.path("exOrEffDate").asText("");
                    if (exDate.length() < 10) continue;
                    int year;
                    String exIso;
                    try {
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
                    StockSourceQuery.DividendBasis basis = store.calcDividendBasis(
                            stockCode, "美股", LocalDate.parse(exIso));
                    rows.add(new DividendRow(year,
                            BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.ZERO, exIso, null, payIso, null,
                            basis.fillDays(), basis.previousClose()));
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
        return new DividendHistoryResult(stockCode, "美股", "NASDAQ", "查無股利資料", List.of());
    }

    // ─── TWSE 假日 ─────────────────────────────────────────────────────────────

    public Map<String, String> getTwHolidays(int year) {
        return twHolidayCache.computeIfAbsent(year, this::fetchTwHolidaysFromTwse);
    }

    private Map<String, String> fetchTwHolidaysFromTwse(int year) {
        int rocYear = year - 1911;
        String rocYearStr = String.valueOf(rocYear);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://openapi.twse.com.tw/v1/holidaySchedule/holidaySchedule"))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", UA).header("Accept", "application/json").GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            Map<String, String> holidays = new LinkedHashMap<>();
            for (JsonNode item : root) {
                String date = item.path("Date").asText();
                String name = item.path("Name").asText();
                if (name.contains("開始交易日") || name.contains("最後交易日")) continue;
                if (!date.startsWith(rocYearStr)) continue;
                String mmdd = date.substring(rocYearStr.length());
                int month = Integer.parseInt(mmdd.substring(0, 2));
                int day = Integer.parseInt(mmdd.substring(2, 4));
                holidays.put(String.format("%04d-%02d-%02d", year, month, day), name);
            }
            log.info("TWSE holidays fetched for {}: {} entries", year, holidays.size());
            return holidays;
        } catch (Exception e) {
            log.warn("Failed to fetch TWSE holidays for {}: {}", year, e.getMessage());
            return Map.of();
        }
    }

    // ─── 股票名稱 ──────────────────────────────────────────────────────────────

    /** 台股名稱：FinMind TaiwanStockInfo。 */
    public String fetchTwStockName(String code) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockInfo&data_id="
                    + code.trim().toUpperCase();
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 15), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "";
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (data.isArray() && data.size() > 0) {
                String name = data.get(0).path("stock_name").asText("").trim();
                if (!name.isEmpty() && !name.equalsIgnoreCase(code)) return name;
            }
        } catch (Exception e) {
            log.warn("FinMind 查詢台股名稱失敗 {}: {}", code, e.getMessage());
        }
        return "";
    }

    /** 美股名稱：Yahoo Finance chart meta（用 curl 子程序避免 fingerprint 偵測）。 */
    public String fetchUsStockName(String code) {
        return fetchYahooStockName(code, code.trim().toUpperCase());
    }

    /** 英股 UCITS ETF 名稱：Yahoo Finance chart meta，ticker = {code}.L。 */
    public String fetchUkStockName(String code) {
        return fetchYahooStockName(code, code.trim().toUpperCase() + ".L");
    }

    private String fetchYahooStockName(String code, String yahooTicker) {
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + yahooTicker
                    + "?interval=1d&range=1d";
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            JsonNode meta = mapper.readTree(body)
                    .path("chart").path("result").path(0).path("meta");
            String name = meta.path("shortName").asText("").trim();
            if (name.isEmpty()) name = meta.path("longName").asText("").trim();
            if (!name.isEmpty() && !name.equalsIgnoreCase(code)) return name;
        } catch (Exception e) {
            log.warn("Yahoo 查詢股票名稱失敗 {}: {}", yahooTicker, e.getMessage());
        }
        return "";
    }

    // ─── HTTP helpers / Yahoo crumb ───────────────────────────────────────────

    /**
     * 取得 Yahoo crumb。改用 curl 子程序（而非 Java HttpClient）：Yahoo 的反 bot WAF 會依 TLS/HTTP
     * 指紋辨識 Java HttpClient 並對 getcrumb / quoteSummary 一律回 429（同容器、同 IP 的 curl 卻正常）。
     * 流程：fc.yahoo.com prime A1/A3 cookie 到 cookie 檔（回 404 但 Set-Cookie）→ /v1/test/getcrumb 取 crumb。
     */
    private synchronized String getYahooCrumb() throws Exception {
        if (yahooCrumb != null) return yahooCrumb;
        if (System.currentTimeMillis() < yahooCrumbBlockedUntil) {
            throw new RuntimeException("Yahoo Finance crumb negative cache 中");
        }
        // prime cookie 到檔案（fc.yahoo.com 回 404 但帶 Set-Cookie: A1/A3）
        runCurl("-s", "-c", YAHOO_COOKIE_FILE, "-A", YAHOO_UA, "https://fc.yahoo.com");
        String crumb = runCurl("-s", "-b", YAHOO_COOKIE_FILE, "-A", YAHOO_UA,
                "https://query2.finance.yahoo.com/v1/test/getcrumb").trim();
        if (!crumb.isBlank() && !crumb.contains(" ") && crumb.length() <= 50
                && !crumb.startsWith("{") && !crumb.contains("Too Many")) {
            yahooCrumb = crumb;
            return yahooCrumb;
        }
        yahooCrumbBlockedUntil = System.currentTimeMillis() + YAHOO_CRUMB_BLOCK_MS;
        throw new RuntimeException("無法取得 Yahoo Finance crumb");
    }

    /** 以 curl 子程序抓 Yahoo quoteSummary（帶 prime 過的 cookie 檔 + 短 UA）；回傳 response body。 */
    private String yahooApiGet(String url) throws Exception {
        return runCurl("-s", "-b", YAHOO_COOKIE_FILE, "-A", YAHOO_UA, url);
    }

    /** 執行 curl 並回傳 stdout（合併 stderr）。 */
    private String runCurl(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("curl");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();
        return out;
    }

    private String get(String url) throws Exception {
        HttpResponse<String> resp = httpClient.send(buildRequest(url), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/html, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "identity")
                .header("Referer", "https://finance.yahoo.com/");
        return b.GET().build();
    }

    private HttpRequest finmindRequest(String url, int timeoutSec) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity");
        if (!finmindToken.isEmpty()) {
            b.header("Authorization", "Bearer " + finmindToken);
        }
        return b.GET().build();
    }
}
