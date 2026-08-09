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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 證交所公開資訊抓取（Task 149.21）：三大法人買賣金額（BFI82U）＋大盤成交統計（FMTQIK）。
 * 皆為官方 JSON，較新聞面更「硬」的量化訊號（外資/投信買賣超、成交量能），供市場分析研判當日資金流向。
 * URL 帶交易日參數使每日唯一（天然去重）。逐來源 graceful（失敗只 warn、回空）。
 */
@Slf4j
@Component
public class TwseInfoFetchClient {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    public static final String INSTITUTIONAL_PROVIDER = "TWSE_BFI82U";
    public static final String INSTITUTIONAL_AVAILABLE = "AVAILABLE";
    public static final String INSTITUTIONAL_UNAVAILABLE = "UNAVAILABLE";
    public static final String INSTITUTIONAL_AVAILABILITY_BASIS =
            "OBSERVED_AT_NO_PUBLISHED_TIMESTAMP";

    /** 三大法人買賣金額統計表（www.twse.com.tw RWD 版，支援 ?date=；openapi 版在 2026 維護期會 302→HTML）。 */
    private static final String BFI82U_TPL =
            "https://www.twse.com.tw/rwd/zh/fund/BFI82U?response=json&date=";
    /** 大盤成交統計（openapi，回最近數個交易日陣列，取最新一筆）。 */
    private static final String FMTQIK_URL =
            "https://openapi.twse.com.tw/v1/exchangeReport/FMTQIK";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * BFI82U 的 typed numeric 結果。這份 record 是法人數值落庫的唯一來源；新聞標題只是
     * 同一份結果的顯示 projection，永遠不得再反向解析成數值。
     */
    public record InstitutionalObservation(
            LocalDate tradingDate,
            BigDecimal foreignNet,
            BigDecimal trustNet,
            BigDecimal dealerNet,
            BigDecimal totalNet,
            String provider,
            String sourceUrl,
            Instant observedAt,
            Instant sourceAvailableAt,
            String availabilityBasis,
            String status,
            String errorReason,
            String detail) {

        public boolean available() {
            return INSTITUTIONAL_AVAILABLE.equals(status)
                    && tradingDate != null
                    && foreignNet != null
                    && trustNet != null
                    && dealerNet != null
                    && totalNet != null;
        }
    }

    /** One TWSE request bundle so callers can persist typed evidence without re-fetching or parsing news. */
    public record FetchResult(
            List<NewsRow> newsRows,
            InstitutionalObservation institutionalObservation) {
        public FetchResult {
            newsRows = newsRows == null ? List.of() : List.copyOf(newsRows);
        }
    }

    /** 抓 TWSE 公開資訊，逐項 graceful，回合併清單（通常各 1 則）。 */
    public List<NewsRow> fetchAll() {
        return fetchAllTyped().newsRows();
    }

    /** 抓同一批公開資訊並同時回傳法人 typed numeric observation。 */
    public FetchResult fetchAllTyped() {
        List<NewsRow> out = new ArrayList<>();
        InstitutionalObservation observation = fetchInstitutionalObservation();
        NewsRow institutionalNews = toInstitutionalNews(observation);
        if (institutionalNews != null) out.add(institutionalNews);
        try { NewsRow r = fetchTurnover(); if (r != null) out.add(r); }
        catch (Exception e) { log.warn("TWSE 大盤成交統計抓取失敗：{}", e.getMessage()); }
        return new FetchResult(out, observation);
    }

    // ===== 三大法人買賣金額（BFI82U）=====

    /** 抓取法人原始數值；任何失敗都回明確 UNAVAILABLE observation，不偽造 0。 */
    public InstitutionalObservation fetchInstitutionalObservation() {
        String ymd = LocalDate.now(TW).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd
        String sourceUrl = BFI82U_TPL + ymd;
        try {
            String body = get(sourceUrl);
            return parseInstitutionalResponse(body, Instant.now(), sourceUrl);
        } catch (Exception e) {
            Instant observedAt = Instant.now();
            log.warn("TWSE 三大法人抓取失敗：{}", e.getMessage());
            return unavailableInstitutional(observedAt, sourceUrl, e.getMessage());
        }
    }

    /** package-private for deterministic parser tests; production calls the public fetch method above. */
    InstitutionalObservation parseInstitutionalResponse(
            String body, Instant observedAt, String sourceUrl) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (!"OK".equals(root.path("stat").asText())) {
            return unavailableInstitutional(observedAt, sourceUrl,
                    "TWSE stat=" + root.path("stat").asText("MISSING"));
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return unavailableInstitutional(observedAt, sourceUrl, "TWSE BFI82U data 為空");
        }
        String dateStr = root.path("date").asText(""); // 實際資料日（假日會回最近交易日）
        LocalDate tradeDate = parseYyyymmdd(dateStr);
        if (tradeDate == null) {
            return unavailableInstitutional(observedAt, sourceUrl, "TWSE BFI82U date 無效");
        }

        // 依單位名稱累加買賣差額（index 3）
        BigDecimal foreign = BigDecimal.ZERO, trust = BigDecimal.ZERO, dealer = BigDecimal.ZERO, total = null;
        boolean foreignSeen = false, trustSeen = false, dealerSeen = false, totalSeen = false;
        StringBuilder detail = new StringBuilder();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 4) continue;
            String name = row.get(0).asText("");
            BigDecimal diff = parseAmount(row.get(3).asText(""));
            if (diff == null) continue;
            detail.append(name).append(" ").append(yi(diff)).append("；");
            // 「外資自營商」金額已計入自營商(自行買賣/避險)兩列、且不納入三大法人合計（TWSE 官方註記），
            // 故此列只入 detail、不獨立計入任何桶，避免外資/自營/合計重複計算。用 equals 精確跳過
            // （「外資及陸資(不含外資自營商)」字串亦含「外資自營商」，不可用 contains）。
            if (name.equals("外資自營商")) continue;
            if (name.contains("外資")) {                                  // 只命中「外資及陸資(不含外資自營商)」
                foreign = foreign.add(diff);
                foreignSeen = true;
            } else if (name.contains("投信")) {
                trust = trust.add(diff);
                trustSeen = true;
            } else if (name.contains("自營商")) {                          // 自行買賣 + 避險（已含外資自營商）
                dealer = dealer.add(diff);
                dealerSeen = true;
            } else if (name.contains("合計")) {
                total = diff;
                totalSeen = true;
            }
        }
        if (!foreignSeen || !trustSeen || !dealerSeen || !totalSeen || total == null) {
            return unavailableInstitutional(observedAt, sourceUrl,
                    "TWSE BFI82U 缺少外資／投信／自營商／合計原始數值");
        }
        return new InstitutionalObservation(
                tradeDate, foreign, trust, dealer, total,
                INSTITUTIONAL_PROVIDER, sourceUrl, observedAt, null,
                INSTITUTIONAL_AVAILABILITY_BASIS, INSTITUTIONAL_AVAILABLE, null,
                detail.toString());
    }

    private static InstitutionalObservation unavailableInstitutional(
            Instant observedAt, String sourceUrl, String reason) {
        return new InstitutionalObservation(
                null, null, null, null, null,
                INSTITUTIONAL_PROVIDER, sourceUrl, observedAt, null,
                INSTITUTIONAL_AVAILABILITY_BASIS, INSTITUTIONAL_UNAVAILABLE,
                reason == null || reason.isBlank() ? "TWSE BFI82U unavailable" : reason,
                null);
    }

    private static NewsRow toInstitutionalNews(InstitutionalObservation observation) {
        if (observation == null || !observation.available()) return null;
        LocalDate tradeDate = observation.tradingDate();
        String title = String.format("三大法人買賣超（%s）：外資 %s、投信 %s、自營 %s、合計 %s",
                tradeDate, yi(observation.foreignNet()), yi(observation.trustNet()),
                yi(observation.dealerNet()), yi(observation.totalNet()));
        String dateStr = tradeDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return new NewsRow(title, "twse",
                "https://www.twse.com.tw/zh/trading/foreign/bfi82u.html?date=" + dateStr,
                "twse-institutional", "TW",
                observation.detail(),
                tradeDate.atStartOfDay(TW).toInstant());
    }

    // ===== 大盤成交統計（FMTQIK）=====

    private NewsRow fetchTurnover() throws Exception {
        JsonNode arr = mapper.readTree(get(FMTQIK_URL));
        if (!arr.isArray() || arr.isEmpty()) return null;
        JsonNode row = arr.get(arr.size() - 1); // 最新一筆
        LocalDate tradeDate = parseRocDate(row.path("Date").asText(""));
        if (tradeDate == null) return null;
        BigDecimal taiex = parseAmount(row.path("TAIEX").asText(""));
        BigDecimal change = parseAmount(row.path("Change").asText(""));
        BigDecimal value = parseAmount(row.path("TradeValue").asText(""));
        BigDecimal volume = parseAmount(row.path("TradeVolume").asText(""));
        String title = String.format("台股大盤成交統計（%s）：加權指數 %s（%s）、成交值 %s、成交量 %s",
                tradeDate,
                taiex == null ? "-" : taiex.toPlainString(),
                change == null ? "-" : (change.signum() >= 0 ? "+" : "") + change.toPlainString(),
                zhao(value), yiShares(volume));
        String ymd = tradeDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return new NewsRow(title, "twse",
                "https://www.twse.com.tw/zh/trading/exchange/FMTQIK.html?date=" + ymd,
                "twse-turnover", "TW",
                null,
                tradeDate.atStartOfDay(TW).toInstant());
    }

    // ===== helpers =====

    /** "413,214,615,558" / "-1,234" → BigDecimal；空/"-"/無法解析回 null。 */
    private static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.replace(",", "").trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    /** 金額 → 億（÷1e8，1 位小數，帶正負號）。 */
    private static String yi(BigDecimal v) {
        if (v == null) return "-";
        BigDecimal y = v.divide(BigDecimal.valueOf(100_000_000L), 1, RoundingMode.HALF_UP);
        return (y.signum() > 0 ? "+" : "") + y.toPlainString() + "億";
    }

    /** 成交值 → 兆（÷1e12，2 位小數）。 */
    private static String zhao(BigDecimal v) {
        if (v == null) return "-";
        return v.divide(BigDecimal.valueOf(1_000_000_000_000L), 2, RoundingMode.HALF_UP).toPlainString() + "兆";
    }

    /** 成交量（股）→ 億股（÷1e8，1 位小數）。 */
    private static String yiShares(BigDecimal v) {
        if (v == null) return "-";
        return v.divide(BigDecimal.valueOf(100_000_000L), 1, RoundingMode.HALF_UP).toPlainString() + "億股";
    }

    /** "20260707" → LocalDate；失敗回 null。 */
    private static LocalDate parseYyyymmdd(String s) {
        if (s == null || s.length() != 8) return null;
        try {
            return LocalDate.of(Integer.parseInt(s.substring(0, 4)),
                    Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8)));
        } catch (Exception e) { return null; }
    }

    /** 民國 "1150706"（YYYMMDD，3 位民國年）→ 西元 LocalDate；失敗回 null。 */
    private static LocalDate parseRocDate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() != 7) return null;
        try {
            return LocalDate.of(Integer.parseInt(t.substring(0, 3)) + 1911,
                    Integer.parseInt(t.substring(3, 5)), Integer.parseInt(t.substring(5, 7)));
        } catch (Exception e) { return null; }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> res = http.send(req,
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + res.statusCode() + " @ " + url);
        }
        return res.body();
    }
}
