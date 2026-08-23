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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Current official TWSE/TPEx ex-right/ex-dividend announcement snapshot.
 *
 * <p>Neither endpoint accepts an historical decision instant.  Consequently this
 * adapter only attests the exact current Taiwan date and the fixed +45-day scope.
 * Both official datasets must carry current HTTP Date and Last-Modified evidence;
 * a stale/missing header, transport error or malformed row fails the entire scope
 * closed.  FinMind historical data is intentionally not used here.</p>
 */
@Component
@Slf4j
public class TaiwanOfficialDividendCalendarClient implements DividendUpcomingScopeClient {

    static final String TWSE_URL =
            "https://openapi.twse.com.tw/v1/exchangeReport/TWT48U_ALL";
    static final String TPEX_URL =
            "https://www.tpex.org.tw/openapi/v1/tpex_exright_prepost";
    static final String PROVIDER = "TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST";

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Duration SERVER_CLOCK_TOLERANCE = Duration.ofMinutes(5);
    private static final BigDecimal STOCK_PAR_VALUE = BigDecimal.TEN;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;
    /**
     * Market-wide official payload cache.  Success is reused for a short period
     * within the current official day, while a failed refresh receives only a
     * short negative TTL.  A permanent failure entry would let one transient
     * TWSE/TPEx outage suppress all symbols until midnight (or a restart).
     */
    private static final Duration SUCCESS_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration FAILURE_CACHE_TTL = Duration.ofSeconds(30);
    private final Map<ScopeKey, CachedScope> scopes = new ConcurrentHashMap<>();

    public TaiwanOfficialDividendCalendarClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(),
                new ObjectMapper(), Clock.systemUTC());
    }

    TaiwanOfficialDividendCalendarClient(HttpClient http, ObjectMapper mapper, Clock clock) {
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public UpcomingScope fetch(String stockCode, String market, LocalDate from, LocalDate to) {
        if (!"台股".equals(market)) {
            return UpcomingScope.unavailable("TWSE/TPEx upcoming calendar 僅適用台股");
        }
        LocalDate officialAsOf = LocalDate.now(clock.withZone(TAIPEI));
        if (stockCode == null || stockCode.isBlank() || from == null || to == null
                || !from.equals(officialAsOf) || !to.equals(from.plusDays(45))) {
            return UpcomingScope.unavailable(
                    "TWSE/TPEx current snapshot 無法證明非當日或非 +45 日 scope");
        }
        ScopeKey key = new ScopeKey(from, to, officialAsOf);
        CachedScope cached = scopes.compute(key, (ignored, previous) -> {
            Instant now = clock.instant();
            if (previous != null && now.isBefore(previous.expiresAt())) return previous;
            ScopeResult loaded = loadScope(officialAsOf);
            Duration ttl = loaded.complete() ? SUCCESS_CACHE_TTL : FAILURE_CACHE_TTL;
            return new CachedScope(loaded, now.plus(ttl));
        });
        ScopeResult result = cached.result();
        if (!result.complete()) return UpcomingScope.unavailable(result.errorReason());
        List<DividendFetchClient.DividendEvent> events = selectTarget(
                stockCode, from, to, result.events());
        return new UpcomingScope(PROVIDER, from, to, result.sourceAvailableAt(),
                true, events, null, List.of(TWSE_URL, TPEX_URL));
    }

    private ScopeResult loadScope(LocalDate officialAsOf) {
        try {
            OfficialPayload twse = fetchOfficial(TWSE_URL, officialAsOf, this::parseTwse);
            OfficialPayload tpex = fetchOfficial(TPEX_URL, officialAsOf, this::parseTpex);
            return ScopeResult.success(
                    List.of(twse.events(), tpex.events()).stream().flatMap(List::stream).toList(),
                    latest(twse.sourceAvailableAt(), tpex.sourceAvailableAt()));
        } catch (Exception e) {
            log.warn("台股官方 upcoming dividend scope 無法驗證 {}: {}", officialAsOf, concise(e));
            return ScopeResult.failure("TWSE/TPEx official upcoming scope unavailable: " + concise(e));
        }
    }

    private OfficialPayload fetchOfficial(
            String url, LocalDate requiredAsOf, BodyParser parser) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " url=" + url);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new IllegalStateException("Content-Type 非 JSON url=" + url);
        }
        Instant responseDate = headerInstant(response, "Date");
        Instant lastModified = headerInstant(response, "Last-Modified");
        validateAsOf(requiredAsOf, responseDate, lastModified, url);
        return new OfficialPayload(parser.parse(response.body()), lastModified);
    }

    private void validateAsOf(
            LocalDate requiredAsOf, Instant responseDate, Instant lastModified, String url) {
        Instant now = clock.instant();
        if (!responseDate.atZone(TAIPEI).toLocalDate().equals(requiredAsOf)
                || Duration.between(responseDate, now).abs().compareTo(SERVER_CLOCK_TOLERANCE) > 0) {
            throw new IllegalStateException("HTTP Date 無法證明 exact as-of url=" + url);
        }
        if (!lastModified.atZone(TAIPEI).toLocalDate().equals(requiredAsOf)
                || lastModified.isAfter(responseDate.plus(SERVER_CLOCK_TOLERANCE))) {
            throw new IllegalStateException("Last-Modified 非 requested as-of url=" + url);
        }
    }

    private List<OfficialEvent> parseTwse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (!root.isArray()) throw new IllegalStateException("TWSE root 不是 array");
        List<OfficialEvent> events = new ArrayList<>();
        for (JsonNode row : root) {
            LocalDate date = requiredRocDate(row, "Date", "TWSE");
            String code = requiredText(row, "Code", "TWSE");
            String kind = requiredText(row, "Exdividend", "TWSE");
            BigDecimal stock = stockDividend(row, "StockDividendRatio", "TWSE");
            BigDecimal cash = optionalDecimal(row, "CashDividend", "TWSE");
            if (kind.contains("息") || positive(stock)) {
                events.add(new OfficialEvent(code, toEvent(date, cash, stock, kind)));
            }
        }
        return List.copyOf(events);
    }

    private List<OfficialEvent> parseTpex(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (!root.isArray()) throw new IllegalStateException("TPEx root 不是 array");
        List<OfficialEvent> events = new ArrayList<>();
        for (JsonNode row : root) {
            LocalDate date = requiredRocDate(row, "ExRrightsExDividendDate", "TPEx");
            String code = requiredText(row, "SecuritiesCompanyCode", "TPEx");
            String kind = requiredText(row, "ExRrightsExDividend", "TPEx");
            BigDecimal stock = stockDividend(row, "StockDividendRatio", "TPEx");
            BigDecimal cash = optionalDecimal(row, "CashDividend", "TPEx");
            if (kind.contains("息") || positive(stock)) {
                events.add(new OfficialEvent(code, toEvent(date, cash, stock, kind)));
            }
        }
        return List.copyOf(events);
    }

    /**
     * 357.2e：本來就只有單一日期欄（無現金／股票兩個原始日期可 fallback），依 {@code kind}
     * 判讀結果分派：合併列（現金與股票皆為正）兩欄同填同一天（台股官方日曆的合併配股配息
     * 事件除權與除息基準日本來就是同一天）；{@code kind} 含「息」→ 純現金／合併已在上面處理，
     * 落 exDividendDate；{@code kind} 不含「息」（呼叫端 filter 已保證此時 stock 必為正，即
     * 純配股）→ 落 exRightsDate、exDividendDate 為 null。修正前不論 kind 為何一律塞進
     * exDividendDate，是除權日被誤標成除息日的原始缺陷之一。
     */
    private static DividendFetchClient.DividendEvent toEvent(
            LocalDate date, BigDecimal cash, BigDecimal stock, String kind) {
        String iso = date.toString();
        if (positive(cash) && positive(stock)) {
            return new DividendFetchClient.DividendEvent(
                    date.getYear(), cash, stock, iso, iso, null, null);
        }
        if (kind != null && kind.contains("息")) {
            return new DividendFetchClient.DividendEvent(
                    date.getYear(), cash, stock, iso, null, null, null);
        }
        return new DividendFetchClient.DividendEvent(
                date.getYear(), cash, stock, null, iso, null, null);
    }

    @SafeVarargs
    private static List<DividendFetchClient.DividendEvent> selectTarget(
            String stockCode, LocalDate from, LocalDate to, List<OfficialEvent>... sources) {
        String target = stockCode.trim().toUpperCase(Locale.ROOT);
        Map<LocalDate, DividendFetchClient.DividendEvent> byDate = new LinkedHashMap<>();
        for (List<OfficialEvent> source : sources) {
            for (OfficialEvent event : source) {
                // 357.2e：純配股列的 exDividendDate 拆分後為 null，dedupe key 必須改用
                // anchorDate（COALESCE(exDividendDate, exRightsDate)）解析，否則
                // LocalDate.parse(null) 會直接拋 NPE 並中斷整個 fetch()。
                LocalDate date = anchorDate(event.event());
                if (date == null || !target.equals(event.code().toUpperCase(Locale.ROOT))
                        || date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                DividendFetchClient.DividendEvent existing = byDate.putIfAbsent(date, event.event());
                if (existing != null && !existing.equals(event.event())) {
                    throw new IllegalStateException("TWSE/TPEx 同日事件互相矛盾 code=" + target
                            + " date=" + date);
                }
            }
        }
        return List.copyOf(byDate.values());
    }

    private static LocalDate anchorDate(DividendFetchClient.DividendEvent event) {
        LocalDate ex = parseDateOrNull(event.exDividendDate());
        LocalDate rights = parseDateOrNull(event.exRightsDate());
        if (ex == null) return rights;
        if (rights == null) return ex;
        return ex.isBefore(rights) ? ex : rights;
    }

    private static LocalDate parseDateOrNull(String value) {
        try { return value == null ? null : LocalDate.parse(value); }
        catch (RuntimeException e) { return null; }
    }

    private static String requiredText(JsonNode row, String field, String source) {
        JsonNode value = row.get(field);
        String text = value == null || value.isNull() ? "" : value.asText().trim();
        if (text.isEmpty()) throw new IllegalStateException(source + " 缺必要欄位 " + field);
        return text;
    }

    private static LocalDate requiredRocDate(JsonNode row, String field, String source) {
        String digits = requiredText(row, field, source).replaceAll("[^0-9]", "");
        try {
            if (digits.length() == 7) {
                return LocalDate.of(Integer.parseInt(digits.substring(0, 3)) + 1911,
                        Integer.parseInt(digits.substring(3, 5)),
                        Integer.parseInt(digits.substring(5, 7)));
            }
            if (digits.length() == 8) {
                return LocalDate.of(Integer.parseInt(digits.substring(0, 4)),
                        Integer.parseInt(digits.substring(4, 6)),
                        Integer.parseInt(digits.substring(6, 8)));
            }
        } catch (RuntimeException ignored) {
            // handled by the fail-closed exception below
        }
        throw new IllegalStateException(source + " 日期不可解析 " + field);
    }

    private static BigDecimal stockDividend(JsonNode row, String field, String source) {
        BigDecimal ratio = optionalDecimal(row, field, source);
        return ratio == null ? null : ratio.multiply(STOCK_PAR_VALUE);
    }

    private static BigDecimal optionalDecimal(JsonNode row, String field, String source) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim().replace(",", "");
        if (text.isEmpty() || text.contains("尚未公告") || text.contains("待公告")
                || "N/A".equalsIgnoreCase(text) || "--".equals(text)) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(source + " 數值不可解析 " + field);
        }
    }

    private static Instant headerInstant(HttpResponse<?> response, String header) {
        String value = response.headers().firstValue(header)
                .orElseThrow(() -> new IllegalStateException("缺 HTTP " + header));
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            throw new IllegalStateException("HTTP " + header + " 不可解析");
        }
    }

    private static Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String concise(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record OfficialEvent(String code, DividendFetchClient.DividendEvent event) {}
    private record OfficialPayload(List<OfficialEvent> events, Instant sourceAvailableAt) {}

    private record ScopeKey(LocalDate from, LocalDate to, LocalDate observedDate) {}

    private record ScopeResult(List<OfficialEvent> events, Instant sourceAvailableAt, String errorReason) {
        static ScopeResult success(List<OfficialEvent> events, Instant sourceAvailableAt) {
            return new ScopeResult(events == null ? List.of() : List.copyOf(events), sourceAvailableAt, null);
        }

        static ScopeResult failure(String reason) { return new ScopeResult(List.of(), null, reason); }

        boolean complete() { return errorReason == null && sourceAvailableAt != null; }
    }

    private record CachedScope(ScopeResult result, Instant expiresAt) {}

    @FunctionalInterface
    private interface BodyParser {
        List<OfficialEvent> parse(String body) throws Exception;
    }
}
