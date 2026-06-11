package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 從 FinMind / NASDAQ 抓股利歷史，原本在 backend MarketDataService.getDividendHistory，
 * 已遷至此服務以滿足「對外抓資料邏輯集中於 external-materials-service」的架構規範。
 */
@Slf4j
@Component
public class DividendFetchClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String finmindToken;

    public DividendFetchClient(@Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken) {
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static final ZoneId ET = ZoneId.of("America/New_York");

    public record DividendEvent(
            Integer year,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            String exDividendDate,        // ISO YYYY-MM-DD or null
            String cashPaymentDate,       // ISO or null
            String stockPaymentDate       // ISO or null
    ) {}

    /** 抓取結果含實際採用的資料源（寫入 stock_dividend_history.source）。 */
    public record DividendFetchResult(String source, List<DividendEvent> events) {}

    private static final DividendFetchResult EMPTY = new DividendFetchResult(null, Collections.emptyList());

    public DividendFetchResult fetch(String stockCode, String market, int years) {
        if ("台股".equals(market)) return fetchTw(stockCode, years);
        if ("美股".equals(market)) return fetchUs(stockCode, years);
        return EMPTY;
    }

    private DividendFetchResult fetchTw(String stockCode, int years) {
        List<DividendEvent> out = new ArrayList<>();
        try {
            JsonNode data = finmindData("TaiwanStockDividend", stockCode, years);
            if (data != null) for (JsonNode item : data) {
                double cash = item.path("CashEarningsDistribution").asDouble(0)
                            + item.path("CashStatutorySurplus").asDouble(0);
                double stock = item.path("StockEarningsDistribution").asDouble(0)
                             + item.path("StockStatutorySurplus").asDouble(0);
                if (cash == 0 && stock == 0) continue;

                String exDate = item.path("CashExDividendTradingDate").asText("");
                if (exDate.isEmpty()) exDate = item.path("StockExDividendTradingDate").asText("");
                Integer year = parseYear(exDate);
                if (year == null) {
                    String date = item.path("date").asText("");
                    year = parseYear(date);
                    if (year == null) continue;
                }
                String cashPay = nullIfEmpty(item.path("CashDividendPaymentDate").asText(""));
                String stockPay = nullIfEmpty(item.path("StockDividendPaymentDate").asText(""));

                out.add(new DividendEvent(
                        year,
                        BigDecimal.valueOf(cash).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(stock).setScale(4, RoundingMode.HALF_UP),
                        nullIfEmpty(exDate),
                        cashPay, stockPay
                ));
            }
        } catch (Exception e) {
            log.warn("台股股利歷史查詢失敗 {}: {}", stockCode, e.getMessage());
        }
        // TaiwanStockDividend 是上市櫃「公司」盈餘分配表，債券 ETF / 收益分配型 ETF（如 00751B）
        // 配的是利息收益分配，不在此表 → 回空。改打 TaiwanStockDividendResult（除權息結果表）補抓。
        if (out.isEmpty()) out = fetchTwDividendResult(stockCode, years);
        return new DividendFetchResult("FinMind", out);
    }

    /**
     * Fallback：FinMind TaiwanStockDividendResult（除權息結果表）。
     * 僅 date（除息日）+ stock_and_cache_dividend（合併配息金額），無發放日、無現金/配股拆分。
     * ETF 收益分配皆為「除息」，stock_or_cache_dividend 含「權」且不含「息」才當配股，其餘當現金配息。
     */
    private List<DividendEvent> fetchTwDividendResult(String stockCode, int years) {
        List<DividendEvent> out = new ArrayList<>();
        try {
            JsonNode data = finmindData("TaiwanStockDividendResult", stockCode, years);
            if (data == null) return out;
            for (JsonNode item : data) {
                double amount = item.path("stock_and_cache_dividend").asDouble(0);
                if (amount == 0) continue;
                String exDate = item.path("date").asText("");
                Integer year = parseYear(exDate);
                if (year == null) continue;
                String type = item.path("stock_or_cache_dividend").asText("");
                boolean isStock = type.contains("權") && !type.contains("息");
                BigDecimal amt = BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP);
                out.add(new DividendEvent(
                        year,
                        isStock ? BigDecimal.ZERO : amt,
                        isStock ? amt : BigDecimal.ZERO,
                        nullIfEmpty(exDate),
                        null, null
                ));
            }
        } catch (Exception e) {
            log.warn("台股除權息結果表查詢失敗 {}: {}", stockCode, e.getMessage());
        }
        return out;
    }

    /** FinMind data API 共用呼叫，回傳 data 陣列節點；非 200 或非陣列回 null。 */
    private JsonNode finmindData(String dataset, String stockCode, int years) throws Exception {
        String startDate = LocalDate.now().minusYears(years).toString();
        String url = "https://api.finmindtrade.com/api/v4/data"
                + "?dataset=" + dataset
                + "&data_id=" + stockCode
                + "&start_date=" + startDate;
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity");
        if (!finmindToken.isEmpty()) b.header("Authorization", "Bearer " + finmindToken);
        HttpResponse<String> resp = httpClient.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("FinMind {} {} 回應 {}", dataset, stockCode, resp.statusCode());
            return null;
        }
        JsonNode data = mapper.readTree(resp.body()).path("data");
        return data.isArray() ? data : null;
    }

    private DividendFetchResult fetchUs(String stockCode, int years) {
        int fromYear = LocalDate.now().getYear() - years + 1;
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetclass=" + assetClass;
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", UA)
                        .header("Accept", "application/json, text/html, */*")
                        .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Accept-Encoding", "identity")
                        .header("Referer", "https://finance.yahoo.com/")
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;
                JsonNode rowsNode = mapper.readTree(resp.body()).path("data").path("dividends").path("rows");
                if (!rowsNode.isArray() || rowsNode.isEmpty()) continue;
                List<DividendEvent> rows = new ArrayList<>();
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
                    try { amt = Double.parseDouble(amtStr); } catch (NumberFormatException e) { continue; }
                    String payDate = r.path("paymentDate").asText("");
                    String payIso = null;
                    if (payDate.length() >= 10) {
                        try {
                            String[] pp = payDate.split("/");
                            payIso = String.format("%04d-%02d-%02d",
                                    Integer.parseInt(pp[2]), Integer.parseInt(pp[0]), Integer.parseInt(pp[1]));
                        } catch (Exception ignore) {}
                    }
                    rows.add(new DividendEvent(
                            year,
                            BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.ZERO,
                            exIso,
                            payIso, null
                    ));
                }
                if (!rows.isEmpty()) return new DividendFetchResult("NASDAQ", rows);
            } catch (Exception e) {
                log.warn("美股股利歷史查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        // NASDAQ /dividends 對非 NASDAQ 上市（NYSE / NYSEARCA，如 VOO、SGOV、SCHD）回 N/A。
        // 改打 Yahoo chart events=div 補抓（與 MarketDataFetchService.getYahooDividendRate 同一資料源）。
        List<DividendEvent> yahoo = fetchUsYahoo(stockCode, years);
        if (!yahoo.isEmpty()) return new DividendFetchResult("Yahoo Finance", yahoo);
        return EMPTY;
    }

    /**
     * Fallback：Yahoo chart events=div（curl 子程序，避開 Java HttpClient 被 WAF 擋）。
     * events.dividends 每筆含 amount（每股現金配息）與 date（除息日 epoch 秒）。Yahoo 僅有現金配息、無發放日。
     */
    private List<DividendEvent> fetchUsYahoo(String stockCode, int years) {
        List<DividendEvent> out = new ArrayList<>();
        int fromYear = LocalDate.now().getYear() - years + 1;
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + stockCode.trim().toUpperCase() + "?interval=1d&range=" + years + "y&events=div";
            // Yahoo WAF 對長 Chrome UA + curl TLS 指紋判為 bot 回 429；短 "Mozilla/5.0" 才放行
            // （與 MarketDataFetchService.getYahooDividendRateForTicker 一致）。
            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            JsonNode divs = mapper.readTree(body).path("chart").path("result").path(0)
                    .path("events").path("dividends");
            if (!divs.isObject()) return out;
            for (JsonNode d : divs) {
                double amt = d.path("amount").asDouble(0);
                long ts = d.path("date").asLong(0);
                if (amt <= 0 || ts <= 0) continue;
                LocalDate exDate = Instant.ofEpochSecond(ts).atZone(ET).toLocalDate();
                if (exDate.getYear() < fromYear) continue;
                out.add(new DividendEvent(
                        exDate.getYear(),
                        BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO,
                        exDate.toString(),
                        null, null
                ));
            }
        } catch (Exception e) {
            log.warn("Yahoo 美股股利歷史查詢失敗 {}: {}", stockCode, e.getMessage());
        }
        return out;
    }

    private static Integer parseYear(String s) {
        if (s == null || s.length() < 4) return null;
        try { return Integer.parseInt(s.substring(0, 4)); } catch (Exception e) { return null; }
    }

    private static String nullIfEmpty(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
