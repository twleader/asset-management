package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 從 FinMind / NASDAQ 抓股利歷史，原本在 backend MarketDataService.getDividendHistory，
 * 已遷至此服務以滿足「對外抓資料邏輯集中於 external-materials-service」的架構規範。
 */
@Slf4j
@Component
public class DividendFetchClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String finmindToken;
    private final DividendUpcomingScopeClient upcomingScopeClient;
    private final Clock clock;

    @Autowired
    public DividendFetchClient(
            @Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken,
            DividendUpcomingScopeClient upcomingScopeClient) {
        this(finmindToken, upcomingScopeClient, Clock.systemUTC(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    DividendFetchClient(String finmindToken, DividendUpcomingScopeClient upcomingScopeClient,
                        Clock clock, HttpClient httpClient) {
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
        this.upcomingScopeClient = upcomingScopeClient;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.httpClient = httpClient;
    }

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /**
     * @param exDividendDate 除息日（現金股利基準日），ISO yyyy-MM-dd 或 null（Task 357／Requirement 94：
     *                       停止與除權日互相 fallback，只配股事件此欄一律 null，不得以除權日冒充）
     * @param exRightsDate   除權日（股票股利基準日），ISO yyyy-MM-dd 或 null；只配息事件此欄一律 null
     */
    public record DividendEvent(
            Integer year,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            String exDividendDate,        // ISO YYYY-MM-DD or null
            String exRightsDate,          // ISO YYYY-MM-DD or null
            String cashPaymentDate,       // ISO or null
            String stockPaymentDate       // ISO or null
    ) {}

    public enum FetchStatus { COMPLETE, EMPTY_COMPLETE, PARTIAL, FAILED }

    /**
     * Typed fetch observation。空事件只有在 provider 明確成功完成整段 scope 時才是
     * {@link FetchStatus#EMPTY_COMPLETE}；例外／timeout 不得再與成功查無事件混成同一結果。
     */
    public record DividendFetchResult(
            String source,
            List<DividendEvent> events,
            FetchStatus status,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            Instant sourceAvailableAt,
            String errorReason,
            List<String> sourceUrls) {
        public DividendFetchResult(String source, List<DividendEvent> events,
                                   FetchStatus status, LocalDate scopeFrom, LocalDate scopeTo,
                                   Instant sourceAvailableAt, String errorReason) {
            this(source, events, status, scopeFrom, scopeTo, sourceAvailableAt, errorReason, List.of());
        }

        public DividendFetchResult {
            events = events == null ? List.of() : List.copyOf(events);
            sourceUrls = sourceUrls == null ? List.of() : sourceUrls.stream()
                    .filter(url -> url != null && !url.isBlank()).distinct().toList();
        }

        public DividendFetchResult(String source, List<DividendEvent> events) {
            this(source, events, FetchStatus.PARTIAL,
                    LocalDate.now().minusYears(1), LocalDate.now(), Instant.now(),
                    "legacy result 未提供可驗證的 upcoming scope", List.of());
        }

        public boolean complete() {
            return status == FetchStatus.COMPLETE || status == FetchStatus.EMPTY_COMPLETE;
        }
    }

    public DividendFetchResult fetch(String stockCode, String market, int years) {
        List<DividendFetchResult> observations = fetchObservations(stockCode, market, years);
        return observations.stream()
                .filter(DividendFetchResult::complete)
                .findFirst()
                .orElseGet(() -> observations.getFirst());
    }

    /**
     * Fetches independent append-only observations for the historical and upcoming scopes.
     *
     * <p>The historical provider can retain occurred events, but is never promoted to
     * COMPLETE because it does not prove the upcoming 45-day scope.  A verified upcoming
     * adapter is stored as a separate observation so its authority cannot accidentally
     * discard historical events from another provider.</p>
     */
    public List<DividendFetchResult> fetchObservations(String stockCode, String market, int years) {
        if (!"台股".equals(market) && !"美股".equals(market)) {
            return List.of(failed(null, years, LocalDate.now(clock), "不支援的市場：" + market));
        }
        LocalDate today = marketToday(market);
        LocalDate horizon = today.plusDays(45);
        DividendFetchResult historical = "台股".equals(market)
                ? fetchTw(stockCode, years, today) : fetchUs(stockCode, years, today);
        DividendUpcomingScopeClient.UpcomingScope upcoming = upcomingScopeClient == null
                ? DividendUpcomingScopeClient.UpcomingScope.unavailable("未設定 upcoming dividend adapter")
                : upcomingScopeClient.fetch(stockCode, market, today, horizon);
        if (upcoming != null && upcoming.covers(today, horizon)) {
            FetchStatus status = upcoming.events().isEmpty()
                    ? FetchStatus.EMPTY_COMPLETE : FetchStatus.COMPLETE;
            DividendFetchResult verifiedUpcoming = new DividendFetchResult(
                    upcoming.provider(), upcoming.events(), status,
                    today, horizon, upcoming.sourceAvailableAt(), null, upcoming.sourceUrls());
            return List.of(historical, verifiedUpcoming);
        }
        String upcomingError = upcoming == null ? "upcoming provider 未回結果" : upcoming.errorReason();
        // Preserve an incomplete upcoming payload even when the provider returned known
        // events.  Dropping it here made the append-only audit indistinguishable from an
        // empty calendar and silently discarded source URLs/scope evidence.  It remains
        // non-authoritative because PARTIAL never satisfies covers()/complete().
        DividendFetchResult partialUpcoming = upcoming == null
                ? failed(null, years, today, upcomingError)
                : new DividendFetchResult(
                        upcoming.provider(), upcoming.events(), FetchStatus.PARTIAL,
                        upcoming.scopeFrom(), upcoming.scopeTo(), upcoming.sourceAvailableAt(),
                        upcomingError == null ? "upcoming scope incomplete" : upcomingError,
                        upcoming.sourceUrls());
        return List.of(historical, partialUpcoming);
    }

    /**
     * Provider fallback for the explicit upcoming scope router.  This is deliberately separate
     * from {@link #fetchObservations(String, String, int)} so routing back into the scope client
     * cannot recurse.  The provider request is bounded by {@code to}; a provider that returns a
     * successful payload but no event is therefore an explicit empty scope, while transport or
     * parsing failure remains unavailable.  Official calendar adapters remain the first choice in
     * {@link MarketDividendUpcomingScopeClient}; FinMind/Yahoo are a deterministic fallback.
     */
    public DividendUpcomingScopeClient.UpcomingScope fetchProviderUpcomingScope(
            String stockCode, String market, LocalDate from, LocalDate to) {
        if (stockCode == null || stockCode.isBlank() || from == null || to == null
                || from.isAfter(to) || to.isAfter(from.plusDays(45))) {
            return DividendUpcomingScopeClient.UpcomingScope.unavailable(
                    "provider upcoming scope 不合法");
        }
        try {
            // Historical quote endpoints are deliberately not reused here.  They may return a
            // successful payload while silently omitting announced future distributions.  Only
            // the Taiwan FinMind datasets have a bounded [from,to] request contract; Nasdaq/Yahoo
            // are retained as evidence-only fallbacks until they can prove the complete scope.
            if ("台股".equals(market)) {
                return fetchFinMindUpcomingScope(stockCode, from, to);
            }
            if ("美股".equals(market)) {
                return fetchUsUpcomingEvidence(stockCode, from, to);
            }
            return DividendUpcomingScopeClient.UpcomingScope.unavailable(
                    "provider upcoming scope 不支援市場：" + market);
        } catch (RuntimeException e) {
            log.warn("provider upcoming dividend scope 失敗 {} {}..{}: {}",
                    stockCode, from, to, e.getMessage());
            return DividendUpcomingScopeClient.UpcomingScope.unavailable(
                    "provider upcoming scope failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * FinMind upcoming contract.  Both datasets must acknowledge a bounded request with a
     * success envelope and every returned row must be parseable.  A malformed row is not skipped:
     * it makes the whole observation incomplete so a missing/cancelled event cannot be inferred.
     */
    private DividendUpcomingScopeClient.UpcomingScope fetchFinMindUpcomingScope(
            String stockCode, LocalDate from, LocalDate to) {
        BoundedFinMindDataset primary = fetchFinMindBounded(
                "TaiwanStockDividend", stockCode, from, to);
        BoundedFinMindDataset result = fetchFinMindBounded(
                "TaiwanStockDividendResult", stockCode, from, to);
        List<DividendEvent> events = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        boolean valid = primary.success() && result.success();
        if (!primary.success()) errors.append(primary.errorReason());
        if (!result.success()) {
            if (!errors.isEmpty()) errors.append("; ");
            errors.append(result.errorReason());
        }
        if (primary.success()) {
            ParseEvents parsed = parseFinMindDividendRows(primary.data(), from, to);
            events.addAll(parsed.events());
            if (!parsed.valid()) {
                valid = false;
                if (!errors.isEmpty()) errors.append("; ");
                errors.append(parsed.errorReason());
            }
        }
        if (result.success()) {
            ParseEvents parsed = parseFinMindResultRows(result.data(), from, to);
            events.addAll(parsed.events());
            if (!parsed.valid()) {
                valid = false;
                if (!errors.isEmpty()) errors.append("; ");
                errors.append(parsed.errorReason());
            }
        }
        List<DividendEvent> merged = mergeTaiwanEvents(List.of(), events);
        String source = "FinMind[TaiwanStockDividend+TaiwanStockDividendResult]";
        if (!valid && errors.isEmpty()) errors.append("FinMind bounded provider partial");
        String error = valid ? null : "FinMind bounded provider partial: " + errors;
        return new DividendUpcomingScopeClient.UpcomingScope(
                source, from, to, clock.instant(), valid, merged, error,
                List.of(finmindEndpoint("TaiwanStockDividend", stockCode, from, to),
                        finmindEndpoint("TaiwanStockDividendResult", stockCode, from, to)));
    }

    /** Nasdaq and Yahoo responses are bounded evidence only; neither endpoint proves an empty
     * future calendar.  Return events for disclosure but never COMPLETE/EMPTY_COMPLETE. */
    private DividendUpcomingScopeClient.UpcomingScope fetchUsUpcomingEvidence(
            String stockCode, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        List<String> sourceUrls = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean anySuccess = false;
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetclass=" + assetClass;
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20)).header("User-Agent", UA)
                        .header("Accept", "application/json, text/html, */*")
                        .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Accept-Encoding", "identity").GET().build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    errors.add("Nasdaq historical HTTP " + response.statusCode());
                    continue;
                }
                JsonNode rows = mapper.readTree(response.body()).path("data").path("dividends").path("rows");
                if (!rows.isArray()) {
                    errors.add("Nasdaq historical rows 非 array");
                    continue;
                }
                anySuccess = true;
                sourceUrls.add(url);
                ParseEvents parsed = parseNasdaqRows(rows, from, to);
                events.addAll(parsed.events());
                if (!parsed.valid()) errors.add(parsed.errorReason());
            } catch (Exception e) {
                errors.add("Nasdaq historical failed: " + e.getClass().getSimpleName());
            }
        }
        ParseEvents yahoo = fetchYahooBoundedEvidence(stockCode, from, to);
        events.addAll(yahoo.events());
        if (yahoo.valid()) anySuccess = true;
        else errors.add(yahoo.errorReason());
        List<DividendEvent> bounded = events.stream().filter(event -> event != null
                && event.exDividendDate() != null).distinct().toList();
        String reason = "Nasdaq/Yahoo historical feeds do not prove complete upcoming scope"
                + (errors.isEmpty() ? "" : ": " + String.join("; ", errors));
        return new DividendUpcomingScopeClient.UpcomingScope(
                anySuccess ? "NASDAQ+Yahoo Finance" : "NASDAQ/Yahoo Finance",
                from, to, clock.instant(), false, bounded, reason,
                concatUrls(sourceUrls, List.of(yahooEndpoint(stockCode, from, to))));
    }

    private ParseEvents fetchYahooBoundedEvidence(String stockCode, LocalDate from, LocalDate to) {
        try {
            long period1 = from.atStartOfDay(ET).toEpochSecond();
            long period2 = to.plusDays(1).atStartOfDay(ET).toEpochSecond();
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + stockCode.trim().toUpperCase() + "?period1=" + period1
                    + "&period2=" + period2 + "&interval=1d&events=div";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ParseEvents.invalid("Yahoo bounded HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode chartError = root.path("chart").path("error");
            if (!chartError.isNull() && !chartError.isMissingNode()) {
                return ParseEvents.invalid("Yahoo bounded response error");
            }
            JsonNode result = root.path("chart").path("result");
            if (!result.isArray() || result.isEmpty() || !result.get(0).isObject()) {
                return ParseEvents.invalid("Yahoo bounded result 非 object");
            }
            JsonNode dividends = result.get(0).path("events").path("dividends");
            if (!dividends.isObject()) {
                // A successful chart response with no dividends is not an explicit empty
                // upcoming calendar; preserve the distinction as incomplete evidence.
                return ParseEvents.invalid("Yahoo bounded dividends 缺失");
            }
            List<DividendEvent> events = new ArrayList<>();
            for (JsonNode item : dividends) {
                long epoch = item.path("date").asLong(0);
                BigDecimal amount = decimalNode(item.get("amount"));
                if (epoch <= 0 || amount == null || amount.signum() <= 0) {
                    return ParseEvents.invalid("Yahoo bounded dividend row malformed");
                }
                LocalDate date = Instant.ofEpochSecond(epoch).atZone(ET).toLocalDate();
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    // US-only：Yahoo chart events=div 無股票股利概念，exRightsDate 恆 null。
                    events.add(new DividendEvent(date.getYear(), amount.setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.ZERO, date.toString(), null, null, null));
                }
            }
            // Even a valid non-empty historical row does not establish that future rows are
            // complete; caller intentionally marks the whole US provider scope incomplete.
            return ParseEvents.valid(events);
        } catch (Exception e) {
            return ParseEvents.invalid("Yahoo bounded failed: " + e.getClass().getSimpleName());
        }
    }

    private ParseEvents parseNasdaqRows(JsonNode rows, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        for (JsonNode row : rows) {
            String date = row.path("exOrEffDate").asText("");
            String[] parts = date.split("/");
            BigDecimal amount = decimalText(row.path("amount").asText("" ).replace("$", "").trim());
            if (parts.length != 3 || amount == null || amount.signum() <= 0) {
                return ParseEvents.invalid("Nasdaq historical dividend row malformed");
            }
            try {
                LocalDate ex = LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]));
                if (!ex.isBefore(from) && !ex.isAfter(to)) {
                    // US-only：NASDAQ dividends API 只回現金股利，exRightsDate 恆 null。
                    events.add(new DividendEvent(ex.getYear(), amount.setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.ZERO, ex.toString(), null, null, null));
                }
            } catch (RuntimeException e) {
                return ParseEvents.invalid("Nasdaq historical dividend date malformed");
            }
        }
        return ParseEvents.valid(events);
    }

    /**
     * 357.2a／357.2b／357.2c：{@code CashExDividendTradingDate}（除息日）與
     * {@code StockExDividendTradingDate}（除權日）不再互相 fallback，各自落到
     * {@link DividendEvent#exDividendDate()}／{@link DividendEvent#exRightsDate()}。
     * malformed 判定、區間過濾、year 推導三處改用 {@code anchorDate}（兩者中較早且非
     * null 者）；只配股（無除息日）的列不得因此被判為 malformed 而整批拒收——兩個
     * 日期至少要有一個可解析才是有效，兩個都缺才是無效。
     */
    private ParseEvents parseFinMindDividendRows(JsonNode rows, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        for (JsonNode item : rows) {
            BigDecimal cash = requiredDecimal(item, "CashEarningsDistribution");
            BigDecimal cashSurplus = requiredDecimal(item, "CashStatutorySurplus");
            BigDecimal stock = requiredDecimal(item, "StockEarningsDistribution");
            BigDecimal stockSurplus = requiredDecimal(item, "StockStatutorySurplus");
            String cashEx = nullIfEmpty(item.path("CashExDividendTradingDate").asText(null));
            String stockEx = nullIfEmpty(item.path("StockExDividendTradingDate").asText(null));
            LocalDate cashExDate = parseDateIso(cashEx);
            LocalDate stockExDate = parseDateIso(stockEx);
            LocalDate anchor = anchorDate(cashExDate, stockExDate);
            if (cash == null || cashSurplus == null || stock == null || stockSurplus == null
                    || anchor == null) {
                return ParseEvents.invalid("FinMind TaiwanStockDividend row malformed");
            }
            if (!anchor.isBefore(from) && !anchor.isAfter(to)
                    && cash.add(cashSurplus).add(stock).add(stockSurplus).signum() > 0) {
                events.add(new DividendEvent(anchor.getYear(),
                        cash.add(cashSurplus).setScale(4, RoundingMode.HALF_UP),
                        stock.add(stockSurplus).setScale(4, RoundingMode.HALF_UP),
                        cashExDate == null ? null : cashExDate.toString(),
                        stockExDate == null ? null : stockExDate.toString(),
                        nullIfEmpty(item.path("CashDividendPaymentDate").asText("")),
                        nullIfEmpty(item.path("StockDividendPaymentDate").asText(""))));
            }
        }
        return ParseEvents.valid(events);
    }

    /**
     * 357.2f：此資料集僅有單一日期欄（無現金／股票兩個原始日期可 fallback），依既有
     * {@code stock_or_cache_dividend} 判準（含「權」不含「息」→ 純配股）分派到
     * {@link DividendEvent#exRightsDate()} 或 {@link DividendEvent#exDividendDate()}，
     * 不得像修正前一律塞進 exDividendDate（那正是除權日被誤標成除息日的原始缺陷，
     * 主要影響個股資料集查無的 ETF）。
     */
    private ParseEvents parseFinMindResultRows(JsonNode rows, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        for (JsonNode item : rows) {
            BigDecimal amount = requiredDecimal(item, "stock_and_cache_dividend");
            String ex = firstNonBlank(item.path("date").asText(null));
            if (amount == null || ex == null || parseDateIso(ex) == null) {
                return ParseEvents.invalid("FinMind TaiwanStockDividendResult row malformed");
            }
            LocalDate date = parseDateIso(ex);
            if (!date.isBefore(from) && !date.isAfter(to) && amount.signum() > 0) {
                String type = item.path("stock_or_cache_dividend").asText("");
                boolean stock = type.contains("權") && !type.contains("息");
                events.add(new DividendEvent(date.getYear(), stock ? BigDecimal.ZERO : amount,
                        stock ? amount : BigDecimal.ZERO,
                        stock ? null : date.toString(),
                        stock ? date.toString() : null,
                        null, null));
            }
        }
        return ParseEvents.valid(events);
    }

    private BoundedFinMindDataset fetchFinMindBounded(
            String dataset, String stockCode, LocalDate from, LocalDate to) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=" + dataset
                    + "&data_id=" + stockCode + "&start_date=" + from + "&end_date=" + to;
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", UA)
                    .header("Accept", "application/json").header("Accept-Encoding", "identity");
            if (!finmindToken.isEmpty()) builder.header("Authorization", "Bearer " + finmindToken);
            HttpResponse<String> response = httpClient.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return BoundedFinMindDataset.failed(dataset + " HTTP " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode status = root.get("status");
            if (status == null || !status.isIntegralNumber() || status.asInt() != 200) {
                return BoundedFinMindDataset.failed(dataset + " success status missing/non-200");
            }
            String message = root.path("msg").asText("").trim();
            if (!message.isEmpty() && !message.equalsIgnoreCase("success")) {
                return BoundedFinMindDataset.failed(dataset + " message=" + message);
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return BoundedFinMindDataset.failed(dataset + " data 非 array");
            }
            return new BoundedFinMindDataset(true, data, null);
        } catch (Exception e) {
            return BoundedFinMindDataset.failed(dataset + " failed: " + e.getClass().getSimpleName());
        }
    }

    private static BigDecimal requiredDecimal(JsonNode row, String field) {
        JsonNode node = row == null ? null : row.get(field);
        return decimalNode(node);
    }

    private static BigDecimal decimalNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String value = node.asText("").trim().replace(",", "");
        if (value.isEmpty() || "N/A".equalsIgnoreCase(value)) return null;
        return decimalText(value);
    }

    private static BigDecimal decimalText(String value) {
        try { return value == null || value.isBlank() ? null : new BigDecimal(value); }
        catch (NumberFormatException e) { return null; }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static String finmindEndpoint(String dataset, String stockCode,
                                          LocalDate from, LocalDate to) {
        return "https://api.finmindtrade.com/api/v4/data?dataset=" + dataset
                + "&data_id=" + stockCode + "&start_date=" + from + "&end_date=" + to;
    }

    private static String yahooEndpoint(String stockCode, LocalDate from, LocalDate to) {
        long period1 = from.atStartOfDay(ET).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ET).toEpochSecond();
        return "https://query2.finance.yahoo.com/v8/finance/chart/"
                + stockCode.trim().toUpperCase() + "?period1=" + period1
                + "&period2=" + period2 + "&interval=1d&events=div";
    }

    private static List<String> usHistoryUrls(String stockCode, int years, LocalDate today) {
        LocalDate from = today.minusYears(Math.max(1, years));
        return List.of(
                "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetclass=stocks",
                "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetclass=etf",
                yahooEndpoint(stockCode, from, today));
    }

    private static List<String> concatUrls(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>();
        if (first != null) all.addAll(first);
        if (second != null) all.addAll(second);
        return all.stream().filter(url -> url != null && !url.isBlank()).distinct().toList();
    }

    private DividendFetchResult fetchTw(String stockCode, int years, LocalDate today) {
        List<DividendEvent> out = new ArrayList<>();
        boolean primaryFailed = false;
        try {
            JsonNode data = finmindData("TaiwanStockDividend", stockCode, years, today);
            // null means the provider did not return a valid successful dataset;
            // an empty array is the only evidence that the scope was checked and
            // no event was found.
            if (data == null) primaryFailed = true;
            if (data != null) for (JsonNode item : data) {
                double cash = item.path("CashEarningsDistribution").asDouble(0)
                            + item.path("CashStatutorySurplus").asDouble(0);
                double stock = item.path("StockEarningsDistribution").asDouble(0)
                             + item.path("StockStatutorySurplus").asDouble(0);
                if (cash == 0 && stock == 0) continue;

                // 357.2a／357.2b／357.2c：除息日／除權日各自落地，不再互相 fallback。
                // year 推導改用 anchorDate（兩者中較早且非 null 者），只配股（無除息日）
                // 的事件不得因此被 continue 靜默丟棄——保留原本 lenient parseYear 與
                // item.date 兩層 fallback，確保既有可解析格式逐位不變。
                String cashExDate = nullIfEmpty(item.path("CashExDividendTradingDate").asText(""));
                String stockExDate = nullIfEmpty(item.path("StockExDividendTradingDate").asText(""));
                LocalDate anchor = anchorDate(parseDateIso(cashExDate), parseDateIso(stockExDate));
                Integer year = anchor != null ? anchor.getYear()
                        : parseYear(firstNonBlank(cashExDate, stockExDate));
                if (year == null) {
                    String date = item.path("date").asText("");
                    year = parseYear(date);
                    if (year == null) continue;
                }
                String cashPay = nullIfEmpty(item.path("CashDividendPaymentDate").asText(""));
                String stockPay = nullIfEmpty(item.path("StockDividendPaymentDate").asText(""));

                out.add(new DividendEvent(
                        year,
                        BigDecimal.valueOf(cash).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(stock).setScale(4, RoundingMode.HALF_UP),
                        cashExDate,
                        stockExDate,
                        cashPay, stockPay
                ));
            }
        } catch (Exception e) {
            primaryFailed = true;
            log.warn("台股股利歷史查詢失敗 {}: {}", stockCode, e.getMessage());
        }
        EventBatch primary = new EventBatch(out, !primaryFailed);

        // 兩張 FinMind 表的覆蓋範圍不同：TaiwanStockDividend 有公司現金/股票拆分與
        // 發放日，TaiwanStockDividendResult 才涵蓋部分 ETF 的除權息結果。即使主表已有
        // 資料，也必須查第二張表並以事件鍵合併，否則會把「主表有資料」誤當成完整覆蓋。
        EventBatch fallback = fetchTwDividendResult(stockCode, years, today);
        List<DividendEvent> merged = mergeTaiwanEvents(primary.events(), fallback.events());
        boolean anySuccessful = primary.successful() || fallback.successful();
        if (!anySuccessful) {
            return failed("FinMind", years, today,
                    "FinMind TaiwanStockDividend 與 TaiwanStockDividendResult 均失敗",
                    List.of(finmindEndpoint("TaiwanStockDividend", stockCode,
                                    today.minusYears(Math.max(1, years)), today),
                            finmindEndpoint("TaiwanStockDividendResult", stockCode,
                                    today.minusYears(Math.max(1, years)), today)));
        }
        String reason = null;
        if (!primary.successful() || !fallback.successful()) {
            reason = "FinMind datasets partial: "
                    + (!primary.successful() ? "TaiwanStockDividend failed" : "")
                    + (!primary.successful() && !fallback.successful() ? "; " : "")
                    + (!fallback.successful() ? "TaiwanStockDividendResult failed" : "");
        }
        return result("FinMind[TaiwanStockDividend+TaiwanStockDividendResult]", merged,
                reason == null ? null : FetchStatus.PARTIAL, years, today, reason,
                List.of(finmindEndpoint("TaiwanStockDividend", stockCode,
                                today.minusYears(Math.max(1, years)), today),
                        finmindEndpoint("TaiwanStockDividendResult", stockCode,
                                today.minusYears(Math.max(1, years)), today)));
    }

    /**
     * 合併兩個台股股利資料集。相同除息日且總金額相同視為同一事件；主表通常有
     * 現金/股票拆分及發放日，結果表只有合併金額，因此保留欄位較完整的一筆，並
     * 以另一筆的非空日期補齊。不同金額則保留為同日的獨立分配，避免把多次配息
     * 靜默相加。
     */
    private static List<DividendEvent> mergeTaiwanEvents(
            List<DividendEvent> primary, List<DividendEvent> fallback) {
        Map<String, DividendEvent> merged = new LinkedHashMap<>();
        for (DividendEvent event : concat(primary, fallback)) {
            if (event == null) continue;
            String key = taiwanEventKey(event);
            DividendEvent existing = merged.get(key);
            merged.put(key, existing == null ? event : richer(existing, event));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(
                        DividendFetchClient::eventDateForSort,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static List<DividendEvent> concat(List<DividendEvent> primary,
                                              List<DividendEvent> fallback) {
        List<DividendEvent> all = new ArrayList<>();
        if (primary != null) all.addAll(primary);
        if (fallback != null) all.addAll(fallback);
        return all;
    }

    /**
     * 357.2g：key 改用 {@code anchorDate}（COALESCE(exDividendDate, exRightsDate)），
     * 不得再對 {@code exDividendDate()==null} 退化為固定字串 {@code "NO_DATE"}——
     * 357.2a／357.2f 之後純配股事件的 exDividendDate 恆為 null，若仍用固定字串組 key，
     * 同一標的、不同年度、金額相同的兩筆純配股事件會產生相同 key 而在合併時互相覆蓋
     * （已用運行中 DB 實測證實：2885 於 2022 與 2025 兩年的純配股事件 stock_dividend
     * 皆為 0.300000）。
     */
    private static String taiwanEventKey(DividendEvent event) {
        LocalDate anchor = anchorDate(event);
        String date = anchor == null ? "NO_DATE" : anchor.toString();
        BigDecimal cash = event.cashDividend() == null ? BigDecimal.ZERO : event.cashDividend();
        BigDecimal stock = event.stockDividend() == null ? BigDecimal.ZERO : event.stockDividend();
        return date + "|" + normalized(cash.add(stock));
    }

    private static DividendEvent richer(DividendEvent first, DividendEvent second) {
        DividendEvent winner = detailScore(second) > detailScore(first) ? second : first;
        DividendEvent other = winner == first ? second : first;
        return new DividendEvent(
                winner.year() == null ? other.year() : winner.year(),
                winner.cashDividend() == null ? other.cashDividend() : winner.cashDividend(),
                winner.stockDividend() == null ? other.stockDividend() : winner.stockDividend(),
                winner.exDividendDate() == null ? other.exDividendDate() : winner.exDividendDate(),
                winner.exRightsDate() == null ? other.exRightsDate() : winner.exRightsDate(),
                winner.cashPaymentDate() == null ? other.cashPaymentDate() : winner.cashPaymentDate(),
                winner.stockPaymentDate() == null ? other.stockPaymentDate() : winner.stockPaymentDate());
    }

    private static int detailScore(DividendEvent event) {
        if (event == null) return 0;
        int score = 0;
        if (event.year() != null) score++;
        if (event.cashDividend() != null && event.cashDividend().signum() != 0) score++;
        if (event.stockDividend() != null && event.stockDividend().signum() != 0) score++;
        if (event.exDividendDate() != null) score++;
        if (event.exRightsDate() != null) score++;
        if (event.cashPaymentDate() != null) score++;
        if (event.stockPaymentDate() != null) score++;
        return score;
    }

    private static String normalized(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private static LocalDate eventDateForSort(DividendEvent event) {
        return event == null ? null : anchorDate(event);
    }

    /**
     * Fallback：FinMind TaiwanStockDividendResult（除權息結果表）。
     * 僅 date（除息日）+ stock_and_cache_dividend（合併配息金額），無發放日、無現金/配股拆分。
     * ETF 收益分配皆為「除息」，stock_or_cache_dividend 含「權」且不含「息」才當配股，其餘當現金配息。
     */
    private EventBatch fetchTwDividendResult(String stockCode, int years, LocalDate today) {
        List<DividendEvent> out = new ArrayList<>();
        try {
            JsonNode data = finmindData("TaiwanStockDividendResult", stockCode, years, today);
            if (data == null) return EventBatch.failed();
            for (JsonNode item : data) {
                double amount = item.path("stock_and_cache_dividend").asDouble(0);
                if (amount == 0) continue;
                String exDate = item.path("date").asText("");
                Integer year = parseYear(exDate);
                if (year == null) continue;
                String type = item.path("stock_or_cache_dividend").asText("");
                boolean isStock = type.contains("權") && !type.contains("息");
                BigDecimal amt = BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP);
                // 357.2f：純配股（isStock）落 exRightsDate，現金落 exDividendDate，
                // 不得像修正前一律塞進 exDividendDate。
                out.add(new DividendEvent(
                        year,
                        isStock ? BigDecimal.ZERO : amt,
                        isStock ? amt : BigDecimal.ZERO,
                        isStock ? null : nullIfEmpty(exDate),
                        isStock ? nullIfEmpty(exDate) : null,
                        null, null
                ));
            }
            return EventBatch.success(out);
        } catch (Exception e) {
            log.warn("台股除權息結果表查詢失敗 {}: {}", stockCode, e.getMessage());
            return EventBatch.failed();
        }
    }

    /** FinMind data API 共用呼叫，回傳 data 陣列節點；非 200 或非陣列回 null。 */
    private JsonNode finmindData(
            String dataset, String stockCode, int years, LocalDate today) throws Exception {
        String startDate = today.minusYears(years).toString();
        String url = "https://api.finmindtrade.com/api/v4/data"
                + "?dataset=" + dataset
                + "&data_id=" + stockCode
                + "&start_date=" + startDate
                + "&end_date=" + today;
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity");
        if (!finmindToken.isEmpty()) b.header("Authorization", "Bearer " + finmindToken);
        HttpResponse<String> resp = httpClient.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("FinMind {} {} 回應 {}", dataset, stockCode, resp.statusCode());
            return null;
        }
        JsonNode data = mapper.readTree(resp.body()).path("data");
        return data.isArray() ? data : null;
    }

    private DividendFetchResult fetchUs(String stockCode, int years, LocalDate today) {
        int fromYear = today.getYear() - years + 1;
        boolean failed = false;
        List<DividendEvent> nasdaqEvents = new ArrayList<>();
        for (String assetClass : new String[]{"stocks", "etf"}) {
            try {
                String url = "https://api.nasdaq.com/api/quote/" + stockCode
                        + "/dividends?assetclass=" + assetClass;
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", UA)
                        .header("Accept", "application/json, text/html, */*")
                        .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Accept-Encoding", "identity")
                        .header("Referer", "https://finance.yahoo.com/")
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    failed = true;
                    continue;
                }
                JsonNode rowsNode = mapper.readTree(resp.body()).path("data").path("dividends").path("rows");
                if (!rowsNode.isArray()) {
                    failed = true;
                    continue;
                }
                if (rowsNode.isEmpty()) continue;
                List<DividendEvent> rows = new ArrayList<>();
                for (JsonNode r : rowsNode) {
                    String exDate = r.path("exOrEffDate").asText("");
                    if (exDate.length() < 10) continue;
                    int year;
                    String exIso;
                    try {
                        String[] p = exDate.split("/");
                        year = Integer.parseInt(p[2]);
                        exIso = String.format("%04d-%02d-%02d", year,
                                Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                    } catch (Exception e) { continue; }
                    if (year < fromYear) continue;
                    String amtStr = r.path("amount").asText("").replace("$", "").trim();
                    if (amtStr.isEmpty() || amtStr.equals("N/A")) continue;
                    double amt;
                    try { amt = Double.parseDouble(amtStr); } catch (NumberFormatException e) { continue; }
                    String payDate = r.path("paymentDate").asText("");
                    String payIso = null;
                    if (payDate.length() >= 10) {
                        try {
                            String[] pp = payDate.split("/");
                            payIso = String.format("%04d-%02d-%02d",
                                    Integer.parseInt(pp[2]), Integer.parseInt(pp[0]), Integer.parseInt(pp[1]));
                        } catch (Exception ignore) {}
                    }
                    // US-only：NASDAQ dividends 只回現金股利，exRightsDate 恆 null。
                    rows.add(new DividendEvent(
                            year,
                            BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.ZERO,
                            exIso,
                            null,
                            payIso, null
                    ));
                }
                nasdaqEvents.addAll(rows);
            } catch (Exception e) {
                failed = true;
                log.warn("美股股利歷史查詢失敗 {} ({}): {}", stockCode, assetClass, e.getMessage());
            }
        }
        nasdaqEvents = mergeUsEvents(nasdaqEvents);
        if (!nasdaqEvents.isEmpty() && !failed) {
            return result("NASDAQ", nasdaqEvents, null, years, today, null,
                    usHistoryUrls(stockCode, years, today));
        }
        // NASDAQ /dividends 對非 NASDAQ 上市（NYSE / NYSEARCA，如 VOO、SGOV、SCHD）回 N/A。
        // 改打 Yahoo chart events=div 補抓（與 MarketDataFetchService.getYahooDividendRate 同一資料源）。
        EventBatch yahoo = fetchUsYahoo(stockCode, years, today);
        if (!nasdaqEvents.isEmpty() || !yahoo.events().isEmpty()) {
            List<DividendEvent> merged = mergeUsEvents(concat(nasdaqEvents, yahoo.events()));
            String source = nasdaqEvents.isEmpty() ? "Yahoo Finance"
                    : (yahoo.events().isEmpty() ? "NASDAQ" : "NASDAQ+Yahoo Finance");
            String reason = failed || !yahoo.successful()
                    ? "NASDAQ/Yahoo historical providers partial" : null;
            return result(source, merged, reason == null ? null : FetchStatus.PARTIAL,
                    years, today, reason, usHistoryUrls(stockCode, years, today));
        }
        if (failed && !yahoo.successful()) {
            return failed("NASDAQ/Yahoo Finance", years, today,
                    "all dividend providers failed", usHistoryUrls(stockCode, years, today));
        }
        return failed || !yahoo.successful()
                ? result("NASDAQ/Yahoo Finance", List.of(), FetchStatus.PARTIAL, years,
                        today, "one or more dividend providers failed",
                        usHistoryUrls(stockCode, years, today))
                : result("NASDAQ/Yahoo Finance", List.of(), FetchStatus.EMPTY_COMPLETE,
                        years, today, null, usHistoryUrls(stockCode, years, today));
    }

    private static List<DividendEvent> mergeUsEvents(List<DividendEvent> events) {
        Map<String, DividendEvent> merged = new LinkedHashMap<>();
        if (events != null) {
            for (DividendEvent event : events) {
                if (event == null) continue;
                String key = (event.exDividendDate() == null ? "NO_DATE" : event.exDividendDate())
                        + "|" + normalized(event.cashDividend());
                DividendEvent existing = merged.get(key);
                merged.put(key, existing == null ? event : richer(existing, event));
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(
                        DividendFetchClient::eventDateForSort,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private record EventBatch(List<DividendEvent> events, boolean successful) {
        static EventBatch success(List<DividendEvent> events) {
            return new EventBatch(events == null ? List.of() : List.copyOf(events), true);
        }

        static EventBatch failed() { return new EventBatch(List.of(), false); }
    }

    private record BoundedFinMindDataset(boolean success, JsonNode data, String errorReason) {
        static BoundedFinMindDataset failed(String reason) {
            return new BoundedFinMindDataset(false, null, reason);
        }
    }

    private record ParseEvents(boolean valid, List<DividendEvent> events, String errorReason) {
        static ParseEvents valid(List<DividendEvent> events) {
            return new ParseEvents(true, events == null ? List.of() : List.copyOf(events), null);
        }

        static ParseEvents invalid(String reason) {
            return new ParseEvents(false, List.of(), reason);
        }
    }

    private DividendFetchResult result(
            String source, List<DividendEvent> events, FetchStatus explicit,
            int years, LocalDate to, String errorReason) {
        return result(source, events, explicit, years, to, errorReason, List.of());
    }

    private DividendFetchResult result(
            String source, List<DividendEvent> events, FetchStatus explicit,
            int years, LocalDate to, String errorReason, List<String> sourceUrls) {
        LocalDate from = to.minusYears(Math.max(1, years));
        FetchStatus status = explicit == FetchStatus.FAILED ? FetchStatus.FAILED : FetchStatus.PARTIAL;
        String reason = errorReason == null
                ? "historical provider 未證明 upcoming 45-day scope" : errorReason;
        List<DividendEvent> bounded = events == null ? List.of() : events.stream()
                .filter(event -> event == null || event.exDividendDate() == null
                        || parseDateIso(event.exDividendDate()) == null
                        || !parseDateIso(event.exDividendDate()).isAfter(to))
                .toList();
        return new DividendFetchResult(source, bounded, status, from, to, clock.instant(), reason,
                sourceUrls);
    }

    private DividendFetchResult failed(
            String source, int years, LocalDate today, String reason) {
        return result(source, List.of(), FetchStatus.FAILED, years, today, reason);
    }

    private DividendFetchResult failed(
            String source, int years, LocalDate today, String reason, List<String> sourceUrls) {
        return result(source, List.of(), FetchStatus.FAILED, years, today, reason, sourceUrls);
    }

    /**
     * Fallback：Yahoo chart events=div（curl 子程序，避開 Java HttpClient 被 WAF 擋）。
     * events.dividends 每筆含 amount（每股現金配息）與 date（除息日 epoch 秒）。Yahoo 僅有現金配息、無發放日。
     */
    private EventBatch fetchUsYahoo(String stockCode, int years, LocalDate today) {
        List<DividendEvent> out = new ArrayList<>();
        int fromYear = today.getYear() - years + 1;
        try {
            String url = "https://query2.finance.yahoo.com/v8/finance/chart/"
                    + stockCode.trim().toUpperCase() + "?interval=1d&range=" + years + "y&events=div";
            // Yahoo WAF 對長 Chrome UA + curl TLS 指紋判為 bot 回 429；短 "Mozilla/5.0" 才放行
            // （與 MarketDataFetchService.getYahooDividendRateForTicker 一致）。
            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();
            if (exit != 0) return EventBatch.failed();
            JsonNode divs = mapper.readTree(body).path("chart").path("result").path(0)
                    .path("events").path("dividends");
            if (!divs.isObject()) return EventBatch.failed();
            for (JsonNode d : divs) {
                double amt = d.path("amount").asDouble(0);
                long ts = d.path("date").asLong(0);
                if (amt <= 0 || ts <= 0) continue;
                LocalDate exDate = Instant.ofEpochSecond(ts).atZone(ET).toLocalDate();
                if (exDate.getYear() < fromYear) continue;
                // US-only：Yahoo chart events=div 只有現金配息，exRightsDate 恆 null。
                out.add(new DividendEvent(
                        exDate.getYear(),
                        BigDecimal.valueOf(amt).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO,
                        exDate.toString(),
                        null,
                        null, null
                ));
            }
            return EventBatch.success(out);
        } catch (Exception e) {
            log.warn("Yahoo 美股股利歷史查詢失敗 {}: {}", stockCode, e.getMessage());
            return EventBatch.failed();
        }
    }

    private static Integer parseYear(String s) {
        if (s == null || s.length() < 4) return null;
        try { return Integer.parseInt(s.substring(0, 4)); } catch (Exception e) { return null; }
    }

    private LocalDate marketToday(String market) {
        return LocalDate.now(clock.withZone("美股".equals(market) ? ET : TAIPEI));
    }

    private static String nullIfEmpty(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static LocalDate parseDateIso(String value) {
        try { return value == null ? null : LocalDate.parse(value); }
        catch (RuntimeException e) { return null; }
    }

    /**
     * 357.2c：事件的錨定日期＝除息日與除權日中較早且非 null 者。只有一個日期時恆等於
     * 該日期本身（與修正前 {@code firstNonBlank} 取到的 {@code ex} 逐位相同），兩個日期
     * 皆存在時取較早者，避免事件因為只比較其中一個日期而落在 scope 區間外被漏抓。
     * 僅供 malformed 判定／區間過濾／year 推導／merge key 使用，**不得**用來覆蓋
     * {@link DividendEvent#exDividendDate()} 本身落地的原始日期。
     */
    private static LocalDate anchorDate(LocalDate exDividendDate, LocalDate exRightsDate) {
        if (exDividendDate == null) return exRightsDate;
        if (exRightsDate == null) return exDividendDate;
        return exDividendDate.isBefore(exRightsDate) ? exDividendDate : exRightsDate;
    }

    private static LocalDate anchorDate(DividendEvent event) {
        if (event == null) return null;
        return anchorDate(parseDateIso(event.exDividendDate()), parseDateIso(event.exRightsDate()));
    }

    private static String joinErrors(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "; " + second;
    }
}
