package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.SourceFailureKind;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dividend-only transport. It preserves HTTP provenance and never certifies financial completeness. */
@Component
public class DividendFinMindTransport {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_KEYS = 256, MAX_BODY = 8 * 1024 * 1024;
    private final String token;
    private final Clock clock;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final Object[] stripes = new Object[64];
    private final Map<Key, DatasetResponse> cache = new LinkedHashMap<>(16, .75f, true);
    private final Object quotaLock = new Object();
    private Instant retryNotBefore;
    private boolean probeInFlight;
    private int quotaFailures;

    @Autowired
    public DividendFinMindTransport(@Value("${finmind.token:${FINMIND_TOKEN:}}") String token) {
        this(token, Clock.systemUTC(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).version(HttpClient.Version.HTTP_1_1).build());
    }
    DividendFinMindTransport(String token, Clock clock, HttpClient http) {
        this.token = token == null ? "" : token.trim(); this.clock = Objects.requireNonNull(clock); this.http = http;
        Arrays.setAll(stripes, ignored -> new Object());
    }
    public record DatasetResponse(String dataset, String code, LocalDate requestedFrom, String actualUrl,
                                  Instant capturedAt, Instant sourceAvailableAt, JsonNode rows,
                                  SourceFailureKind failureKind, Integer httpStatus, String errorReason,
                                  Instant retryNotBefore, boolean cacheHit) {
        public DatasetResponse { rows = rows == null ? null : rows.deepCopy(); }
        boolean successful() { return failureKind == SourceFailureKind.NONE && rows != null; }
        DatasetResponse cachedRead() { return cachedRead(true); }
        DatasetResponse cachedRead(boolean hit) {
            return new DatasetResponse(dataset, code, requestedFrom, actualUrl, capturedAt, sourceAvailableAt,
                    rows, failureKind, httpStatus, errorReason, retryNotBefore, hit);
        }
    }
    private record Key(String dataset, String code) {}
    private record Admission(boolean allowed, boolean probe) {}

    public DatasetResponse fetch(String dataset, String code, LocalDate from) {
        if (!Set.of("TaiwanStockDividend", "TaiwanStockDividendResult").contains(dataset)
                || code == null || !code.matches("[A-Za-z0-9]{1,20}") || from == null)
            throw new IllegalArgumentException("Invalid dividend dataset request");
        Key key = new Key(dataset, code);
        synchronized (stripes[Math.floorMod(key.hashCode(), stripes.length)]) {
            DatasetResponse old = cached(key, from);
            if (old != null) return old;
            Admission admission = admit();
            if (!admission.allowed()) return new DatasetResponse(dataset, code, from, null, null, null, null,
                    SourceFailureKind.COOLDOWN, null, "COOLDOWN: 股利FinMind配額退避中，本機短路未外呼", nextQuotaRetryAt(), false);
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=" + dataset + "&data_id=" + code + "&start_date=" + from;
            Instant captured = null;
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").header("Accept-Encoding", "identity");
                if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
                HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
                captured = clock.instant();
                String body = response.body();
                JsonNode root = null;
                if (body != null && body.length() <= MAX_BODY) {
                    try { root = mapper.readTree(body); } catch (Exception ignored) { /* classify schema below */ }
                }
                if (response.statusCode() == 402 && root != null && root.path("status").isIntegralNumber()
                        && root.path("status").asInt() == 402 && root.path("msg").isTextual()
                        && root.path("msg").asText().contains("Requests reach the upper limit")) {
                    quota(response.headers(), captured);
                    return new DatasetResponse(dataset, code, from, url, captured, null, null,
                            SourceFailureKind.QUOTA_EXHAUSTED, 402, "QUOTA_EXHAUSTED: FinMind HTTP 402，來源明示請求已達上限", nextQuotaRetryAt(), false);
                }
                if (admission.probe()) endQuotaProbe();
                if (response.statusCode() != 200) return failure(dataset, code, from, url, captured,
                        SourceFailureKind.HTTP_ERROR, response.statusCode(), "FinMind HTTP " + response.statusCode());
                if (root == null || !root.path("status").isIntegralNumber() || root.path("status").asInt(-1) != 200
                        || !root.path("data").isArray()) return failure(dataset, code, from, url, captured,
                        SourceFailureKind.SCHEMA_ERROR, 200, "FinMind success envelope/data invalid");
                // A successful HTTP capture proves only when we saw this document, not its first announcement.
                DatasetResponse result = new DatasetResponse(dataset, code, from, url, captured, null,
                        root.path("data"), SourceFailureKind.NONE, 200, null, null, false);
                synchronized (cache) {
                    cache.entrySet().removeIf(entry -> !clock.instant().isBefore(entry.getValue().capturedAt().plus(TTL)));
                    cache.put(key, result);
                    while (cache.size() > MAX_KEYS) cache.remove(cache.keySet().iterator().next());
                }
                return result.cachedRead(false);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                if (admission.probe()) endQuotaProbe();
                return failure(dataset, code, from, url, captured, SourceFailureKind.TRANSPORT_ERROR, null,
                        "FinMind transport failed: " + exception.getClass().getSimpleName());
            }
        }
    }
    private DatasetResponse failure(String dataset, String code, LocalDate from, String url, Instant captured,
                                    SourceFailureKind kind, Integer httpStatus, String reason) {
        return new DatasetResponse(dataset, code, from, url, captured, null, null, kind, httpStatus, reason, null, false);
    }
    private DatasetResponse cached(Key key, LocalDate requestedFrom) {
        synchronized (cache) {
            DatasetResponse response = cache.get(key);
            if (response == null) return null;
            Instant now = clock.instant();
            if (response.capturedAt().isAfter(now) || !now.isBefore(response.capturedAt().plus(TTL))) { cache.remove(key); return null; }
            return !response.requestedFrom().isAfter(requestedFrom) ? response.cachedRead() : null;
        }
    }
    private Admission admit() {
        synchronized (quotaLock) {
            if (retryNotBefore == null) return new Admission(true, false);
            if (clock.instant().isBefore(retryNotBefore) || probeInFlight) return new Admission(false, false);
            probeInFlight = true; return new Admission(true, true);
        }
    }
    private void quota(HttpHeaders headers, Instant now) {
        synchronized (quotaLock) {
            quotaFailures = Math.min(3, quotaFailures + 1);
            Instant local = now.plus(Duration.ofMinutes(5L * quotaFailures));
            Instant provider = retryAfter(headers, now);
            Instant next = provider != null && provider.isAfter(local) ? provider : local;
            if (retryNotBefore == null || next.isAfter(retryNotBefore)) retryNotBefore = next;
            probeInFlight = false;
        }
    }
    private static Instant retryAfter(HttpHeaders headers, Instant now) {
        if (headers == null) return null;
        String text = headers.firstValue("Retry-After").orElse("").trim();
        try {
            Instant at = text.matches("[0-9]+") ? now.plusSeconds(Long.parseLong(text))
                    : ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return at.isAfter(now) ? at : null;
        } catch (RuntimeException ignored) { return null; }
    }
    private void endQuotaProbe() {
        synchronized (quotaLock) { retryNotBefore = null; probeInFlight = false; quotaFailures = 0; }
    }
    public boolean quotaRetryReady() {
        synchronized (quotaLock) { return retryNotBefore == null || !clock.instant().isBefore(retryNotBefore) && !probeInFlight; }
    }
    public Instant nextQuotaRetryAt() { synchronized (quotaLock) { return retryNotBefore; } }
    int cachedDatasetCount() { synchronized (cache) { return cache.size(); } }
}
