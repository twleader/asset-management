package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * FundClear（基金資訊觀測站）NAV 抓取（Requirement 19）。
 *
 * 依 fund_master.site 分流：
 * - offshore: POST /api/offshore/nav-profit/query-history
 *   DTO: {organizeCode, fundCode, fundClassCode, startDate, endDate}（YYYY/MM/DD）
 * - onshore:  POST /api/onshore/nav-profit/query-history
 *   DTO: {orgId, fundNo, fundClassCode, startDate, endDate}
 *
 * Response: {"fundCurr":"USD","tableList":[{"navTxnDate":"2026/04/29","navValue":"4.563000",...}]}
 */
@Slf4j
@Component
public class FundNavFetchClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String BASE = "https://www.fundclear.com.tw";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public record NavResult(LocalDate navDate, BigDecimal nav, String currency) {}

    /**
     * 抓單支基金的最新淨值。預設查最近 14 天範圍，取 tableList[0]（最新）。
     * 找不到回 Optional.empty。
     */
    public Optional<NavResult> fetchLatest(String site, String fcOrg, String fcFund, String fcClass) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(14);
        return fetchRange(site, fcOrg, fcFund, fcClass, from, today)
                .filter(arr -> arr.size() > 0)
                .map(arr -> arr.get(0));
    }

    /**
     * 抓日期區間。回傳依 navTxnDate 由新到舊排序。
     */
    public Optional<java.util.List<NavResult>> fetchRange(String site, String fcOrg, String fcFund,
                                                          String fcClass, LocalDate from, LocalDate to) {
        try {
            boolean offshore = "offshore".equalsIgnoreCase(site);
            String url = BASE + (offshore ? "/api/offshore/nav-profit/query-history"
                                          : "/api/onshore/nav-profit/query-history");

            String body = offshore
                    ? buildOffshoreBody(fcOrg, fcFund, fcClass, from, to)
                    : buildOnshoreBody(fcOrg, fcFund, fcClass, from, to);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Content-Type", "application/json")
                    .header("Origin", BASE)
                    .header("Referer", BASE + "/")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FundClear NAV {} 回 HTTP {}: {}", site, resp.statusCode(), resp.body());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            // 失敗時 server 回 {"message":"...","data":null} — 偵測一下
            if (root.has("message") && !root.has("tableList")) {
                log.warn("FundClear NAV {} message: {}", site, root.path("message").asText());
                return Optional.empty();
            }
            String currency = root.path("fundCurr").asText(null);
            JsonNode list = root.path("tableList");
            if (!list.isArray() || list.isEmpty()) return Optional.of(java.util.List.of());

            java.util.List<NavResult> out = new java.util.ArrayList<>();
            for (JsonNode n : list) {
                String dateStr = n.path("navTxnDate").asText(null);
                String navStr = n.path("navValue").asText(null);
                if (dateStr == null || navStr == null) continue;
                try {
                    LocalDate d = LocalDate.parse(dateStr, DATE_FMT);
                    BigDecimal v = new BigDecimal(navStr);
                    out.add(new NavResult(d, v, currency));
                } catch (Exception e) {
                    log.debug("跳過無法解析的 NAV row: {} / {}", dateStr, navStr);
                }
            }
            // 由新到舊（FundClear 已是降序但保險再排）
            out.sort((a, b) -> b.navDate.compareTo(a.navDate));
            return Optional.of(out);
        } catch (Exception e) {
            log.warn("FundClear NAV 抓取失敗 site={} org={} fund={} class={}: {}",
                    site, fcOrg, fcFund, fcClass, e.toString());
            return Optional.empty();
        }
    }

    private String buildOffshoreBody(String fcOrg, String fcFund, String fcClass,
                                     LocalDate from, LocalDate to) {
        return String.format(
                "{\"organizeCode\":\"%s\",\"fundCode\":\"%s\",\"fundClassCode\":\"%s\",\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                fcOrg, fcFund, fcClass, DATE_FMT.format(from), DATE_FMT.format(to));
    }

    private String buildOnshoreBody(String fcOrg, String fcFund, String fcClass,
                                    LocalDate from, LocalDate to) {
        return String.format(
                "{\"orgId\":\"%s\",\"fundNo\":\"%s\",\"fundClassCode\":\"%s\",\"startDate\":\"%s\",\"endDate\":\"%s\"}",
                fcOrg, fcFund, fcClass, DATE_FMT.format(from), DATE_FMT.format(to));
    }
}
