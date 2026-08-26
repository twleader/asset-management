package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.externalmaterials.service.ProviderTimedPriceObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Internal-only client for the Python adapter's one normalized quote endpoint. */
@Component
@ConditionalOnProperty(prefix = "fubon", name = "enabled", havingValue = "true")
public class FubonNormalizedQuoteClient {

    private static final Pattern CODE = Pattern.compile("^[0-9A-Z]{2,10}$");
    private static final int MAX_CODES = 100;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String NORMALIZED_PATH = "/internal/market-data/tw-quotes";
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Pattern FAILURE_REASON = Pattern.compile("^[A-Z_]{1,64}$");
    private static final Pattern CANONICAL_DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Set<String> ROOT_FIELDS = Set.of("batchId", "counters", "quotes");
    private static final Set<String> ROW_FIELDS = Set.of("stockCode", "status", "reason", "quote");
    private static final Set<String> QUOTE_FIELDS = Set.of(
            "stockCode", "stockName", "market", "actualPrice", "previousClose", "openPrice", "highPrice",
            "lowPrice", "buyPrice", "sellPrice", "volume", "updatedAt", "tradingDate", "source", "closed",
            "quoteStatus", "orderBook");
    private static final Set<String> REQUIRED_QUOTE_FIELDS = Set.of(
            "stockCode", "stockName", "market", "actualPrice", "previousClose", "openPrice", "highPrice",
            "lowPrice", "buyPrice", "sellPrice", "volume", "updatedAt", "tradingDate", "source", "closed",
            "quoteStatus");
    private static final Set<String> ORDER_BOOK_FIELDS = Set.of(
            "bookUpdatedAt", "averagePrice", "turnoverYi", "innerVolumeLots", "outerVolumeLots", "levels");
    private static final Set<String> ORDER_BOOK_LEVEL_FIELDS = Set.of(
            "level", "bidPrice", "bidVolumeLots", "askPrice", "askVolumeLots");
    /** Adapter outcome order is a contract, not an open-ended telemetry map. */
    private static final List<String> COUNTER_NAMES = List.of(
            "DISABLED", "MISCONFIGURED", "CALENDAR_UNKNOWN", "ACCOUNTING_FAILED", "RECONCILE_FAILED",
            "QUOTE_FAILED", "NO_OWNER", "NO_TODAY_SNAPSHOT", "BROKER_MISSING", "DRY_RUN", "SUCCESS",
            "EMPTY_CLEARED", "ROLLED_BACK");

    private final String baseUrl;
    private final String tokenPath;
    private final Transport transport;
    private final ObjectMapper mapper;
    private final FubonNormalizedQuoteMapper quoteMapper;

    @Autowired
    public FubonNormalizedQuoteClient(
            @Value("${fubon.base-url:http://fubon-broker-service:8080}") String baseUrl,
            @Value("${fubon.shared-token-path:/run/secrets/fubon/shared/internal-service-token}") String tokenPath) {
        this(baseUrl, tokenPath, new JdkTransport(), Clock.systemUTC());
    }

    FubonNormalizedQuoteClient(String baseUrl, String tokenPath, Transport transport, Clock clock) {
        this.baseUrl = baseUrl;
        this.tokenPath = tokenPath;
        this.transport = transport;
        // A normal tree parser silently accepts duplicate JSON keys.  The mirror keeps a lossless,
        // auditable normalized response, so duplicate keys must be rejected before anything is mapped.
        this.mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        this.quoteMapper = new FubonNormalizedQuoteMapper(clock);
    }

    public BatchResult fetch(List<String> requestedCodes) {
        List<String> codes;
        try {
            codes = validateCodes(requestedCodes);
        } catch (IllegalArgumentException ex) {
            return BatchResult.failed(BatchStatus.INVALID_REQUEST, requestedCodes == null ? 0 : requestedCodes.size());
        }

        URI endpoint = endpointUri();
        String token = FubonSharedTokenReader.read(tokenPath);
        if (endpoint == null || token == null) {
            return BatchResult.failed(BatchStatus.MISCONFIGURED, codes.size());
        }

        final String requestBody;
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode codeArray = body.putArray("codes");
            codes.forEach(codeArray::add);
            body.put("purpose", "LIVE");
            requestBody = mapper.writeValueAsString(body);
        } catch (Exception ex) {
            return BatchResult.failed(BatchStatus.INVALID_REQUEST, codes.size());
        }

        RawResponse response;
        try {
            response = transport.post(endpoint, token, requestBody, REQUEST_TIMEOUT);
        } catch (Exception ex) {
            return BatchResult.failed(BatchStatus.SERVICE_UNAVAILABLE, codes.size());
        }
        if (response.statusCode() == 503) {
            return BatchResult.failed(BatchStatus.SERVICE_UNAVAILABLE, codes.size());
        }
        if (response.statusCode() == 429) {
            Map<String, String> reasons = new LinkedHashMap<>();
            codes.forEach(code -> reasons.put(code, "RATE_LIMITED"));
            return new BatchResult(BatchStatus.RATE_LIMITED, List.of(), codes.size(), codes.size(), Map.copyOf(reasons));
        }
        if (response.statusCode() != 200 || response.body() == null
                || response.body().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
        }
        return parseResponse(codes, response.body());
    }

    private BatchResult parseResponse(List<String> codes, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode batchId = root == null ? null : root.get("batchId");
            JsonNode counters = root == null ? null : root.get("counters");
            JsonNode rows = root == null ? null : root.get("quotes");
            if (!hasExactly(root, ROOT_FIELDS) || batchId == null || !batchId.isTextual()
                    || !BATCH_ID.matcher(batchId.textValue()).matches() || rows == null || !rows.isArray()) {
                return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
            }
            String countersJson = validateCounters(counters);

            Set<String> requested = new LinkedHashSet<>(codes);
            Set<String> seen = new HashSet<>();
            for (JsonNode row : rows) {
                JsonNode code = row == null ? null : row.get("stockCode");
                if (!hasExactly(row, ROW_FIELDS) || code == null || !code.isTextual()
                        || !requested.contains(code.textValue()) || !seen.add(code.textValue())) {
                    return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
                }
            }
            if (!seen.equals(requested)) {
                return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
            }

            List<ProviderTimedPriceObservation> observations = new ArrayList<>();
            Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> orderBooks = new LinkedHashMap<>();
            Map<String, String> failureReasons = new LinkedHashMap<>();
            Map<String, String> responseRows = new LinkedHashMap<>();
            int rejected = 0;
            for (JsonNode row : rows) {
                String code = row.get("stockCode").textValue();
                JsonNode status = row.get("status");
                if (status == null || !status.isTextual()) {
                    return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
                }
                if ("FAILURE".equals(status.textValue())) {
                    if (!validFailureRow(row)) return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
                    rejected++;
                    failureReasons.put(code, row.get("reason").textValue());
                    responseRows.put(code, mapper.writeValueAsString(row));
                    continue;
                }
                if (!"SUCCESS".equals(status.textValue())) {
                    return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
                }
                JsonNode quote = row.get("quote");
                if (!validSuccessRow(code, row, quote)) return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
                // The raw validated row is retained even if the established current-price mapper later
                // rejects it as stale, closed, future, or an incomplete canonical five-book.
                responseRows.put(code, mapper.writeValueAsString(row));
                try {
                    FubonNormalizedQuoteMapper.MappedQuote mapped = quoteMapper.mapWithOrderBook(code, quote);
                    observations.add(mapped.observation());
                    if (mapped.orderBook() != null) {
                        orderBooks.put(code, mapped.orderBook());
                    }
                } catch (FubonNormalizedQuoteMapper.MappingException ex) {
                    rejected++;
                    failureReasons.put(code, ex.reason());
                }
            }
            BatchStatus status = rejected == 0 ? BatchStatus.SUCCESS : BatchStatus.PARTIAL_FAILURE;
            return new BatchResult(status, List.copyOf(observations), codes.size(), rejected,
                    Map.copyOf(failureReasons), Map.copyOf(orderBooks),
                    new ValidatedEnvelope(batchId.textValue(), countersJson, Map.copyOf(responseRows)));
        } catch (Exception ex) {
            return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
        }
    }

    private String validateCounters(JsonNode counters) throws Exception {
        if (!hasExactly(counters, Set.copyOf(COUNTER_NAMES))) throw new IllegalArgumentException("invalid counters");
        for (String name : COUNTER_NAMES) {
            JsonNode value = counters.get(name);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                throw new IllegalArgumentException("invalid counter");
            }
        }
        return mapper.writeValueAsString(counters);
    }

    private static boolean validFailureRow(JsonNode row) {
        JsonNode reason = row.get("reason");
        JsonNode quote = row.get("quote");
        return reason != null && reason.isTextual() && FAILURE_REASON.matcher(reason.textValue()).matches()
                && quote != null && quote.isNull();
    }

    private static boolean validSuccessRow(String requestedCode, JsonNode row, JsonNode quote) {
        return row.get("reason") != null && row.get("reason").isNull()
                && quote != null && validQuote(requestedCode, quote);
    }

    private static boolean validQuote(String requestedCode, JsonNode quote) {
        if (!quote.isObject() || !QUOTE_FIELDS.containsAll(fieldNames(quote))
                || !fieldNames(quote).containsAll(REQUIRED_QUOTE_FIELDS)) return false;
        if (!requiredText(quote, "stockCode", requestedCode) || !requiredText(quote, "stockName", null)
                || !"台股".equals(text(quote, "market")) || !"FUBON_INTRADAY".equals(text(quote, "source"))
                || !"LIVE".equals(text(quote, "quoteStatus")) || !booleanValue(quote, "closed")) return false;
        if (!positiveDecimal(quote, "actualPrice") || !positiveDecimal(quote, "previousClose")
                || !positiveDecimal(quote, "openPrice") || !positiveDecimal(quote, "highPrice")
                || !positiveDecimal(quote, "lowPrice") || !nullablePositiveDecimal(quote, "buyPrice")
                || !nullablePositiveDecimal(quote, "sellPrice") || !nonNegativeLong(quote, "volume")) return false;
        if (!isoDate(quote, "tradingDate") || !instant(quote, "updatedAt")) return false;
        JsonNode book = quote.get("orderBook");
        return book == null || validOrderBook(book);
    }

    private static boolean validOrderBook(JsonNode book) {
        if (!hasExactly(book, ORDER_BOOK_FIELDS) || !instant(book, "bookUpdatedAt")
                || !nullablePositiveDecimal(book, "averagePrice") || !nullableNonNegativeDecimal(book, "turnoverYi")
                || !nullableNonNegativeLong(book, "innerVolumeLots") || !nullableNonNegativeLong(book, "outerVolumeLots")) {
            return false;
        }
        JsonNode levels = book.get("levels");
        if (levels == null || !levels.isArray() || levels.size() != 5) return false;
        for (int index = 0; index < 5; index++) {
            JsonNode level = levels.get(index);
            if (!hasExactly(level, ORDER_BOOK_LEVEL_FIELDS) || level.get("level") == null
                    || !level.get("level").isIntegralNumber() || !level.get("level").canConvertToInt()
                    || level.get("level").intValue() != index + 1
                    || !pairedNullableBookSide(level, "bidPrice", "bidVolumeLots")
                    || !pairedNullableBookSide(level, "askPrice", "askVolumeLots")) return false;
        }
        return true;
    }

    private static boolean pairedNullableBookSide(JsonNode level, String price, String lots) {
        JsonNode priceValue = level.get(price);
        JsonNode lotsValue = level.get(lots);
        boolean noPrice = priceValue == null || priceValue.isNull();
        boolean noLots = lotsValue == null || lotsValue.isNull();
        if (noPrice || noLots) return noPrice && noLots;
        return canonicalDecimal(priceValue, true) && lotsValue.isIntegralNumber()
                && lotsValue.canConvertToLong() && lotsValue.longValue() >= 0;
    }

    private static boolean hasExactly(JsonNode node, Set<String> expected) {
        return node != null && node.isObject() && fieldNames(node).equals(expected);
    }

    private static Set<String> fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) return Set.of();
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean requiredText(JsonNode object, String field, String expected) {
        String value = text(object, field);
        return value != null && !value.isBlank() && (expected == null || expected.equals(value));
    }

    private static boolean booleanValue(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isBoolean();
    }

    private static boolean isoDate(JsonNode object, String field) {
        try { LocalDate.parse(text(object, field)); return true; }
        catch (Exception invalid) { return false; }
    }

    private static boolean instant(JsonNode object, String field) {
        try { Instant.parse(text(object, field)); return true; }
        catch (Exception invalid) { return false; }
    }

    private static boolean positiveDecimal(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && canonicalDecimal(value, true);
    }

    private static boolean nullablePositiveDecimal(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && (value.isNull() || canonicalDecimal(value, true));
    }

    private static boolean nullableNonNegativeDecimal(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && (value.isNull() || canonicalDecimal(value, false));
    }

    private static boolean canonicalDecimal(JsonNode value, boolean positive) {
        if (!value.isTextual() || !CANONICAL_DECIMAL.matcher(value.textValue()).matches()) return false;
        try {
            BigDecimal decimal = new BigDecimal(value.textValue());
            return (positive ? decimal.signum() > 0 : decimal.signum() >= 0)
                    && decimal.precision() <= 20 && decimal.scale() >= 0 && decimal.scale() <= 10;
        } catch (NumberFormatException invalid) { return false; }
    }

    private static boolean nonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private static boolean nullableNonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && (value.isNull() || (value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0));
    }

    private URI endpointUri() {
        try {
            URI base = URI.create(baseUrl == null ? "" : baseUrl.trim());
            String path = base.getPath();
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !(path == null || path.isEmpty() || "/".equals(path))) {
                return null;
            }
            return URI.create(base.toString().replaceAll("/$", "") + NORMALIZED_PATH);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<String> validateCodes(List<String> requestedCodes) {
        if (requestedCodes == null || requestedCodes.isEmpty() || requestedCodes.size() > MAX_CODES) {
            throw new IllegalArgumentException("invalid code count");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : requestedCodes) {
            if (raw == null) throw new IllegalArgumentException("invalid code");
            String code = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (!CODE.matcher(code).matches() || !normalized.add(code)) {
                throw new IllegalArgumentException("invalid or duplicate code");
            }
        }
        return List.copyOf(normalized);
    }

    public enum BatchStatus {
        SUCCESS,
        PARTIAL_FAILURE,
        MISCONFIGURED,
        SERVICE_UNAVAILABLE,
        INVALID_REQUEST,
        INVALID_RESPONSE,
        RATE_LIMITED
    }

    public record BatchResult(
            BatchStatus status,
            List<ProviderTimedPriceObservation> observations,
            int requested,
            int rejected,
            Map<String, String> failureReasons,
            Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> orderBooks,
            ValidatedEnvelope envelope
    ) {
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected, Map<String, String> failureReasons) {
            this(status, observations, requested, rejected, failureReasons, Map.of(), null);
        }
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected) {
            this(status, observations, requested, rejected, Map.of(), Map.of(), null);
        }
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected, Map<String, String> failureReasons,
                           Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> orderBooks) {
            this(status, observations, requested, rejected, failureReasons, orderBooks, null);
        }
        static BatchResult failed(BatchStatus status, int requested) {
            return new BatchResult(status, List.of(), requested, requested, Map.of(), Map.of(), null);
        }
    }

    /** Exact validated wire envelope.  The JSON strings are only normalized adapter output, never SDK raw data. */
    public record ValidatedEnvelope(String batchId, String countersJson, Map<String, String> responseRows) {
        public ValidatedEnvelope {
            responseRows = responseRows == null ? Map.of() : Map.copyOf(responseRows);
        }
    }

    record RawResponse(int statusCode, String body) {}

    @FunctionalInterface
    interface Transport {
        RawResponse post(URI endpoint, String token, String body, Duration timeout) throws Exception;
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        @Override
        public RawResponse post(URI endpoint, String token, String body, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header(TOKEN_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RawResponse(response.statusCode(), response.body());
        }
    }
}
