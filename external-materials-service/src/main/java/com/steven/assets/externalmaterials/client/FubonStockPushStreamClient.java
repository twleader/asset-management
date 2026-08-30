package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonMarketData;
import com.steven.assets.externalmaterials.service.FubonStockPushStream;
import com.steven.assets.externalmaterials.service.MarketClock;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Cancellable normalized stock SSE. No dependency on the TAIEX client, SDK, DB or Redis. */
@Component
public class FubonStockPushStreamClient implements FubonStockPushStream {
    private static final String PATH = "/internal/market-data/stock-push/stream";
    private static final int MAX_FRAME_BYTES = 16 * 1024;
    private static final long[] BACKOFF = {250, 500, 1000, 2000, 5000};
    private final String enabled;
    private final FubonMarketConfigState config;
    private final MarketClock clock;
    private final Transport transport;
    private final Sleeper sleeper;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<InputStream> activeBody = new AtomicReference<>();
    private volatile ExecutorService worker;
    private volatile boolean running;
    private volatile String state = "DISABLED";

    @Autowired
    public FubonStockPushStreamClient(@Value("${fubon.stock-push-enabled:false}") String enabled,
            FubonMarketConfigState config, MarketClock clock) {
        this(enabled, config, clock, new JdkTransport(), Thread::sleep);
    }
    FubonStockPushStreamClient(String enabled, FubonMarketConfigState config, MarketClock clock,
                              Transport transport, Sleeper sleeper) {
        this.enabled = enabled; this.config = config; this.clock = clock; this.transport = transport; this.sleeper = sleeper;
    }
    @Override public synchronized void start(Consumer<FubonMarketData.StockEvent> consumer) {
        if (running) return;
        String reason = FubonMarketData.featureReason(enabled, "STOCK_PUSH_DISABLED");
        if (reason != null) { state = reason; return; }
        var access = config.snapshot();
        if (access.reason() != null) { state = access.reason(); return; }
        long version = generation.incrementAndGet();
        running = true;
        worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        worker.submit(() -> consume(version, consumer));
    }
    @Override @PreDestroy public synchronized void stop() {
        running = false; generation.incrementAndGet();
        closeQuietly(activeBody.getAndSet(null));
        ExecutorService current = worker; worker = null;
        if (current != null) current.shutdownNow();
        state = "DISABLED";
    }
    @Override public boolean isRunning() { return running; }
    public String state() { return state; }
    private boolean active(long version) { return running && generation.get() == version; }

    private void consume(long version, Consumer<FubonMarketData.StockEvent> consumer) {
        int retries = 0;
        try {
            while (active(version)) {
                var access = config.snapshot();
                if (access.reason() != null) { state = access.reason(); return; }
                InputStream ownedBody = null;
                try (Response response = transport.open(access.endpoint(PATH), access.token())) {
                    if (!active(version)) return;
                    if (response == null || response.status() != 200 || response.body() == null
                            || response.contentType() == null || !response.contentType().startsWith("text/event-stream")) {
                        state = "RECONNECTING";
                    } else {
                        ownedBody = response.body();
                        activeBody.set(response.body());
                        if (!active(version)) { closeQuietly(activeBody.getAndSet(null)); return; }
                        state = "CONNECTED";
                        readFrames(response.body(), version, consumer);
                    }
                } catch (Exception failure) { if (active(version)) state = "RECONNECTING"; }
                finally { if (ownedBody != null) activeBody.compareAndSet(ownedBody, null); }
                if (!active(version)) return;
                try { sleeper.sleep(BACKOFF[Math.min(retries++, BACKOFF.length - 1)]); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
            }
        } finally { if (generation.get() == version) running = false; }
    }
    private void readFrames(InputStream input, long version, Consumer<FubonMarketData.StockEvent> consumer) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        Map<String, String> frame = new HashMap<>();
        boolean invalid = false;
        int bytes = 0;
        String line;
        while (active(version) && (line = line(buffered)) != null) {
            if (line.isEmpty()) {
                if (!invalid && frame.keySet().equals(Set.of("event", "id", "data"))
                        && "stock-price".equals(frame.get("event"))) {
                    try {
                        var event = FubonMarketJson.stock(FubonMarketJson.parse(frame.get("data")), frame.get("id"), clock.instant());
                        if (active(version)) consumer.accept(event);
                    } catch (RuntimeException badEvent) { if (active(version)) state = "INVALID_EVENT"; }
                }
                frame.clear(); invalid = false; bytes = 0; continue;
            }
            bytes += line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_FRAME_BYTES) throw new IOException("FRAME_TOO_LARGE");
            if (line.startsWith(":")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) { invalid = true; continue; }
            String key = line.substring(0, colon);
            String value = line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (!Set.of("event", "id", "data").contains(key) || frame.putIfAbsent(key, value) != null) invalid = true;
        }
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= MAX_FRAME_BYTES) throw new IOException("FRAME_TOO_LARGE");
            bytes.write(value);
        }
        if (value == -1 && bytes.size() == 0) return null;
        byte[] raw = bytes.toByteArray();
        int length = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw, 0, length)).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("INVALID_UTF8"); }
    }
    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) {}
    }
    record Response(int status, InputStream body, String contentType) implements AutoCloseable {
        @Override public void close() { closeQuietly(body); }
    }
    interface Transport { Response open(URI uri, String token) throws Exception; }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private static final class JdkTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        @Override public Response open(URI uri, String token) throws Exception {
            var request = HttpRequest.newBuilder(uri).header("X-Internal-Service-Token", token)
                    .header("Accept", "text/event-stream").GET().build();
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            try {
                var response = pending.get(10, TimeUnit.SECONDS);
                return new Response(response.statusCode(), response.body(), response.headers().firstValue("content-type").orElse(null));
            } finally { if (!pending.isDone()) pending.cancel(true); }
        }
    }
}
