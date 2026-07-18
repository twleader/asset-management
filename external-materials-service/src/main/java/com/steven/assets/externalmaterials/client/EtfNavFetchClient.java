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
import java.util.HashMap;
import java.util.Map;

/**
 * 台股 ETF 淨值與折溢價抓取（Task 209）。
 *
 * <p>來源＝證交所基本市況報導 MIS 的全市場 ETF 彙整檔 {@code all_etf.txt}：一次 GET 回全市場約 350 檔，
 * 同時涵蓋上市（tse）與上櫃（otc）——使用者持有的 3 檔上櫃債券 ETF（00697B／00751B／00679B）也在其中，
 * 故不需要另外打櫃買。免登入、免 key。
 *
 * <p><b>欄位語意</b>（證交所無公開文件，係與 MIS getStockInfo 的成交價／昨收價交叉比對逆推，實測驗證）：
 * <ul>
 *   <li>{@code a}＝代號、{@code b}＝名稱</li>
 *   <li>{@code e}＝市價（與 getStockInfo 的成交價 z 相同）</li>
 *   <li>{@code f}＝盤中即時預估淨值 iNAV</li>
 *   <li><b>{@code g}＝證交所已算好的折溢價%</b>（逐檔驗算 g=(e−f)/f 皆吻合）</li>
 *   <li>{@code h}＝<b>前一交易日</b>單位淨值、{@code i}＝資料日期、{@code j}＝資料時間</li>
 * </ul>
 *
 * <p><b>兩個必須遵守的禁忌</b>（違反會產生「平盤日看起來正常、大跌日錯到離譜」的靜默錯誤）：
 * <ol>
 *   <li><b>折溢價一律直接取 {@code g}，不得自行以 (e−f)/f 重算</b>——{@code f} 在股票型 ETF 被四捨五入到
 *       小數 2 位，重算誤差可達 0.07 個百分點，與各站顯示對不起來卻又不夠離譜到會被發現。</li>
 *   <li><b>不得使用 {@code h} 計算折溢價</b>——{@code h} 對全部檔位都是 T-1 淨值（不分國內外資產型）。
 *       實測 2026/07/17 台股重挫日，0050 的 h=106.36（7/16 淨值）配當日市價 100.15 會算出 −5.8% 折價，
 *       而真實 iNAV 口徑是 +1.2% 溢價。</li>
 * </ol>
 *
 * <p>回應 JSON 為 {@code {"a1":[{...群組...}]}}，群組中夾雜一個沒有 {@code msgArray} 的空物件（未跳過會 NPE）；
 * 且同一欄位型別混雜（float／字串／帶千分位逗號的字串），故數值一律過 {@link #parseDecimal}。
 */
@Slf4j
@Component
public class EtfNavFetchClient {

    /** 全市場 ETF 淨值彙整檔（靜態檔，HEAD 會回 502，只能用 GET）。 */
    private static final String ALL_ETF_URL = "https://mis.twse.com.tw/stock/data/all_etf.txt";

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EtfNavFetchClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 單一 ETF 的淨值與折溢價。{@code premiumDiscountPct} 為百分比數值（例 1.2 表示溢價 1.2%）。
     *
     * @param navAsOf 淨值資料時點（台股取 {@code i}+{@code j}；美股取該筆報價日）
     */
    public record EtfNav(String stockCode, String market, BigDecimal nav,
                         BigDecimal premiumDiscountPct, String navAsOf, String source) {}

    /**
     * 抓全市場台股 ETF 淨值／折溢價，回 {@code 代號 → EtfNav}。
     * 失敗一律回空 Map（呼叫端保留上一輪 Redis 值），不拋出中斷排程。
     */
    public Map<String, EtfNav> fetchTwAll() {
        Map<String, EtfNav> out = new HashMap<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ALL_ETF_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA)
                    .header("Referer", "https://mis.twse.com.tw/stock/index.jsp")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Encoding", "identity")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("台股 ETF 淨值抓取失敗：HTTP {}", resp.statusCode());
                return out;
            }
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode group : root.path("a1")) {
                JsonNode arr = group.path("msgArray");
                if (!arr.isArray()) continue; // a1 內夾雜無 msgArray 的空物件
                for (JsonNode item : arr) {
                    String code = item.path("a").asText("").trim();
                    if (code.isEmpty()) continue;
                    BigDecimal nav = parseDecimal(item.path("f").asText(""));
                    BigDecimal pct = parseDecimal(item.path("g").asText("")); // 證交所已算好，不自行重算
                    if (nav == null && pct == null) continue;
                    String asOf = (item.path("i").asText("").trim() + " "
                            + item.path("j").asText("").trim()).trim();
                    out.put(code, new EtfNav(code, "台股", nav, pct, asOf, "TWSE"));
                }
            }
            log.info("台股 ETF 淨值抓取完成：{} 檔", out.size());
        } catch (Exception e) {
            log.warn("台股 ETF 淨值抓取失敗：{}", e.getMessage());
        }
        return out;
    }

    /** 數值解析：先去千分位逗號；空值／`-`／`N/A` 回 null（比照 PriceFetchClient 慣例）。 */
    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "-".equals(t) || "--".equals(t) || "N/A".equalsIgnoreCase(t)) return null;
        try { return new BigDecimal(t.replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }
}
