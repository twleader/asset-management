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

    public PriceResult getStockPrice(String stockCode, String market) {
        if ("台股".equals(market)) {
            return getTwseRealTimePrice(stockCode)
                    .orElseThrow(() -> new RuntimeException("查無股價：" + stockCode));
        }
        return getNasdaqPrice(stockCode)
                .orElseThrow(() -> new RuntimeException("查無股價：" + stockCode));
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

    public Optional<PriceResult> getTwClosingPriceFromFinMind(String stockCode, LocalDate startDate) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=TaiwanStockPrice"
                    + "&data_id=" + stockCode
                    + "&start_date=" + startDate.toString();
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
            log.warn("FinMind 取得台股 {} 收盤失敗: {}", stockCode, e.getMessage());
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
                // 不再提供 OpenPrice / PreviousClose / Volume。改從 primaryData 取，OpenPrice 無資料就留 null。
                JsonNode keyStats = root.path("data").path("keyStats");
                BigDecimal[] hl = parseRange(keyStats.path("dayrange").path("value").asText(""));
                if (hl[0] == null && hl[1] == null) {
                    hl = parseRange(keyStats.path("Dayrange").path("value").asText(""));
                }
                BigDecimal openPrice = null; // 暫無對應欄位

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
                    BigDecimal estimated;
                    String source;
                    if (buyPrice != null && sellPrice != null) {
                        estimated = buyPrice.add(sellPrice)
                                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                        source = "TWSE(買賣中價)";
                    } else if (prevClose != null) {
                        estimated = prevClose;
                        source = "TWSE(前收)";
                    } else {
                        continue;
                    }
                    BigDecimal estChange = prevClose != null ? estimated.subtract(prevClose) : BigDecimal.ZERO;
                    BigDecimal estChangePct = (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) != 0)
                            ? estChange.multiply(BigDecimal.valueOf(100)).divide(prevClose, 6, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return Optional.of(new PriceResult(
                            stockCode, "台股", estimated, estChange, estChangePct, source,
                            name.isEmpty() ? null : name,
                            buyPrice, sellPrice, openPrice, prevClose, highPrice, lowPrice, volumeLots));
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
}
