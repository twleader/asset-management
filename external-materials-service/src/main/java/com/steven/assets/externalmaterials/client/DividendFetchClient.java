package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.steven.assets.externalmaterials.model.DividendDates;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendNormalization;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exact single-dataset dividend observations. Collection completeness never follows from HTTP alone. */
@Slf4j
@Component
public class DividendFetchClient {
    private static final String UA = "Mozilla/5.0";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String TW_PRIMARY = "FinMind[TaiwanStockDividend]";
    private static final String TW_RESULT = "FinMind[TaiwanStockDividendResult]";
    private static final String YAHOO = "Yahoo Finance (nominal from verified splits)";
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final HttpClient httpClient;
    private final DividendFinMindTransport finmind;
    private final DividendUpcomingScopeClient upcomingScopeClient;
    private final Clock clock;
    private final boolean yahooCurl;

    @Autowired
    public DividendFetchClient(@Value("${finmind.token:${FINMIND_TOKEN:}}") String token,
                               DividendUpcomingScopeClient upcoming, DividendFinMindTransport finmind) {
        this(token, upcoming, Clock.systemUTC(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).version(HttpClient.Version.HTTP_1_1).build(), true, finmind);
    }
    public DividendFetchClient(String token, DividendUpcomingScopeClient upcoming) {
        this(token, upcoming, new DividendFinMindTransport(token));
    }
    DividendFetchClient(String token, DividendUpcomingScopeClient upcoming, Clock clock, HttpClient http) {
        this(token, upcoming, clock, http, false, new DividendFinMindTransport(token, clock, http));
    }
    DividendFetchClient(String token, DividendUpcomingScopeClient upcoming, Clock clock, HttpClient http,
                        DividendFinMindTransport finmind) {
        this(token, upcoming, clock, http, false, finmind);
    }
    private DividendFetchClient(String token, DividendUpcomingScopeClient upcoming, Clock clock, HttpClient http,
                                boolean yahooCurl, DividendFinMindTransport finmind) {
        this.finmind = finmind;
        this.upcomingScopeClient = upcoming;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.httpClient = http;
        this.yahooCurl = yahooCurl;
    }
    public boolean quotaRetryReady() { return finmind.quotaRetryReady(); }
    public Instant nextQuotaRetryAt() { return finmind.nextQuotaRetryAt(); }

    public DividendFetchResult fetch(String code, String market, int years) {
        var observations = fetchObservations(code, market, years);
        return observations.stream().filter(DividendFetchResult::complete).findFirst().orElse(observations.getFirst());
    }

    public List<DividendFetchResult> fetchObservations(String code, String market, int years) {
        if (years < 1 || years > 20) throw new IllegalArgumentException("years must be 1..20");
        LocalDate today = LocalDate.now(clock.withZone("美股".equals(market) ? ET : TAIPEI));
        LocalDate from = today.minusYears(years);
        if (!Set.of("台股", "美股").contains(market))
            return List.of(failed("UNKNOWN", from, today, "不支援的市場", List.of()));
        DividendFetchResult historical = "台股".equals(market) ? fetchTw(code, from, today) : fetchUs(code, from, today);
        var upcoming = upcomingScopeClient == null ? DividendUpcomingScopeClient.UpcomingScope.unavailable("未設定 upcoming dividend adapter")
                : upcomingScopeClient.fetch(code, market, today, today.plusDays(45));
        if (upcoming == null) upcoming = DividendUpcomingScopeClient.UpcomingScope.unavailable("upcoming provider 未回結果");
        boolean complete = upcoming.covers(today, today.plusDays(45));
        FetchStatus status = complete ? upcoming.events().isEmpty() ? FetchStatus.EMPTY_COMPLETE : FetchStatus.COMPLETE : FetchStatus.PARTIAL;
        DividendFetchResult future = new DividendFetchResult(upcoming.provider(), upcoming.events(), status,
                upcoming.scopeFrom(), upcoming.scopeTo(), upcoming.sourceAvailableAt(),
                complete ? null : upcoming.errorReason() == null ? "upcoming scope incomplete" : upcoming.errorReason(),
                upcoming.sourceUrls(), upcoming.normalizerVersion(), upcoming.amountBasis(), upcoming.dateGroupsComplete(), upcoming.capturedAt(), upcoming.failureKind());
        List<DividendFetchResult> observations = new ArrayList<>(storageGroups(historical));
        observations.addAll(storageGroups(future));
        return List.copyOf(observations);
    }

    public DividendUpcomingScopeClient.UpcomingScope fetchProviderUpcomingScope(String code, String market, LocalDate from, LocalDate to) {
        if (code == null || code.isBlank() || from == null || to == null || from.isAfter(to) || to.isAfter(from.plusDays(45)))
            return DividendUpcomingScopeClient.UpcomingScope.unavailable("provider upcoming scope 不合法");
        DividendFetchResult result = switch (market) {
            case "台股" -> fetchTw(code, from, to);
            case "美股" -> fetchUs(code, from, to);
            default -> failed("UNKNOWN", from, to, "不支援的市場", List.of());
        };
        return new DividendUpcomingScopeClient.UpcomingScope(result.source(), from, to, result.sourceAvailableAt(), false,
                result.events(), "Provider datasets do not prove complete upcoming scope (partial)"
                + (result.errorReason() == null ? "" : ": " + result.errorReason()), result.sourceUrls(),
                result.normalizerVersion(), result.amountBasis(), result.dateGroupsComplete(), result.capturedAt(), result.failureKind());
    }

    private DividendFetchResult fetchTw(String code, LocalDate from, LocalDate to) {
        var primary = finmind.fetch("TaiwanStockDividend", code, from);
        if (primary.failureKind().quotaRelated()) return sourceFailure(TW_PRIMARY, from, to, primary, primary.errorReason());
        String primaryError = primary.errorReason();
        if (primary.successful()) {
            Parsed parsed = parseFinMindDividendRows(primary.rows(), code, from, to);
            if (parsed.valid() && (!parsed.events().isEmpty() || !primary.rows().isEmpty()))
                return provenance(normalized(TW_PRIMARY, parsed, from, to, urls(primary)), primary);
            if (!parsed.valid()) primaryError = parsed.error();
        }
        var fallback = finmind.fetch("TaiwanStockDividendResult", code, from);
        if (fallback.failureKind().quotaRelated()) return sourceFailure(TW_RESULT, from, to, fallback, fallback.errorReason());
        if (fallback.successful()) {
            Parsed parsed = parseFinMindResultRows(fallback.rows(), code, from, to);
            if (parsed.valid() && parsed.events().isEmpty() && primaryError != null)
                return sourceFailure(TW_RESULT, from, to, fallback, "FinMind partial: " + primaryError);
            if (parsed.valid()) return provenance(normalized(TW_RESULT, parsed, from, to, urls(fallback)), fallback);
            return sourceFailure(TW_RESULT, from, to, fallback, parsed.error());
        }
        return sourceFailure(TW_RESULT, from, to, fallback,
                "FinMind datasets unavailable: " + Objects.toString(primaryError, "primary empty")
                + "; " + Objects.toString(fallback.errorReason(), "result unavailable"));
    }
    private static List<String> urls(DividendFinMindTransport.DatasetResponse response) {
        return response.actualUrl() == null ? List.of() : List.of(response.actualUrl());
    }
    private DividendFetchResult provenance(DividendFetchResult result, DividendFinMindTransport.DatasetResponse response) {
        return new DividendFetchResult(result.source(), result.events(), result.status(), result.scopeFrom(), result.scopeTo(),
                response.sourceAvailableAt(), result.errorReason(), urls(response), result.normalizerVersion(), result.amountBasis(),
                result.dateGroupsComplete(), response.capturedAt(), response.failureKind());
    }
    private DividendFetchResult sourceFailure(String source, LocalDate from, LocalDate to,
                                              DividendFinMindTransport.DatasetResponse response, String reason) {
        SourceFailureKind kind = response.failureKind() == SourceFailureKind.NONE ? SourceFailureKind.SCHEMA_ERROR : response.failureKind();
        return new DividendFetchResult(source, List.of(), FetchStatus.FAILED, from, to, response.sourceAvailableAt(), reason,
                urls(response), DividendNormalization.VERSION, DividendNormalization.NOMINAL, false, response.capturedAt(), kind);
    }

    private Parsed parseFinMindDividendRows(JsonNode rows, String code, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        try {
            for (JsonNode row : rows) {
                matchingStock(row, code);
                BigDecimal cash = required(row, "CashEarningsDistribution").add(required(row, "CashStatutorySurplus"));
                BigDecimal stock = required(row, "StockEarningsDistribution").add(required(row, "StockStatutorySurplus"));
                DividendNormalization.exact(cash); DividendNormalization.exact(stock);
                if (cash.signum() == 0 && stock.signum() == 0) continue;
                LocalDate ex = isoDate(row.path("CashExDividendTradingDate").asText(null));
                LocalDate rights = isoDate(row.path("StockExDividendTradingDate").asText(null));
                if (cash.signum() > 0 && ex == null || stock.signum() > 0 && rights == null)
                    return Parsed.invalid("FinMind primary row malformed: 分項缺少對應除息/除權日");
                LocalDate anchor = DividendDates.anchorDate(ex, rights);
                if (anchor == null) return Parsed.invalid("FinMind action date missing");
                if (!within(ex, from, to) && !within(rights, from, to)) continue;
                String nativeId = first(row, "id", "eventId");
                String period = first(row, "year");
                String component = nativeId != null ? DividendNormalization.componentId("FINMIND_PRIMARY_ID", code, nativeId)
                        : DividendNormalization.componentId("FINMIND_PRIMARY_PERIOD", code, period);
                events.add(new DividendEvent(anchor.getYear(), cash, stock, iso(ex), iso(rights),
                        iso(isoDate(row.path("CashDividendPaymentDate").asText(null))),
                        iso(isoDate(row.path("StockDividendPaymentDate").asText(null))), component));
            }
            Map<String, Long> counts = new HashMap<>();
            Map<String, Long> dateCounts = new HashMap<>();
            events.stream().map(DividendEvent::upstreamComponentId).filter(Objects::nonNull).forEach(id -> counts.merge(id, 1L, Long::sum));
            events.forEach(event -> dateCounts.merge(Objects.toString(event.exDividendDate(), "NO_CASH") + "|"
                    + Objects.toString(event.exRightsDate(), "NO_STOCK"), 1L, Long::sum));
            // A repeated year/period is not an upstream component identifier (notably ETFs).
            // A source-date identity for a unique returned group does not claim cross-date revision tracking.
            events = events.stream().map(event -> event.upstreamComponentId() == null
                    || event.upstreamComponentId().startsWith("FINMIND_PRIMARY_PERIOD:") && counts.getOrDefault(event.upstreamComponentId(), 0L) > 1
                    ? new DividendEvent(event.year(), event.cashDividend(), event.stockDividend(), event.exDividendDate(),
                        event.exRightsDate(), event.cashPaymentDate(), event.stockPaymentDate(),
                        dateCounts.get(Objects.toString(event.exDividendDate(), "NO_CASH") + "|" + Objects.toString(event.exRightsDate(), "NO_STOCK")) == 1
                            ? DividendNormalization.componentId("FINMIND_PRIMARY_DATE", code,
                                Objects.toString(event.exDividendDate(), "NO_CASH"), Objects.toString(event.exRightsDate(), "NO_STOCK")) : null)
                    : event).toList();
            return Parsed.valid(events);
        } catch (RuntimeException invalid) { return Parsed.invalid("FinMind primary row malformed/precision: " + invalid.getClass().getSimpleName()); }
    }

    private Parsed parseFinMindResultRows(JsonNode rows, String code, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        try {
            for (JsonNode row : rows) {
                matchingStock(row, code);
                BigDecimal cash = required(row, "stock_and_cache_dividend");
                LocalDate ex = isoDate(row.path("date").asText(null));
                if (ex == null) return Parsed.invalid("FinMind Result action date missing");
                String type = row.path("stock_or_cache_dividend").asText("");
                // The result field is a reference-price adjustment for all rights-bearing rows.
                if (!Set.of("息", "除息").contains(type)) {
                    if (within(ex, from, to)) events.add(new DividendEvent(ex.getYear(), null, null,
                            type.contains("息") || !type.contains("權") ? iso(ex) : null,
                            type.contains("權") ? iso(ex) : null, null, null,
                            DividendNormalization.componentId("FINMIND_RESULT_UNVERIFIED", code, iso(ex), type.isBlank() ? "UNKNOWN" : type)));
                    continue;
                }
                if (cash.signum() > 0 && within(ex, from, to))
                    events.add(new DividendEvent(ex.getYear(), cash, BigDecimal.ZERO, iso(ex), null, null, null,
                            DividendNormalization.componentId("FINMIND_RESULT", code, iso(ex), "CASH")));
            }
            return Parsed.valid(events);
        } catch (RuntimeException invalid) { return Parsed.invalid("FinMind Result row malformed/precision: " + invalid.getClass().getSimpleName()); }
    }

    private DividendFetchResult fetchUs(String code, LocalDate from, LocalDate to) {
        List<String> urls = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (String assetClass : List.of("stocks", "etf")) {
            String url = "https://api.nasdaq.com/api/quote/" + code + "/dividends?assetclass=" + assetClass;
            urls.add(url);
            try {
                HttpResponse<String> response = httpClient.send(request(url).header("Referer", "https://finance.yahoo.com/")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) { reasons.add("Nasdaq HTTP " + response.statusCode()); continue; }
                JsonNode root = mapper.readTree(response.body());
                if (!root.path("status").path("rCode").isIntegralNumber() || root.path("status").path("rCode").asInt(-1) != 200) {
                    reasons.add("Nasdaq " + assetClass + " not applicable/non-success"); continue;
                }
                JsonNode rows = root.path("data").path("dividends").path("rows");
                if (!rows.isArray()) { reasons.add("Nasdaq rows 非 array"); continue; }
                Parsed parsed = parseNasdaqRows(rows, code, from, to);
                if (!parsed.valid()) { reasons.add(parsed.error()); continue; }
                if (!rows.isEmpty()) return captured(normalized("NASDAQ." + assetClass, parsed, from, to, List.of(url)), clock.instant());
            } catch (Exception e) { reasons.add("Nasdaq failed: " + e.getClass().getSimpleName()); }
        }
        String url = yahooEndpoint(code, from, to);
        urls.add(url);
        Parsed parsed = yahoo(code, from, to, url);
        if (parsed.valid()) return captured(normalized(YAHOO, parsed, from, to, List.of(url)), clock.instant());
        reasons.add(parsed.error());
        return failed(YAHOO, from, to, String.join("; ", reasons), urls);
    }

    private Parsed parseNasdaqRows(JsonNode rows, String code, LocalDate from, LocalDate to) {
        List<DividendEvent> events = new ArrayList<>();
        Set<String> declarationIdentities = new HashSet<>();
        Map<String, String> cashTypesByDate = new HashMap<>();
        try {
            for (JsonNode row : rows) {
                LocalDate ex = usDate(row.path("exOrEffDate").asText(null));
                if (ex == null) return Parsed.invalid("Nasdaq malformed date");
                if (!within(ex, from, to)) continue;
                String type = first(row, "type");
                String declaration = first(row, "declarationDate");
                String nativeId = first(row, "id", "eventId");
                if (!Set.of("Cash", "Special Cash").contains(Objects.toString(type, ""))) {
                    events.add(new DividendEvent(ex.getYear(), null, null, iso(ex), null, null, null,
                            DividendNormalization.componentId("NASDAQ_UNVERIFIED", code, iso(ex), Objects.toString(type, "UNKNOWN"))));
                    continue;
                }
                BigDecimal cash = parseDecimal(row.path("amount").asText("").replace("$", "").replace(",", ""));
                if (cash == null || cash.signum() <= 0) return Parsed.invalid("Nasdaq malformed cash/date");
                LocalDate pay = usDate(row.path("paymentDate").asText(null));
                String currency = first(row, "currency");
                if (currency != null && !"USD".equals(currency)) return Parsed.invalid("Nasdaq non-USD cash basis");
                // A missing declaration is common for ETF distributions. A unique returned
                // ex-date/type can identify that day's fact, but cannot prove a cross-date revision.
                boolean datedDeclaration = false;
                try { datedDeclaration = declaration != null && usDate(declaration) != null; }
                catch (RuntimeException ignored) { /* Non-date provider placeholders are not native IDs. */ }
                String component = nativeId != null ? DividendNormalization.componentId("NASDAQ", code, nativeId)
                        : datedDeclaration ? DividendNormalization.componentId("NASDAQ", code, type, declaration)
                        : DividendNormalization.componentId("NASDAQ_DATE", code, iso(ex), type);
                if (nativeId == null && datedDeclaration) declarationIdentities.add(component);
                cashTypesByDate.put(iso(ex), type);
                events.add(new DividendEvent(ex.getYear(), cash, BigDecimal.ZERO, iso(ex), null, iso(pay), null, component));
            }
            Map<String, Long> dateCounts = new HashMap<>(), componentCounts = new HashMap<>();
            events.forEach(event -> {
                dateCounts.merge(event.exDividendDate(), 1L, Long::sum);
                if (event.upstreamComponentId() != null) componentCounts.merge(event.upstreamComponentId(), 1L, Long::sum);
            });
            // One board declaration may announce several actual distributions (ASML).
            // Such a repeated declaration cannot act as the component's cross-date identity.
            events = events.stream().map(event -> {
                String id = event.upstreamComponentId();
                boolean needsDateIdentity = id != null && (id.startsWith("NASDAQ_DATE:")
                        || declarationIdentities.contains(id) && componentCounts.get(id) > 1);
                if (!needsDateIdentity) return event;
                String dateId = dateCounts.get(event.exDividendDate()) == 1
                        ? DividendNormalization.componentId("NASDAQ_DATE", code, event.exDividendDate(),
                            cashTypesByDate.get(event.exDividendDate())) : null;
                return new DividendEvent(event.year(), event.cashDividend(), event.stockDividend(), event.exDividendDate(),
                        event.exRightsDate(), event.cashPaymentDate(), event.stockPaymentDate(), dateId);
            }).toList();
            return Parsed.valid(events);
        } catch (RuntimeException invalid) { return Parsed.invalid("Nasdaq malformed/precision: " + invalid.getClass().getSimpleName()); }
    }

    private Parsed yahoo(String code, LocalDate from, LocalDate to, String url) {
        try {
            String body;
            if (yahooCurl) {
                Process process = new ProcessBuilder("curl", "--silent", "--show-error", "--fail", "--connect-timeout", "8", "--max-time", "20",
                        "--max-filesize", "8388608", "-H", "User-Agent: Mozilla/5.0", url).start();
                body = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (process.waitFor() != 0) return Parsed.invalid("Yahoo transport failed");
            } else {
                HttpResponse<String> response = httpClient.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return Parsed.invalid("Yahoo HTTP " + response.statusCode());
                body = response.body();
            }
            return parseYahoo(body, code, from, to);
        } catch (Exception e) { return Parsed.invalid("Yahoo failed: " + e.getClass().getSimpleName()); }
    }

    /** Same requested chart document carries the adjusted amounts and every split through its end. */
    Parsed parseYahoo(String body, String code, LocalDate from, LocalDate to) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode error = root.path("chart").path("error");
            if (!error.isNull() && !error.isMissingNode()) return Parsed.invalid("Yahoo chart error");
            JsonNode results = root.path("chart").path("result");
            if (!results.isArray() || results.size() != 1) return Parsed.invalid("Yahoo chart result invalid");
            JsonNode chart = results.get(0);
            if (!code.equalsIgnoreCase(chart.path("meta").path("symbol").asText(""))) return Parsed.invalid("Yahoo symbol mismatch");
            if (!"USD".equals(chart.path("meta").path("currency").asText(""))) return Parsed.invalid("Yahoo currency mismatch");
            JsonNode events = chart.path("events");
            if (!events.isObject()) return Parsed.invalid("Yahoo events missing");
            JsonNode dividends = events.get("dividends");
            if (dividends == null || !dividends.isObject()) return Parsed.invalid("Yahoo dividend events missing/invalid");
            JsonNode splitRows = events.get("splits");
            if (splitRows != null && !splitRows.isObject()) return Parsed.invalid("Yahoo split events invalid");
            List<Split> splits = new ArrayList<>();
            if (splitRows != null) for (var fields = splitRows.fields(); fields.hasNext();) {
                var field = fields.next(); JsonNode row = field.getValue();
                long epoch = epoch(row.get("date"));
                if (!Long.toString(epoch).equals(field.getKey())) return Parsed.invalid("Yahoo split identity mismatch");
                LocalDate date = Instant.ofEpochSecond(epoch).atZone(ET).toLocalDate();
                if (!within(date, from, to)) return Parsed.invalid("Yahoo split outside requested scope");
                BigDecimal numerator = positiveInteger(row.get("numerator"));
                BigDecimal denominator = positiveInteger(row.get("denominator"));
                if (row.has("splitRatio")) {
                    String[] ratio = row.path("splitRatio").asText("").split(":");
                    if (ratio.length != 2 || new BigDecimal(ratio[0]).multiply(denominator)
                            .compareTo(new BigDecimal(ratio[1]).multiply(numerator)) != 0)
                        return Parsed.invalid("Yahoo split ratio mismatch");
                }
                splits.add(new Split(date, numerator, denominator));
            }
            List<DividendEvent> output = new ArrayList<>();
            for (var fields = dividends.fields(); fields.hasNext();) {
                var field = fields.next(); JsonNode row = field.getValue();
                long epoch = epoch(row.get("date"));
                if (!Long.toString(epoch).equals(field.getKey())) return Parsed.invalid("Yahoo dividend identity mismatch");
                LocalDate date = Instant.ofEpochSecond(epoch).atZone(ET).toLocalDate();
                if (!within(date, from, to)) return Parsed.invalid("Yahoo dividend outside requested scope");
                BigDecimal adjusted = required(row, "amount");
                if (adjusted.signum() <= 0) return Parsed.invalid("Yahoo cash not positive");
                BigDecimal numerator = BigDecimal.ONE, denominator = BigDecimal.ONE;
                for (Split split : splits) {
                    if (split.date().equals(date)) return Parsed.invalid("Yahoo same-day split basis unverified");
                    if (split.date().isAfter(date)) {
                        numerator = numerator.multiply(split.numerator()); denominator = denominator.multiply(split.denominator());
                    }
                }
                // Exact divide rejects nonterminating decimals; persistence range is checked without rounding.
                BigDecimal nominal = DividendNormalization.exact(adjusted.multiply(numerator).divide(denominator));
                output.add(new DividendEvent(date.getYear(), nominal, BigDecimal.ZERO, iso(date), null, null, null,
                        DividendNormalization.componentId("YAHOO", code, field.getKey())));
            }
            return Parsed.valid(output);
        } catch (Exception e) { return Parsed.invalid("Yahoo chart/precision invalid: " + e.getClass().getSimpleName()); }
    }

    private DividendFetchResult captured(DividendFetchResult result, Instant capturedAt) {
        return new DividendFetchResult(result.source(), result.events(), result.status(), result.scopeFrom(), result.scopeTo(),
                result.sourceAvailableAt(), result.errorReason(), result.sourceUrls(), result.normalizerVersion(), result.amountBasis(),
                result.dateGroupsComplete(), capturedAt, result.failureKind());
    }
    private DividendFetchResult normalized(String source, Parsed parsed, LocalDate from, LocalDate to, List<String> urls) {
        boolean completeGroups = parsed.valid() && DividendNormalization.completeComponents(parsed.events());
        return new DividendFetchResult(source, parsed.events(), FetchStatus.PARTIAL, from, to, null,
                completeGroups ? "已返回日期組完整；未證明未來45日公告完整性" : "日期組元件身分缺失或相互矛盾",
                urls, DividendNormalization.VERSION, DividendNormalization.NOMINAL, completeGroups);
    }

    /** Isolate a disputed date without making another returned date or a cash/rights leg disappear. */
    private static List<DividendFetchResult> storageGroups(DividendFetchResult fetched) {
        if (fetched.normalizerVersion() != 2 || fetched.dateGroupsComplete() || fetched.events().size() < 2) return List.of(fetched);
        List<List<DividendEvent>> groups = new ArrayList<>();
        for (DividendEvent event : fetched.events()) {
            List<DividendEvent> merged = new ArrayList<>(List.of(event));
            boolean changed;
            do {
                changed = false;
                for (var iterator = groups.iterator(); iterator.hasNext();) {
                    var group = iterator.next();
                    if (group.stream().anyMatch(a -> merged.stream().anyMatch(b -> connected(a, b)))) {
                        merged.addAll(group); iterator.remove(); changed = true;
                    }
                }
            } while (changed);
            groups.add(merged);
        }
        if (groups.size() == 1) return List.of(fetched);
        List<DividendFetchResult> scoped = new ArrayList<>();
        for (var group : groups) {
            List<LocalDate> dates = group.stream().flatMap(event -> java.util.stream.Stream.of(event.exDividendDate(), event.exRightsDate()))
                    .filter(Objects::nonNull).map(LocalDate::parse).sorted().toList();
            if (dates.isEmpty()) return List.of(fetched);
            boolean complete = DividendNormalization.completeComponents(group);
            scoped.add(new DividendFetchResult(fetched.source(), group, FetchStatus.PARTIAL, dates.getFirst(), dates.getLast(),
                    fetched.sourceAvailableAt(), complete ? "獨立已返回日期組完整；未證明日曆完整性" : "此日期組元件身分不明或互相矛盾",
                    fetched.sourceUrls(), fetched.normalizerVersion(), fetched.amountBasis(), complete, fetched.capturedAt(), fetched.failureKind()));
        }
        return List.copyOf(scoped);
    }
    private static boolean connected(DividendEvent left, DividendEvent right) {
        if (left.upstreamComponentId() != null && left.upstreamComponentId().equals(right.upstreamComponentId())) return true;
        for (String date : new String[]{left.exDividendDate(), left.exRightsDate()})
            if (date != null && (date.equals(right.exDividendDate()) || date.equals(right.exRightsDate()))) return true;
        return false;
    }
    private static LocalDate anchorDate(LocalDate cash, LocalDate stock) { return DividendDates.anchorDate(cash, stock); }
    private DividendFetchResult failed(String source, LocalDate from, LocalDate to, String error, List<String> urls) {
        return new DividendFetchResult(source, List.of(), FetchStatus.FAILED, from, to, null, error, urls,
                DividendNormalization.VERSION, DividendNormalization.NOMINAL, false);
    }
    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", UA)
                .header("Accept", "application/json").header("Accept-Encoding", "identity");
    }
    private static String finmindEndpoint(String dataset, String code, LocalDate from) {
        return "https://api.finmindtrade.com/api/v4/data?dataset=" + dataset + "&data_id=" + code + "&start_date=" + from;
    }
    private static String yahooEndpoint(String code, LocalDate from, LocalDate to) {
        return "https://query2.finance.yahoo.com/v8/finance/chart/" + code.trim().toUpperCase(Locale.ROOT)
                + "?period1=" + from.atStartOfDay(ET).toEpochSecond() + "&period2=" + to.plusDays(1).atStartOfDay(ET).toEpochSecond()
                + "&interval=1d&events=div,splits";
    }
    private static void matchingStock(JsonNode row, String code) {
        if (row.hasNonNull("stock_id") && !code.equals(row.path("stock_id").asText())) throw new IllegalArgumentException("stock mismatch");
    }
    private static BigDecimal required(JsonNode row, String field) {
        BigDecimal value = parseDecimal(row.path(field).asText(null));
        if (value == null) throw new IllegalArgumentException("missing decimal");
        return value;
    }
    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank() || "N/A".equalsIgnoreCase(value.trim())) return null;
        return DividendNormalization.exact(new BigDecimal(value.trim()));
    }
    private static BigDecimal positiveInteger(JsonNode value) {
        if (value == null || value.isNull()) throw new IllegalArgumentException("missing split");
        BigDecimal number = new BigDecimal(value.asText()).stripTrailingZeros();
        if (number.signum() <= 0 || number.scale() > 0 || number.precision() > 12) throw new IllegalArgumentException("invalid split");
        return number;
    }
    private static long epoch(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
            throw new IllegalArgumentException("invalid timestamp");
        return value.longValue();
    }
    private static LocalDate isoDate(String text) {
        if (text == null || text.isBlank() || "N/A".equalsIgnoreCase(text)) return null;
        return LocalDate.parse(text);
    }
    private static LocalDate usDate(String text) {
        if (text == null || text.isBlank() || "N/A".equalsIgnoreCase(text)) return null;
        return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern("M/d/uuuu"));
    }
    private static String first(JsonNode row, String... keys) {
        for (String key : keys) { String text = row.path(key).asText(null); if (text != null && !text.isBlank() && !"N/A".equalsIgnoreCase(text) && !"null".equalsIgnoreCase(text)) return text; }
        return null;
    }
    private static String iso(LocalDate date) { return date == null ? null : date.toString(); }
    private static boolean within(LocalDate date, LocalDate from, LocalDate to) { return date != null && !date.isBefore(from) && !date.isAfter(to); }
    private record Split(LocalDate date, BigDecimal numerator, BigDecimal denominator) {}
    record Parsed(boolean valid, List<DividendEvent> events, String error) {
        static Parsed valid(List<DividendEvent> events) { return new Parsed(true, List.copyOf(events), null); }
        static Parsed invalid(String error) { return new Parsed(false, List.of(), error); }
    }
}
