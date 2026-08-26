package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Strict producer-only Yahoo Taiwan order-book probe.
 *
 * <p>Only a 404 is a structural suffix miss. All other transport, page, schema, identity, or
 * five-level validation failures terminate the code for this producer round. No 9090 request
 * path injects or calls this client.</p>
 */
@Component
public class TwQuoteDetailFetchClient {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    /** Across a timed-out round and its successor, no more than four real outbound reads may run. */
    private static final Semaphore OUTBOUND_PROBE_SLOTS = new Semaphore(4, true);
    /**
     * The outer probe thread installs its lease here so the JDK body-reader and close tasks can
     * keep the physical-outbound slot while they are winding down after the caller's deadline.
     */
    private static final ThreadLocal<OutboundProbeLease> ACTIVE_PROBE_LEASE = new ThreadLocal<>();
    private static final Pattern TAIWAN_CODE = Pattern.compile("^[0-9]{4,6}[A-Z]?$");
    private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Pattern SIGNED_DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final String MARKER = "\"quote\":{\"data\":";
    private static final String MARKET = "台股";
    private static final String SOURCE = "YAHOO_TW";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 AssetManagementYahooOrderBook/1.0";

    private final Transport transport;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration probeTimeout;

    @Autowired
    public TwQuoteDetailFetchClient(ObjectMapper mapper) {
        this(new JdkTransport(), mapper, Clock.systemUTC(), PROBE_TIMEOUT);
    }

    TwQuoteDetailFetchClient(Transport transport, ObjectMapper mapper, Clock clock) {
        this(transport, mapper, clock, PROBE_TIMEOUT);
    }

    TwQuoteDetailFetchClient(Transport transport, ObjectMapper mapper, Clock clock, Duration probeTimeout) {
        this.transport = transport;
        this.mapper = mapper;
        this.clock = clock;
        if (probeTimeout == null || probeTimeout.isZero() || probeTimeout.isNegative()) {
            throw new IllegalArgumentException("probeTimeout must be positive");
        }
        this.probeTimeout = probeTimeout;
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
                                    BigDecimal outerPercent, Long bidTotalLots, Long askTotalLots,
                                    List<OrderBookLevel> levels) {}

    /** Immutable result for one suffix request; only FOUND carries a snapshot. */
    public record YahooProbeOutcome(Kind kind, QuoteDetailResult snapshot) {
        public enum Kind { FOUND, STRUCTURAL_MISS, TRANSIENT_OR_INVALID }

        public static YahooProbeOutcome found(QuoteDetailResult snapshot) {
            return new YahooProbeOutcome(Kind.FOUND, snapshot);
        }

        public static YahooProbeOutcome structuralMiss() {
            return new YahooProbeOutcome(Kind.STRUCTURAL_MISS, null);
        }

        public static YahooProbeOutcome transientOrInvalid() {
            return new YahooProbeOutcome(Kind.TRANSIENT_OR_INVALID, null);
        }
    }

    /** Probe a {@code .TW} or {@code .TWO} URL. Invalid input makes no network request. */
    public YahooProbeOutcome probe(String rawCode, String suffix) {
        String code = normalizeTaiwanCode(rawCode);
        if (code == null || !("TW".equals(suffix) || "TWO".equals(suffix))) {
            return YahooProbeOutcome.transientOrInvalid();
        }
        final RawResponse response;
        response = boundedTransportGet(uri(code, suffix));
        if (response == null) return YahooProbeOutcome.transientOrInvalid();
        if (response.statusCode() == 404) return YahooProbeOutcome.structuralMiss();
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.bodyTooLarge()
                || response.body() == null || response.body().length > MAX_BODY_BYTES) {
            return YahooProbeOutcome.transientOrInvalid();
        }
        try {
            String object = extractQuoteData(decodeUtf8(response.body()));
            if (object == null) return YahooProbeOutcome.transientOrInvalid();
            QuoteDetailResult snapshot = mapStrict(code, mapper.readTree(object));
            return snapshot == null ? YahooProbeOutcome.transientOrInvalid() : YahooProbeOutcome.found(snapshot);
        } catch (Exception ignored) {
            return YahooProbeOutcome.transientOrInvalid();
        }
    }

    public YahooProbeOutcome probeTw(String code) {
        return probe(code, "TW");
    }

    public YahooProbeOutcome probeTwo(String code) {
        return probe(code, "TWO");
    }

    /**
     * Makes the two-second boundary cover the complete transport operation, including a slow body.
     * A task that fails to stop after cancellation keeps its shared slot until it really returns, so
     * a following fallback round cannot create a fifth outbound Yahoo read.
     */
    private RawResponse boundedTransportGet(URI endpoint) {
        if (!OUTBOUND_PROBE_SLOTS.tryAcquire()) return null;
        OutboundProbeLease lease = new OutboundProbeLease();
        FutureTask<RawResponse> task = new FutureTask<>(() -> transport.get(endpoint, probeTimeout));
        try {
            Thread.ofVirtual().name("tw-yahoo-order-book-transport-", 0).start(() -> {
                ACTIVE_PROBE_LEASE.set(lease);
                try {
                    task.run();
                } finally {
                    ACTIVE_PROBE_LEASE.remove();
                    lease.completed();
                }
            });
        } catch (RuntimeException startFailure) {
            lease.completed();
            return null;
        }
        try {
            return task.get(probeTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            return null;
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failure) {
            return null;
        }
    }

    /** Compatibility façade for producer callers; only a .TW 404 may reach .TWO. */
    @Deprecated(forRemoval = false)
    public QuoteDetailResult fetch(String code, String market) {
        if (!MARKET.equals(market) || normalizeTaiwanCode(code) == null) return unsupported(code, market);
        YahooProbeOutcome tw = probeTw(code);
        if (tw.kind() == YahooProbeOutcome.Kind.FOUND) return tw.snapshot();
        if (tw.kind() == YahooProbeOutcome.Kind.STRUCTURAL_MISS) {
            YahooProbeOutcome two = probeTwo(code);
            if (two.kind() == YahooProbeOutcome.Kind.FOUND) return two.snapshot();
        }
        return unavailable(code, market);
    }

    static String extractQuoteData(String html) {
        if (html == null) return null;
        int marker = html.indexOf(MARKER);
        if (marker < 0 || html.indexOf(MARKER, marker + MARKER.length()) >= 0) return null;
        int start = marker + MARKER.length();
        if (start >= html.length() || html.charAt(start) != '{') return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return html.substring(start, i + 1);
            }
        }
        return null;
    }

    private QuoteDetailResult mapStrict(String requestedCode, JsonNode data) {
        if (data == null || !data.isObject()
                || !requestedCode.equals(text(data, "systexId"))
                || !"TWD".equals(text(data, "currency"))
                || !("TAI".equals(text(data, "exchange")) || "TWO".equals(text(data, "exchange")))
                || !"open".equalsIgnoreCase(text(data, "marketStatus"))) {
            return null;
        }
        String name = nonBlank(text(data, "symbolName"));
        BigDecimal price = positiveRaw(data.get("price"));
        BigDecimal previousClose = positiveRaw(data.get("regularMarketPreviousClose"));
        Instant sourceTime = instant(text(data, "regularMarketTime"));
        Instant now = clock.instant();
        if (name == null || name.equalsIgnoreCase(requestedCode) || price == null || previousClose == null
                || sourceTime == null || !microsecond(sourceTime) || sourceTime.isAfter(now)
                || !sourceTime.atZone(TAIPEI).toLocalDate().equals(now.atZone(TAIPEI).toLocalDate())) {
            return null;
        }
        List<OrderBookLevel> levels = strictLevels(data.get("orderbook"));
        if (levels == null) return null;

        BigDecimal open = positiveRaw(data.get("regularMarketOpen"));
        BigDecimal high = positiveRaw(data.get("regularMarketDayHigh"));
        BigDecimal low = positiveRaw(data.get("regularMarketDayLow"));
        if (high != null && low != null && high.compareTo(low) < 0) return null;
        BigDecimal change = signedRaw(data.get("change"));
        BigDecimal changePercent = percent(data.get("changePercent"));
        BigDecimal amplitude = high != null && low != null ? percentage(high.subtract(low), previousClose) : null;
        Long inner = optionalNonNegativeLong(data.get("inMarket"));
        Long outer = optionalNonNegativeLong(data.get("outMarket"));
        return new QuoteDetailResult(
                requestedCode, name, MARKET, true, true, SOURCE, null,
                sourceTime, now, "OPEN", price, previousClose, open, high, low,
                positiveDecimal(data.get("avgPrice")), change, changePercent,
                divide100(optionalDecimal(data.get("turnoverM"))), optionalNonNegativeLong(data.get("volumeK")),
                optionalNonNegativeLong(data.get("previousVolumeK")), amplitude, inner, outer,
                sharePercentage(inner, outer, true), sharePercentage(inner, outer, false),
                total(levels, true), total(levels, false), List.copyOf(levels));
    }

    private static List<OrderBookLevel> strictLevels(JsonNode orderbook) {
        if (orderbook == null || !orderbook.isArray() || orderbook.size() != 5) return null;
        List<OrderBookLevel> levels = new ArrayList<>(5);
        Set<BigDecimal> bids = new HashSet<>();
        Set<BigDecimal> asks = new HashSet<>();
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        for (int index = 0; index < 5; index++) {
            JsonNode row = orderbook.get(index);
            BigDecimal bid = positiveDecimal(field(row, "bid"));
            BigDecimal ask = positiveDecimal(field(row, "ask"));
            Long bidLots = positiveLong(field(row, "bidVolK"));
            Long askLots = positiveLong(field(row, "askVolK"));
            if (bid == null || ask == null || bidLots == null || askLots == null
                    || !bids.add(bid.stripTrailingZeros()) || !asks.add(ask.stripTrailingZeros())
                    || (previousBid != null && previousBid.compareTo(bid) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(ask) >= 0)) {
                return null;
            }
            levels.add(new OrderBookLevel(index + 1, bid, bidLots, ask, askLots));
            previousBid = bid;
            previousAsk = ask;
        }
        return levels;
    }

    private static URI uri(String code, String suffix) {
        return URI.create("https://tw.stock.yahoo.com/quote/" + code + "." + suffix);
    }

    private static String normalizeTaiwanCode(String rawCode) {
        if (rawCode == null) return null;
        String code = rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        return TAIWAN_CODE.matcher(code).matches() && !"0000".equals(code) ? code : null;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = field(object, field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static JsonNode field(JsonNode object, String field) {
        return object == null || !object.isObject() ? null : object.get(field);
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static Instant instant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean microsecond(Instant instant) {
        return instant.getNano() % 1_000 == 0;
    }

    private static BigDecimal positiveRaw(JsonNode value) {
        return value == null || !value.isObject() ? null : positiveDecimal(value.get("raw"));
    }

    private static BigDecimal signedRaw(JsonNode value) {
        return value == null || !value.isObject() ? null : signedDecimal(value.get("raw"));
    }

    private static BigDecimal positiveDecimal(JsonNode value) {
        BigDecimal decimal = optionalDecimal(value);
        return decimal != null && decimal.signum() > 0 ? decimal : null;
    }

    private static BigDecimal optionalDecimal(JsonNode value) {
        return decimal(value, DECIMAL);
    }

    private static BigDecimal signedDecimal(JsonNode value) {
        return decimal(value, SIGNED_DECIMAL);
    }

    private static BigDecimal decimal(JsonNode value, Pattern syntax) {
        if (value == null || value.isNull() || !(value.isTextual() || value.isNumber())) return null;
        return decimal(value.asText(), syntax);
    }

    private static BigDecimal decimal(String raw, Pattern syntax) {
        if (raw == null || !syntax.matcher(raw).matches()) return null;
        try {
            BigDecimal decimal = new BigDecimal(raw);
            return decimal.precision() <= 20 && decimal.scale() >= 0 && decimal.scale() <= 10 ? decimal : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long positiveLong(JsonNode value) {
        Long number = optionalNonNegativeLong(value);
        return number != null && number > 0 ? number : null;
    }

    private static Long optionalNonNegativeLong(JsonNode value) {
        BigDecimal decimal = optionalDecimal(value);
        try {
            return decimal != null && decimal.signum() >= 0 && decimal.stripTrailingZeros().scale() <= 0
                    ? decimal.longValueExact() : null;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static BigDecimal percent(JsonNode value) {
        if (value == null || value.isNull()) return null;
        String raw = value.asText();
        if (raw == null) return null;
        if (raw.endsWith("%")) raw = raw.substring(0, raw.length() - 1);
        return decimal(raw, SIGNED_DECIMAL);
    }

    private static BigDecimal divide100(BigDecimal value) {
        return value == null ? null : value.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() <= 0 ? null
                : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sharePercentage(Long inner, Long outer, boolean useInner) {
        if (inner == null || outer == null) return null;
        try {
            long total = Math.addExact(inner, outer);
            if (total <= 0) return null;
            BigDecimal innerPercent = BigDecimal.valueOf(inner).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            return useInner ? innerPercent : BigDecimal.valueOf(100).setScale(2).subtract(innerPercent);
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static Long total(List<OrderBookLevel> levels, boolean bid) {
        try {
            long total = 0;
            for (OrderBookLevel level : levels) total = Math.addExact(total,
                    bid ? level.bidVolumeLots() : level.askVolumeLots());
            return total;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static QuoteDetailResult unsupported(String code, String market) {
        return empty(code, market, false, "此市場不支援行情五檔");
    }

    private static QuoteDetailResult unavailable(String code, String market) {
        return empty(code, market, true, "暫時無法取得行情五檔");
    }

    private static QuoteDetailResult empty(String code, String market, boolean supported, String message) {
        return new QuoteDetailResult(code, null, market, supported, false, null, message,
                null, null, "UNKNOWN", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of());
    }

    record RawResponse(int statusCode, byte[] body, boolean bodyTooLarge) {}

    @FunctionalInterface
    interface Transport {
        RawResponse get(URI endpoint, Duration timeout) throws Exception;
    }

    static final class JdkTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(PROBE_TIMEOUT).build();

        @Override
        public RawResponse get(URI endpoint, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("User-Agent", UA)
                    .header("Accept-Encoding", "identity").GET().build();
            long deadlineNanos = deadlineAfter(timeout);
            var responseFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                response = responseFuture.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeoutFailure) {
                responseFuture.cancel(true);
                throw timeoutFailure;
            } catch (InterruptedException interrupted) {
                responseFuture.cancel(true);
                throw interrupted;
            } catch (ExecutionException failure) {
                throw new IOException("Yahoo response headers unavailable", failure.getCause());
            }
            InputStream body = response.body();
            // A non-2xx outcome never needs its potentially slow HTML body: 404 is structural
            // while every other non-2xx is transient at the parser boundary. Close asynchronously
            // so the caller deadline never waits for a stalled peer during resource teardown.
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeAsynchronously(body, ACTIVE_PROBE_LEASE.get());
                return new RawResponse(response.statusCode(), new byte[0], false);
            }
            byte[] bytes = readAtMost(body, MAX_BODY_BYTES, deadlineNanos);
            closeAsynchronously(body, ACTIVE_PROBE_LEASE.get());
            return new RawResponse(response.statusCode(), bytes, bytes == null);
        }

        /** Package-visible deterministic seam proving that a slow body is closed at its deadline. */
        static byte[] readAtMost(InputStream input, int maximum, Duration timeout) throws Exception {
            return readAtMost(input, maximum, deadlineAfter(timeout));
        }

        private static byte[] readAtMost(InputStream input, int maximum, long deadlineNanos) throws Exception {
            if (input == null) return null;
            OutboundProbeLease lease = ACTIVE_PROBE_LEASE.get();
            if (lease != null && !lease.childStarted()) {
                throw new IOException("Yahoo probe slot was closed before body reading started");
            }
            FutureTask<byte[]> reader = new FutureTask<>(() -> readAtMostUnbounded(input, maximum));
            Thread readerThread;
            try {
                readerThread = Thread.ofVirtual().name("tw-yahoo-order-book-body-", 0).start(() -> {
                    try {
                        reader.run();
                    } finally {
                        if (lease != null) lease.completed();
                    }
                });
            } catch (RuntimeException startFailure) {
                if (lease != null) lease.completed();
                throw startFailure;
            }
            try {
                return reader.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                cancelReaderAndClose(input, reader, readerThread, lease);
                throw timeout;
            } catch (InterruptedException interrupted) {
                cancelReaderAndClose(input, reader, readerThread, lease);
                throw interrupted;
            } catch (ExecutionException failure) {
                cancelReaderAndClose(input, reader, readerThread, lease);
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw new IOException("Yahoo body reader failed", cause);
            }
        }

        private static byte[] readAtMostUnbounded(InputStream input, int maximum) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8 * 1024));
            byte[] buffer = new byte[8 * 1024];
            int read;
            while (true) {
                int remaining = maximum - output.size();
                // Read at most one byte beyond the accepted cap.  That makes the 2 MiB body
                // limit physical as well as retained-memory bounded, while still distinguishing
                // an exactly-2-MiB EOF from a larger response.
                int requested = Math.min(buffer.length, remaining == 0 ? 1 : remaining + 1);
                read = input.read(buffer, 0, requested);
                if (read == -1) break;
                if (read > remaining) return null;
                if (read == 0) continue;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }

        private static long deadlineAfter(Duration timeout) {
            try {
                return Math.addExact(System.nanoTime(), timeout.toNanos());
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        private static long remainingNanos(long deadlineNanos) throws TimeoutException {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Yahoo probe deadline elapsed");
            return remaining;
        }

        /**
         * Cancellation must not wait for an adversarial stream's {@code close()} or {@code read()}.
         * Both outstanding tasks retain the shared outbound lease until their actual completion.
         */
        private static void cancelReaderAndClose(InputStream input, FutureTask<byte[]> reader,
                                                 Thread readerThread, OutboundProbeLease lease) {
            reader.cancel(true);
            readerThread.interrupt();
            closeAsynchronously(input, lease);
        }

        /**
         * Resource cleanup intentionally runs outside the caller's deadline. If it stalls, its
         * lease keeps one of the four global physical-outbound slots occupied, rather than letting
         * a successor round exceed the cap.
         */
        private static void closeAsynchronously(InputStream input, OutboundProbeLease lease) {
            if (input == null) return;
            if (lease != null && !lease.childStarted()) return;
            try {
                Thread.ofVirtual().name("tw-yahoo-order-book-close-", 0).start(() -> {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                        // A failed close still ends this cleanup task; the peer is already unusable.
                    } finally {
                        if (lease != null) lease.completed();
                    }
                });
            } catch (RuntimeException startFailure) {
                if (lease != null) lease.completed();
            }
        }
    }

    /**
     * A permit is released only after the outer request plus every body-reader/close task has
     * stopped. This is intentionally independent of the round executor: cancelling a 9-second
     * round cannot allow a successor to create a fifth physical Yahoo request.
     */
    private static final class OutboundProbeLease {
        private int outstanding = 1;
        private boolean released;

        synchronized boolean childStarted() {
            if (released) return false;
            outstanding++;
            return true;
        }

        void completed() {
            boolean release = false;
            synchronized (this) {
                if (outstanding <= 0) return;
                outstanding--;
                if (outstanding == 0 && !released) {
                    released = true;
                    release = true;
                }
            }
            if (release) OUTBOUND_PROBE_SLOTS.release();
        }
    }
}
