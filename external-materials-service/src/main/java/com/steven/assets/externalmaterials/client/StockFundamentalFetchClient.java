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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 台股／美股個股基本面來源 client（Requirement 46 / Task 292；美股見 Task 293）。
 *
 * <p>台股結構化資料第一順位只走 TWSE／TPEx 官方 OpenAPI；官方來源缺少足以形成衍生因子的歷史時，
 * 才依序嘗試 Yahoo、玩股網、FinMind。玩股網目前可靠能力只有既有 {@link NewsFetchClient} 的公開資訊新聞，
 * 沒有可稽核的結構化財報端點，所以本類別刻意回空並繼續 FinMind，絕不從標題硬解析數字。</p>
 *
 * <p>美股改走 SEC EDGAR {@code companyfacts} 官方 XBRL API 取 EPS／淨利／權益，PE／PB 沿用 Yahoo
 * {@code quoteSummary}；FinMind 的 USStockPrice 只有收盤價，不提供任何財報欄位，不列入美股基本面鏈。</p>
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
    public static final String SEC_EDGAR = "SEC_EDGAR";
    /**
     * 由已入庫 SEC 官方季報「推導」出的美股歷史估值（Requirement 74 / Task 334），**不是任何來源
     * 觀測到的公告值**。本 client 不產生這個 provider 的列（沒有對應的外部端點）；常數放在這裡是
     * 因為其餘 provider 標籤都在這裡，且 {@code stock_valuation_daily.provider} 是 varchar(20)，
     * 11 字元在長度上限內。產生者為 {@code UsValuationDerivationService}。
     */
    public static final String SEC_DERIVED = "SEC_DERIVED";
    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String TWSE = "https://openapi.twse.com.tw/v1/";
    private static final String TPEX = "https://www.tpex.org.tw/openapi/v1/";
    private static final List<String> FINANCIAL_SUFFIXES = List.of("ci", "fh", "ins", "bd", "mim", "basi");
    private static final String SEC_TICKER_MAP = "https://www.sec.gov/files/company_tickers.json";
    private static final String SEC_COMPANYFACTS = "https://data.sec.gov/api/xbrl/companyfacts/CIK%010d.json";
    private static final String SEC_USER_AGENT = "asset-management-trading-radar (contact: tw.leader@gmail.com)";
    private static final Duration SEC_TIMEOUT = Duration.ofSeconds(15);
    /** ticker→CIK 映射只在記憶體快取、不落 DB；TTL 約束「單一抓取輪次」的粒度，不跨輪次持久化。 */
    private static final Duration SEC_TICKER_CACHE_TTL = Duration.ofMinutes(30);
    /**
     * SEC {@code companyfacts} 只保留 {@code end} 落在最近幾年內的事實（Task 334.2 由 3 改為 11）。
     *
     * <p>歷史深度不再是「順便多抓一點」，而是 Requirement 74 推導序列的**必要輸入**：
     * {@code UsValuationDerivationService} 以已入庫季報逐交易日推導 PE／PB／殖利率，序列長度直接
     * 決定 {@code FundamentalAnalysisService} 的 250 筆分位門檻能不能被滿足。11 年對齊
     * {@code stock_price_history} 美股實際覆蓋區間（實測 2016-08-15～2026-08-14），再往前拉也沒有
     * 對應的收盤價可配對。</p>
     */
    private static final int SEC_LOOKBACK_YEARS = 11;
    /** SEC 申報的可見時點以美東交易時區換算（{@link #filedInstant}）。 */
    private static final ZoneId US_EXCHANGE_ZONE = ZoneId.of("America/New_York");
    /**
     * {@code filed} 只有日期精度，一律以「當日美股收盤之後」作為公開時點的保守估計（16:30 America/New_York）。
     *
     * <p>大型股的 10-Q／10-K 慣例在收盤後申報，取 16:30 ET 讓該期別<b>從申報日的下一個交易日起</b>才可見。
     * 舊值是「當日中午 UTC」＝08:00 ET，對台股消費端（20:00 台北，晚於 13:30 收盤）確實保守，但對美股是
     * <b>開盤前</b>——{@code UsValuationDerivationService} 以「≤ D 當日美股收盤時刻」判可見性，會讓申報當日
     * 的推導列變成「盤前價 ÷ 尚未公開的財報」，構成一個交易日的 look-ahead（每季一天，且恰好落在財報公布
     * 日這種最敏感的一天）。</p>
     */
    private static final LocalTime SEC_FILING_VISIBLE_TIME = LocalTime.of(16, 30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String finmindToken;
    private final MarketDataFetchService marketDataFetchService;
    private volatile Map<String, Long> tickerCikCache;
    private volatile Instant tickerCikCacheExpiry = Instant.EPOCH;

    public StockFundamentalFetchClient(
            @Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken,
            MarketDataFetchService marketDataFetchService) {
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
        this.marketDataFetchService = marketDataFetchService;
    }

    public record Valuation(
            String stockCode,
            String market,
            LocalDate tradingDate,
            BigDecimal peRatio,
            BigDecimal pbRatio,
            BigDecimal dividendYieldPct,
            Boolean peLossFlag,
            String provider,
            List<String> sourceUrls,
            Instant sourceAvailableAt,
            String availabilityBasis) {}

    /** EPS／母公司淨利採同年度累計口徑；權益為期末值。美股（{@code market="美股"}）數值單位為原始 USD，不做千元換算。 */
    public record Financial(
            String stockCode,
            String market,
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
            String market,
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
     *
     * <p>{@code market} 決定 symbol 組法（Task 293）：{@code "台股"} 依序嘗試 {@code .TW}／{@code .TWO}
     * 後綴（既有行為不變）；{@code "美股"} symbol 原樣使用，不加任何後綴。</p>
     */
    public Bundle fetchYahoo(String code, String market, Instant observedAt) {
        List<String> symbols = US_MARKET.equals(market)
                ? List.of(code)
                : List.of(code + ".TW", code + ".TWO");
        int attempts = 0;
        int successes = 0;
        List<String> failures = new ArrayList<>();
        for (String symbol : symbols) {
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
                        code, market, observedAt.atZone(TAIPEI).toLocalDate(), pe,
                        valuation.pbRatio(), valuation.dividendYieldPct(),
                        pe == null ? null : Boolean.FALSE, YAHOO, List.of(valuation.sourceUrl()),
                        observedAt, "OBSERVED")),
                        List.of(), List.of(), attempts, successes, List.copyOf(failures));
            }
        }
        return new Bundle(List.of(), List.of(), List.of(), attempts, successes, List.copyOf(failures));
    }

    /**
     * 美股 EPS／淨利／權益：SEC EDGAR {@code companyfacts} 官方 XBRL API（Task 293）。免 API key，
     * 但要求具名 {@code User-Agent}；4xx/5xx／逾時／解析失敗一律回空結果，不擲例外，讓 provider 鏈
     * 可以繼續往下一順位（比照本類別其餘 fetch 方法既有 fail-soft 風格）。
     *
     * <p>ticker→CIK 映射只在記憶體快取一段時間（不落 DB、不跨輪次持久化），新股上市會在下次快取到期後
     * 自然更新。只抓 {@code EarningsPerShareDiluted}（缺則退回 {@code EarningsPerShareBasic}）、
     * {@code NetIncomeLoss}、{@code StockholdersEquity} 三個 concept，且只保留 {@code end} 落在最近
     * {@value #SEC_LOOKBACK_YEARS} 年內的列。<b>視窗長度（Task 334.2 由 3 年改為 11 年）不是抓取量
     * 的偏好，而是 Requirement 74 的必要輸入</b>：美股歷史估值序列由這些季報逐交易日推導而來
     * （{@code UsValuationDerivationService}），季報深度不足就湊不出 250 筆分位樣本，整組 VALUATION
     * 證據會維持 MISSING。11 年對齊 {@code stock_price_history} 美股實際覆蓋的 2016-08-15～2026-08-14，
     * 更早的季報沒有對應收盤價可配對。</p>
     *
     * <p><b>放寬視窗不等於放寬解析防線。</b>{@link #extractFacts} 仍只收 {@code form ∈ {10-Q, 10-K}}，
     * {@link #buildPeriodMap}／{@link #selectCumulative} 的比較年度標籤陷阱與累計口徑陷阱防護一律不變——
     * 舊年度的申報文件有同樣的污染，放寬只會讓錯誤數字靜默入庫。</p>
     */
    public Bundle fetchSecEdgarFacts(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return Bundle.EMPTY;
        java.util.Optional<Long> cik = findCik(tickerCikMap(), stockCode);
        if (cik.isEmpty()) {
            log.warn("SEC EDGAR 查無 ticker 對應 CIK：{}", stockCode);
            return Bundle.EMPTY;
        }
        String url = String.format(SEC_COMPANYFACTS, cik.get());
        java.util.Optional<JsonNode> root = fetchSecJson(url);
        if (root.isEmpty()) {
            return new Bundle(List.of(), List.of(), List.of(), 1, 0,
                    List.of("SEC_EDGAR:" + url + ":fetch failed"));
        }
        try {
            List<Financial> financials = parseCompanyFacts(root.get(), stockCode, url);
            return new Bundle(List.of(), financials, List.of(), 1, 1, List.of());
        } catch (Exception e) {
            log.warn("SEC EDGAR companyfacts 解析失敗 {}：{}", stockCode, message(e));
            return new Bundle(List.of(), List.of(), List.of(), 1, 0,
                    List.of("SEC_EDGAR:" + url + ":parse failed:" + message(e)));
        }
    }

    /** ticker→CIK 記憶體快取；到期或首次呼叫時重新抓取，抓取失敗時沿用舊值（fail-soft）。 */
    private Map<String, Long> tickerCikMap() {
        Instant now = Instant.now();
        Map<String, Long> cached = tickerCikCache;
        if (cached != null && now.isBefore(tickerCikCacheExpiry)) return cached;
        java.util.Optional<Map<String, Long>> fresh = fetchSecJson(SEC_TICKER_MAP)
                .map(StockFundamentalFetchClient::parseTickerMap);
        if (fresh.isEmpty() || fresh.get().isEmpty()) {
            return cached == null ? Map.of() : cached;
        }
        tickerCikCache = fresh.get();
        tickerCikCacheExpiry = now.plus(SEC_TICKER_CACHE_TTL);
        return tickerCikCache;
    }

    /** {@code https://www.sec.gov/files/company_tickers.json} 的 {ticker: cik_str} 映射解析。 */
    static Map<String, Long> parseTickerMap(JsonNode root) {
        Map<String, Long> map = new HashMap<>();
        if (root == null || !root.isObject()) return map;
        root.fields().forEachRemaining(entry -> {
            JsonNode v = entry.getValue();
            String ticker = v.path("ticker").asText(null);
            if (ticker == null || ticker.isBlank() || !v.hasNonNull("cik_str")) return;
            long cikValue = v.path("cik_str").asLong(-1);
            if (cikValue < 0) return;
            map.put(ticker.trim().toUpperCase(), cikValue);
        });
        return map;
    }

    static java.util.Optional<Long> findCik(Map<String, Long> tickerToCik, String stockCode) {
        if (stockCode == null || stockCode.isBlank() || tickerToCik == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(tickerToCik.get(stockCode.trim().toUpperCase()));
    }

    /** SEC EDGAR 專用 GET；4xx/5xx／逾時／JSON 解析失敗一律回空，不擲例外。 */
    java.util.Optional<JsonNode> fetchSecJson(String url) {
        return fetchSecJson(url, SEC_TIMEOUT);
    }

    /** 帶自訂逾時的重載，供測試以極短逾時驗證 fail-soft 行為，不必真的等待正式逾時秒數。 */
    java.util.Optional<JsonNode> fetchSecJson(String url, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", SEC_USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("SEC EDGAR 呼叫失敗 {}：HTTP {}", url, response.statusCode());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(mapper.readTree(response.body()));
        } catch (Exception e) {
            log.warn("SEC EDGAR 呼叫失敗 {}：{}", url, message(e));
            return java.util.Optional.empty();
        }
    }

    /**
     * 解析 companyfacts 的 {@code facts.us-gaap} 節點為 {@link Financial} 列（Task 293）。
     *
     * <p><b>比較年度標籤陷阱（Task 293 追加修正 v3）</b>：SEC XBRL 對「比較年度」數字（同一份申報文件
     * 內附帶的前一年度對照數字）常沿用**申報文件本身**所屬的 {@code fy}，而非數字實際所屬的年度——
     * 同一組真實數字會在不同申報年度的文件中反覆出現、卻各自被貼上不同的 {@code fy}。故期間身分改由
     * {@link #buildPeriodMap} 直接依 {@code (start,end)} 這組物理日期推導，不信任 {@code fy}
     * 做跨年度分組；{@code fp}（Q1/Q2/Q3/Q4/FY）本身無跡象不可靠，季別判定仍沿用它。
     * {@code StockholdersEquity}（資產負債表時點值，無 {@code start}）改為與 EPS／淨利共用同一份由
     * duration 概念算出的期間對照表，依自身 {@code end} 查表取得期間標籤，避免同一污染源也作用於
     * 瞬時值（實測 {@code equity_parent} 在多檔美股皆出現跨「年度」重複值，證實污染同樣發生於此）。</p>
     *
     * <p><b>累計口徑陷阱（Task 293 原始版本）</b>：SEC XBRL 對同一 concept／{@code fy}／{@code fp} 常同時
     * 存在「單季 3 個月」與「年度累計 YTD」兩種期間長度不同的事實。{@code cumulativeEps}／
     * {@code cumulativeNetIncomeParent} 的既定語意是「自會計年度起算至該季止的累計值」，
     * {@link #buildPeriodMap} 只信任「同一 {@code start} 下累積到 ≥2 個相異 {@code end}」的分組
     * （真正的年度起點會隨季度推進累積出多個 {@code end}；單季 3 個月事實偶然落在非年度起點的
     * {@code start} 只會是孤例），避免下游 {@code standalone()} 的減法邏輯把單季誤當累計算出錯誤但
     * 不拋例外的數字。代價是每個真實會計年度的第一季要等第二季出現才會連同回填揭露，屬刻意的
     * 精確度／時效取捨。</p>
     */
    static List<Financial> parseCompanyFacts(JsonNode root, String stockCode, String sourceUrl) {
        JsonNode gaap = root.path("facts").path("us-gaap");
        if (!gaap.isObject()) return List.of();
        LocalDate cutoff = LocalDate.now(TAIPEI).minusYears(SEC_LOOKBACK_YEARS);

        List<XbrlFact> epsFacts = extractFacts(gaap, "EarningsPerShareDiluted", "USD/shares", cutoff);
        if (epsFacts.isEmpty()) epsFacts = extractFacts(gaap, "EarningsPerShareBasic", "USD/shares", cutoff);
        List<XbrlFact> incomeFacts = extractFacts(gaap, "NetIncomeLoss", "USD", cutoff);
        List<XbrlFact> equityFacts = extractFacts(gaap, "StockholdersEquity", "USD", cutoff);

        Map<LocalDate, ValidatedPeriod> periodByEnd = buildPeriodMap(epsFacts, incomeFacts);
        Map<FyQuarter, ChosenFact> epsByQuarter = selectCumulative(epsFacts, periodByEnd);
        Map<FyQuarter, ChosenFact> incomeByQuarter = selectCumulative(incomeFacts, periodByEnd);
        Map<FyQuarter, ChosenFact> equityByQuarter = selectInstant(equityFacts, periodByEnd);

        Set<FyQuarter> periods = new TreeSet<>(
                Comparator.comparingInt(FyQuarter::year).thenComparingInt(FyQuarter::quarter));
        periods.addAll(epsByQuarter.keySet());
        periods.addAll(incomeByQuarter.keySet());
        periods.addAll(equityByQuarter.keySet());

        List<Financial> out = new ArrayList<>();
        for (FyQuarter fq : periods) {
            ChosenFact epsFact = epsByQuarter.get(fq);
            ChosenFact incomeFact = incomeByQuarter.get(fq);
            ChosenFact equityFact = equityByQuarter.get(fq);
            BigDecimal eps = epsFact == null ? null : epsFact.val();
            Long income = incomeFact == null ? null : toLong(incomeFact.val());
            Long equity = equityFact == null ? null : toLong(equityFact.val());
            if (eps == null && income == null && equity == null) continue;
            // source_available_at 一律取「首次申報時點」（最早 filed），不是被選中那筆的 filed（最新 filed）。
            // 值仍取最新 filed 的那一筆（重述後的正確數字），兩者刻意分開追蹤，理由見 selectCumulative。
            Instant firstFiled = earliest(
                    epsFact == null ? null : epsFact.firstFiled(),
                    incomeFact == null ? null : incomeFact.firstFiled(),
                    equityFact == null ? null : equityFact.firstFiled());
            Instant availableAt = firstFiled != null ? firstFiled : Instant.now();
            String basis = firstFiled != null ? "PUBLISHED" : "OBSERVED";
            out.add(new Financial(stockCode, US_MARKET, fq.year(), fq.quarter(), eps, income, equity,
                    SEC_EDGAR, List.of(sourceUrl), availableAt, basis));
        }
        return out;
    }

    /** 只保留 {@code form} 為 10-Q／10-K、{@code end} 落在 cutoff 之後、可辨識 {@code fp} 的事實。 */
    private static List<XbrlFact> extractFacts(JsonNode gaap, String concept, String unit, LocalDate cutoff) {
        JsonNode arr = gaap.path(concept).path("units").path(unit);
        List<XbrlFact> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode f : arr) {
            String form = f.path("form").asText("");
            if (!"10-Q".equals(form) && !"10-K".equals(form)) continue;
            String fpRaw = f.path("fp").asText(null);
            int quarter = fpToQuarter(fpRaw);
            if (quarter == 0) continue;
            if (!f.hasNonNull("end")) continue;
            LocalDate end = isoDate(f.path("end").asText(null));
            if (end == null || end.isBefore(cutoff)) continue;
            if (!f.hasNonNull("val")) continue;
            BigDecimal val = f.path("val").decimalValue();
            int fy = f.path("fy").asInt(-1);
            if (fy <= 0) continue;
            LocalDate start = f.hasNonNull("start") ? isoDate(f.path("start").asText(null)) : null;
            Instant filed = f.hasNonNull("filed") ? filedInstant(f.path("filed").asText(null)) : null;
            out.add(new XbrlFact(start, end, val, fy, fpRaw.toUpperCase(), filed));
        }
        return out;
    }

    /**
     * 期間身分的唯一權威來源（Task 293 追加修正 v4）：直接由一或多個 duration 概念（EPS＋淨利）的
     * {@code (start,end)} 這組真實日期聯集推導，不信任 SEC 的 {@code fy} 標籤——比較年度數字常沿用
     * 申報文件本身的年度，非數字實際所屬年度。
     *
     * <p>先以 {@code (start,end)} 去重（同一真實區間被多份文件各報一次時取 {@code filed} 最新的一筆），
     * 再以 {@code start} 分組——同一個會計年度的起始日是物理事實，不受標籤品質影響，天然把同一真實
     * 年度的各季資料分在同一組。**只信任組內存在 {@code fp="Q1"} 成員的分組**：{@code fp=Q1} 由 SEC
     * 慣例明確指「會計年度第一季」，其 {@code start} 定義上必為真正的會計年度起點；spurious 的單季
     * standalone 分組（Q2／Q3／Q4 各自的三個月起點）依慣例只會標 {@code fp=Q2/Q3/Q4}，不會出現
     * {@code fp=Q1}，天然被排除。**先前版本改用「≥2 個相異 end」判斷，經真實 AMZN companyfacts 驗證
     * 證實不足**：{@code NetIncomeLoss} 對 AMZN 額外揭露一筆 trailing-twelve-month（非季度邊界）事實
     * （例如 {@code start=2025-07-01, end=2026-06-30, fp=Q2}），其 {@code start} 恰好與一筆真實的 Q3
     * 單季 standalone 事實（{@code start=2025-07-01, end=2025-09-30, fp=Q3}）撞在一起湊出 2 個相異
     * {@code end}，讓該偽分組被誤判為真實年度序列、把 Q3 數值錯貼上不存在的年度標籤。要求
     * {@code fp=Q1} 成員存在可正確排除這類巧合，且不再需要等第二筆事實佐證，Q1 一經申報即可信任。</p>
     *
     * <p>組內每個 {@code end} 的季別沿用既有 {@link #fpToQuarter}；判斷「這個 {@code start} 是否為
     * 真正的會計年度起點」（{@code hasGenuineFiscalYearStart}）時，除了要求存在 {@code fp="Q1"} 成員，
     * **還要求該成員的實際天數（{@code end−start}）落在單季量級（約 60–100 天）**——這一步是實測
     * AMZN 真實資料後才發現的必要防線：AMZN 每年 Q1 10-Q 慣例會附一筆 trailing-twelve-month（跨度
     * 364–365 天）的補充淨利數字，卻同樣貼上 {@code fp="Q1"}，單純檢查「組內是否存在 {@code fp=Q1}」
     * 不足以防範這類「{@code fp} 正確、但事實本身跨度不合該量級」的情況，會讓一個原本不該被信任的
     * {@code start}（湊巧非年度起點、只是巧合共用一個 TTM 事實）被誤判為真實會計年度起點。**此天數
     * 檢查只用於判斷是否信任整個 start 分組，不影響組內其餘成員（含真正的 Q2 累計／單季事實）本身
     * 是否被納入**——`fp` 相同時累計值與單季值的天數量級本就不同（例如 Q2 累計約 182 天、Q2 單季約
     * 91 天皆合法存在），對每個成員套用統一天數門檻會誤刪合法的單季事實，真正區分累計與單季的判準
     * 仍是既有的「{@code start} 是否等於本分組已驗證的起點」（見 {@link #selectCumulative}）。</p>
     * {@code fiscalYear} 標籤只依 {@code start} 決定（起始月 1–6 算當年、7–12 算隔年，貼近美股常見
     * 的錯位會計年命名慣例），不隨後續季別陸續到位而回頭改變已指派的標籤，避免同一真實區間在不同
     * 抓取輪次被寫成不同的 {@code (fiscal_year, fiscal_quarter)} 而在表裡留下孤兒列。</p>
     */
    static Map<LocalDate, ValidatedPeriod> buildPeriodMap(List<XbrlFact> epsFacts, List<XbrlFact> incomeFacts) {
        Map<LocalDate, Map<LocalDate, XbrlFact>> byStartThenEnd = new HashMap<>();
        for (List<XbrlFact> facts : List.of(epsFacts, incomeFacts)) {
            for (XbrlFact f : facts) {
                if (f.start() == null || f.end() == null) continue;
                Map<LocalDate, XbrlFact> byEnd = byStartThenEnd.computeIfAbsent(f.start(), k -> new HashMap<>());
                XbrlFact existing = byEnd.get(f.end());
                if (existing == null || isNewer(f.filed(), existing.filed())) byEnd.put(f.end(), f);
            }
        }
        Map<LocalDate, ValidatedPeriod> periodByEnd = new HashMap<>();
        for (Map.Entry<LocalDate, Map<LocalDate, XbrlFact>> entry : byStartThenEnd.entrySet()) {
            LocalDate start = entry.getKey();
            Map<LocalDate, XbrlFact> byEnd = entry.getValue();
            boolean hasGenuineFiscalYearStart = byEnd.values().stream().anyMatch(f ->
                    "Q1".equals(f.fp()) && isQuarterConsistentDuration("Q1", ChronoUnit.DAYS.between(start, f.end())));
            if (!hasGenuineFiscalYearStart) continue;
            int fiscalYear = start.getMonthValue() <= 6 ? start.getYear() : start.getYear() + 1;
            for (XbrlFact f : byEnd.values()) {
                int quarter = fpToQuarter(f.fp());
                if (quarter == 0) continue;
                periodByEnd.putIfAbsent(f.end(), new ValidatedPeriod(start, new FyQuarter(fiscalYear, quarter)));
            }
        }
        return periodByEnd;
    }

    /**
     * 累計期間事實（EPS／淨利）依 {@link #buildPeriodMap} 已驗證的期間身分挑值：**必須同時符合
     * {@code start} 與 {@code end}** 才接受——只比對 {@code end} 會讓單季 3 個月事實（{@code start}
     * 較晚、但 {@code end} 恰好與已驗證累計期間相同）透過巧合的 {@code end} 混入正確分組（例如 Q2
     * 累計 {@code start=1/1,end=6/30} 與 Q2 單季 {@code start=4/1,end=6/30} 共用同一個
     * {@code end}），重新引入本次要修的同一類「取到錯誤期間值」問題。查無對應期間或 {@code start}
     * 不符的事實直接捨棄；同一期間有多筆候選時取 {@code filed} 最新的一筆。
     *
     * <p><b>「取哪個值」與「什麼時候可見」是兩件事，必須分開追蹤（Task 334 對抗式審查追加）</b>：值取
     * {@code filed} <b>最新</b>的一筆（申報重述之後的正確數字），但 {@code source_available_at} 取同一期間
     * 全部候選中<b>最早</b>的 {@code filed}（＝真實首次申報時點）。每一份 10-Q／10-K 都夾帶去年同季的比較
     * 數字，若把「最新 filed」當成可見時點，每一個舊期別都會被推遲整整一年才「可見」，只有還沒被下一年
     * 申報提及的最新四季例外——實測 GOOGL 每季 {@code source_available_at} 距其日曆期末 388–401 天，最新
     * 四季卻只有 23–36 天。這個位移對期別是保序的，{@code UsValuationDerivationService} 的
     * {@code effective_available_at} 單調化（取 min-over-newer）<b>取不掉</b>，結果是同一條推導序列的
     * 歷史區段用落後約四季的 TTM 分母、最近一年用當期值，成長股的「今天」因此必然落在自身歷史 PE 的極低
     * 分位而輸出「現在最便宜」。單調化只該負責修真正倒置的日期（AMZN 2025Q2／2025Q3），不該被拿來當這個
     * 系統性位移的補償。</p>
     */
    private static Map<FyQuarter, ChosenFact> selectCumulative(
            List<XbrlFact> facts, Map<LocalDate, ValidatedPeriod> periodByEnd) {
        Map<FyQuarter, XbrlFact> chosen = new HashMap<>();
        Map<FyQuarter, Instant> firstFiled = new HashMap<>();
        for (XbrlFact f : facts) {
            if (f.start() == null || f.end() == null) continue;
            ValidatedPeriod period = periodByEnd.get(f.end());
            if (period == null || !period.start().equals(f.start())) continue;
            rememberFirstFiled(firstFiled, period.fyQuarter(), f.filed());
            XbrlFact existing = chosen.get(period.fyQuarter());
            if (existing == null || isNewer(f.filed(), existing.filed())) chosen.put(period.fyQuarter(), f);
        }
        return toChosenMap(chosen, firstFiled);
    }

    /**
     * 資產負債表時點值（{@code StockholdersEquity}，無 {@code start}）依 {@link #buildPeriodMap}
     * 已驗證的期間身分挑值，純依 {@code end} 查表（無 {@code start} 可比對），與 EPS／淨利共用同一份
     * 期間對照表，讓同一真實季度的三個值必然落在同一個 {@code (fiscal_year, fiscal_quarter)} key 下
     * （{@code roeFactor()} 需要權益與淨利同列才能算近似 ROE）。查無對應 duration 期間的權益時點值
     * 直接捨棄，不產生孤兒列；同一期間有多筆候選時取 {@code filed} 最新的一筆。
     */
    private static Map<FyQuarter, ChosenFact> selectInstant(
            List<XbrlFact> facts, Map<LocalDate, ValidatedPeriod> periodByEnd) {
        Map<FyQuarter, XbrlFact> chosen = new HashMap<>();
        Map<FyQuarter, Instant> firstFiled = new HashMap<>();
        for (XbrlFact f : facts) {
            if (f.end() == null) continue;
            ValidatedPeriod period = periodByEnd.get(f.end());
            if (period == null) continue;
            rememberFirstFiled(firstFiled, period.fyQuarter(), f.filed());
            XbrlFact existing = chosen.get(period.fyQuarter());
            if (existing == null || isNewer(f.filed(), existing.filed())) chosen.put(period.fyQuarter(), f);
        }
        return toChosenMap(chosen, firstFiled);
    }

    private record ValidatedPeriod(LocalDate start, FyQuarter fyQuarter) {}

    private static Map<FyQuarter, ChosenFact> toChosenMap(
            Map<FyQuarter, XbrlFact> chosen, Map<FyQuarter, Instant> firstFiled) {
        Map<FyQuarter, ChosenFact> result = new HashMap<>();
        chosen.forEach((k, v) -> result.put(k, new ChosenFact(v.val(), v.filed(), firstFiled.get(k))));
        return result;
    }

    /** 記住某期間曾被提及的<b>最早</b> {@code filed}；null（無申報日）不參與比較。 */
    private static void rememberFirstFiled(
            Map<FyQuarter, Instant> firstFiled, FyQuarter period, Instant filed) {
        if (filed == null) return;
        Instant known = firstFiled.get(period);
        if (known == null || filed.isBefore(known)) firstFiled.put(period, filed);
    }

    /** {@code FY}（10-K 年報）視同第 4 季累計值，與台股「年度累計至 Q4」語意一致。 */
    private static int fpToQuarter(String fp) {
        if (fp == null) return 0;
        return switch (fp.toUpperCase()) {
            case "Q1" -> 1;
            case "Q2" -> 2;
            case "Q3" -> 3;
            case "Q4", "FY" -> 4;
            default -> 0;
        };
    }

    /**
     * 天數與 {@code fp} 宣稱的季別量級是否相符（Task 293 追加修正 v4）。實測 AMZN 真實 companyfacts
     * 確認會有 trailing-twelve-month（跨度 364–365 天）的補充淨利數字被貼上 {@code fp="Q1"}，天數與
     * {@code fp} 是兩個獨立訊號，任一不符即視為雜訊。門檻留有緩衝（52/53 週會計曆、閏年）：
     * Q1 約 91 天（60–100）、Q2 約 182 天（150–210）、Q3 約 273 天（240–300）、Q4／FY 約 365 天
     * （330–380）。
     */
    private static boolean isQuarterConsistentDuration(String fp, long days) {
        if (fp == null || days <= 0) return false;
        return switch (fp.toUpperCase()) {
            case "Q1" -> days >= 60 && days <= 100;
            case "Q2" -> days >= 150 && days <= 210;
            case "Q3" -> days >= 240 && days <= 300;
            case "Q4", "FY" -> days >= 330 && days <= 380;
            default -> false;
        };
    }

    private static boolean isNewer(Instant candidate, Instant current) {
        if (candidate == null) return false;
        if (current == null) return true;
        return candidate.isAfter(current);
    }

    private static Long toLong(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** 三個 concept 各自的首次申報時點中最早的一個；全為 null 時回 null。 */
    private static Instant earliest(Instant... values) {
        Instant best = null;
        for (Instant v : values) {
            if (v != null && (best == null || v.isBefore(best))) best = v;
        }
        return best;
    }

    /** SEC EDGAR {@code filed} 只有日期精度；見 {@link #SEC_FILING_VISIBLE_TIME}（當日美股收盤之後）。 */
    private static Instant filedInstant(String raw) {
        LocalDate date = isoDate(raw);
        return date == null ? null : date.atTime(SEC_FILING_VISIBLE_TIME).atZone(US_EXCHANGE_ZONE).toInstant();
    }

    private record XbrlFact(LocalDate start, LocalDate end, BigDecimal val, int fy, String fp, Instant filed) {}

    private record FyQuarter(int year, int quarter) {}

    /**
     * 被選中的事實。{@code filed} 是被選中那一筆（最新 filed）的申報日，{@code firstFiled} 是<b>同一期間
     * 曾被任何一份申報提及過的最早</b> filed——後者才是 {@code source_available_at} 的正確語意。
     */
    private record ChosenFact(BigDecimal val, Instant filed, Instant firstFiled) {}

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
                code, TW_MARKET, date, pe,
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
        return java.util.Optional.of(new Revenue(code, TW_MARKET, ym[0], ym[1],
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
            out.add(new Valuation(code, TW_MARKET, date, pe, decimalNode(row.get("PBR")),
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
            out.add(new Revenue(code, TW_MARKET, year, month, null, entry.getValue(), prior, yoy,
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
            out.add(new Financial(code, TW_MARKET, year, quarter, cumEps, cumIncome, q.equity,
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
            return new Financial(key.code(), TW_MARKET, key.year(), key.quarter(), eps, netIncome, equity,
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
