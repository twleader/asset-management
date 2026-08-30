package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonMarketDataPort;
import com.steven.assets.externalmaterials.service.MarketClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Exact normalized adapter routes only. No account selector, readiness probe or vendor fallback. */
@Component
public class FubonScheduledMarketClient implements FubonMarketDataPort {
    public static final String TOKEN_HEADER = "X-Internal-Service-Token";
    public static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final FubonMarketConfigState config;
    private final MarketClock clock;
    private final Transport transport;

    @Autowired
    public FubonScheduledMarketClient(FubonMarketConfigState config, MarketClock clock) {
        this(config, clock, new JdkTransport());
    }
    FubonScheduledMarketClient(FubonMarketConfigState config, MarketClock clock, Transport transport) {
        this.config = config; this.clock = clock; this.transport = transport;
    }
    @Override public DividendBatch dividends(List<String> symbols, LocalDate date) {
        validateSymbols(symbols, 2000, false);
        String body = post("/internal/market-data/dividends/read",
                Map.of("symbols", symbols, "from", date.minusDays(320).toString(), "to", date.plusDays(45).toString()),
                8 * 1024 * 1024);
        try { return FubonMarketJson.dividends(FubonMarketJson.parse(body), symbols, date, clock.instant()); }
        catch (RuntimeException invalid) { throw new Unavailable("DIVIDEND_SCHEMA_INVALID"); }
    }
    @Override public TechnicalRead technical(String symbol, LocalDate date) {
        validateSymbols(List.of(symbol), 1, false);
        String body = post("/internal/market-data/technical-indicators/read",
                Map.of("symbol", symbol, "from", date.minusDays(120).toString(), "to", date.toString()), 512 * 1024);
        try { return FubonMarketJson.technical(FubonMarketJson.parse(body), symbol, date, clock.instant()); }
        catch (RuntimeException invalid) { throw new Unavailable("TECHNICAL_SCHEMA_INVALID"); }
    }
    @Override public SubscriptionAck subscriptions(List<String> symbols) {
        validateSymbols(symbols, 300, true);
        String body = post("/internal/market-data/stock-push/subscriptions", Map.of("symbols", symbols), 8 * 1024);
        try { return FubonMarketJson.subscription(FubonMarketJson.parse(body), symbols.size()); }
        catch (RuntimeException invalid) { throw new Unavailable("SUBSCRIPTION_SCHEMA_INVALID"); }
    }
    private void validateSymbols(List<String> symbols, int limit, boolean emptyAllowed) {
        if (symbols == null || (!emptyAllowed && symbols.isEmpty()) || symbols.size() > limit
                || symbols.stream().anyMatch(s -> !validSymbol(s)) || new HashSet<>(symbols).size() != symbols.size())
            throw new Unavailable("INVALID_REQUEST");
    }
    private String post(String path, Map<String, ?> request, int limit) {
        FubonMarketConfigState.Snapshot access = config.snapshot();
        if (access.reason() != null) throw new Unavailable(access.reason(), true);
        try {
            RawResponse response = transport.post(access.endpoint(path), access.token(),
                    FubonMarketJson.MAPPER.writeValueAsString(request), limit, TIMEOUT);
            if (response.status() == 429) throw new Unavailable("RATE_LIMITED", true);
            if (response.status() == 401 || response.status() == 403 || response.status() == 503)
                throw new Unavailable("UPSTREAM_UNAVAILABLE", true);
            if (response.status() != 200 || response.body() == null || response.body().length > limit)
                throw new Unavailable("UPSTREAM_UNAVAILABLE");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
        } catch (Unavailable failure) { throw failure; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("INTERRUPTED", true);
        } catch (Exception failure) { throw new Unavailable("UPSTREAM_UNAVAILABLE"); }
    }
    record RawResponse(int status, byte[] body) {}
    interface Transport {
        RawResponse post(URI uri, String token, String body, int limit, Duration timeout) throws Exception;
    }
    private static final class JdkTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public RawResponse post(URI uri, String token, String body, int limit, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header(TOKEN_HEADER, token)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> new LimitedBody(limit));
            try {
                HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new RawResponse(response.statusCode(), response.body());
            } finally { if (!pending.isDone()) pending.cancel(true); }
        }
    }
    /** Enforces the cap while reading, before an oversized body can be allocated. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private long received;
        private Flow.Subscription subscription;
        private boolean done;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > limit) {
                done = true; subscription.cancel(); delegate.onError(new IOException("RESPONSE_TOO_LARGE"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
}
