package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.MarketDataFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 台股個股基本面來源 client（Requirement 46 / Task 292）。
 *
 * <p>結構化資料第一順位只走 TWSE／TPEx 官方 OpenAPI；官方來源缺少足以形成衍生因子的歷史時，
 * 才依序嘗試 Yahoo、玩股網、FinMind。玩股網目前可靠能力只有既有 {@link NewsFetchClient} 的公開資訊新聞，
 * 沒有可稽核的結構化財報端點，所以本類別刻意回空並繼續 FinMind，絕不從標題硬解析數字。</p>
 *
 * <p>TWSE／TPEx 端點皆為政府資料開放通道；禁止另抓 MOPS HTML。Yahoo Finance 的非正式端點與 FinMind
 * 都只作使用者持股／觀察代號的 fail-soft fallback，不掃全市場。fallback 若沒有原始發布時間，
 * {@code sourceAvailableAt} 一律使用第一次成功觀測時間，避免把後來抓到的資料倒灌到歷史回測。</p>
 */
@Slf4j
@Component
public class StockFundamentalFetchClient {

    public static final String EXCHANGE = "EXCHANGE";
    public static final String YAHOO = "YAHOO";
    public static final String WANTGOO = "WANTGOO";
    public static final String FINMIND = "FINMIND";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String TWSE = "https://openapi.twse.com.tw/v1/";
    private static final String TPEX = "https://www.tpex.org.tw/openapi/v1/";
    private static final List<String> FINANCIAL_SUFFIXES = List.of("ci", "fh", "ins", "bd", "mim", "basi");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String finmindToken;
    private final MarketDataFetchService marketDataFetchService;

    public StockFundamentalFetchClient(
            @Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken,
            MarketDataFetchService marketDataFetchService) {
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
        this.marketDataFetchService = marketDataFetchService;
    }

    public record Valuation(
            String stockCode,
            LocalDate tradingDate,
            BigDecimal peRatio,
            BigDecimal pbRatio,
            BigDecimal dividendYieldPct,
            Boolean peLossFlag,
            String provider,
            List<String> sourceUrls,
            Instant sourceAvailableAt,
            String availabilityBasis) {}

    /** EPS／母公司淨利採同年度累計口徑；權益為期末值。 */
    public record Financial(
            String stockCode,
            int fiscalYear,
            int fiscalQuarter,
            BigDecimal cumulativeEps,
            Long cumulativeNetIncomeParent,
            Long equityParent,
            String provider,
            List<String> sourceUrls,
            Instant sourceAvailableAt,
            String availabilityBasis) {}

    public record Revenue(
            String stockCode,
            int revenueYear,
            int revenueMonth,
            String industryName,
            Long revenue,
            Long priorYearRevenue,
            BigDecimal revenueYoyPct,
            String provider,
            List<String> sourceUrls,
            Instant sourceAvailableAt,
            String availabilityBasis) {}

    /**
     * 一個 provider 呼叫的資料與 HTTP 健康度。成功空陣列是 successful attempt；例外／非 2xx 才列 failure。
     * 三參數建構子保留給純資料過濾與測試，不會虛構來源成功。
     */
    public record Bundle(
            List<Valuation> valuations,
            List<Financial> financials,
            List<Revenue> revenues,
            int attempts,
            int successes,
            List<String> failures) {
        public static final Bundle EMPTY = new Bundle(List.of(), List.of(), List.of(), 0, 0, List.of());

        public Bundle(List<Valuation> valuations, List<Financial> financials, List<Revenue> revenues) {
            this(valuations, financials, revenues, 0, 0, List.of());
        }
    }

    /** 一輪抓取上市＋上櫃官方整批來源；單一端點失敗只使該來源缺值。 */
    public Bundle fetchOfficial() {
        List<Valuation> valuations = new ArrayList<>();
        List<Revenue> revenues = new ArrayList<>();
        Map<QuarterKey, MutableFinancial> financials = new LinkedHashMap<>();
        FetchStats stats = new FetchStats();

        fetchArray(TWSE + "exchangeReport/BWIBBU_ALL", stats).ifPresent(rows ->
                rows.forEach(row -> parseValuation(row, TWSE + "exchangeReport/BWIBBU_ALL", false)
                        .ifPresent(valuations::add)));
        fetchArray(TPEX + "tpex_mainboard_peratio_analysis", stats).ifPresent(rows ->
                rows.forEach(row -> parseValuation(row, TPEX + "tpex_mainboard_peratio_analysis", true)
                        .ifPresent(valuations::add)));

        fetchArray(TWSE + "opendata/t187ap05_L", stats).ifPresent(rows ->
                rows.forEach(row -> parseRevenue(row, TWSE + "opendata/t187ap05_L")
                        .ifPresent(revenues::add)));
        fetchArray(TPEX + "mopsfin_t187ap05_O", stats).ifPresent(rows ->
                rows.forEach(row -> parseRevenue(row, TPEX + "mopsfin_t187ap05_O")
                        .ifPresent(revenues::add)));

        for (String suffix : FINANCIAL_SUFFIXES) {
            parseIncomeEndpoint(TWSE + "opendata/t187ap06_L_" + suffix, financials, stats);
            parseBalanceEndpoint(TWSE + "opendata/t187ap07_L_" + suffix, financials, stats);
            parseIncomeEndpoint(TPEX + "mopsfin_t187ap06_O_" + suffix, financials, stats);
            parseBalanceEndpoint(TPEX + "mopsfin_t187ap07_O_" + suffix, financials, stats);
        }

        return new Bundle(List.copyOf(valuations),
                financials.values().stream().map(MutableFinancial::toRecord)
                        .filter(java.util.Objects::nonNull).toList(),
                List.copyOf(revenues), stats.attempts, stats.successes, List.copyOf(stats.failures));
    }

    /**
     * Yahoo 只在能取得可追溯的 current valuation 時回值；共用 {@link MarketDataFetchService}
     * 既有的 cookie／crumb 與限流狀態，429／401／缺欄皆為正常 fail-soft。
     */
    public Bundle fetchYahoo(String code, Instant observedAt) {
        int attempts = 0;
        int successes = 0;
        List<String> failures = new ArrayList<>();
        for (String suffix : List.of(".TW", ".TWO")) {
            String symbol = code + suffix;
            attempts++;
            MarketDataFetchService.YahooValuationFetch fetched =
                    marketDataFetchService.getYahooValuation(symbol);
            if (fetched == null || !fetched.succeeded()) {
                failures.add("YAHOO:" + symbol + ":" + (fetched == null ? "no status" : fetched.error()));
                continue;
            }
            successes++;
            MarketDataFetchService.YahooValuation valuation = fetched.valuation();
            if (valuation != null) {
                BigDecimal pe = valuation.peRatio();
                return new Bundle(List.of(new Valuation(
                        code, observedAt.atZone(TAIPEI).toLocalDate(), pe,
                        valuation.pbRatio(), valuation.dividendYieldPct(),
                        pe == null ? null : Boolean.FALSE, YAHOO, List.of(valuation.sourceUrl()),
                        observedAt, "OBSERVED")),
                        List.of(), List.of(), attempts, successes, List.copyOf(failures));
            }
        }
        return new Bundle(List.of(), List.of(), List.of(), attempts, successes, List.copyOf(failures));
    }

    /** 玩股網結構化 fallback：目前無可信數值端點；新聞證據由 public_info/news_headline 路徑提供。 */
    public Bundle fetchWantGoo(String code, Instant observedAt) {
        log.debug("玩股網 {} 僅提供公開資訊新聞，結構化基本面繼續 fallback", code);
        return Bundle.EMPTY;
    }

    /** FinMind 最後順位：只查單一目標代號，補 5 年估值、月營收與季度財報歷史。 */
    public Bundle fetchFinMind(String code, Instant observedAt) {
        LocalDate since = observedAt.atZone(TAIPEI).toLocalDate().minusYears(5);
        List<Valuation> valuations = List.of();
        List<Revenue> revenues = List.of();
        List<Financial> financials = List.of();
        int successes = 0;
        List<String> failures = new ArrayList<>();
        try {
            valuations = finMindValuation(code, since, observedAt);
            successes++;
        } catch (Exception e) {
            failures.add("FINMIND:TaiwanStockPER:" + message(e));
        }
        try {
            revenues = finMindRevenue(code, since, observedAt);
            successes++;
        } catch (Exception e) {
            failures.add("FINMIND:TaiwanStockMonthRevenue:" + message(e));
        }
        try {
            financials = finMindFinancial(code, since, observedAt);
            successes++;
        } catch (Exception e) {
            failures.add("FINMIND:financial-statements:" + message(e));
        }
        failures.forEach(failure -> log.warn("FinMind 基本面 {} 失敗：{}", code, failure));
        return new Bundle(valuations, financials, revenues, 3, successes, List.copyOf(failures));
    }

    java.util.Optional<Valuation> parseValuation(JsonNode row, String url, boolean tpex) {
        String code = text(row, tpex ? List.of("SecuritiesCompanyCode", "公司代號", "Code")
                : List.of("Code", "公司代號"));
        LocalDate date = rocDate(text(row, List.of("Date", "出表日期")));
        if (code == null || date == null) return java.util.Optional.empty();
        String peRaw = textAllowEmpty(row, tpex
                ? List.of("PriceEarningRatio", "本益比", "PEratio")
                : List.of("PEratio", "本益比"));
        BigDecimal pe = decimal(peRaw);
        Boolean loss = pe != null ? Boolean.FALSE : (peRaw != null ? Boolean.TRUE : null);
        return java.util.Optional.of(new Valuation(
                code, date, pe,
                decimal(textAllowEmpty(row, tpex ? List.of("PriceBookRatio", "股價淨值比")
                        : List.of("PBratio", "股價淨值比"))),
                decimal(textAllowEmpty(row, tpex ? List.of("YieldRatio", "殖利率(%)")
                        : List.of("DividendYield", "殖利率(%)"))),
                loss, EXCHANGE, List.of(url),
                date.atTime(LocalTime.of(14, 0)).atZone(TAIPEI).toInstant(), "PUBLISHED"));
    }

    java.util.Optional<Revenue> parseRevenue(JsonNode row, String url) {
        String code = text(row, List.of("公司代號", "SecuritiesCompanyCode", "Code"));
        String period = text(row, List.of("資料年月", "YearMonth"));
        LocalDate published = rocDate(text(row, List.of("出表日期", "Date")));
        int[] ym = rocYearMonth(period);
        if (code == null || published == null || ym == null) return java.util.Optional.empty();
        Long revenue = integer(textAllowEmpty(row,
                List.of("營業收入-當月營收", "當月營收", "MonthlyRevenue")));
        Long prior = integer(textAllowEmpty(row,
                List.of("營業收入-去年當月營收", "去年當月營收", "PriorYearRevenue")));
        BigDecimal yoy = decimal(textAllowEmpty(row,
                List.of("營業收入-去年同月增減(%)", "去年同月增減(%)", "RevenueYearOnYear")));
        return java.util.Optional.of(new Revenue(code, ym[0], ym[1],
                text(row, List.of("產業別", "Industry")), revenue, prior, yoy,
                EXCHANGE, List.of(url),
                published.atTime(LocalTime.of(18, 0)).atZone(TAIPEI).toInstant(), "PUBLISHED"));
    }

    private void parseIncomeEndpoint(
            String url, Map<QuarterKey, MutableFinancial> out, FetchStats stats) {
        fetchArray(url, stats).ifPresent(rows -> rows.forEach(row -> {
            QuarterKey key = quarterKey(row);
            if (key == null) return;
            MutableFinancial m = out.computeIfAbsent(key, MutableFinancial::new);
            IncomeFields fields = incomeFields(row);
            // 六種產業 schema 有時會對同公司回傳空欄；空值不得覆蓋先前已解到的數字。
            if (fields.eps() != null) m.eps = fields.eps();
            if (fields.netIncome() != null) m.netIncome = fields.netIncome();
            m.addSource(url, availableAt(row));
        }));
    }

    private void parseBalanceEndpoint(
            String url, Map<QuarterKey, MutableFinancial> out, FetchStats stats) {
        fetchArray(url, stats).ifPresent(rows -> rows.forEach(row -> {
            QuarterKey key = quarterKey(row);
            if (key == null) return;
            MutableFinancial m = out.computeIfAbsent(key, MutableFinancial::new);
            Long equity = equityField(row);
            if (equity != null) m.equity = equity;
            m.addSource(url, availableAt(row));
        }));
    }

    private QuarterKey quarterKey(JsonNode row) {
        String code = text(row, List.of("公司代號", "SecuritiesCompanyCode", "Code"));
        Integer year = rocYear(text(row, List.of("年度", "Year")));
        Integer quarter = integerValue(text(row, List.of("季別", "Season", "Quarter")));
        if (code == null || year == null || quarter == null || quarter < 1 || quarter > 4) return null;
        return new QuarterKey(code, year, quarter);
    }

    record IncomeFields(BigDecimal eps, Long netIncome) {}

    static IncomeFields incomeFields(JsonNode row) {
        return new IncomeFields(
                firstDecimal(row, "基本每股盈餘（元）", "基本每股盈餘(元)", "基本每股盈餘", "EPS"),
                firstLong(row, "淨利（淨損）歸屬於母公司業主", "歸屬於母公司業主之淨利（損）",
                        "淨利（損）歸屬於母公司業主", "NetIncomeAttributableToOwnersOfParent"));
    }

    static Long equityField(JsonNode row) {
        return firstLong(row, "歸屬於母公司業主之權益合計", "歸屬於母公司業主權益",
                "歸屬於母公司業主之權益", "歸屬於母公司業主權益合計",
                "EquityAttributableToOwnersOfParent");
    }

    private Instant availableAt(JsonNode row) {
        LocalDate date = rocDate(text(row, List.of("出表日期", "Date")));
        return date == null ? null : date.atTime(LocalTime.of(18, 0)).atZone(TAIPEI).toInstant();
    }

    private List<Valuation> finMindValuation(String code, LocalDate since, Instant observedAt) throws Exception {
        String url = finMindUrl("TaiwanStockPER", code, since);
        JsonNode data = finMindData(url);
        List<Valuation> out = new ArrayList<>();
        for (JsonNode row : data) {
            LocalDate date = isoDate(row.path("date").asText(null));
            if (date == null) continue;
            BigDecimal pe = decimalNode(row.get("PER"));
            out.add(new Valuation(code, date, pe, decimalNode(row.get("PBR")),
                    decimalNode(row.get("dividend_yield")), pe == null ? null : Boolean.FALSE,
                    FINMIND, List.of(url), observedAt, "OBSERVED"));
        }
        return out;
    }

    private List<Revenue> finMindRevenue(String code, LocalDate since, Instant observedAt) throws Exception {
        String url = finMindUrl("TaiwanStockMonthRevenue", code, since);
        JsonNode data = finMindData(url);
        Map<String, Long> values = new HashMap<>();
        for (JsonNode row : data) {
            int year = row.path("revenue_year").asInt(0);
            int month = row.path("revenue_month").asInt(0);
            BigDecimal rawValue = decimalNode(row.get("revenue"));
            if (year > 0 && month >= 1 && month <= 12 && rawValue != null) {
                values.put(year + "-" + month, rawValue.divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValue());
            }
        }
        List<Revenue> out = new ArrayList<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            String[] parts = entry.getKey().split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            Long prior = values.get((year - 1) + "-" + month);
            BigDecimal yoy = prior == null || prior <= 0 ? null
                    : BigDecimal.valueOf(entry.getValue()).subtract(BigDecimal.valueOf(prior))
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(prior), 4, RoundingMode.HALF_UP);
            out.add(new Revenue(code, year, month, null, entry.getValue(), prior, yoy,
                    FINMIND, List.of(url), observedAt, "OBSERVED"));
        }
        out.sort(Comparator.comparingInt(Revenue::revenueYear).thenComparingInt(Revenue::revenueMonth));
        return out;
    }

    private List<Financial> finMindFinancial(String code, LocalDate since, Instant observedAt) throws Exception {
        String incomeUrl = finMindUrl("TaiwanStockFinancialStatements", code, since);
        String balanceUrl = finMindUrl("TaiwanStockBalanceSheet", code, since);
        Map<LocalDate, FinMindQuarter> byDate = new LinkedHashMap<>();
        for (JsonNode row : finMindData(incomeUrl)) {
            LocalDate date = isoDate(row.path("date").asText(null));
            if (date == null) continue;
            FinMindQuarter q = byDate.computeIfAbsent(date, FinMindQuarter::new);
            String type = row.path("type").asText("");
            if ("EPS".equals(type)) q.standaloneEps = decimalNode(row.get("value"));
            if ("EquityAttributableToOwnersOfParent".equals(type)) {
                q.standaloneNetIncome = thousandLong(row.get("value"));
            }
        }
        for (JsonNode row : finMindData(balanceUrl)) {
            LocalDate date = isoDate(row.path("date").asText(null));
            if (date == null) continue;
            if ("EquityAttributableToOwnersOfParent".equals(row.path("type").asText(""))) {
                byDate.computeIfAbsent(date, FinMindQuarter::new).equity = thousandLong(row.get("value"));
            }
        }
        List<FinMindQuarter> sorted = byDate.values().stream().sorted(Comparator.comparing(q -> q.date)).toList();
        Map<Integer, BigDecimal> epsRunning = new HashMap<>();
        Map<Integer, Long> incomeRunning = new HashMap<>();
        List<Financial> out = new ArrayList<>();
        for (FinMindQuarter q : sorted) {
            int quarter = (q.date.getMonthValue() + 2) / 3;
            int year = q.date.getYear();
            BigDecimal cumEps = q.standaloneEps == null ? null
                    : epsRunning.getOrDefault(year, BigDecimal.ZERO).add(q.standaloneEps);
            Long cumIncome = q.standaloneNetIncome == null ? null
                    : incomeRunning.getOrDefault(year, 0L) + q.standaloneNetIncome;
            if (cumEps != null) epsRunning.put(year, cumEps);
            if (cumIncome != null) incomeRunning.put(year, cumIncome);
            out.add(new Financial(code, year, quarter, cumEps, cumIncome, q.equity,
                    FINMIND, List.of(incomeUrl, balanceUrl), observedAt, "OBSERVED"));
        }
        return out;
    }

    private java.util.Optional<List<JsonNode>> fetchArray(String url, FetchStats stats) {
        stats.attempts++;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode node = getJson(url);
                if (!node.isArray()) throw new IllegalStateException("root is not array");
                List<JsonNode> rows = new ArrayList<>();
                node.forEach(rows::add);
                stats.successes++;
                return java.util.Optional.of(rows);
            } catch (Exception e) {
                if (attempt == 0) {
                    try { Thread.sleep(250); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        stats.failures.add("EXCHANGE:" + url + ":interrupted");
                        return java.util.Optional.empty();
                    }
                } else {
                    log.warn("基本面來源失敗 {}：{}", url, e.getMessage());
                    stats.failures.add("EXCHANGE:" + url + ":" + message(e));
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class FetchStats {
        private int attempts;
        private int successes;
        private final List<String> failures = new ArrayList<>();
    }

    private JsonNode finMindData(String url) throws Exception {
        JsonNode root = getJson(url, !finmindToken.isBlank());
        JsonNode data = root.path("data");
        if (root.path("status").asInt(200) != 200 || !data.isArray()) {
            throw new IllegalStateException(root.path("msg").asText("invalid FinMind response"));
        }
        return data;
    }

    private String finMindUrl(String dataset, String code, LocalDate since) {
        return "https://api.finmindtrade.com/api/v4/data?dataset=" + encode(dataset)
                + "&data_id=" + encode(code) + "&start_date=" + since;
    }

    private JsonNode getJson(String url) throws Exception {
        return getJson(url, false);
    }

    private JsonNode getJson(String url, boolean bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (bearer) builder.header("Authorization", "Bearer " + finmindToken);
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode row, List<String> keys) {
        String value = textAllowEmpty(row, keys);
        return value == null || value.isBlank() ? null : value;
    }

    private static String textAllowEmpty(JsonNode row, List<String> keys) {
        for (String key : keys) {
            if (!row.has(key) || row.get(key).isNull()) continue;
            return row.get(key).asText().trim();
        }
        return null;
    }

    private static BigDecimal firstDecimal(JsonNode row, String... keys) {
        return decimal(textAllowEmpty(row, List.of(keys)));
    }

    private static Long firstLong(JsonNode row, String... keys) {
        return integer(textAllowEmpty(row, List.of(keys)));
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "-".equals(value) || "--".equals(value)) return null;
        try { return new BigDecimal(value.replace(",", "")); }
        catch (Exception e) { return null; }
    }

    private static BigDecimal decimalNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return decimal(node.asText());
    }

    private static Long integer(String value) {
        BigDecimal decimal = decimal(value);
        return decimal == null ? null : decimal.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static Integer integerValue(String value) {
        Long result = integer(value);
        return result == null ? null : result.intValue();
    }

    private static Long thousandLong(JsonNode node) {
        BigDecimal value = decimalNode(node);
        return value == null ? null : value.divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValue();
    }

    static LocalDate rocDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        try {
            if (digits.length() == 7) {
                return LocalDate.of(Integer.parseInt(digits.substring(0, 3)) + 1911,
                        Integer.parseInt(digits.substring(3, 5)), Integer.parseInt(digits.substring(5, 7)));
            }
            if (digits.length() == 8) {
                return LocalDate.of(Integer.parseInt(digits.substring(0, 4)),
                        Integer.parseInt(digits.substring(4, 6)), Integer.parseInt(digits.substring(6, 8)));
            }
        } catch (Exception ignored) {
            // malformed source row is skipped by caller
        }
        return null;
    }

    static Integer rocYear(String raw) {
        Integer value = integerValue(raw);
        if (value == null) return null;
        return value < 1911 ? value + 1911 : value;
    }

    static int[] rocYearMonth(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        try {
            if (digits.length() == 5) {
                return new int[]{Integer.parseInt(digits.substring(0, 3)) + 1911,
                        Integer.parseInt(digits.substring(3, 5))};
            }
            if (digits.length() == 6) {
                return new int[]{Integer.parseInt(digits.substring(0, 4)),
                        Integer.parseInt(digits.substring(4, 6))};
            }
        } catch (Exception ignored) {
            // malformed source row is skipped by caller
        }
        return null;
    }

    private static LocalDate isoDate(String raw) {
        try { return raw == null ? null : LocalDate.parse(raw); }
        catch (Exception e) { return null; }
    }

    private record QuarterKey(String code, int year, int quarter) {}

    private static final class MutableFinancial {
        private final QuarterKey key;
        private BigDecimal eps;
        private Long netIncome;
        private Long equity;
        private Instant availableAt;
        private final Set<String> sourceUrls = new LinkedHashSet<>();

        private MutableFinancial(QuarterKey key) { this.key = key; }

        private void addSource(String url, Instant available) {
            sourceUrls.add(url);
            if (available != null && (availableAt == null || available.isAfter(availableAt))) availableAt = available;
        }

        private Financial toRecord() {
            // 官方財報若沒有可驗證的出表日就不落庫；Epoch 會讓回測錯誤地視為永遠可見。
            if (availableAt == null) return null;
            return new Financial(key.code(), key.year(), key.quarter(), eps, netIncome, equity,
                    EXCHANGE, List.copyOf(sourceUrls), availableAt, "PUBLISHED");
        }
    }

    private static final class FinMindQuarter {
        private final LocalDate date;
        private BigDecimal standaloneEps;
        private Long standaloneNetIncome;
        private Long equity;

        private FinMindQuarter(LocalDate date) { this.date = date; }
    }
}
