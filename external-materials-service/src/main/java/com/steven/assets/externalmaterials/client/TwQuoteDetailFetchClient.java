package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Task 348 的 Yahoo parser historical implementation.  It is deliberately not a Spring bean
 * and no current endpoint/worker injects or calls it; Task 373 serves only cached FUBON_BOOKS.
 */
@Slf4j
@Deprecated(forRemoval = false)
public class TwQuoteDetailFetchClient {
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9.\\-]{1,12}$");
    private static final String MARKER = "\"quote\":{\"data\":";
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 AssetManagementQuoteDetail/1.0";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TwQuoteDetailFetchClient() {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(), Clock.system(TAIPEI));
    }

    TwQuoteDetailFetchClient(HttpClient httpClient, ObjectMapper mapper, Clock clock) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record OrderBookLevel(int level, BigDecimal bidPrice, Long bidVolumeLots,
                                 BigDecimal askPrice, Long askVolumeLots) {}
    public record QuoteDetailResult(String stockCode, String stockName, String market,
                                    boolean supported, boolean available, String source, String message,
                                    Instant sourceTime, Instant fetchedAt, String marketStatus,
                                    BigDecimal price, BigDecimal previousClose, BigDecimal openPrice,
                                    BigDecimal highPrice, BigDecimal lowPrice, BigDecimal averagePrice,
                                    BigDecimal change, BigDecimal changePercent, BigDecimal turnoverYi,
                                    Long volumeLots, Long previousVolumeLots, BigDecimal amplitudePercent,
                                    Long innerVolumeLots, Long outerVolumeLots, BigDecimal innerPercent,
                                    BigDecimal outerPercent, Long bidTotalLots, Long askTotalLots, List<OrderBookLevel> levels) {}

    public QuoteDetailResult fetch(String code, String market) {
        if (!"台股".equals(market) || "0000".equals(code) || code == null || !CODE.matcher(code).matches()) {
            return unsupported(code, market);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://tw.stock.yahoo.com/quote/" + code + ".TW"))
                    .timeout(Duration.ofSeconds(12)).header("User-Agent", UA).header("Accept-Encoding", "identity").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                return unavailable(code, market);
            }
            String object = extractQuoteData(response.body());
            if (object == null) return unavailable(code, market);
            JsonNode data = mapper.readTree(object);
            return map(code, market, data);
        } catch (Exception e) {
            log.warn("Yahoo quote-detail unavailable for {} ({})", code, e.getClass().getSimpleName());
            return unavailable(code, market);
        }
    }

    static String extractQuoteData(String html) {
        int marker = html.indexOf(MARKER);
        if (marker < 0 || html.indexOf(MARKER, marker + MARKER.length()) >= 0) return null;
        int start = marker + MARKER.length();
        if (start >= html.length() || html.charAt(start) != '{') return null;
        int depth = 0; boolean inString = false; boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return html.substring(start, i + 1);
        }
        return null;
    }

    private QuoteDetailResult map(String code, String market, JsonNode d) {
        if (d == null || !code.equals(text(d, "systexId")) || !"TWD".equals(text(d, "currency"))
                || !("TAI".equals(text(d, "exchange")) || "TWO".equals(text(d, "exchange")))) return unavailable(code, market);
        BigDecimal price = rawPositive(d.get("price"));
        BigDecimal previous = rawPositive(d.get("regularMarketPreviousClose"));
        JsonNode orderbook = d.get("orderbook");
        if (price == null || previous == null || orderbook == null || !orderbook.isArray()) return unavailable(code, market);
        BigDecimal high = rawPositive(d.get("regularMarketDayHigh"));
        BigDecimal low = rawPositive(d.get("regularMarketDayLow"));
        Long inner = nonNegativeLong(d.get("inMarket")); Long outer = nonNegativeLong(d.get("outMarket"));
        BigDecimal amplitude = high != null && low != null && high.compareTo(low) >= 0
                ? high.subtract(low).divide(previous, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal innerPct = null, outerPct = null;
        if (inner != null && outer != null && inner + outer > 0) {
            innerPct = BigDecimal.valueOf(inner).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(inner + outer), 2, RoundingMode.HALF_UP);
            outerPct = BigDecimal.valueOf(100).setScale(2).subtract(innerPct);
        }
        List<OrderBookLevel> levels = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            JsonNode row = i < orderbook.size() ? orderbook.get(i) : null;
            levels.add(new OrderBookLevel(i + 1, positive(row, "bid"), nonNegativeLong(row == null ? null : row.get("bidVolK")),
                    positive(row, "ask"), nonNegativeLong(row == null ? null : row.get("askVolK"))));
        }
        Long bidTotal = total(levels, true), askTotal = total(levels, false);
        String name = text(d, "symbolName");
        String status = text(d, "marketStatus");
        String mappedStatus = "open".equalsIgnoreCase(status) ? "OPEN" :
                ("close".equalsIgnoreCase(status) || "closed".equalsIgnoreCase(status) ? "CLOSED" : "UNKNOWN");
        return new QuoteDetailResult(code, blankToNull(name), market, true, true, "YAHOO_TW", null,
                instant(d, "regularMarketTime"), clock.instant(), mappedStatus,
                price, previous, rawPositive(d.get("regularMarketOpen")), high, low, positive(d, "avgPrice"),
                rawSigned(d.get("change")), percent(d.get("changePercent")), divide100(decimal(d.get("turnoverM"))),
                nonNegativeLong(d.get("volumeK")), nonNegativeLong(d.get("previousVolumeK")), amplitude, inner, outer, innerPct, outerPct, bidTotal, askTotal, levels);
    }

    private QuoteDetailResult unsupported(String code, String market) { return empty(code, market, false); }
    private QuoteDetailResult unavailable(String code, String market) { return empty(code, market, true); }
    private QuoteDetailResult empty(String code, String market, boolean supported) {
        return new QuoteDetailResult(code, null, market, supported, false, "YAHOO_TW", "暫時無法取得行情五檔",
                null, null, "UNKNOWN", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.<OrderBookLevel>of());
    }
    private static String text(JsonNode n, String key) { return n == null || n.get(key) == null || !n.get(key).isTextual() ? null : n.get(key).asText(); }
    private static Instant instant(JsonNode n, String key) { try { String v=text(n,key); return v == null || v.isBlank() ? null : Instant.parse(v); } catch (RuntimeException e) { return null; } }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v; }
    private static BigDecimal rawPositive(JsonNode n) { return n == null || !n.isObject() ? null : positive(n, "raw"); }
    private static BigDecimal rawSigned(JsonNode n) { return n == null || !n.isObject() ? null : decimal(n.get("raw")); }
    private static BigDecimal positive(JsonNode n, String key) { return n == null ? null : positive(n.get(key)); }
    private static BigDecimal positive(JsonNode n) { BigDecimal v = decimal(n); return v != null && v.signum() > 0 ? v : null; }
    private static BigDecimal decimal(JsonNode n) { try { return n == null || n.isNull() ? null : new BigDecimal(n.asText().replace(",", "")); } catch (RuntimeException e) { return null; } }
    private static Long nonNegativeLong(JsonNode n) { BigDecimal v = decimal(n); try { return v != null && v.signum() >= 0 && v.stripTrailingZeros().scale() <= 0 ? v.longValueExact() : null; } catch (ArithmeticException e) { return null; } }
    private static BigDecimal percent(JsonNode n) { if (n == null) return null; try { return new BigDecimal(n.asText().replace("%", "").replace(",", "").trim()); } catch (RuntimeException e) { return null; } }
    private static BigDecimal divide100(BigDecimal v) { return v == null ? null : v.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP); }
    private static Long total(List<OrderBookLevel> levels, boolean bid) { long total=0; boolean any=false; for(OrderBookLevel level:levels){Long v=bid?level.bidVolumeLots():level.askVolumeLots();if(v!=null){total+=v;any=true;}}return any?total:null; }
}
