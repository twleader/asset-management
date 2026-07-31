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

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /**
     * DGBAS 專屬 HttpClient（Requirement 30）：ws.dgbas.gov.tw 伺服器漏送中繼憑證，
     * 以打包的 TWCA 中繼憑證為信任錨建鏈、主機名驗證維持啟用；僅此 client 使用，不影響其他抓取。
     */
    private final HttpClient dgbasHttp = buildDgbasHttpClient();
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
     * 海外指數代碼 → Yahoo symbol。
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

    /**
     * 海外指數（美股四大 + 英德韓日）近 10 年每日 OHLC（Yahoo Finance v8 chart API，range=10y&interval=1d）。
     * 用 curl 子程序避開 Yahoo 對 Java HTTP/2 fingerprint 的封鎖（與 PriceFetchClient.fetchUsHistoricalRange 同 pattern）。
     * timestamp → 交易日依 Yahoo meta exchangeTimezoneName 轉當地時區（美股 daily bar 在開盤時刻，轉 NY 與交易所
     * 時區同結果；但亞洲/歐洲指數 daily bar 在 UTC 午夜＝當地開盤，用 NY 會把日期回退一日，故一律讀交易所時區）。
     * 無資料 / 不認得 code 回空 list（呼叫端略過）。
     */
    public List<DailyOhlc> fetchUsIndexDaily(String indexCode) {
        String symbol = US_INDEX_YAHOO.get(indexCode);
        if (symbol == null) {
            log.warn("未知海外指數代碼: {}", indexCode);
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
                out.add(new DailyOhlc(d,
                        jsonDecimal4(quotes.path("open").path(i)),
                        jsonDecimal4(quotes.path("high").path(i)),
                        jsonDecimal4(quotes.path("low").path(i)),
                        jsonDecimal4(close)));
            }
            return out;
        } catch (Exception e) {
            log.warn("Yahoo 指數 {} 抓取失敗: {}", indexCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static BigDecimal jsonDecimal4(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        return BigDecimal.valueOf(v.asDouble()).setScale(4, RoundingMode.HALF_UP);
    }

    /** 指數市場代碼 → Yahoo symbol（含台股大盤 ^TWII 與海外指數，供「當日」分時）。 */
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

    /**
     * 指數當日 5 分 K 摘要（Task 263）：date 為該批點位所屬的交易所當地日期，呼叫端據此守門。
     * 四個數值欄各自獨立判 null（某陣列整日皆 null 時該欄為 null，不影響其餘欄位）。
     */
    public record DayQuote(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                           BigDecimal low, BigDecimal latestClose) {}

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
            for (int i : idx) {
                BigDecimal o = jsonDecimal4(quotes.path("open").path(i));
                BigDecimal h = jsonDecimal4(quotes.path("high").path(i));
                BigDecimal l = jsonDecimal4(quotes.path("low").path(i));
                BigDecimal c = jsonDecimal4(quotes.path("close").path(i));
                if (open == null && o != null) open = o;                     // 當日第一格的開盤
                if (h != null && (high == null || h.compareTo(high) > 0)) high = h;
                if (l != null && (low == null || l.compareTo(low) < 0)) low = l;
                if (c != null) latestClose = c;                              // 當日最後一格的收盤
            }
            return new DayQuote(day, open, high, low, latestClose);
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
