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
     * TWSE 大盤每日 OHLC 月報。從 FMTQIK（只有 close）改用 MI_5MINS_HIST 提供 OHLC 四欄。
     *
     * 用 www.twse.com.tw 版本（支援 ?date= 歷史月份查詢；openapi 版的 MI_5MINS_HIST 永遠
     * 回最新資料、不支援 date 參數，無法回補歷史）。FMTQIK 在 2026 站台維護期失效但
     * MI_5MINS_HIST 仍可用。
     *
     * 回應 schema: { stat:"OK", fields:[日期, 開盤指數, 最高指數, 最低指數, 收盤指數],
     *                data:[["民國年/月/日","12,345.67",...], ...] }
     */
    private static final String TWSE_DAILY_OHLC_URL =
            "https://www.twse.com.tw/rwd/zh/TAIEX/MI_5MINS_HIST?response=json&date=";

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
     * TWSE MI_5MINS_HIST 月報（www.twse.com.tw 版本）。
     * 回應 schema: { stat: "OK", fields: [日期, 開盤指數, 最高指數, 最低指數, 收盤指數],
     *               data: [["民國年/月/日","12,345.67",...], ...] }
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
            if (!"OK".equals(root.path("stat").asText())) return Collections.emptyList();
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) return Collections.emptyList();

            List<DailyOhlc> out = new ArrayList<>();
            for (JsonNode row : data) {
                if (!row.isArray() || row.size() < 5) continue;
                String rocDate = row.get(0).asText("");  // e.g. "115/05/04"
                String[] parts = rocDate.split("/");
                if (parts.length != 3) continue;
                LocalDate d;
                try {
                    d = LocalDate.of(
                            Integer.parseInt(parts[0]) + 1911,
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
                } catch (Exception e) { continue; }

                BigDecimal open  = parseIndex(row.get(1).asText(""));
                BigDecimal high  = parseIndex(row.get(2).asText(""));
                BigDecimal low   = parseIndex(row.get(3).asText(""));
                BigDecimal close = parseIndex(row.get(4).asText(""));
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
