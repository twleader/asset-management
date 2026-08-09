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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit-range upcoming dividend adapter backed by Nasdaq's date-scoped
 * dividend calendar.  A scope is complete only when every calendar day returns
 * rCode=200 and an asOf matching the requested date; {@code rows=null} is then a
 * provider-proven empty day, not a transport failure.
 */
@Component
@Slf4j
public class NasdaqDividendCalendarClient implements DividendUpcomingScopeClient {

    private static final String URL = "https://api.nasdaq.com/api/calendar/dividends?date=";
    private static final String UA = "Mozilla/5.0";
    private static final DateTimeFormatter AS_OF = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US);
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;
    /**
     * Per-request/day scope cache.  A successful response is reused briefly so a
     * watchlist does not fan out one identical request per symbol; failures are
     * negative-cached only for a short retry window.  Keeping a failure forever
     * would make a transient Nasdaq outage poison the entire trading day and
     * prevent a later decision from recovering without a process restart.
     */
    private static final Duration SUCCESS_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration FAILURE_CACHE_TTL = Duration.ofSeconds(30);
    private final Map<ScopeKey, CachedScope> dailyScopes = new ConcurrentHashMap<>();

    public NasdaqDividendCalendarClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), new ObjectMapper(), Clock.systemUTC());
    }

    NasdaqDividendCalendarClient(HttpClient http, ObjectMapper mapper, Clock clock) {
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public UpcomingScope fetch(String stockCode, String market, LocalDate from, LocalDate to) {
        if (!"美股".equals(market)) {
            return UpcomingScope.unavailable("Nasdaq upcoming calendar 僅適用美股");
        }
        if (stockCode == null || stockCode.isBlank() || from == null || to == null
                || from.isAfter(to) || to.isAfter(from.plusDays(45))) {
            return UpcomingScope.unavailable("upcoming dividend scope 不合法");
        }
        ScopeKey key = new ScopeKey(from, to, LocalDate.now(clock));
        CachedScope cached = dailyScopes.compute(key, (ignored, previous) -> {
            Instant now = clock.instant();
            if (previous != null && now.isBefore(previous.expiresAt())) return previous;
            try {
                return CachedScope.success(load(from, to), now.plus(SUCCESS_CACHE_TTL));
            } catch (ScopeFetchException e) {
                return CachedScope.failure(e.getMessage(), now.plus(FAILURE_CACHE_TTL));
            }
        });
        ScopeResult result = cached.result();
        if (!result.complete()) return UpcomingScope.unavailable(result.errorReason());
        ScopeCalendar calendar = result.calendar();
        List<DividendFetchClient.DividendEvent> events = calendar.bySymbol()
                .getOrDefault(stockCode.trim().toUpperCase(Locale.ROOT), List.of());
        return new UpcomingScope("NASDAQ_DIVIDEND_CALENDAR", from, to,
                calendar.fetchedAt(), true, events, null,
                java.util.stream.IntStream.rangeClosed(0, (int) ChronoUnit.DAYS.between(from, to))
                        .mapToObj(i -> URL + from.plusDays(i)).toList());
    }

    private ScopeCalendar load(LocalDate from, LocalDate to) {
        Map<String, List<DividendFetchClient.DividendEvent>> bySymbol = new HashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DayCalendar day = fetchDay(date);
            if (!day.complete()) throw new ScopeFetchException(day.errorReason());
            for (SymbolEvent event : day.events()) {
                bySymbol.computeIfAbsent(event.symbol(), ignored -> new ArrayList<>()).add(event.event());
            }
        }
        Map<String, List<DividendFetchClient.DividendEvent>> immutable = new HashMap<>();
        bySymbol.forEach((symbol, events) -> immutable.put(symbol, List.copyOf(events)));
        return new ScopeCalendar(Map.copyOf(immutable), clock.instant());
    }

    private DayCalendar fetchDay(LocalDate requested) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL + requested))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", UA)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return DayCalendar.failed("Nasdaq dividend calendar HTTP " + response.statusCode()
                        + " date=" + requested);
            }
            return parseDay(requested, response.body());
        } catch (Exception e) {
            log.warn("Nasdaq upcoming dividend calendar {} 失敗：{}", requested, e.getMessage());
            return DayCalendar.failed("Nasdaq dividend calendar failed date=" + requested);
        }
    }

    DayCalendar parseDay(LocalDate requested, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.path("status").path("rCode").asInt(-1) != 200) {
                return DayCalendar.failed("Nasdaq dividend calendar rCode 非 200 date=" + requested);
            }
            JsonNode calendar = root.path("data").path("calendar");
            String asOf = calendar.path("asOf").asText("");
            if (asOf.isBlank() || !LocalDate.parse(asOf, AS_OF).equals(requested)) {
                return DayCalendar.failed("Nasdaq dividend calendar asOf 不符 date=" + requested);
            }
            JsonNode rows = calendar.path("rows");
            if (rows.isMissingNode() || rows.isNull()) return DayCalendar.complete(List.of());
            if (!rows.isArray()) return DayCalendar.failed("Nasdaq dividend calendar rows 非 array");
            List<SymbolEvent> events = new ArrayList<>();
            for (JsonNode row : rows) {
                String symbol = row.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
                LocalDate exDate = parseDate(row.path("dividend_Ex_Date").asText(null));
                BigDecimal amount = decimal(row.get("dividend_Rate"));
                if (symbol.isBlank() || exDate == null || !exDate.equals(requested)
                        || amount == null || amount.signum() <= 0) {
                    return DayCalendar.failed("Nasdaq dividend calendar row 無法完整解析 date=" + requested);
                }
                LocalDate payment = parseDate(row.path("payment_Date").asText(null));
                events.add(new SymbolEvent(symbol, new DividendFetchClient.DividendEvent(
                        exDate.getYear(), amount.setScale(4, RoundingMode.HALF_UP), BigDecimal.ZERO,
                        exDate.toString(), payment == null ? null : payment.toString(), null)));
            }
            return DayCalendar.complete(events);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return DayCalendar.failed("Nasdaq dividend calendar JSON/date parse failed date=" + requested);
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try { return new BigDecimal(node.asText()); }
        catch (RuntimeException e) { return null; }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value)) return null;
        try { return LocalDate.parse(value.trim(), US_DATE); }
        catch (RuntimeException e) { return null; }
    }

    record SymbolEvent(String symbol, DividendFetchClient.DividendEvent event) {}

    record DayCalendar(boolean complete, List<SymbolEvent> events, String errorReason) {
        static DayCalendar complete(List<SymbolEvent> events) {
            return new DayCalendar(true, events == null ? List.of() : List.copyOf(events), null);
        }
        static DayCalendar failed(String reason) { return new DayCalendar(false, List.of(), reason); }
    }

    private record ScopeKey(LocalDate from, LocalDate to, LocalDate observedDate) {}
    private record ScopeCalendar(Map<String, List<DividendFetchClient.DividendEvent>> bySymbol,
                                 Instant fetchedAt) {}

    private record ScopeResult(ScopeCalendar calendar, String errorReason) {
        static ScopeResult success(ScopeCalendar calendar) { return new ScopeResult(calendar, null); }
        static ScopeResult failure(String reason) { return new ScopeResult(null, reason); }
        boolean complete() { return calendar != null; }
    }

    private record CachedScope(ScopeResult result, Instant expiresAt) {
        static CachedScope success(ScopeCalendar calendar, Instant expiresAt) {
            return new CachedScope(ScopeResult.success(calendar), expiresAt);
        }

        static CachedScope failure(String reason, Instant expiresAt) {
            return new CachedScope(ScopeResult.failure(reason), expiresAt);
        }
    }

    private static final class ScopeFetchException extends RuntimeException {
        private ScopeFetchException(String message) { super(message); }
    }
}
