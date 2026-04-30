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
import java.time.LocalDate;
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

    public record DividendEvent(
            Integer year,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            String exDividendDate,        // ISO YYYY-MM-DD or null
            String cashPaymentDate,       // ISO or null
            String stockPaymentDate       // ISO or null
    ) {}

    public List<DividendEvent> fetch(String stockCode, String market, int years) {
        if ("台股".equals(market)) return fetchTw(stockCode, years);
        if ("美股".equals(market)) return fetchUs(stockCode, years);
        return Collections.emptyList();
    }

    private List<DividendEvent> fetchTw(String stockCode, int years) {
        List<DividendEvent> out = new ArrayList<>();
        try {
            String startDate = LocalDate.now().minusYears(years).toString();
            String url = "https://api.finmindtrade.com/api/v4/data"
                    + "?dataset=TaiwanStockDividend"
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
                log.warn("FinMind TaiwanStockDividend {} 回應 {}", stockCode, resp.statusCode());
                return out;
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray()) return out;
            for (JsonNode item : data) {
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
        return out;
    }

    private List<DividendEvent> fetchUs(String stockCode, int years) {
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
                if (!rows.isEmpty()) return rows;
            } catch (Exception e) {
                log.warn("美股股利歷史查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        return Collections.emptyList();
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

    public String source(String market) {
        return "美股".equals(market) ? "NASDAQ" : "FinMind";
    }
}
