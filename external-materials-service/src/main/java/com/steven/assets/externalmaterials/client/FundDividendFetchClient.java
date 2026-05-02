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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FundClear 信託基金配息歷史抓取 (Requirement 20)。
 *
 * Offshore: POST /api/offshore/fund-info/info-dividend/query
 * Onshore:  POST /api/onshore/fund-info/info-dividend/query-dividend
 *
 * DTO（兩邊欄位名一致）：
 *   queryType=1, organizeCode/orgUuid, fundCode/fundUuid, fundClassCode/fundClassUuid,
 *   asiFreqList=[], baseBeginDate "YYYY/MM", baseEndDate "YYYY/MM", _pageNum, _pageSize
 *
 * Response: list[].asiBaseDate (YYYY/MM/DD), asiAmt (每單位原幣), asiFreq (每月/每季)
 */
@Slf4j
@Component
public class FundDividendFetchClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String BASE = "https://www.fundclear.com.tw";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public record DividendRow(LocalDate baseDate, BigDecimal amount, String currency, String frequency) {}

    /** 抓近 N 個月的配息紀錄（依 base_date 由新到舊排序）。失敗回 empty list。 */
    public List<DividendRow> fetchRecent(String site, String fcOrg, String fcFund,
                                         String fcClass, int monthsBack) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate from = today.minusMonths(monthsBack);
            String beginYM = MONTH_FMT.format(from);
            String endYM = MONTH_FMT.format(today);

            boolean offshore = "offshore".equalsIgnoreCase(site);
            String url = BASE + (offshore
                    ? "/api/offshore/fund-info/info-dividend/query"
                    : "/api/onshore/fund-info/info-dividend/query-dividend");

            // offshore 接受 baseBeginDate / baseEndDate（YYYY/MM）合併欄位；
            // onshore 嚴格要求 startYear / startMonth / endYear / endMonth 四個分開欄位 → 同時都帶以兼容。
            String body = String.format(
                    "{\"queryType\":\"1\",\"searchName\":\"\",\"organizeCode\":\"%s\","
                  + "\"fundCode\":\"%s\",\"fundClassCode\":\"%s\",\"asiFreqList\":[],"
                  + "\"baseBeginDate\":\"%s\",\"baseEndDate\":\"%s\","
                  + "\"startYear\":\"%d\",\"startMonth\":\"%02d\",\"endYear\":\"%d\",\"endMonth\":\"%02d\","
                  + "\"_pageNum\":1,\"_pageSize\":50}",
                    fcOrg, fcFund, fcClass, beginYM, endYM,
                    from.getYear(), from.getMonthValue(),
                    today.getYear(), today.getMonthValue());

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
                log.warn("FundClear dividend {} HTTP {}: {}", site, resp.statusCode(), resp.body());
                return List.of();
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root.has("message") && !root.has("list")) {
                String msg = root.path("message").asText();
                // 「無符合您搜尋條件」= 該基金（多半是累積型）無配息紀錄，正常情況
                if (msg.contains("無符合")) {
                    log.info("FundClear dividend {} 無配息紀錄（可能為累積型基金）: org={} fund={} class={}",
                            site, fcOrg, fcFund, fcClass);
                    return List.of();
                }
                log.warn("FundClear dividend {} message: {}", site, msg);
                return List.of();
            }

            JsonNode list = root.path("list");
            if (!list.isArray() || list.isEmpty()) return List.of();

            List<DividendRow> out = new ArrayList<>();
            for (JsonNode n : list) {
                String dateStr = n.path("asiBaseDate").asText(null);
                String amtStr = n.path("asiAmt").asText(null);
                String currName = n.path("currencyName").asText(null);
                String freq = n.path("asiFreq").asText(null);
                if (dateStr == null || amtStr == null) continue;
                try {
                    LocalDate d = LocalDate.parse(dateStr, DAY_FMT);
                    BigDecimal v = new BigDecimal(amtStr);
                    out.add(new DividendRow(d, v, currName, freq));
                } catch (Exception e) {
                    log.debug("跳過無法解析的 dividend row: {} / {}", dateStr, amtStr);
                }
            }
            out.sort((a, b) -> b.baseDate.compareTo(a.baseDate));
            return out;
        } catch (Exception e) {
            log.warn("FundClear dividend 抓取失敗 site={} org={} fund={} class={}: {}",
                    site, fcOrg, fcFund, fcClass, e.toString());
            return List.of();
        }
    }
}
