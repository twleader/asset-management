package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonMarketDataPort;
import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
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
    public static final Duration TECHNICAL_V2_TIMEOUT = Duration.ofSeconds(70);
    public static final Duration MARKET_DATA_V1_TIMEOUT = Duration.ofSeconds(8);
    private final FubonMarketConfigState config;
    private final MarketClock clock;
    private final Transport transport;
    private final ExternalApiErrorLogWriter errorLogWriter;

    @Autowired
    public FubonScheduledMarketClient(FubonMarketConfigState config, MarketClock clock,
                                      ExternalApiErrorLogWriter errorLogWriter) {
        this(config, clock, new JdkTransport(), errorLogWriter);
    }
    FubonScheduledMarketClient(FubonMarketConfigState config, MarketClock clock, Transport transport) {
        this(config, clock, transport, null);
    }
    FubonScheduledMarketClient(FubonMarketConfigState config, MarketClock clock, Transport transport,
                               ExternalApiErrorLogWriter errorLogWriter) {
        this.config = config; this.clock = clock; this.transport = transport; this.errorLogWriter = errorLogWriter;
    }
    @Override public DividendBatch dividends(List<String> symbols, LocalDate date) {
        validateSymbols(symbols, 2000, false);
        String body = post("FUBON_DIVIDENDS_READ", "股利資料查詢", "/internal/market-data/dividends/read",
                Map.of("symbols", symbols, "from", date.minusDays(320).toString(), "to", date.plusDays(45).toString()),
                8 * 1024 * 1024);
        try {
            DividendBatch result = FubonMarketJson.dividends(FubonMarketJson.parse(body), symbols, date, clock.instant());
            if (result.rows().stream().anyMatch(row -> "FAILED".equals(row.status())))
                brokerFailure("FUBON_DIVIDENDS_READ", "股利資料查詢", "FAILED_OUTCOME");
            return result;
        }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_DIVIDENDS_READ", "股利資料查詢", "DIVIDEND_SCHEMA_INVALID", invalid); }
    }
    @Override public TechnicalRead technical(String symbol, LocalDate date) {
        validateSymbols(List.of(symbol), 1, false);
        String body = post("FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "/internal/market-data/technical-indicators/read",
                Map.of("symbol", symbol, "from", date.minusDays(120).toString(), "to", date.toString()), 512 * 1024);
        try {
            TechnicalRead result = FubonMarketJson.technical(FubonMarketJson.parse(body), symbol, date, clock.instant());
            if (result.groups().values().stream().anyMatch(group -> !group.available() && !"NO_DATA".equals(group.reason())))
                brokerFailure("FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "FAILED_OUTCOME");
            return result;
        }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "TECHNICAL_SCHEMA_INVALID", invalid); }
    }
    @Override public TechnicalBundle technicalV2(String symbol, LocalDate date) {
        validateSymbols(List.of(symbol), 1, false);
        String body = post("FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "/internal/market-data/technical-indicators/read", Map.of("symbol", symbol),
                4 * 1024 * 1024, TECHNICAL_V2_TIMEOUT);
        try {
            TechnicalBundle result = FubonMarketJson.technicalV2(FubonMarketJson.parse(body), symbol, date, clock.instant());
            if (result.profiles().stream().anyMatch(profile -> !profile.available() && !"NO_DATA".equals(profile.reason())))
                brokerFailure("FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "FAILED_OUTCOME");
            return result;
        }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "TECHNICAL_SCHEMA_INVALID", invalid); }
    }
    @Override public StockBasicRead basic(String symbol, LocalDate date) {
        validateSymbols(List.of(symbol), 1, false);
        String body = postV1("FUBON_STOCK_BASIC_READ", "個股基本資料查詢", "/internal/market-data/stock-basic/read", Map.of("symbol", symbol), 256 * 1024);
        try { return FubonMarketJson.stockBasic(FubonMarketJson.parse(body), symbol, date, clock.instant()); }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_STOCK_BASIC_READ", "個股基本資料查詢", "STOCK_BASIC_SCHEMA_INVALID", invalid); }
    }
    @Override public IntradayCandlesRead candles(String symbol, LocalDate date) {
        validateSymbols(List.of(symbol), 1, false);
        String body = postV1("FUBON_INTRADAY_CANDLES_READ", "分鐘 K 線查詢", "/internal/market-data/intraday-candles/read", Map.of("symbol", symbol), 1024 * 1024);
        try { return FubonMarketJson.candles(FubonMarketJson.parse(body), symbol, date, clock.instant()); }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_INTRADAY_CANDLES_READ", "分鐘 K 線查詢", "INTRADAY_CANDLES_SCHEMA_INVALID", invalid); }
    }
    @Override public IntradayVolumesRead intradayVolumes(String symbol, LocalDate date) {
        validateSymbols(List.of(symbol), 1, false);
        String body = postV1("FUBON_INTRADAY_VOLUMES_READ", "個股當日分價量查詢",
                "/internal/market-data/intraday-volumes/read", Map.of("symbol", symbol), 2 * 1024 * 1024);
        try { return FubonMarketJson.intradayVolumes(FubonMarketJson.parse(body), symbol, date, clock.instant()); }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_INTRADAY_VOLUMES_READ", "個股當日分價量查詢", "INVALID_RESPONSE", invalid); }
    }
    @Override public HistoricalDailyCandlesRead historicalDailyCandles(String symbol, LocalDate from, LocalDate to) {
        validateSymbols(List.of(symbol), 1, false);
        if (from == null || to == null || from.isAfter(to) || from.plusDays(365).isBefore(to))
            throw new Unavailable("INVALID_REQUEST");
        String body = postV1("FUBON_HISTORICAL_DAILY_CANDLES_READ", "個股歷史日K線查詢",
                "/internal/market-data/historical-daily-candles/read",
                Map.of("symbol", symbol, "from", from.toString(), "to", to.toString()), 2 * 1024 * 1024);
        try { return FubonMarketJson.historicalDailyCandles(FubonMarketJson.parse(body), symbol, from, to, clock.instant()); }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_HISTORICAL_DAILY_CANDLES_READ", "個股歷史日K線查詢", "INVALID_RESPONSE", invalid); }
    }
    @Override public SubscriptionAck subscriptions(List<String> symbols) {
        validateSymbols(symbols, 300, true);
        String body = post("FUBON_STOCK_PUSH_SUBSCRIPTIONS", "個股推播訂閱", "/internal/market-data/stock-push/subscriptions", Map.of("symbols", symbols), 8 * 1024);
        try { return FubonMarketJson.subscription(FubonMarketJson.parse(body), symbols.size()); }
        catch (RuntimeException invalid) { throw schemaFailure("FUBON_STOCK_PUSH_SUBSCRIPTIONS", "個股推播訂閱", "SUBSCRIPTION_SCHEMA_INVALID", invalid); }
    }
    private void validateSymbols(List<String> symbols, int limit, boolean emptyAllowed) {
        if (symbols == null || (!emptyAllowed && symbols.isEmpty()) || symbols.size() > limit
                || symbols.stream().anyMatch(s -> !validSymbol(s)) || new HashSet<>(symbols).size() != symbols.size())
            throw new Unavailable("INVALID_REQUEST");
    }
    private String post(String operationKey, String apiName, String path, Map<String, ?> request, int limit) {
        return post(operationKey, apiName, path, request, limit, TIMEOUT);
    }
    private String post(String operationKey, String apiName, String path, Map<String, ?> request, int limit, Duration timeout) {
        FubonMarketConfigState.Snapshot access = config.snapshot();
        if (access.reason() != null) throw new Unavailable(access.reason(), true);
        boolean outboundStarted = false;
        try {
            String requestBody = FubonMarketJson.MAPPER.writeValueAsString(request);
            outboundStarted = true;
            RawResponse response = transport.post(access.endpoint(path), access.token(),
                    requestBody, limit, timeout);
            if (response.status() == 429) throw outboundFailure(operationKey, apiName, "RATE_LIMITED", true, response.status(), path);
            if (response.status() == 401 || response.status() == 403 || response.status() == 503)
                throw outboundFailure(operationKey, apiName, "UPSTREAM_UNAVAILABLE", true, response.status(), path);
            if (response.status() != 200 || response.body() == null || response.body().length > limit)
                throw outboundFailure(operationKey, apiName, "UPSTREAM_UNAVAILABLE", false, response.status(), path);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
        } catch (Unavailable failure) { throw failure; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("INTERRUPTED", true);
        } catch (Exception failure) {
            if (outboundStarted) record(operationKey, apiName, failure);
            throw new Unavailable("UPSTREAM_UNAVAILABLE");
        }
    }
    /** Task408/425 v1 routes have an exact root error body, unlike frozen older routes. */
    private String postV1(String operationKey, String apiName, String path, Map<String, ?> request, int limit) {
        FubonMarketConfigState.Snapshot access = config.snapshot();
        if (access.reason() != null) throw new Unavailable(access.reason(), true);
        boolean outboundStarted = false;
        try {
            String requestBody = FubonMarketJson.MAPPER.writeValueAsString(request);
            outboundStarted = true;
            RawResponse response = transport.post(access.endpoint(path), access.token(),
                    requestBody, limit, MARKET_DATA_V1_TIMEOUT);
            if (response.status() == 200 && response.body() != null && response.body().length <= limit)
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            String reason = v1Reason(response);
            if ("RATE_LIMITED".equals(reason) || "HISTORY_BUDGET_EXHAUSTED".equals(reason))
                throw outboundFailure(operationKey, apiName, reason, true, response.status(), path);
            if ("MISCONFIGURED".equals(reason)) throw outboundFailure(operationKey, apiName, "MISCONFIGURED", true, response.status(), path);
            if ("STALE_QUERY".equals(reason) || "SCHEMA_INVALID".equals(reason) || "INVALID_RESPONSE".equals(reason))
                throw outboundFailure(operationKey, apiName, "INVALID_RESPONSE", false, response.status(), path);
            throw outboundFailure(operationKey, apiName, "UPSTREAM_UNAVAILABLE", response.status() == 401 || response.status() == 403 || response.status() == 503, response.status(), path);
        } catch (Unavailable failure) { throw failure; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new Unavailable("INTERRUPTED", true);
        } catch (Exception failure) {
            if (outboundStarted) record(operationKey, apiName, failure);
            throw new Unavailable("UPSTREAM_UNAVAILABLE");
        }
    }
    private Unavailable schemaFailure(String operationKey, String apiName, String reason, RuntimeException failure) {
        record(operationKey, apiName, failure);
        return new Unavailable(reason);
    }
    private Unavailable outboundFailure(String operationKey, String apiName, String reason, boolean stopRun, int status, String path) {
        record(operationKey, apiName, new IllegalStateException("Fubon scheduled market boundary status=" + status + " path=" + path));
        return new Unavailable(reason, stopRun);
    }
    private void brokerFailure(String operationKey, String apiName, String reason) {
        record(operationKey, apiName, new IllegalStateException("Fubon scheduled market broker failure outcome=" + reason));
    }
    private void record(String operationKey, String apiName, Throwable failure) {
        if (errorLogWriter != null) errorLogWriter.record(operationKey, apiName, failure, clock.instant());
    }
    private static String v1Reason(RawResponse response) {
        if (response.body() == null || response.body().length > 4096) return null;
        try {
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            var root = FubonMarketJson.parse(body); FubonMarketJson.fields(root, Set.of("reason"));
            return FubonMarketJson.text(root.get("reason"));
        } catch (Exception invalid) { return null; }
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
