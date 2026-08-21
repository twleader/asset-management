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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
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

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

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

    /**
     * 主計總處 NA8101A1A「國民所得統計常用資料-年」XML 下載點（data.gov.tw 資料集 44218 列出）。
     * 設定化以便日後 DGBAS 變更路徑時不需改 code；失效時 fetchDgbasNationalIncome 回空 map → IMF fallback。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${macro.dgbas.na8101-url:https://ws.dgbas.gov.tw/001/Upload/461/relfile/11525/230514/na8101a1a.xml}")
    private String dgbasNa8101Url;

    /** NA8101 XML 每筆觀測：<Obs><Item>..</Item><TIME_PERIOD>YYYY</TIME_PERIOD><FREQ>..</FREQ><TYPE>..</TYPE><Item_VALUE>..</Item_VALUE></Obs> */
    private static final java.util.regex.Pattern DGBAS_OBS = java.util.regex.Pattern.compile(
            "<Obs><Item>(.*?)</Item><TIME_PERIOD>(.*?)</TIME_PERIOD><FREQ>.*?</FREQ><TYPE>(.*?)</TYPE>\\s*<Item_VALUE>(.*?)</Item_VALUE></Obs>",
            java.util.regex.Pattern.DOTALL);

    private final HttpClient http;
    /**
     * DGBAS 專屬 HttpClient（Requirement 30）：ws.dgbas.gov.tw 伺服器漏送中繼憑證，
     * 以打包的 TWCA 中繼憑證為信任錨建鏈、主機名驗證維持啟用；僅此 client 使用，不影響其他抓取。
     */
    private final HttpClient dgbasHttp;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public MacroDataFetchClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** 套件內建構子僅供官方資料 fixture 測試注入可控 HTTP client。 */
    MacroDataFetchClient(HttpClient http) {
        this.http = http;
        this.dgbasHttp = buildDgbasHttpClient();
        this.mapper = new ObjectMapper();
    }

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

    /**
     * DGBAS 專屬 HttpClient（Requirement 30）：以打包的 TWCA 中繼憑證為信任錨建立 SSLContext，
     * 解決伺服器漏送中繼導致的 PKIX 建鏈失敗；建構失敗則退回預設 client（維持既有「失敗→IMF fallback」行為）。
     */
    private static HttpClient buildDgbasHttpClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        try {
            b.sslContext(buildDgbasSslContext());
        } catch (Exception e) {
            log.warn("建立 DGBAS 專屬 SSLContext 失敗，退回預設（可能 PKIX 失敗→IMF fallback）：{}", e.getMessage());
        }
        return b.build();
    }

    /**
     * 專屬 SSLContext：把 classpath 內的 TWCA 中繼憑證（{@code /certs/twca-secure-ssl-ca.pem}，
     * 由公信根 TWCA Global Root CA 簽發）作為信任錨載入。ws.dgbas.gov.tw 只送 leaf，故以中繼為 anchor
     * 建鏈成立（leaf → 中繼-anchor），且主機名驗證維持啟用（leaf SAN 含 ws.dgbas.gov.tw），較 curl -k 安全。
     */
    private static javax.net.ssl.SSLContext buildDgbasSslContext() throws Exception {
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        try (java.io.InputStream in = MacroDataFetchClient.class.getResourceAsStream("/certs/twca-secure-ssl-ca.pem")) {
            if (in == null) throw new IllegalStateException("找不到 TWCA 中繼憑證 /certs/twca-secure-ssl-ca.pem");
            int i = 0;
            for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
                ks.setCertificateEntry("twca-" + (i++), c);
            }
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * 主計總處（DGBAS）國民所得統計常用資料-年（表號 NA8101A1A）。
     * 取「經濟成長率(%)」（實質 GDP 成長率）與「平均每人GDP(名目值，美元)」逐年原始值。
     * 涵蓋 1951 起至最新「實際」年度（不含未來預測），為台灣官方權威來源，優先於 IMF。
     * 回傳 {"growth": {year:val}, "gdpUsd": {year:val}}；抓取失敗回空 map（交由 IMF fallback）。
     *
     * 來源 URL 為 data.gov.tw「國民所得統計-常用資料-年」資料集列出的 XML 下載點；
     * 無 WAF，用一般 HttpClient 即可（不需 curl shell-out）。
     */
    public Map<String, Map<Integer, BigDecimal>> fetchDgbasNationalIncome() {
        Map<Integer, BigDecimal> growth = new LinkedHashMap<>();
        Map<Integer, BigDecimal> gdpUsd = new LinkedHashMap<>();
        try {
            // ws.dgbas.gov.tw 由 TWCA 簽發，但伺服器漏送中繼憑證（伺服器端設定錯誤，非用戶端缺根），
            // 預設 truststore 無法建鏈。改用內建 TWCA 中繼憑證為信任錨的專屬 HttpClient（見
            // buildDgbasSslContext），完整驗證憑證鏈與主機名，取代原本 curl -k（--insecure 連主機名都不驗）。
            // Requirement 30。失敗維持回空 map → IMF fallback。
            HttpRequest req = HttpRequest.newBuilder(URI.create(dgbasNa8101Url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(25))
                    .GET().build();
            HttpResponse<String> res = dgbasHttp.send(req,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                log.warn("DGBAS NA8101 HTTP {}", res.statusCode());
                return Map.of("growth", growth, "gdpUsd", gdpUsd);
            }
            String body = res.body();
            java.util.regex.Matcher m = DGBAS_OBS.matcher(body);
            while (m.find()) {
                String item = m.group(1);
                int year;
                try { year = Integer.parseInt(m.group(2).trim()); }
                catch (NumberFormatException e) { continue; }
                String type = m.group(3);
                String valStr = m.group(4).trim();
                if (!type.contains("原始") || valStr.isEmpty()) continue;
                BigDecimal val;
                try { val = new BigDecimal(valStr); }
                catch (NumberFormatException e) { continue; }
                // 用 contains 子字串比對，避開全形括號/逗號（「(名目值，美元)」）的字面編碼風險
                if (item.contains("經濟成長率")) {
                    growth.put(year, val);
                } else if (item.contains("平均每人GDP") && item.contains("美元")) {
                    gdpUsd.put(year, val);
                }
            }
            log.info("DGBAS NA8101：成長率 {} 年、人均GDP(USD) {} 年", growth.size(), gdpUsd.size());
        } catch (Exception e) {
            log.warn("DGBAS NA8101 抓取失敗：{}", e.getMessage());
        }
        return Map.of("growth", growth, "gdpUsd", gdpUsd);
    }

    /**
     * 大盤/指數每日 OHLC ＋成交量（從 MI_5MINS_HIST 月報拆出 OHLC，FMTQIK 月報拆出成交量）。
     * volume=成交股數(股)；value=成交金額(元)，僅 TWSE 有 turnover；TPEX 與其餘 code-keyed 指數的 value 恆 null。
     */
    public record DailyOhlc(LocalDate tradingDate,
                             BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                             Long volume, BigDecimal value) {}

    /** FMTQIK 單日成交股數／成交金額（供 fetchTwseMonthlyDaily 併抓 join 用）。套件內可見，供測試直接建構。 */
    record Turnover(Long volume, BigDecimal value) {}

    /**
     * TWSE FMTQIK 月報（市場成交資訊）。MI_5MINS_HIST 只有 OHLC 四欄、沒有成交量，故另抓本表 join。
     * 用 www.twse.com.tw 的 rwd 版（支援 ?date= 回補歷史；openapi 版只回最新一批、不支援 date，
     * 那支是 {@code TwseInfoFetchClient} 供「大盤成交統計」新聞用，與本方法互不影響）。
     * 回應 schema: { stat:"OK", hints:"單位：元、股",
     *                fields:[日期, 成交股數, 成交金額, 成交筆數, 發行量加權股價指數, 漲跌點數],
     *                data:[["115/07/01","14,683,404,939","1,367,817,795,171",...], ...] }
     */
    private static final String TWSE_TURNOVER_URL =
            "https://www.twse.com.tw/rwd/zh/afterTrading/FMTQIK?response=json&date=";

    /**
     * FMTQIK 回應 JSON 解析為 date→Turnover map；stat!=OK 或無 data 陣列回空 map。
     * 套件內可見、純函式（不做 I/O），供 fetchTwseTurnoverMonthly 呼叫，亦供測試直接驗證解析與 join 邏輯。
     */
    static Map<LocalDate, Turnover> parseTwseTurnoverJson(JsonNode root) {
        if (!"OK".equals(root.path("stat").asText())) return Collections.emptyMap();
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) return Collections.emptyMap();

        Map<LocalDate, Turnover> out = new LinkedHashMap<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 3) continue;
            String rocDate = row.get(0).asText("");  // e.g. "115/07/01"
            String[] parts = rocDate.split("/");
            if (parts.length != 3) continue;
            LocalDate d;
            try {
                d = LocalDate.of(
                        Integer.parseInt(parts[0]) + 1911,
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (Exception e) { continue; }

            Long volume = parseLong(row.get(1).asText(""));
            BigDecimal value = parseIndex(row.get(2).asText(""));
            out.put(d, new Turnover(volume, value));
        }
        return out;
    }

    /**
     * FMTQIK 短期熔斷：實測（2026-08-03）10 年批次回補逐月併抓 FMTQIK 時，約第 26 個月起
     * TWSE 開始對 MI_5MINS_HIST 與 FMTQIK 兩支端點「同時」回 307（推測為同一組 WAF 限流），
     * 若照樣繼續打，會把「本次新增的 FMTQIK 呼叫」的代價轉嫁到「既有的 MI_5MINS_HIST 呼叫」
     * 上──連本來單獨呼叫可撐完整個批次的價格資料都被拖累跳過。三次以上連續非 2xx 視為已被
     * 限流，暫停呼叫 FMTQIK 一段冷卻期（不再消耗 request 額度），讓 MI_5MINS_HIST 至少維持
     * 本次變更前的既有可靠度；剩餘月份的量欄留 null，之後重新回補時自然逐步補齊。
     */
    private final java.util.concurrent.atomic.AtomicInteger fmtqikConsecutiveNon2xx =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long fmtqikCooldownUntilMs = 0L;
    private static final int FMTQIK_TRIP_THRESHOLD = 3;
    private static final long FMTQIK_COOLDOWN_MS = 5 * 60_000L;

    /** 任何失敗（非 2xx／stat != OK／例外）一律回空 map、不拋，由呼叫端降級為量欄 null。 */
    private Map<LocalDate, Turnover> fetchTwseTurnoverMonthly(int year, int month) {
        if (System.currentTimeMillis() < fmtqikCooldownUntilMs) {
            return Collections.emptyMap();   // 熔斷中：不消耗額外 request 額度，不拖累 MI_5MINS_HIST
        }
        try {
            String date = String.format("%04d%02d01", year, month);
            HttpRequest req = HttpRequest.newBuilder(URI.create(TWSE_TURNOVER_URL + date))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                tripFmtqikCircuitIfNeeded();
                return Collections.emptyMap();
            }
            fmtqikConsecutiveNon2xx.set(0);
            JsonNode root = mapper.readTree(res.body());
            return parseTwseTurnoverJson(root);
        } catch (Exception e) {
            log.warn("TWSE FMTQIK {}/{} 月報抓取失敗：{}", year, month, e.getMessage());
            tripFmtqikCircuitIfNeeded();
            return Collections.emptyMap();
        }
    }

    private void tripFmtqikCircuitIfNeeded() {
        if (fmtqikConsecutiveNon2xx.incrementAndGet() >= FMTQIK_TRIP_THRESHOLD) {
            fmtqikCooldownUntilMs = System.currentTimeMillis() + FMTQIK_COOLDOWN_MS;
            fmtqikConsecutiveNon2xx.set(0);
            log.warn("TWSE FMTQIK 連續 {} 次非 2xx，判定已被限流，暫停呼叫 {} 分鐘",
                    FMTQIK_TRIP_THRESHOLD, FMTQIK_COOLDOWN_MS / 60_000);
        }
    }

    /** "413,214,615,558" → 413214615558L；空/"-"/無法解析回 null。 */
    private static Long parseLong(String raw) {
        if (raw == null) return null;
        String s = raw.replace(",", "").trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    /**
     * MI_5MINS_HIST 回應 JSON ＋ 已解析好的成交量 map，join 成 DailyOhlc 列。
     * 套件內可見、純函式（不做 I/O），供 fetchTwseMonthlyDaily 呼叫，亦供測試直接驗證。
     * FMTQIK 該日查無對應 turnover 時，量欄留 null，OHLC 仍照原樣加入——不得因此略過該列。
     */
    static List<DailyOhlc> parseTwseMonthlyOhlcJson(JsonNode root, Map<LocalDate, Turnover> turnovers) {
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
            Turnover t = turnovers.get(d);
            out.add(new DailyOhlc(d, open, high, low, close,
                    t == null ? null : t.volume(), t == null ? null : t.value()));
        }
        return out;
    }

    /**
     * TWSE MI_5MINS_HIST 月報（www.twse.com.tw 版本）＋ FMTQIK 月報（成交股數／成交金額）併抓後以交易日 join。
     * 回應 schema: { stat: "OK", fields: [日期, 開盤指數, 最高指數, 最低指數, 收盤指數],
     *               data: [["民國年/月/日","12,345.67",...], ...] }
     * FMTQIK 該月失敗或該日查無時，量欄留 null，OHLC 仍照原樣回傳——不得因此整月略過。
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
            Map<LocalDate, Turnover> turnovers = fetchTwseTurnoverMonthly(year, month);
            return parseTwseMonthlyOhlcJson(root, turnovers);
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

    /** TWSE 發行量加權股價報酬指數（含息）單日收盤點：tradingDate 為 ISO 日期字串。 */
    public record TwseReturnIndexPoint(String tradingDate, BigDecimal close) {}

    /**
     * TWSE 發行量加權股價報酬指數（含息，MI_INDEX type=IND 日報）。
     * 打 MI_INDEX?date=YYYYMMDD&type=IND，於 tables[] 找 fields[0]=="報酬指數" 的表，
     * 於其 data[] 找第一欄=="發行量加權股價報酬指數" 的列，取「收盤指數」欄（第 2 欄，去逗號 → BigDecimal）。
     * stat!="OK" 或找不到（非交易日 / 查無資料）→ 回 null（呼叫端回 204）。
     */
    public TwseReturnIndexPoint fetchTwseReturnIndexDaily(LocalDate date) {
        try {
            String url = "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date="
                    + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    + "&type=IND&response=json";
            String body = curlGetWithRetry(url, 2);
            JsonNode root = mapper.readTree(body);
            if (!"OK".equals(root.path("stat").asText())) return null;
            JsonNode tables = root.path("tables");
            if (!tables.isArray()) return null;
            for (JsonNode table : tables) {
                JsonNode fields = table.path("fields");
                if (!fields.isArray() || fields.isEmpty()) continue;
                if (!"報酬指數".equals(fields.get(0).asText())) continue;
                JsonNode data = table.path("data");
                if (!data.isArray()) continue;
                for (JsonNode row : data) {
                    if (!row.isArray() || row.size() < 2) continue;
                    if (!"發行量加權股價報酬指數".equals(row.get(0).asText())) continue;
                    BigDecimal close = parseIndex(row.get(1).asText(""));
                    if (close == null) return null;
                    return new TwseReturnIndexPoint(date.toString(), close);
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("TWSE 報酬指數 {} 抓取失敗：{}", date, e.getMessage());
            return null;
        }
    }

    /**
     * Yahoo 來源的 code-keyed 指數代碼 → symbol（TPEX 刻意不在此 map）。
     * 美股四大：道瓊 / 標普500 / 那斯達克綜合 / 費城半導體；
     * 海外主要：英國富時100 / 德國DAX / 韓國KOSPI / 日經225。
     */
    private static final Map<String, String> US_INDEX_YAHOO = Map.of(
            "DJI", "^DJI",
            "SPX", "^GSPC",
            "SP500TR", "^SP500TR",
            "IXIC", "^IXIC",
            "SOX", "^SOX",
            "FTSE", "^FTSE",
            "DAX", "^GDAXI",
            "KOSPI", "^KS11",
            "N225", "^N225");

    private static final String TPEX_DAILY_OHLC_URL =
            "https://www.tpex.org.tw/www/zh-tw/indexInfo/inx?date=";
    private static final String TPEX_DAILY_VOLUME_URL =
            "https://www.tpex.org.tw/web/stock/aftertrading/daily_trading_index/st41_result.php?l=zh-tw&d=";
    private static final String TPEX_REFERER = "https://www.tpex.org.tw/";

    /**
     * 除 TWSE 外的 code-keyed 指數近 10 年每日 OHLC。TPEX 走 TPEx 官方逐月 OHLC／成交量；
     * 其餘合法 code（DJI、SPX、SP500TR、IXIC、SOX、FTSE、DAX、KOSPI、N225）走 Yahoo Finance v8 chart
     * API（range=10y&interval=1d）。Yahoo 用 curl 子程序避開 Java HTTP/2 fingerprint 封鎖，timestamp 依
     * exchangeTimezoneName 轉交易日，避免亞洲／歐洲指數在 UTC 午夜被錯退一天。無資料／不認得 code 回空 list。
     */
    public List<DailyOhlc> fetchUsIndexDaily(String indexCode) {
        if ("TPEX".equals(indexCode)) {
            return fetchTpexIndexDaily();
        }
        String symbol = US_INDEX_YAHOO.get(indexCode);
        if (symbol == null) {
            log.warn("未知 code-keyed 指數代碼: {}", indexCode);
            return Collections.emptyList();
        }
        try {
            // caret 需 URL-encode 為 %5E（實測 raw '^' 在部分環境會被 Yahoo 視為無效）
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + symbol.replace("^", "%5E") + "?range=10y&interval=1d";
            String body = curlGetWithRetry(url, 2);
            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo 指數 {} 無資料: {}", symbol, err.isEmpty() ? "no timestamps" : err);
                return Collections.emptyList();
            }
            // 依交易所時區轉交易日（美股＝America/New_York、海外指數＝各自時區）；缺值 fallback NY
            java.time.ZoneId zone = java.time.ZoneId.of(
                    chart.path("meta").path("exchangeTimezoneName").asText("America/New_York"));
            List<DailyOhlc> out = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                LocalDate d = java.time.Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(zone).toLocalDate();
                JsonNode volumeNode = quotes.path("volume").path(i);
                Long volume = (volumeNode.isNull() || volumeNode.isMissingNode()) ? null : volumeNode.asLong();
                out.add(new DailyOhlc(d,
                        jsonDecimal4(quotes.path("open").path(i)),
                        jsonDecimal4(quotes.path("high").path(i)),
                        jsonDecimal4(quotes.path("low").path(i)),
                        jsonDecimal4(close),
                        volume, null));   // value（成交金額）Yahoo 無此欄，恆 null
            }
            return out;
        } catch (Exception e) {
            log.warn("Yahoo 指數 {} 抓取失敗: {}", indexCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * TPEx 櫃買指數近十年日線。官方端點每次僅回一個月，OHLC 為主資料：任何已結束月份
     * 失敗都回空，避免把缺月資料當成完整十年；成交量則是 fail-soft，單月失敗只留下 null。
     */
    private List<DailyOhlc> fetchTpexIndexDaily() {
        YearMonth current = YearMonth.now(TAIPEI);
        YearMonth first = current.minusYears(10);
        List<DailyOhlc> out = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(current); month = month.plusMonths(1)) {
            List<DailyOhlc> ohlc;
            try {
                String date = String.format("%04d%%2F%02d%%2F01", month.getYear(), month.getMonthValue());
                JsonNode root = getTpexOfficialJson(TPEX_DAILY_OHLC_URL + date + "&response=json");
                ohlc = parseTpexMonthlyOhlc(root, month);
            } catch (Exception e) {
                log.warn("TPEx 指數 OHLC {}/{} 抓取失敗：{}", month.getYear(), month.getMonthValue(), e.getMessage());
                ohlc = null;
            }
            if (ohlc == null) return Collections.emptyList();
            if (ohlc.isEmpty()) {
                if (!month.equals(current)) return Collections.emptyList();
                continue; // 僅當月官方完整空表可略過；schema 非法已在上方 fail closed。
            }

            Map<LocalDate, Long> volumes = Collections.emptyMap();
            try {
                String rocMonth = String.format("%03d%%2F%02d", month.getYear() - 1911, month.getMonthValue());
                JsonNode root = getTpexOfficialJson(TPEX_DAILY_VOLUME_URL + rocMonth + "&o=json");
                Map<LocalDate, BigDecimal> closes = ohlc.stream().collect(java.util.stream.Collectors.toMap(
                        DailyOhlc::tradingDate, DailyOhlc::close));
                volumes = parseTpexMonthlyVolumes(root, month, closes);
            } catch (Exception e) {
                log.warn("TPEx 指數成交量 {}/{} 抓取失敗，該月量欄留空：{}",
                        month.getYear(), month.getMonthValue(), e.getMessage());
            }
            for (DailyOhlc row : ohlc) {
                out.add(new DailyOhlc(row.tradingDate(), row.open(), row.high(), row.low(), row.close(),
                        volumes.get(row.tradingDate()), null));
            }
        }
        return out;
    }

    private JsonNode getOfficialJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }

    /** 僅 TPEx 的官方月報使用 browser-compatible headers；不改動其他 provider 的 HTTP policy。 */
    private JsonNode getTpexOfficialJson(String url) throws Exception {
        HttpRequest request = tpexOfficialRequest(url);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }

    static HttpRequest tpexOfficialRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-TW,zh;q=0.9")
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", TPEX_REFERER)
                .timeout(Duration.ofSeconds(20)).GET().build();
    }

    /** 官方 TPEx indexInfo tables[] 的唯一 OHLC target；不接受 root fields/data 或欄位位置猜測。 */
    static List<DailyOhlc> parseTpexMonthlyOhlc(JsonNode root, YearMonth requestedMonth) {
        JsonNode table = findExactlyOneTable(root, List.of("日期", "開市", "最高", "最低", "收市"));
        if (table == null) return null;
        Map<String, Integer> columns = fieldIndexes(table.path("fields"));
        JsonNode data = table.path("data");
        List<DailyOhlc> out = new ArrayList<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() != columns.size()) return null;
            LocalDate date = parseOfficialDate(row.get(columns.get("日期")).asText(""));
            BigDecimal open = parsePositiveDecimal(row.get(columns.get("開市")).asText(""));
            BigDecimal high = parsePositiveDecimal(row.get(columns.get("最高")).asText(""));
            BigDecimal low = parsePositiveDecimal(row.get(columns.get("最低")).asText(""));
            BigDecimal close = parsePositiveDecimal(row.get(columns.get("收市")).asText(""));
            if (date == null || !YearMonth.from(date).equals(requestedMonth) || open == null || high == null
                    || low == null || close == null || high.compareTo(open.max(close)) < 0
                    || low.compareTo(open.min(close)) > 0 || high.compareTo(low) < 0) return null;
            out.add(new DailyOhlc(date, open, high, low, close, null, null));
        }
        return out;
    }

    /** 成交張數及舊制成交股數（仟股）都換算為股；單筆缺漏只使該日量欄為 null。 */
    static Map<LocalDate, Long> parseTpexMonthlyVolumes(JsonNode root, YearMonth requestedMonth,
                                                          Map<LocalDate, BigDecimal> ohlcCloses) {
        JsonNode table = findExactlyOneTable(root, List.of("日期", "櫃買指數"), List.of("成交張數", "成交股數（仟股）"));
        if (table == null) return Collections.emptyMap();
        Map<String, Integer> columns = fieldIndexes(table.path("fields"));
        String volumeField = columns.containsKey("成交張數") ? "成交張數" : "成交股數（仟股）";
        JsonNode data = table.path("data");
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() != columns.size()) return Collections.emptyMap();
            LocalDate date = parseOfficialDate(row.get(columns.get("日期")).asText(""));
            Long lots = parsePositiveLong(row.get(columns.get(volumeField)).asText(""));
            BigDecimal indexClose = parsePositiveDecimal(row.get(columns.get("櫃買指數")).asText(""));
            BigDecimal ohlcClose = date == null ? null : ohlcCloses.get(date);
            if (date == null || !YearMonth.from(date).equals(requestedMonth) || lots == null || indexClose == null
                    || ohlcClose == null || indexClose.compareTo(ohlcClose) != 0) {
                log.warn("TPEx 指數成交量列無法對齊 OHLC，該日量欄留空");
                continue;
            }
            try { out.put(date, Math.multiplyExact(lots, 1_000L)); }
            catch (ArithmeticException e) { log.warn("TPEx 指數成交量超出 long 範圍，{} 留空", date); }
        }
        return out;
    }

    private static JsonNode findExactlyOneTable(JsonNode root, List<String> required) {
        return findExactlyOneTable(root, required, List.of());
    }

    private static JsonNode findExactlyOneTable(JsonNode root, List<String> required, List<String> oneOf) {
        if (!"ok".equals(root.path("stat").asText()) || !root.path("tables").isArray()) return null;
        JsonNode match = null;
        for (JsonNode table : root.path("tables")) {
            Map<String, Integer> columns = fieldIndexes(table.path("fields"));
            boolean matches = !columns.isEmpty() && required.stream().allMatch(columns::containsKey)
                    && (oneOf.isEmpty() || oneOf.stream().anyMatch(columns::containsKey)) && table.path("data").isArray();
            if (!matches) continue;
            if (match != null) return null;
            match = table;
        }
        return match;
    }

    private static Map<String, Integer> fieldIndexes(JsonNode fields) {
        if (!fields.isArray() || fields.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i).asText("").trim();
            if (field.isEmpty() || out.putIfAbsent(field, i) != null) return Collections.emptyMap();
        }
        return out;
    }

    private static LocalDate parseOfficialDate(String raw) {
        String[] parts = raw == null ? new String[0] : raw.trim().split("/");
        if (parts.length != 3) return null;
        try {
            int year = Integer.parseInt(parts[0].trim());
            if (year < 1911) year += 1911;
            return LocalDate.of(year, Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (RuntimeException e) { return null; }
    }

    private static BigDecimal parsePositiveDecimal(String raw) {
        BigDecimal value = parseIndex(raw);
        return value != null && value.signum() > 0 ? value : null;
    }

    private static Long parsePositiveLong(String raw) {
        Long value = parseLong(raw);
        return value != null && value >= 0 ? value : null;
    }

    private static BigDecimal jsonDecimal4(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        return BigDecimal.valueOf(v.asDouble()).setScale(4, RoundingMode.HALF_UP);
    }

    /** 指數市場代碼 → Yahoo symbol（含 TWSE 與海外指數，供「當日」分時；TPEX 刻意不在此 map，改走官方 MIS）。 */
    private static final Map<String, String> INDEX_INTRADAY_YAHOO = Map.of(
            "TWSE", "^TWII",
            "DJI", "^DJI",
            "SPX", "^GSPC",
            "IXIC", "^IXIC",
            "SOX", "^SOX",
            "FTSE", "^FTSE",
            "DAX", "^GDAXI",
            "KOSPI", "^KS11",
            "N225", "^N225");

    /** 各指數市場交易時段（當地時區；補滿「當日」分時 5 分格用）。未列市場 fallback 09:30–16:00。 */
    private record TradingHours(java.time.LocalTime open, java.time.LocalTime close) {}
    private static final Map<String, TradingHours> INDEX_TRADING_HOURS = Map.of(
            "TWSE",  new TradingHours(java.time.LocalTime.of(9, 0),  java.time.LocalTime.of(13, 30)),
            "TPEX",  new TradingHours(java.time.LocalTime.of(9, 0),  java.time.LocalTime.of(13, 30)),
            "DJI",   new TradingHours(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(16, 0)),
            "SPX",   new TradingHours(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(16, 0)),
            "IXIC",  new TradingHours(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(16, 0)),
            "SOX",   new TradingHours(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(16, 0)),
            "FTSE",  new TradingHours(java.time.LocalTime.of(8, 0),  java.time.LocalTime.of(16, 30)),
            "DAX",   new TradingHours(java.time.LocalTime.of(9, 0),  java.time.LocalTime.of(17, 30)),
            "KOSPI", new TradingHours(java.time.LocalTime.of(9, 0),  java.time.LocalTime.of(15, 30)),
            // 日經 225：東京證交所 2024-11-05 起收盤由 15:00 延後至 15:30（新增收盤競價）；午休 11:30–12:30 無 bar→留 null
            "N225",  new TradingHours(java.time.LocalTime.of(9, 0),  java.time.LocalTime.of(15, 30)));

    /** 指數「當日」分時一點：time 為當地時區 ISO LocalDateTime（"YYYY-MM-DDTHH:mm:ss"），close 為 5 分 K 收盤。 */
    public record IndexIntradayPoint(String time, BigDecimal close) {}

    /**
     * 指數「當日」分時走勢（Yahoo v8 chart，interval=5m&range=5d）。
     * 依 exchangeTimezoneName 轉當地時區、group by 當地日期，回「最新交易日」整天的 5 分 K 收盤序列。
     * 盤中＝今日部分 bar（即時）、盤後＝最後完整交易日，自動滿足「盤中即時／盤後最後交易日」。
     * transient（不寫 DB）；指數不在 Redis tick 輪詢名單，故不走 Task 88 tick store。
     */
    public List<IndexIntradayPoint> fetchIndexIntraday(String market) {
        if ("TPEX".equals(market)) {
            return fetchTpexIndexIntraday();
        }
        String symbol = INDEX_INTRADAY_YAHOO.get(market);
        if (symbol == null) {
            log.warn("未知指數市場: {}", market);
            return Collections.emptyList();
        }
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + symbol.replace("^", "%5E") + "?interval=5m&range=5d";
            String body = curlGetWithRetry(url, 2);
            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo 指數 {} 分時無資料: {}", symbol, err.isEmpty() ? "no timestamps" : err);
                return Collections.emptyList();
            }
            java.time.ZoneId zone = java.time.ZoneId.of(
                    chart.path("meta").path("exchangeTimezoneName").asText("Asia/Taipei"));
            // group by 當地日期 → 取最新交易日
            java.util.TreeMap<java.time.LocalDate, List<IndexIntradayPoint>> byDate = new java.util.TreeMap<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode close = quotes.path("close").path(i);
                if (close.isNull() || close.isMissingNode()) continue;
                java.time.LocalDateTime ldt = java.time.Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(zone).toLocalDateTime();
                byDate.computeIfAbsent(ldt.toLocalDate(), k -> new ArrayList<>())
                        .add(new IndexIntradayPoint(ldt.toString(), jsonDecimal4(close)));
            }
            if (byDate.isEmpty()) return Collections.emptyList();
            java.time.LocalDate day = byDate.lastKey();
            // 交易時段 open/close（各市場當地時區，見 INDEX_TRADING_HOURS）：
            // 補滿整個時段的 5 分格，盤中尚未到的時段 close 留 null → x 軸固定延伸到「收盤時間」而非「現在時間」
            TradingHours hours = INDEX_TRADING_HOURS.getOrDefault(
                    market, new TradingHours(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(16, 0)));
            java.time.LocalTime open = hours.open();
            java.time.LocalTime close = hours.close();
            // 實際 bar 對到 5 分格（floor）；最後一筆「現價」bar（非整 5 分，如 14:16）也歸入對應格
            java.util.Map<java.time.LocalDateTime, BigDecimal> bySlot = new java.util.HashMap<>();
            for (IndexIntradayPoint p : byDate.get(day)) {
                java.time.LocalDateTime t = java.time.LocalDateTime.parse(p.time());
                java.time.LocalDateTime slot = t.withSecond(0).withNano(0)
                        .withMinute((t.getMinute() / 5) * 5);
                bySlot.put(slot, p.close());
            }
            List<IndexIntradayPoint> out = new ArrayList<>();
            for (java.time.LocalDateTime cur = day.atTime(open), endT = day.atTime(close);
                 !cur.isAfter(endT); cur = cur.plusMinutes(5)) {
                out.add(new IndexIntradayPoint(cur.toString(), bySlot.get(cur)));
            }
            return out;
        } catch (Exception e) {
            log.warn("Yahoo 指數 {} 分時抓取失敗: {}", market, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** TPEx MIS 分時以 epoch milliseconds 為唯一時間基準；ts 僅在提供時交叉驗證。 */
    private List<IndexIntradayPoint> fetchTpexIndexIntraday() {
        try {
            JsonNode root = getOfficialJson("https://mis.twse.com.tw/stock/api/getChartOhlcStatis.jsp?ex=otc&ch=o00.tw&fqy=1");
            List<IndexIntradayPoint> points = parseTpexIndexIntraday(root);
            if (points == null) {
                log.warn("TPEx MIS 分時回應不符契約");
                return Collections.emptyList();
            }
            return points;
        } catch (Exception e) {
            log.warn("TPEx MIS 分時抓取失敗：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    static List<IndexIntradayPoint> parseTpexIndexIntraday(JsonNode root) {
        if (!"OK".equals(root.path("rtmessage").asText())) return null;
        LocalDate day = parseMisTradingDate(root.path("staticObj").path("key").asText(""));
        JsonNode bars = root.path("ohlcArray");
        if (day == null || !bars.isArray()) return null;

        Map<LocalDateTime, TimedClose> bySlot = new LinkedHashMap<>();
        for (JsonNode bar : bars) {
            Long epochMs = parseEpochMillis(bar.path("t"));
            BigDecimal close = parsePositiveDecimal(bar.path("c").asText(""));
            if (epochMs == null || epochMs <= 0 || close == null) return null;
            LocalDateTime time;
            try { time = Instant.ofEpochMilli(epochMs).atZone(TAIPEI).toLocalDateTime(); }
            catch (RuntimeException e) { return null; }
            if (!day.equals(time.toLocalDate())) return null;
            String ts = bar.path("ts").asText("").trim();
            if (!ts.isEmpty() && !matchesTaipeiTs(time, ts)) return null;
            LocalDateTime slot = time.withSecond(0).withNano(0).withMinute((time.getMinute() / 5) * 5);
            TimedClose existing = bySlot.get(slot);
            if (existing == null || epochMs >= existing.epochMs()) bySlot.put(slot, new TimedClose(epochMs, close));
        }
        List<IndexIntradayPoint> out = new ArrayList<>();
        for (LocalDateTime slot = day.atTime(9, 0), end = day.atTime(13, 30);
             !slot.isAfter(end); slot = slot.plusMinutes(5)) {
            TimedClose close = bySlot.get(slot);
            out.add(new IndexIntradayPoint(slot.toString(), close == null ? null : close.close()));
        }
        return out;
    }

    private record TimedClose(long epochMs, BigDecimal close) {}

    /** MIS 現行 key 為 otc_YYYYMMDD；保留舊民國 YYYY/MM/DD fixture 以相容官方格式變動。 */
    private static LocalDate parseMisTradingDate(String key) {
        String raw = key == null ? "" : key.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(".*_(\\d{8})$").matcher(raw);
        if (matcher.matches()) {
            try { return LocalDate.parse(matcher.group(1), java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }
            catch (RuntimeException ignored) { return null; }
        }
        return parseOfficialDate(raw);
    }

    private static Long parseEpochMillis(JsonNode node) {
        String raw = node.isIntegralNumber() ? node.asText() : node.isTextual() ? node.textValue() : null;
        if (raw == null || !raw.matches("\\d+")) return null;
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { return null; }
    }

    private static boolean matchesTaipeiTs(LocalDateTime time, String ts) {
        if (!ts.matches("\\d{6}")) return false;
        String expected = String.format("%02d%02d%02d", time.getHour(), time.getMinute(), time.getSecond());
        return expected.equals(ts);
    }

    /**
     * 指數當日 5 分 K 摘要（Task 263）：date 為該批點位所屬的交易所當地日期，呼叫端據此守門。
     * 四個數值欄各自獨立判 null（某陣列整日皆 null 時該欄為 null，不影響其餘欄位）。
     */
    public record DayQuote(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                           BigDecimal low, BigDecimal latestClose,
                           java.time.Instant freshnessInstant) {
        /** Source-compatible fixture constructor; production always supplies the final 5m timestamp. */
        public DayQuote(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                        BigDecimal low, BigDecimal latestClose) {
            this(date, open, high, low, latestClose,
                    date == null ? null : date.atStartOfDay(java.time.ZoneId.of("Asia/Taipei")).toInstant());
        }
    }

    /**
     * 指數「當日」OHLC 摘要（Task 263）。與 {@link #fetchIndexIntraday} 打同一個 Yahoo URL
     * （interval=5m&range=5d）、走同一支 {@code curlGetWithRetry}，但回傳的是**該批點位所屬日期**
     * 與當日 open / high / low / 最新收盤，供 {@code TaiexIndexPoller} 守門與填欄。
     *
     * <p><b>為什麼不改 {@link #fetchIndexIntraday} 而另開一支：</b>後者的回傳型別
     * {@link IndexIntradayPoint} 只有 time / close 兩欄，且會補滿整個交易時段的 5 分格
     * （未到的時段 close 為 null）以固定「股市大盤查詢」頁當日走勢圖的 x 軸。動它就動到那一頁。
     *
     * <p><b>與 {@code fetchIndexIntraday} 的 {@code byDate.lastKey()} 有一處刻意的差異：</b>
     * 後者在分組前就跳過 close 為 null 的格，故它取的是「最新一個<b>有成交收盤</b>的交易日」；
     * 本方法對全部 timestamp 分日後取 max。兩者只在「今日有格但 close 整日皆 null」時分歧，
     * 而該情形經呼叫端的 {@code latestClose == null} 守門後行為一致（皆不寫入）。
     *
     * @return 查無資料 / 無 timestamp 陣列 / 例外時回 {@code null}
     */
    public DayQuote fetchIndexIntradayDay(String market) {
        String symbol = INDEX_INTRADAY_YAHOO.get(market);
        if (symbol == null) {
            log.warn("未知指數市場: {}", market);
            return null;
        }
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + symbol.replace("^", "%5E") + "?interval=5m&range=5d";
            String body = curlGetWithRetry(url, 2);
            JsonNode root = mapper.readTree(body);
            JsonNode chart = root.path("chart").path("result").path(0);
            JsonNode timestamps = chart.path("timestamp");
            JsonNode quotes = chart.path("indicators").path("quote").path(0);
            if (!timestamps.isArray() || timestamps.isEmpty()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo 指數 {} 當日摘要無資料: {}", symbol, err.isEmpty() ? "no timestamps" : err);
                return null;
            }
            java.time.ZoneId zone = java.time.ZoneId.of(
                    chart.path("meta").path("exchangeTimezoneName").asText("Asia/Taipei"));

            // 對全部 timestamp 分日後取 max（不預先濾 null close，理由見 javadoc）
            java.time.LocalDate day = null;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                java.time.LocalDate d = java.time.Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(zone).toLocalDate();
                if (day == null || d.isAfter(day)) {
                    day = d;
                    idx.clear();
                }
                if (d.equals(day)) idx.add(i);
            }
            if (day == null) return null;

            BigDecimal open = null, high = null, low = null, latestClose = null;
            java.time.Instant latestCloseInstant = null;
            for (int i : idx) {
                BigDecimal o = jsonDecimal4(quotes.path("open").path(i));
                BigDecimal h = jsonDecimal4(quotes.path("high").path(i));
                BigDecimal l = jsonDecimal4(quotes.path("low").path(i));
                BigDecimal c = jsonDecimal4(quotes.path("close").path(i));
                if (open == null && o != null) open = o;                     // 當日第一格的開盤
                if (h != null && (high == null || h.compareTo(high) > 0)) high = h;
                if (l != null && (low == null || l.compareTo(low) < 0)) low = l;
                if (c != null) {
                    long epochSeconds = timestamps.get(i).asLong(0);
                    if (epochSeconds <= 0) return null;
                    latestClose = c;                                         // 當日最後一格的收盤
                    latestCloseInstant = java.time.Instant.ofEpochSecond(epochSeconds);
                }
            }
            if (latestCloseInstant != null
                    && !latestCloseInstant.atZone(zone).toLocalDate().equals(day)) return null;
            return new DayQuote(day, open, high, low, latestClose, latestCloseInstant);
        } catch (Exception e) {
            log.warn("Yahoo 指數 {} 當日摘要抓取失敗: {}", market, e.getMessage());
            return null;
        }
    }

    /**
     * curl 子程序 + 重試（Yahoo Finance 對 Java HTTP client 友善度差，偶發 429）。
     * 回應非 JSON 視為被擋，等 10s/20s/... 後重試，最多 maxRetries 次。
     */
    private String curlGetWithRetry(String url, int maxRetries) throws Exception {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return body;
            if (attempt < maxRetries) {
                try { Thread.sleep((attempt + 1) * 10000L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("中斷", e);
                }
            }
        }
        throw new RuntimeException("Yahoo Finance 重試 " + maxRetries + " 次仍失敗");
    }
}
