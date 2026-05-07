package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 總體經濟資料抓取（IMF DataMapper / TWSE FMTQIK）。
 * 從 backend MacroHistoryService 搬遷至此，集中外部 API 呼叫。
 */
@Slf4j
@Component
public class MacroDataFetchClient {

    private static final String IMF_API_TPL = "https://www.imf.org/external/datamapper/api/v1/";
    /**
     * TWSE 大盤每日 OHLC 月報（openapi 版）。從 FMTQIK（只有 close）改用 MI_5MINS_HIST，提供 OHLC 四欄，
     * 供觀察清單 0000 KD 計算與大盤 K 線圖共用。
     * 回應 schema: [{ Date: "115mmdd"（民國）, OpeningIndex, HighestIndex, LowestIndex, ClosingIndex }, ...]
     */
    private static final String TWSE_DAILY_OHLC_URL =
            "https://openapi.twse.com.tw/v1/exchangeReport/MI_5MINS_HIST?date=";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * IMF DataMapper API。後面是 Akamai WAF；UA 設 Mozilla 或 Java-http-client 會被 403。
     * 用 curl shell-out 避免 Java TLS fingerprint 被擋。
     */
    public Map<Integer, BigDecimal> fetchImf(String indicator, String countryCode, int scale) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-sS", "--max-time", "20",
                "-H", "Accept: application/json",
                IMF_API_TPL + indicator + "/" + countryCode);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String body = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        if (exit != 0) throw new RuntimeException("curl exit=" + exit + ": " + body);
        JsonNode node = mapper.readTree(body)
                .path("values").path(indicator).path(countryCode);
        if (!node.isObject() || node.isEmpty()) {
            throw new RuntimeException("IMF 回應未含 " + indicator + "/" + countryCode);
        }
        Map<Integer, BigDecimal> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isNull()) continue;
            out.put(Integer.parseInt(e.getKey()),
                    BigDecimal.valueOf(e.getValue().asDouble()).setScale(scale, RoundingMode.HALF_UP));
        }
        return out;
    }

    /** TWSE 大盤 12 月份 OHLC 月報，取該月最後一筆作為年末加權指數收盤。 */
    public BigDecimal fetchTwseDecemberClose(int year) {
        List<DailyOhlc> rows = fetchTwseMonthlyDaily(year, 12);
        if (rows.isEmpty()) return null;
        return rows.get(rows.size() - 1).close();
    }

    /** 大盤每日 OHLC（從 MI_5MINS_HIST 月報拆出）。 */
    public record DailyOhlc(LocalDate tradingDate,
                             BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}

    /**
     * TWSE MI_5MINS_HIST 月報（openapi 版）。回應為 JSON array，每筆
     * {Date, OpeningIndex, HighestIndex, LowestIndex, ClosingIndex}；Date 是民國格式如 "1150504"
     * （前 3 碼民國年 + 4 碼月日）。
     */
    public List<DailyOhlc> fetchTwseMonthlyDaily(int year, int month) {
        try {
            String date = String.format("%04d%02d01", year, month);
            HttpRequest req = HttpRequest.newBuilder(URI.create(TWSE_DAILY_OHLC_URL + date))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return Collections.emptyList();
            JsonNode root = mapper.readTree(res.body());
            if (!root.isArray() || root.isEmpty()) return Collections.emptyList();

            List<DailyOhlc> out = new ArrayList<>();
            for (JsonNode row : root) {
                String mingoDate = row.path("Date").asText("");
                if (mingoDate.length() != 7) continue;
                int gYear, mm, dd;
                try {
                    gYear = Integer.parseInt(mingoDate.substring(0, 3)) + 1911;
                    mm = Integer.parseInt(mingoDate.substring(3, 5));
                    dd = Integer.parseInt(mingoDate.substring(5, 7));
                } catch (NumberFormatException e) { continue; }
                LocalDate d;
                try { d = LocalDate.of(gYear, mm, dd); }
                catch (Exception e) { continue; }

                BigDecimal open  = parseIndex(row.path("OpeningIndex").asText(""));
                BigDecimal high  = parseIndex(row.path("HighestIndex").asText(""));
                BigDecimal low   = parseIndex(row.path("LowestIndex").asText(""));
                BigDecimal close = parseIndex(row.path("ClosingIndex").asText(""));
                if (close == null) continue;
                out.add(new DailyOhlc(d, open, high, low, close));
            }
            return out;
        } catch (Exception e) {
            log.warn("TWSE MI_5MINS_HIST {}/{} 月報抓取失敗：{}", year, month, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static BigDecimal parseIndex(String raw) {
        if (raw == null) return null;
        String s = raw.replace(",", "").trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        try { return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP); }
        catch (NumberFormatException e) { return null; }
    }
}
