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
     * TWSE FMTQIK（盤後成交資訊）。原 www.twse.com.tw 端點於 2026 站台維護期間長時間無回應，
     * 改用 openapi 版（無 response 包裝層、直接是 array）。
     * 回應 schema: [{ Date: "115mmdd"（民國）, TradeVolume, TradeValue, Transaction, TAIEX, Change }, ...]
     */
    private static final String TWSE_FMTQIK_URL =
            "https://openapi.twse.com.tw/v1/exchangeReport/FMTQIK?date=";

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

    /** TWSE FMTQIK 12 月份月報，取該月最後一筆作為年末加權指數收盤。 */
    public BigDecimal fetchTwseDecemberClose(int year) {
        List<DailyClose> rows = fetchTwseMonthlyDaily(year, 12);
        if (rows.isEmpty()) return null;
        return rows.get(rows.size() - 1).close();
    }

    public record DailyClose(LocalDate tradingDate, BigDecimal close) {}

    /**
     * TWSE FMTQIK 月報（openapi.twse.com.tw 版本）。
     * 回應為 JSON array，每筆 {Date, TAIEX, ...}；Date 是民國格式如 "1150504"（前 3 碼民國年 + 4 碼月日）。
     */
    public List<DailyClose> fetchTwseMonthlyDaily(int year, int month) {
        try {
            String date = String.format("%04d%02d01", year, month);
            HttpRequest req = HttpRequest.newBuilder(URI.create(TWSE_FMTQIK_URL + date))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return Collections.emptyList();
            JsonNode root = mapper.readTree(res.body());
            if (!root.isArray() || root.isEmpty()) return Collections.emptyList();

            List<DailyClose> out = new ArrayList<>();
            for (JsonNode row : root) {
                String mingoDate = row.path("Date").asText("");  // e.g. "1150504"
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

                String idxStr = row.path("TAIEX").asText("").replace(",", "");
                if (idxStr.isEmpty() || "-".equals(idxStr)) continue;
                BigDecimal close = new BigDecimal(idxStr).setScale(2, RoundingMode.HALF_UP);
                out.add(new DailyClose(d, close));
            }
            return out;
        } catch (Exception e) {
            log.warn("TWSE FMTQIK {}/{} 月報抓取失敗：{}", year, month, e.getMessage());
            return Collections.emptyList();
        }
    }
}
