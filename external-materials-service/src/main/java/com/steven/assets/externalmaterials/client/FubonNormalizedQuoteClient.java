package com.steven.assets.externalmaterials.client;

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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
        this.mapper = new ObjectMapper();
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
        String token = readToken();
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
            JsonNode rows = root == null ? null : root.get("quotes");
            if (root == null || !root.isObject() || batchId == null || !batchId.isTextual()
                    || batchId.textValue().isBlank() || rows == null || !rows.isArray()) {
                return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
            }

            Set<String> requested = new LinkedHashSet<>(codes);
            Set<String> seen = new HashSet<>();
            for (JsonNode row : rows) {
                JsonNode code = row == null ? null : row.get("stockCode");
                if (row == null || !row.isObject() || code == null || !code.isTextual()
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
            int rejected = 0;
            for (JsonNode row : rows) {
                String code = row.get("stockCode").textValue();
                JsonNode status = row.get("status");
                if (status == null || !status.isTextual()) {
                    rejected++;
                    failureReasons.put(code, "INVALID_STATUS");
                    continue;
                }
                if ("FAILURE".equals(status.textValue())) {
                    rejected++;
                    failureReasons.put(code, failureReason(row));
                    continue;
                }
                if (!"SUCCESS".equals(status.textValue())) {
                    rejected++;
                    failureReasons.put(code, "INVALID_STATUS");
                    continue;
                }
                try {
                    FubonNormalizedQuoteMapper.MappedQuote mapped = quoteMapper.mapWithOrderBook(code, row.get("quote"));
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
                    Map.copyOf(failureReasons), Map.copyOf(orderBooks));
        } catch (Exception ex) {
            return BatchResult.failed(BatchStatus.INVALID_RESPONSE, codes.size());
        }
    }

    private static String failureReason(JsonNode row) {
        JsonNode value = row.get("reason");
        if (value == null || !value.isTextual()) return "QUOTE_FAILED";
        String reason = value.textValue();
        return reason.matches("[A-Z_]{1,64}") ? reason : "QUOTE_FAILED";
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

    private String readToken() {
        try {
            Path path = Path.of(tokenPath == null ? "" : tokenPath);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null;
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (InvalidPathException | java.io.IOException | SecurityException ex) {
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
            Map<String, TwQuoteDetailFetchClient.QuoteDetailResult> orderBooks
    ) {
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected, Map<String, String> failureReasons) {
            this(status, observations, requested, rejected, failureReasons, Map.of());
        }
        public BatchResult(BatchStatus status, List<ProviderTimedPriceObservation> observations,
                           int requested, int rejected) {
            this(status, observations, requested, rejected, Map.of(), Map.of());
        }
        static BatchResult failed(BatchStatus status, int requested) {
            return new BatchResult(status, List.of(), requested, requested, Map.of(), Map.of());
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
