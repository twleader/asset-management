package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 對外行情資料抓取：
 * - 台股 live：TWSE mis API
 * - 美股 live：NASDAQ info API
 * - 台股盤後收盤：FinMind TaiwanStockPrice
 *
 * 從 backend MarketDataService 抽出價格相關方法（dividend / ETF / holiday 等留在 business-services）。
 */
@Slf4j
@Component
public class PriceFetchClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String finmindToken;

    public PriceFetchClient(@Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken) {
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        // 強制 HTTP/1.1：NASDAQ / Cloudflare 對 Java 的 HTTP/2 fingerprint 偵測會回 RST_STREAM。
        // 用 HTTP/1.1 才能穩定取到資料。
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cm)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

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
    ) {}

    /**
     * 取得 live 股價。回傳 empty 代表「這一輪不更新」：
     *  - 台股 z='-'（本輪 polling 撞到兩 tick 之間沒有新成交的 5 秒視窗）→ 略過寫 Redis，
     *    保留上一輪成功 poll 的當日 intraday 成交價。**不得退回 y（昨日收盤）覆寫 Redis**，
     *    否則盤中圖表會整批跳回昨收（spec Requirement 7、Task 79）。
     *  - 台股 / 美股 API 查無資料、HTTP 錯誤等也回 empty。
     */
    public Optional<PriceResult> getStockPrice(String stockCode, String market) {
        if ("台股".equals(market)) {
            return getTwseRealTimePrice(stockCode);
        }
        if ("英股".equals(market)) {
            return getYahooLsePrice(stockCode);
        }
        return getNasdaqPrice(stockCode);
    }

    /**
     * 英股（LSE 掛牌 UCITS ETF，如 CSPX.L）即時報價：Yahoo Finance chart endpoint。
     * 走 curl 子程序避開 Yahoo 對 Java HTTP/2 fingerprint 的偵測（與 fetchUsHistoricalRange 同 pattern）。
     * 收盤後 meta.regularMarketPrice 維持當日最後成交價，符合 Requirement 7「抓不到最新值→保留上一筆」精神。
     *
     * <p><b>開盤價取自 {@code indicators.quote[0].open[0]}，不可用 {@code meta.regularMarketOpen}</b>
     * （實測不存在於 Yahoo chart meta，Task 194 修正前誤用該欄，英股 {@code openPrice} 因而恆為 null）；
     * 昨收同理只有 {@code chartPreviousClose} 有值（{@code meta.previousClose} 亦不存在）。
     * high / low / volume 則實測確實存在於 meta，維持沿用。與 {@link #fetchUsTodayOpenFromYahoo}
     * ／{@link #fetchKrIntradayQuote} 同一套欄位落點。
     */
    private Optional<PriceResult> getYahooLsePrice(String stockCode) {
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + stockCode + ".L?interval=1d&range=1d";
            String body = curlGetWithRetry(url, 2);
            JsonNode result = mapper.readTree(body).path("chart").path("result").path(0);
            JsonNode meta = result.path("meta");
            if (meta.isMissingNode() || meta.isEmpty()) return Optional.empty();
            BigDecimal price = jsonDecimal(meta.path("regularMarketPrice"));
            if (price == null) return Optional.empty();
            BigDecimal prevClose = jsonDecimal(meta.path("chartPreviousClose"));
            BigDecimal change = prevClose != null ? price.subtract(prevClose) : null;
            BigDecimal changePct = (prevClose != null && prevClose.signum() > 0)
                    ? change.multiply(BigDecimal.valueOf(100)).divide(prevClose, 6, RoundingMode.HALF_UP)
                    : null;
            BigDecimal open = jsonDecimal(
                    result.path("indicators").path("quote").path(0).path("open").path(0));
            BigDecimal high = jsonDecimal(meta.path("regularMarketDayHigh"));
            BigDecimal low = jsonDecimal(meta.path("regularMarketDayLow"));
            Long volume = meta.hasNonNull("regularMarketVolume") ? meta.get("regularMarketVolume").asLong() : null;
            String name = meta.path("shortName").asText("");
            if (name.isBlank()) name = meta.path("longName").asText("");
            return Optional.of(new PriceResult(
                    stockCode, "英股", price, change, changePct, "Yahoo",
                    name.isBlank() ? null : name,
                    null, null, open, prevClose, high, low, volume));
        } catch (Exception e) {
            log.warn("Yahoo LSE 即時報價失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 從 FinMind USStockPrice 取得美股當日收盤資訊（盤後 1-2 小時發佈）。
     * 用於 16:02 ET Redis dump 之後 18:00 ET 的權威性校正。
     */
    public Optional<PriceResult> getUsClosingPriceFromFinMind(String stockCode, LocalDate startDate) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=USStockPrice"
                    + "&data_id=" + stockCode
                    + "&start_date=" + startDate.toString();
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 15), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FinMind USStockPrice {} 回應 {}（token {}）", stockCode,
                        resp.statusCode(), finmindToken.isEmpty() ? "未設定" : "已設定");
                return Optional.empty();
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();
            JsonNode row = data.get(data.size() - 1);
            BigDecimal close = finmindDecimal(row, "Close");
            if (close == null) return Optional.empty();
            BigDecimal open = finmindDecimal(row, "Open");
            BigDecimal high = finmindDecimal(row, "High");
            BigDecimal low = finmindDecimal(row, "Low");
            long volume = row.path("Volume").asLong(0);
            return Optional.of(new PriceResult(
                    stockCode, "美股", close, null, null, "FinMind",
                    null, null, null,
                    open, null, high, low,
                    volume == 0 ? null : volume));
        } catch (Exception e) {
            log.warn("FinMind 取得美股 {} 收盤失敗: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<PriceResult> getTwClosingPriceFromFinMind(String stockCode, LocalDate expectedDate) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=TaiwanStockPrice"
                    + "&data_id=" + stockCode
                    + "&start_date=" + expectedDate;
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 15), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FinMind TaiwanStockPrice {} 回應 {}（token {}）", stockCode,
                        resp.statusCode(), finmindToken.isEmpty() ? "未設定" : "已設定");
                return Optional.empty();
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) return Optional.empty();
            JsonNode row = data.get(data.size() - 1);
            return parseTwClosingRow(stockCode, row, expectedDate);
        } catch (Exception e) {
            log.warn("FinMind 取得台股 {} 收盤失敗: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** 僅接受來源交易日等於要求日期的 FinMind 台股收盤列，避免把 T-1 OHLC 誤標成今日。 */
    static Optional<PriceResult> parseTwClosingRow(
            String stockCode, JsonNode row, LocalDate expectedDate) {
        if (row == null || expectedDate == null) return Optional.empty();
        try {
            JsonNode dateNode = row.get("date");
            if (dateNode == null || dateNode.isNull()) return Optional.empty();
            LocalDate sourceDate = LocalDate.parse(dateNode.asText());
            if (!expectedDate.equals(sourceDate)) return Optional.empty();
            BigDecimal close = finmindDecimal(row, "close");
            if (close == null) return Optional.empty();
            BigDecimal open = finmindDecimal(row, "open");
            BigDecimal high = finmindDecimal(row, "max");
            BigDecimal low  = finmindDecimal(row, "min");
            BigDecimal spread = finmindDecimal(row, "spread");
            long volume = row.path("Trading_Volume").asLong(0);
            BigDecimal previousClose = spread != null ? close.subtract(spread) : null;
            BigDecimal changePct = null;
            if (spread != null && previousClose != null && previousClose.signum() > 0) {
                changePct = spread.divide(previousClose, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
            return Optional.of(new PriceResult(
                    stockCode, "台股", close, spread, changePct, "FinMind",
                    null, null, null,
                    open, previousClose, high, low,
                    volume == 0 ? null : volume));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static BigDecimal finmindDecimal(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("");
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
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

    private Optional<PriceResult> getNasdaqPrice(String stockCode) {
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode + "/info?assetClass=" + assetClass;
                String body = httpGet(url);
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
                if (change.compareTo(BigDecimal.ZERO) < 0 && changePct.compareTo(BigDecimal.ZERO) > 0) {
                    changePct = changePct.negate();
                }

                String companyName = root.path("data").path("companyName").asText("");
                if (companyName.isBlank()) companyName = null;

                // NASDAQ API 現況（2026/04 起）：keyStats 對 ETF 為 null，對 stocks 只剩 dayrange + 52 週區間，
                // 不再提供 OpenPrice / PreviousClose / Volume。改從 primaryData 取，OpenPrice 改由 Yahoo daily bar 補。
                JsonNode keyStats = root.path("data").path("keyStats");
                BigDecimal[] hl = parseRange(keyStats.path("dayrange").path("value").asText(""));
                if (hl[0] == null && hl[1] == null) {
                    hl = parseRange(keyStats.path("Dayrange").path("value").asText(""));
                }
                BigDecimal openPrice = fetchUsTodayOpenFromYahoo(stockCode).orElse(null);

                // previousClose = price − netChange（API 拿不到實際昨收，用即時計算）
                BigDecimal previousClose = price.subtract(change);

                BigDecimal buyPrice = parseDollar(pd.path("bidPrice").asText(""));
                BigDecimal sellPrice = parseDollar(pd.path("askPrice").asText(""));
                Long volume = parseLong(pd.path("volume").asText(""));

                return Optional.of(new PriceResult(stockCode, "美股", price, change, changePct, "NASDAQ",
                        companyName, buyPrice, sellPrice, openPrice, previousClose, hl[0], hl[1], volume));
            } catch (Exception e) {
                log.warn("NASDAQ price 查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * 美股今日開盤價：NASDAQ `/info` 自 2026/04 不再回 OpenPrice，`/historical`「fromdate==todate」回 400
     * （Provided date is less than from date）、給日期區間盤中又不含今日列，皆無法取得今日 open。
     * 改由 Yahoo chart `interval=1d&range=1d` 的當日 daily bar `indicators.quote[0].open[0]` 取得
     * （盤中＝今日部分 bar 的 open、盤後＝當日完整 bar 的 open；只取 open 欄，不碰 close，不違反 Task 84
     * 「禁寫今日列收盤」）。走 curl 子程序避開 Yahoo 對 Java HTTP/2 fingerprint 偵測。為盤中輪詢的補強
     * 欄位，遇 429 fail-fast 不重試（maxRetries=0）避免拖慢 cron 週期；取不到回 empty，下游
     * `WatchStockService` fallback 至 stock_price_history。
     */
    private Optional<BigDecimal> fetchUsTodayOpenFromYahoo(String stockCode) {
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + stockCode
                    + "?interval=1d&range=1d";
            String body = curlGetWithRetry(url, 0);
            JsonNode open = mapper.readTree(body)
                    .path("chart").path("result").path(0)
                    .path("indicators").path("quote").path(0)
                    .path("open");
            if (!open.isArray() || open.isEmpty()) return Optional.empty();
            return Optional.ofNullable(jsonDecimal(open.path(0)));
        } catch (Exception e) {
            log.debug("Yahoo 今日 open 查詢失敗 {}: {}", stockCode, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<PriceResult> getTwseRealTimePrice(String stockCode) {
        for (String ex : new String[]{"tse", "otc"}) {
            try {
                String url = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch="
                        + ex + "_" + stockCode + ".tw&json=1&delay=3000";
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
                String prevStr = item.path("y").asText("").trim();
                String name = item.path("n").asText("").trim();

                BigDecimal prevClose = (prevStr.isEmpty() || prevStr.startsWith("-"))
                        ? null : new BigDecimal(prevStr);

                BigDecimal buyPrice = parseFirstQuote(item.path("b").asText(""));
                BigDecimal sellPrice = parseFirstQuote(item.path("a").asText(""));
                BigDecimal openPrice = parseDecimal(item.path("o").asText(""));
                BigDecimal highPrice = parseDecimal(item.path("h").asText(""));
                BigDecimal lowPrice = parseDecimal(item.path("l").asText(""));
                Long volumeLots = parseLong(item.path("v").asText(""));

                if (priceStr.isEmpty() || priceStr.startsWith("-")) {
                    // `z='-'` = 本輪 polling 撞到「兩 tick 之間沒有新成交」的 5 秒視窗 → 本輪不更新股價。
                    // PricePoller 收到 empty 即略過寫入，保留上一輪成功 poll 寫入的 Redis 值；
                    // 若 Redis 完全空（首輪、剛清過 cache、TTL 過期），`PriceQueryService.getLive` 自然 fallback
                    // 至 `stock_price_history` 最近一筆收盤（=昨日收盤）— 規格 Requirement 7、Task 79+81。
                    // 不得用 `o`（今日開盤）或 `y`（昨日收盤）回寫 Redis：盤中 Redis 內的值只能由真實 z tick
                    // 推進，避免「圖表看起來在動但其實是推估值」的不誠實顯示。
                    log.debug("台股 {} z='-'，本輪 polling 略過寫入（保留上輪 cache，無 cache 則由 PriceQueryService fallback 昨收）", stockCode);
                    return Optional.empty();
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

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/html, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "identity")
                .header("Referer", "https://finance.yahoo.com/")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static BigDecimal parseFirstQuote(String s) {
        if (s == null || s.isBlank() || s.startsWith("-")) return null;
        return parseDecimal(s.split("_")[0].trim());
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "-".equals(t) || "--".equals(t) || "N/A".equalsIgnoreCase(t)) return null;
        try { return new BigDecimal(t.replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "-".equals(t) || "--".equals(t) || "N/A".equalsIgnoreCase(t)) return null;
        // NASDAQ volume 可能帶小數（如 "108,567,313.747204"），truncate 取整
        String cleaned = t.replace(",", "");
        int dot = cleaned.indexOf('.');
        if (dot >= 0) cleaned = cleaned.substring(0, dot);
        try { return Long.parseLong(cleaned); }
        catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal parseDollar(String s) {
        if (s == null) return null;
        return parseDecimal(s.replace("$", "").replace(",", "").trim());
    }

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

    // ═══════════════════════════════════════════════════════════════════════
    //  歷史收盤價 range 抓取（10 年回補 / 缺口補齊用）
    // ═══════════════════════════════════════════════════════════════════════

    public record HistoricalBar(
            LocalDate tradingDate,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            Long volume,
            String resolvedCode  // FinMind 部分 ETF 需後綴 (00642U/L/R/B)，回傳實際抓到的 code 供 log
    ) {}

    /**
     * FinMind TaiwanStockPrice 拉指定日期區間。部分特殊 ETF 需後綴 (U/L/R/B)，依序嘗試。
     */
    public List<HistoricalBar> fetchTwHistoricalRange(String stockCode, LocalDate start, LocalDate end) {
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(stockCode);
        if (stockCode.matches("\\d{5}")) {
            candidates.add(stockCode + "U");
            candidates.add(stockCode + "L");
            candidates.add(stockCode + "R");
            candidates.add(stockCode + "B");
        }
        for (String candidate : candidates) {
            try {
                String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockPrice"
                        + "&data_id=" + candidate + "&start_date=" + start + "&end_date=" + end;
                HttpResponse<String> resp = httpClient.send(
                        finmindRequest(url, 20), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("FinMind TaiwanStockPrice {} 回應 {}", candidate, resp.statusCode());
                    continue;
                }
                JsonNode data = mapper.readTree(resp.body()).path("data");
                if (!data.isArray() || data.isEmpty()) continue;
                List<HistoricalBar> bars = new java.util.ArrayList<>();
                for (JsonNode row : data) {
                    LocalDate date = LocalDate.parse(row.path("date").asText());
                    BigDecimal close = finmindDecimal(row, "close");
                    if (close == null) continue;
                    bars.add(new HistoricalBar(
                            date,
                            finmindDecimal(row, "open"),
                            finmindDecimal(row, "max"),
                            finmindDecimal(row, "min"),
                            close,
                            row.path("Trading_Volume").asLong(0),
                            candidate));
                }
                if (!candidate.equals(stockCode)) {
                    log.info("台股 {} 在 FinMind 的完整代號為 {}", stockCode, candidate);
                }
                return bars;
            } catch (Exception e) {
                log.warn("FinMind TaiwanStockPrice {} 失敗: {}", candidate, e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * Yahoo Finance chart API 拉美股指定日期區間（用 curl 子程序，避開 Yahoo 對 Java HTTP/2 fingerprint 的封鎖）。
     */
    public List<HistoricalBar> fetchUsHistoricalRange(String stockCode, LocalDate start, LocalDate end) {
        try {
            long period1 = start.atStartOfDay(java.time.ZoneId.of("America/New_York")).toEpochSecond();
            long period2 = end.plusDays(1).atStartOfDay(java.time.ZoneId.of("America/New_York")).toEpochSecond();
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + stockCode
                    + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d";
            String body = curlGetWithRetry(url, 2);

            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo Finance {} 無資料: {}", stockCode, err.isEmpty() ? "no timestamps" : err);
                return List.of();
            }
            List<HistoricalBar> bars = new java.util.ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                long ts = timestamps.get(i).asLong();
                LocalDate date = java.time.Instant.ofEpochSecond(ts)
                        .atZone(java.time.ZoneId.of("America/New_York")).toLocalDate();
                if (date.isBefore(start)) continue;
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                bars.add(new HistoricalBar(
                        date,
                        jsonDecimal(quotes.path("open").path(i)),
                        jsonDecimal(quotes.path("high").path(i)),
                        jsonDecimal(quotes.path("low").path(i)),
                        jsonDecimal(close),
                        quotes.path("volume").path(i).asLong(0),
                        stockCode));
            }
            return bars;
        } catch (Exception e) {
            log.warn("Yahoo Finance {} 區間抓取失敗: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * Yahoo Finance chart API 拉英股（LSE 掛牌 UCITS ETF，如 CSPX.L）指定日期區間。
     * 時區為 Europe/London；其餘行為與 fetchUsHistoricalRange 一致。
     */
    public List<HistoricalBar> fetchUkHistoricalRange(String stockCode, LocalDate start, LocalDate end) {
        try {
            java.time.ZoneId zone = java.time.ZoneId.of("Europe/London");
            long period1 = start.atStartOfDay(zone).toEpochSecond();
            long period2 = end.plusDays(1).atStartOfDay(zone).toEpochSecond();
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + stockCode + ".L"
                    + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d";
            String body = curlGetWithRetry(url, 2);
            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo Finance {}.L 無資料: {}", stockCode, err.isEmpty() ? "no timestamps" : err);
                return List.of();
            }
            List<HistoricalBar> bars = new java.util.ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                long ts = timestamps.get(i).asLong();
                LocalDate date = java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalDate();
                if (date.isBefore(start)) continue;
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                bars.add(new HistoricalBar(
                        date,
                        jsonDecimal(quotes.path("open").path(i)),
                        jsonDecimal(quotes.path("high").path(i)),
                        jsonDecimal(quotes.path("low").path(i)),
                        jsonDecimal(close),
                        quotes.path("volume").path(i).asLong(0),
                        stockCode));
            }
            return bars;
        } catch (Exception e) {
            log.warn("Yahoo Finance {}.L 區間抓取失敗: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * Yahoo Finance chart API 拉韓股（KRX 掛牌個股，如三星電子 005930、SK 海力士 000660）指定日期區間。
     * 時區為 Asia/Seoul、symbol 加 {@code .KS} 後綴（幣別 KRW）；其餘行為與 fetchUsHistoricalRange 一致。
     * 供公開資訊爬蟲的韓股快照（category=kr-market）取用。
     */
    public List<HistoricalBar> fetchKrHistoricalRange(String stockCode, LocalDate start, LocalDate end) {
        try {
            java.time.ZoneId zone = java.time.ZoneId.of("Asia/Seoul");
            long period1 = start.atStartOfDay(zone).toEpochSecond();
            long period2 = end.plusDays(1).atStartOfDay(zone).toEpochSecond();
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + stockCode + ".KS"
                    + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d";
            String body = curlGetWithRetry(url, 2);
            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo Finance {}.KS 無資料: {}", stockCode, err.isEmpty() ? "no timestamps" : err);
                return List.of();
            }
            List<HistoricalBar> bars = new java.util.ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                long ts = timestamps.get(i).asLong();
                LocalDate date = java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalDate();
                if (date.isBefore(start)) continue;
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                bars.add(new HistoricalBar(
                        date,
                        jsonDecimal(quotes.path("open").path(i)),
                        jsonDecimal(quotes.path("high").path(i)),
                        jsonDecimal(quotes.path("low").path(i)),
                        jsonDecimal(close),
                        quotes.path("volume").path(i).asLong(0),
                        stockCode));
            }
            return bars;
        } catch (Exception e) {
            log.warn("Yahoo Finance {}.KS 區間抓取失敗: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 韓股盤中報價（Task 193）：現價＋當日開盤價＋對前一交易日收盤漲跌%（單位為百分點），
     * 另附資料所屬 KST 交易日供呼叫端做交易日閘門。缺料欄位為 null（逐項 graceful）。
     */
    public record KrIntradayQuote(
            String symbol,
            String name,
            BigDecimal price,
            BigDecimal open,
            BigDecimal prevClose,
            BigDecimal changePct,
            LocalDate sessionDate
    ) {}

    /**
     * Yahoo Finance chart API 取韓股（KRX）盤中即時報價，供公開資訊爬蟲的韓股盤中快照
     * （{@code category=kr-intraday}，Task 193）取用。
     *
     * <p>參數為<b>完整 Yahoo symbol</b>（個股 {@code 005930.KS}、大盤 {@code ^KS11}）——大盤無 {@code .KS}
     * 後綴，故不沿用 {@link #fetchKrHistoricalRange} 的後綴字串串接；{@code ^} 於此統一 URL-encode。
     * 走 {@link #curlGetWithRetry}（短 UA {@code Mozilla/5.0}），<b>不可改用 {@code httpGet}</b>
     * （長 Chrome UA 會被 Yahoo WAF 回 429）。亦不走 {@link #getStockPrice}——其 else 分支落到 NASDAQ。
     *
     * <p><b>開盤價取自 {@code indicators.quote[0].open[0]}，而非 {@code meta.regularMarketOpen}</b>：
     * 後者實測不存在於 Yahoo chart meta，誤用會使開盤價恆為 null、功能靜默半殘
     * （{@link #getYahooLsePrice} 原即誤用該欄，英股 {@code openPrice} 恆為 null，Task 194 已修正）。
     * 昨收同理只取 {@code chartPreviousClose}（{@code meta.previousClose} 亦不存在）。
     *
     * <p>{@code sessionDate} 由 {@code meta.regularMarketTime} 換算 {@code Asia/Seoul} 求得，供呼叫端判斷
     * 韓國是否休市（休市時 Yahoo 回前一交易日 bar，日期對不上即可判定，免自建農曆韓國假日曆）。
     * ⚠️ {@code regularMarketTime} <b>只可取日期</b>——實測 KOSPI 回 18:05 KST（落在 15:30 收盤後），
     * 拿它判「現在是否盤中」會誤判；「是否盤中」應由呼叫端以本地時鐘判定。
     */
    public Optional<KrIntradayQuote> fetchKrIntradayQuote(String yahooSymbol) {
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + java.net.URLEncoder.encode(yahooSymbol, java.nio.charset.StandardCharsets.UTF_8)
                    + "?interval=1d&range=1d";
            String body = curlGetWithRetry(url, 2);
            JsonNode result = mapper.readTree(body).path("chart").path("result").path(0);
            JsonNode meta = result.path("meta");
            if (meta.isMissingNode() || meta.isEmpty()) return Optional.empty();

            BigDecimal price = jsonDecimal(meta.path("regularMarketPrice"));
            if (price == null) return Optional.empty();

            long marketTime = meta.path("regularMarketTime").asLong(0);
            if (marketTime <= 0) return Optional.empty();   // 無時戳＝無法判交易日，寧可不產出
            LocalDate sessionDate = java.time.Instant.ofEpochSecond(marketTime)
                    .atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();

            BigDecimal prevClose = jsonDecimal(meta.path("chartPreviousClose"));
            BigDecimal changePct = (prevClose != null && prevClose.signum() > 0)
                    ? price.subtract(prevClose).multiply(BigDecimal.valueOf(100))
                            .divide(prevClose, 4, RoundingMode.HALF_UP)
                    : null;

            BigDecimal open = jsonDecimal(
                    result.path("indicators").path("quote").path(0).path("open").path(0));

            String name = meta.path("shortName").asText("");
            if (name.isBlank()) name = meta.path("longName").asText("");

            return Optional.of(new KrIntradayQuote(
                    yahooSymbol, name.isBlank() ? null : name,
                    price, open, prevClose, changePct, sessionDate));
        } catch (Exception e) {
            log.warn("Yahoo 韓股盤中報價失敗 {}: {}", yahooSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    /** 盤中 5 分鐘 K 線：Yahoo Finance chart API range=Nd / interval=5m，用於警示觸發補抓精確時點。 */
    public record IntradayBar(
            String time,        // ISO LocalDateTime（exchangeTimezone 當地時區）
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {}

    public List<IntradayBar> fetchIntraday5m(String stockCode, String market, int daysBack) {
        if ("台股".equals(market)) {
            // 台股 Yahoo ticker 後綴依掛牌市場：上市（TSE）`.TW`、上櫃（TPEx，含債券 ETF 00xxxB）`.TWO`。
            // 先試 `.TW`，回傳空再 fallback `.TWO`（同 getYahooEtfHoldings / 殖利率查詢既有慣例）。
            // 否則上櫃股的「當日」分時恆空，前端顯示「無當日分時資料」（spec Task 119）。
            List<IntradayBar> bars = fetchIntraday5mYahoo(stockCode + ".TW", daysBack);
            if (bars.isEmpty()) bars = fetchIntraday5mYahoo(stockCode + ".TWO", daysBack);
            return bars;
        }
        String ticker = "英股".equals(market) ? stockCode + ".L" : stockCode;
        return fetchIntraday5mYahoo(ticker, daysBack);
    }

    private List<IntradayBar> fetchIntraday5mYahoo(String ticker, int daysBack) {
        try {
            String range = Math.max(1, daysBack) + "d";
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/" + ticker
                    + "?interval=5m&range=" + range;
            String body = curlGetWithRetry(url, 2);

            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            String tz = chart.path("meta").path("exchangeTimezoneName").asText("Asia/Taipei");
            java.time.ZoneId zone = java.time.ZoneId.of(tz);
            if (!timestamps.isArray()) return List.of();

            List<IntradayBar> bars = new java.util.ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                long ts = timestamps.get(i).asLong();
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                String time = java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalDateTime().toString();
                bars.add(new IntradayBar(time,
                        jsonDecimal(quotes.path("open").path(i)),
                        jsonDecimal(quotes.path("high").path(i)),
                        jsonDecimal(quotes.path("low").path(i)),
                        jsonDecimal(close)));
            }
            return bars;
        } catch (Exception e) {
            log.warn("抓取 {} 盤中 5m 失敗: {}", ticker, e.getMessage());
            return List.of();
        }
    }

    /**
     * FinMind TaiwanStockKBar 5 分鐘 K 線：盤後抓當日完整資料覆寫 Redis tick LIST 用。
     * 回傳 (time, close)；time 為 ISO LocalDateTime（"YYYY-MM-DDTHH:mm:ss"，UTC+8 wall clock）。
     * 用 close 而非 OHLC 是因為「當日」分時走勢 series 只畫一條 price 線。
     */
    public record TickBar(String time, BigDecimal price) {}

    public List<TickBar> fetchTwKBar5m(String stockCode, LocalDate date) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=TaiwanStockKBar"
                    + "&data_id=" + stockCode
                    + "&start_date=" + date
                    + "&end_date=" + date;
            HttpResponse<String> resp = httpClient.send(
                    finmindRequest(url, 20), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FinMind TaiwanStockKBar {} {} 回應 {}（token {}）",
                        stockCode, date, resp.statusCode(),
                        finmindToken.isEmpty() ? "未設定" : "已設定");
                return List.of();
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) return List.of();
            List<TickBar> ticks = new java.util.ArrayList<>();
            for (JsonNode row : data) {
                BigDecimal close = finmindDecimal(row, "close");
                if (close == null) continue;
                String d = row.path("date").asText("");          // YYYY-MM-DD
                String t = row.path("minute").asText("");         // HH:mm or HH:mm:ss
                if (d.isBlank() || t.isBlank()) continue;
                if (t.length() == 5) t = t + ":00";               // 補秒
                ticks.add(new TickBar(d + "T" + t, close));
            }
            return ticks;
        } catch (Exception e) {
            log.warn("FinMind TaiwanStockKBar {} {} 失敗: {}", stockCode, date, e.getMessage());
            return List.of();
        }
    }

    /**
     * curl 子程序 + 重試（Yahoo Finance 對 Java HTTP client 友善度差，且偶發 429）。
     * 回應非 JSON 視為被擋，等 10s/20s/30s... 後重試，最多 maxRetries 次。
     */
    private String curlGetWithRetry(String url, int maxRetries) throws Exception {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0",
                    url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return body;
            if (attempt < maxRetries) {
                long waitMs = (attempt + 1) * 10000L;
                log.info("Yahoo Finance 回非 JSON（疑 429），{}s 後重試 ({}/{})",
                        waitMs / 1000, attempt + 1, maxRetries);
                try { Thread.sleep(waitMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("中斷", e);
                }
            }
        }
        throw new RuntimeException("Yahoo Finance 重試 " + maxRetries + " 次仍失敗");
    }

    private static BigDecimal jsonDecimal(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        return BigDecimal.valueOf(v.asDouble()).setScale(4, RoundingMode.HALF_UP);
    }

}
