package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.FubonTaiexIndexEvent;
import com.steven.assets.externalmaterials.service.FubonTaiexIndexIngestionService;
import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * One active pull connection to the adapter's normalized internal SSE response.
 * It owns no broker SDK and sends no data to any Spring endpoint.
 */
@Slf4j
@Component
public class FubonTaiexIndexStreamClient implements SmartLifecycle {

    private static final String PATH = "/internal/market-data/taiex-index/stream";
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final Set<String> FIELDS = Set.of("symbol", "exchange", "type", "index", "time");
    private static final long[] BACKOFF_MILLIS = {250L, 500L, 1_000L, 2_000L, 5_000L};

    private final boolean fubonEnabled;
    private final boolean streamEnabled;
    private final String configuredSymbol;
    private final String baseUrl;
    private final String tokenPath;
    private final FubonTaiexIndexIngestionService ingestion;
    private final Transport transport;
    private final Sleeper sleeper;
    private final TokenReader tokenReader;
    private final ObjectMapper mapper;
    private final ExternalApiErrorLogWriter errorLogWriter;
    private final LifecycleFailureInterval failureInterval = new LifecycleFailureInterval();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<InputStream> activeBody = new AtomicReference<>();
    private volatile ExecutorService executor;

    @Autowired
    public FubonTaiexIndexStreamClient(
            @Value("${fubon.enabled:false}") boolean fubonEnabled,
            @Value("${fubon.taiex-index-stream.enabled:false}") boolean streamEnabled,
            @Value("${fubon.taiex-index-stream.symbol:}") String configuredSymbol,
            @Value("${fubon.base-url:http://fubon-broker-service:8080}") String baseUrl,
            @Value("${fubon.shared-token-path:/run/secrets/fubon/shared/internal-service-token}") String tokenPath,
            FubonTaiexIndexIngestionService ingestion, ExternalApiErrorLogWriter errorLogWriter) {
        this(fubonEnabled, streamEnabled, configuredSymbol, baseUrl, tokenPath, ingestion,
                new JdkTransport(), Thread::sleep, FubonSharedTokenReader::read, errorLogWriter);
    }

    FubonTaiexIndexStreamClient(
            boolean fubonEnabled,
            boolean streamEnabled,
            String configuredSymbol,
            String baseUrl,
            String tokenPath,
            FubonTaiexIndexIngestionService ingestion,
            Transport transport,
            Sleeper sleeper) {
        this(fubonEnabled, streamEnabled, configuredSymbol, baseUrl, tokenPath, ingestion,
                transport, sleeper, FubonSharedTokenReader::read, null);
    }

    FubonTaiexIndexStreamClient(
            boolean fubonEnabled,
            boolean streamEnabled,
            String configuredSymbol,
            String baseUrl,
            String tokenPath,
            FubonTaiexIndexIngestionService ingestion,
            Transport transport,
            Sleeper sleeper,
            TokenReader tokenReader) {
        this(fubonEnabled, streamEnabled, configuredSymbol, baseUrl, tokenPath, ingestion, transport, sleeper, tokenReader, null);
    }
    FubonTaiexIndexStreamClient(boolean fubonEnabled, boolean streamEnabled, String configuredSymbol, String baseUrl, String tokenPath, FubonTaiexIndexIngestionService ingestion, Transport transport, Sleeper sleeper, TokenReader tokenReader, ExternalApiErrorLogWriter errorLogWriter) {
        this.fubonEnabled = fubonEnabled;
        this.streamEnabled = streamEnabled;
        this.configuredSymbol = configuredSymbol == null ? "" : configuredSymbol.trim();
        this.baseUrl = baseUrl;
        this.tokenPath = tokenPath;
        this.ingestion = ingestion;
        this.transport = transport;
        this.sleeper = sleeper;
        this.tokenReader = tokenReader;
        this.mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        this.errorLogWriter = errorLogWriter;
    }

    @Override
    public synchronized void start() {
        // Non-secret gates intentionally come first: a disabled/invalid feature must not even inspect
        // the token mount, much less issue an internal HTTP GET or retry loop.
        if (!nonSecretGateOpen() || running.get()) return;
        String initialToken = tokenReader.read(tokenPath);
        if (initialToken == null) {
            log.warn("Fubon TAIEX stream is unavailable reason=MISCONFIGURED");
            return;
        }
        URI endpoint = endpointUri();
        if (endpoint == null) {
            log.warn("Fubon TAIEX stream is unavailable reason=INVALID_BASE_URL");
            return;
        }
        // Each lifecycle owns its own error de-duplication interval.  In particular, an old
        // cancelled loop must never observe or mutate a newly started loop's state.
        long version = generation.incrementAndGet();
        running.set(true);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> consumeLoop(endpoint, initialToken, version));
    }

    @Override
    public synchronized void stop() {
        generation.incrementAndGet();
        running.set(false);
        closeQuietly(activeBody.getAndSet(null));
        ExecutorService current = executor;
        executor = null;
        if (current != null) current.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private boolean nonSecretGateOpen() {
        return fubonEnabled && streamEnabled && SYMBOL.matcher(configuredSymbol).matches();
    }

    private boolean active(long version) {
        return running.get() && generation.get() == version;
    }

    private void consumeLoop(URI endpoint, String token, long version) {
        long failures = 0L;
        String currentToken = token;
        try {
            while (active(version)) {
                InputStream ownedBody = null;
                try (RawResponse response = transport.open(endpoint, currentToken)) {
                    if (!active(version)) return;
                    if (response == null || response.statusCode() != 200 || response.body() == null) {
                        recordFailure(version, new IllegalStateException("Fubon TAIEX stream rejected response"));
                        if (active(version)) log.warn("Fubon TAIEX stream response rejected reason=NON_200");
                    } else {
                        ownedBody = response.body();
                        activeBody.set(ownedBody);
                        if (!active(version)) {
                            activeBody.compareAndSet(ownedBody, null);
                            closeQuietly(ownedBody);
                            return;
                        }
                        consumeBody(ownedBody, version);
                        if (active(version)) recordFailure(version, new java.io.EOFException("Fubon TAIEX stream EOF"));
                    }
                } catch (Exception failure) {
                    if (active(version)) {
                        recordFailure(version, failure);
                        if (active(version)) log.warn("Fubon TAIEX stream disconnected reason=STREAM_UNAVAILABLE");
                    }
                } finally {
                    if (ownedBody != null) activeBody.compareAndSet(ownedBody, null);
                }
                if (!active(version)) return;
                long delay = BACKOFF_MILLIS[(int) Math.min(failures, BACKOFF_MILLIS.length - 1L)];
                failures++;
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!active(version)) return;
                // Token rotation is noticed only after the non-secret gate passed.  A missing/empty
                // token stops instead of issuing an unauthenticated request or a retry storm.
                currentToken = tokenReader.read(tokenPath);
                if (currentToken == null) {
                    log.warn("Fubon TAIEX stream is unavailable reason=MISCONFIGURED");
                    return;
                }
            }
        } finally {
            if (generation.get() == version) running.set(false);
        }
    }

    private void consumeBody(InputStream input, long version) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            Frame frame = new Frame();
            String line;
            while (active(version) && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(frame, version);
                    frame = new Frame();
                    continue;
                }
                frame.accept(line);
            }
        }
    }

    private void dispatch(Frame frame, long version) {
        // Heartbeats and unrelated SSE topics have no market-data loss semantics.  Once the
        // adapter identifies a TAIEX event, however, any incomplete or rejected shape is a
        // producer failure and must be surfaced once through the stream's failure latch.
        if (!active(version) || !frame.targetEvent()) return;
        if (!frame.complete()) {
            recordFailure(version, new IllegalStateException("Fubon TAIEX stream malformed target frame"));
            return;
        }
        try {
            JsonNode root = mapper.readTree(frame.data);
            if (root == null || !root.isObject() || !fieldNames(root).equals(FIELDS)) {
                recordFailure(version, new IllegalStateException("Fubon TAIEX stream target schema reject"));
                return;
            }
            JsonNode symbol = root.get("symbol");
            JsonNode exchange = root.get("exchange");
            JsonNode type = root.get("type");
            JsonNode index = root.get("index");
            JsonNode micros = root.get("time");
            if (!symbol.isTextual() || !configuredSymbol.equals(symbol.textValue())
                    || !exchange.isTextual() || !"TWSE".equals(exchange.textValue())
                    || !type.isTextual() || !"INDEX".equals(type.textValue())
                    || !index.isTextual() || !validCanonicalIndex(index.textValue())
                    || !micros.isIntegralNumber() || !micros.canConvertToLong() || micros.longValue() <= 0
                    || !Long.toString(micros.longValue()).equals(frame.id)) {
                recordFailure(version, new IllegalStateException("Fubon TAIEX stream target schema reject"));
                return;
            }
            if (active(version)) {
                ingestion.ingest(new FubonTaiexIndexEvent(symbol.textValue(), exchange.textValue(), type.textValue(),
                        index.textValue(), micros.longValue()));
                if (active(version)) failureInterval.reset(version);
            }
        } catch (Exception invalid) {
            if (active(version)) recordFailure(version, invalid);
            // A malformed frame is data loss, not a reason to accept a looser schema or leak content.
            return;
        }
    }

    private void recordFailure(long version, Throwable failure) {
        if (errorLogWriter != null && active(version) && failureInterval.claim(version) && active(version)) {
            errorLogWriter.record("FUBON_TAIEX_INDEX_STREAM", "加權指數串流", failure, Instant.now());
        }
    }

    private static boolean validCanonicalIndex(String value) {
        if (value == null || !DECIMAL.matcher(value).matches()) return false;
        try {
            java.math.BigDecimal decimal = new java.math.BigDecimal(value);
            return decimal.signum() > 0 && decimal.precision() <= 20
                    && decimal.scale() >= 0 && decimal.scale() <= 10;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
            return URI.create(base.toString().replaceAll("/$", "") + PATH);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // No data value is logged from a shutdown path.
        }
    }

    private static final class Frame {
        private String event;
        private String id;
        private String data;
        private boolean invalid;

        void accept(String line) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                invalid = true;
                return;
            }
            String field = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "event" -> {
                    if (event != null) invalid = true;
                    else event = value;
                }
                case "id" -> {
                    if (id != null) invalid = true;
                    else id = value;
                }
                case "data" -> {
                    if (data != null) invalid = true;
                    else data = value;
                }
                default -> invalid = true;
            }
        }

        boolean complete() {
            return !invalid && targetEvent() && id != null && !id.isBlank()
                    && data != null && !data.isBlank();
        }

        boolean targetEvent() {
            return "taiex-index".equals(event);
        }
    }

    record RawResponse(int statusCode, InputStream body) implements AutoCloseable {
        @Override
        public void close() {
            closeQuietly(body);
        }
    }

    @FunctionalInterface
    interface Transport {
        RawResponse open(URI endpoint, String token) throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface TokenReader {
        String read(String path);
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        @Override
        public RawResponse open(URI endpoint, String token) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Accept", "text/event-stream")
                    .header(TOKEN_HEADER, token)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new RawResponse(response.statusCode(), response.body());
        }
    }
}
