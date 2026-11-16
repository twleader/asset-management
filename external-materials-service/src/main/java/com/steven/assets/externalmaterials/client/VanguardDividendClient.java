package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendNormalization;
import com.steven.assets.externalmaterials.service.port.VanguardDividendSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Issuer-native US ETF distribution history. It never proves a future calendar complete. */
@Component
public final class VanguardDividendClient implements VanguardDividendSource {
    static final String PROVIDER = "VANGUARD_ISSUER";
    static final String BASE = "https://advisors.vanguard.com/investments/products/api/funds/";
    static final int BODY_LIMIT = 2 * 1024 * 1024;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    // The official distributions API has no currency field. USD is the closed US product contract,
    // not a claim that an absent source field independently proves currency.
    private record Product(String portId, String cusip, String currency) {}
    private static final Map<String, Product> PRODUCTS = Map.of(
            "VOO", new Product("0968", "922908363", "USD"),
            "VT", new Product("3141", "922042742", "USD"));
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final Transport transport;
    private final Clock clock;

    public VanguardDividendClient() { this(new JdkTransport(), Clock.systemUTC()); }
    VanguardDividendClient(Transport transport, Clock clock) { this.transport = transport; this.clock = clock; }
    @Override public boolean supports(String code) { return PRODUCTS.containsKey(code); }

    @Override public DividendFetchResult fetch(String code, LocalDate from, LocalDate to) {
        List<String> provenance = new ArrayList<>();
        try {
            Product product = PRODUCTS.get(code);
            if (product == null || from == null || to == null || from.isAfter(to)
                    || to.isAfter(LocalDate.now(clock.withZone(NEW_YORK))))
                throw new IllegalArgumentException("VANGUARD_HISTORY_SCOPE_INVALID");
            JsonNode identity = read(BASE + product.portId(), provenance);
            JsonNode profile = read(BASE + product.portId() + "/profile", provenance);
            require(identity.isObject() && product.portId().equals(identity.path("portId").asText())
                    && code.equals(identity.path("fundIdentifier").asText()) && identity.path("isETF").isBoolean()
                    && identity.path("isETF").booleanValue(), "VANGUARD_PRODUCT_IDENTITY_MISMATCH");
            require(profile.isObject() && product.cusip().equals(profile.path("cusip").asText())
                    && (code + ".IV").equals(profile.path("iovTicker").asText())
                    && profile.path("isETF").isBoolean() && profile.path("isETF").booleanValue(),
                    "VANGUARD_PROFILE_IDENTITY_MISMATCH");
            currency(identity, product); currency(profile, product);
            JsonNode splits = profile.path("fundSplits");
            require(splits.isArray(), "VANGUARD_SPLIT_SCOPE_UNKNOWN");
            for (JsonNode split : splits) {
                LocalDate effective = date(split, "fundSplitEffectiveDate");
                require(effective.isBefore(from) || effective.isAfter(to), "VANGUARD_SPLIT_AMOUNT_BASIS_UNVERIFIED");
            }
            JsonNode rows = read(BASE + product.portId()
                    + "/pricing/distributions?hasDistributionYield=false", provenance);
            require(rows.isArray(), "VANGUARD_DISTRIBUTIONS_SCHEMA_INVALID");
            Map<String, DividendEvent> unique = new LinkedHashMap<>();
            for (JsonNode row : rows) {
                require(row.isObject(), "VANGUARD_DISTRIBUTION_ROW_INVALID"); currency(row, product);
                String type = row.path("typeCode").asText();
                require(Set.of("INC", "CGST", "CGLT", "ROC").contains(type), "VANGUARD_DISTRIBUTION_TYPE_UNKNOWN");
                LocalDate ex = date(row, "exDividendDate"), pay = date(row, "payableDate");
                require(!pay.isBefore(ex), "VANGUARD_PAYMENT_BEFORE_EX_DATE");
                require(row.path("amount").isNumber(), "VANGUARD_AMOUNT_MISSING");
                BigDecimal amount = DividendNormalization.exact(row.path("amount").decimalValue());
                require(amount.signum() > 0, "VANGUARD_AMOUNT_NOT_POSITIVE");
                if (ex.isBefore(from) || ex.isAfter(to)) continue;
                String id = DividendNormalization.componentId("VANGUARD", code, ex.toString(), type);
                DividendEvent event = new DividendEvent(ex.getYear(), amount, BigDecimal.ZERO,
                        ex.toString(), null, pay.toString(), null, id);
                DividendEvent prior = unique.putIfAbsent(id, event);
                require(prior == null || prior.equals(event), "VANGUARD_COMPONENT_CONFLICT");
            }
            return new DividendFetchResult(PROVIDER, List.copyOf(unique.values()), FetchStatus.PARTIAL,
                    from, to, null, "發行人已返回歷史日期組；未證明未來45日完整或未分配事件缺席",
                    provenance, DividendNormalization.VERSION, DividendNormalization.NOMINAL,
                    !unique.isEmpty(), clock.instant(), SourceFailureKind.NONE);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DividendFetchResult(PROVIDER, List.of(), FetchStatus.FAILED, from, to, null,
                    "Vanguard issuer history unavailable: " + error.getClass().getSimpleName() + ": " + error.getMessage(),
                    provenance, DividendNormalization.VERSION, DividendNormalization.NOMINAL,
                    false, clock.instant(), error instanceof IllegalArgumentException
                            ? SourceFailureKind.SCHEMA_ERROR : SourceFailureKind.TRANSPORT_ERROR);
        }
    }

    private JsonNode read(String url, List<String> provenance) throws Exception {
        Document document = transport.get(URI.create(url));
        require(document != null && document.status() == 200 && document.body() != null, "VANGUARD_HTTP_UNAVAILABLE");
        byte[] bytes = document.body().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= BODY_LIMIT, "VANGUARD_BODY_TOO_LARGE");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        provenance.add(url + "#sha256=" + hash);
        return mapper.readTree(document.body());
    }
    private static void currency(JsonNode node, Product product) {
        for (String key : List.of("currency", "currencyCode", "quoteCurrency"))
            if (node.hasNonNull(key)) require(product.currency().equals(node.path(key).asText()), "VANGUARD_CURRENCY_CONFLICT");
    }
    private static LocalDate date(JsonNode row, String key) {
        require(row.path(key).isTextual(), "VANGUARD_DATE_MISSING:" + key);
        String value = row.path(key).asText(); LocalDate date = LocalDate.parse(value);
        require(date.toString().equals(value), "VANGUARD_DATE_NOT_ISO"); return date;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }

    record Document(int status, String body) {}
    interface Transport { Document get(URI uri) throws Exception; }
    private static final class JdkTransport implements Transport {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public Document get(URI uri) throws Exception {
            require("https".equals(uri.getScheme()) && "advisors.vanguard.com".equals(uri.getHost())
                    && uri.toString().startsWith(BASE), "VANGUARD_SOURCE_URL_INVALID");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").header("User-Agent", "Mozilla/5.0").GET().build();
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new LimitedBody());
            try {
                HttpResponse<byte[]> response = pending.get(15, TimeUnit.SECONDS);
                return new Document(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            } finally { if (!pending.isDone()) pending.cancel(true); }
        }
    }
    /** Caps both chunked and Content-Length-free bodies before allocation; cancels on overflow. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long count;
        private boolean done;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) count += buffer.remaining();
            if (count > BODY_LIMIT) { done = true; subscription.cancel(); delegate.onError(new IOException("VANGUARD_BODY_TOO_LARGE")); }
            else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
}
