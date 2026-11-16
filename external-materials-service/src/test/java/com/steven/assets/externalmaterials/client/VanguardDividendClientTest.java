package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendData.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendNormalization;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class VanguardDividendClientTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T16:30:00Z"), ZoneOffset.UTC);
    static final LocalDate FROM = LocalDate.of(2024, 11, 16), TO = LocalDate.of(2026, 11, 16);
    static final ObjectMapper J = new ObjectMapper();
    static String fixture(String code, String kind) throws Exception {
        try (var input = VanguardDividendClientTest.class.getResourceAsStream("/vanguard-dividends/" + code.toLowerCase() + "-" + kind + ".json")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static VanguardDividendClient source(String code, UnaryOperator<String> identity,
                                        UnaryOperator<String> profile, UnaryOperator<String> rows) {
        return new VanguardDividendClient(uri -> {
            String path = uri.getPath();
            String kind = path.endsWith("/profile") ? "profile" : path.endsWith("/distributions") ? "distributions" : "identity";
            String body = fixture(code, kind);
            body = (kind.equals("identity") ? identity : kind.equals("profile") ? profile : rows).apply(body);
            return new VanguardDividendClient.Document(200, body);
        }, CLOCK);
    }
    static String mutate(String json, java.util.function.Consumer<JsonNode> edit) {
        try { var value = J.readTree(json); edit.accept(value); return J.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    static final UnaryOperator<String> SAME = value -> value;

    @ParameterizedTest @ValueSource(strings = {"VOO", "VT"})
    void nativeFixturesPreserveExactAmountsDatesAndPartialContract(String code) throws Exception {
        var result = source(code, SAME, SAME, SAME).fetch(code, FROM, TO);
        assertThat(result.status()).isEqualTo(FetchStatus.PARTIAL);
        assertThat(result.dateGroupsComplete()).isTrue(); assertThat(result.complete()).isFalse();
        assertThat(result.normalizerVersion()).isEqualTo(2);
        assertThat(result.amountBasis()).isEqualTo(DividendNormalization.NOMINAL).isEqualTo("NOMINAL_PER_EX_DATE");
        assertThat(result.events()).hasSize(8);
        var latest = result.events().getFirst();
        assertThat(latest.cashDividend()).isEqualByComparingTo(code.equals("VOO") ? "1.8226" : "0.4084");
        assertThat(latest.exDividendDate()).isEqualTo(code.equals("VOO") ? "2026-09-28" : "2026-09-18");
        assertThat(latest.cashPaymentDate()).isEqualTo(code.equals("VOO") ? "2026-09-30" : "2026-09-22");
        assertThat(result.sourceUrls()).hasSize(3).allMatch(url -> url.matches("https://advisors\\.vanguard\\.com/.*#sha256=[0-9a-f]{64}"));
        JsonNode manifest;
        try (var input = getClass().getResourceAsStream("/vanguard-dividends/provenance.json")) { manifest = J.readTree(input); }
        for (String kind : List.of("identity", "profile", "distributions")) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture(code, kind).getBytes(StandardCharsets.UTF_8)));
            assertThat(hash).isEqualTo(manifest.path(code.toLowerCase() + "-" + kind + ".json").path("sha256").asText());
        }
        assertThat(source(code, SAME, SAME, SAME).fetch(code, LocalDate.of(2016, 1, 1), TO).events()).hasSize(40);
    }

    @Test void wrongIdentityCurrencyDatesTypeAndSplitAreRejectedRatherThanInvented() {
        List<UnaryOperator<String>> identityFailures = List.of(s -> s.replace("VOO", "VT"), s -> s.replace("0968", "3141"),
                s -> mutate(s, n -> ((ObjectNode)n).put("currency", "EUR")));
        for (var bad : identityFailures) assertThat(source("VOO", bad, SAME, SAME).fetch("VOO", FROM, TO).status()).isEqualTo(FetchStatus.FAILED);
        for (var bad : List.<UnaryOperator<String>>of(s -> s.replace("922908363", "922042742"), s -> s.replace("VOO.IV", "VT.IV"),
                s -> s.replace("2013-10-24", "2025-10-24")))
            assertThat(source("VOO", SAME, bad, SAME).fetch("VOO", FROM, TO).status()).isEqualTo(FetchStatus.FAILED);
        for (var bad : List.<UnaryOperator<String>>of(s -> s.replace("2026-09-30", "2026-02-30"),
                s -> s.replace("2026-09-30", "2026-09-01"), s -> s.replace("\"INC\"", "\"UNKNOWN\""),
                s -> s.replace("1.8226", "-1.8226"), s -> mutate(s, n -> ((ObjectNode)n.get(0)).remove("payableDate"))))
            assertThat(source("VOO", SAME, SAME, bad).fetch("VOO", FROM, TO).status()).isEqualTo(FetchStatus.FAILED);
    }

    @Test void sameComponentDeduplicatesConflictRejectsAndDifferentTypesRemainDistinct() {
        UnaryOperator<String> duplicate = s -> mutate(s, n -> ((ArrayNode)n).add(n.get(0).deepCopy()));
        assertThat(source("VOO", SAME, SAME, duplicate).fetch("VOO", FROM, TO).events()).hasSize(8);
        UnaryOperator<String> conflict = s -> mutate(s, n -> {
            ObjectNode copy = n.get(0).deepCopy(); copy.put("amount", 9); ((ArrayNode)n).add(copy);
        });
        assertThat(source("VOO", SAME, SAME, conflict).fetch("VOO", FROM, TO).status()).isEqualTo(FetchStatus.FAILED);
        UnaryOperator<String> special = s -> mutate(s, n -> {
            ObjectNode copy = n.get(0).deepCopy(); copy.put("typeCode", "CGST"); ((ArrayNode)n).add(copy);
        });
        assertThat(source("VOO", SAME, SAME, special).fetch("VOO", FROM, TO).events()).hasSize(9);
        var empty = source("VOO", SAME, SAME, s -> "[]").fetch("VOO", FROM, TO);
        assertThat(empty.status()).isEqualTo(FetchStatus.PARTIAL); assertThat(empty.dateGroupsComplete()).isFalse();
    }

    @Test void transportFailureScopeAndOversizeFailClosed() {
        var failed = new VanguardDividendClient(uri -> { throw new IOException("unavailable"); }, CLOCK);
        assertThat(failed.fetch("VOO", FROM, TO).failureKind()).isEqualTo(SourceFailureKind.TRANSPORT_ERROR);
        assertThat(failed.fetch("VOO", FROM, TO.plusDays(1)).status()).isEqualTo(FetchStatus.FAILED);
        var redirect = new VanguardDividendClient(uri -> new VanguardDividendClient.Document(302, "{}"), CLOCK);
        assertThat(redirect.fetch("VOO", FROM, TO).status()).isEqualTo(FetchStatus.FAILED);
        var oversized = new VanguardDividendClient(uri -> new VanguardDividendClient.Document(200, "x".repeat(VanguardDividendClient.BODY_LIMIT + 1)), CLOCK);
        assertThat(oversized.fetch("VOO", FROM, TO).status()).isEqualTo(FetchStatus.FAILED);
        var body = new VanguardDividendClient.LimitedBody(); var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription); body.onNext(List.of(ByteBuffer.allocate(VanguardDividendClient.BODY_LIMIT + 1)));
        verify(subscription).cancel(); assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    @SuppressWarnings("unchecked")
    @Test void issuerFailureIsRetainedWhileOriginalNasdaqFallbackStillWorks() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":{\"rCode\":200},\"data\":{\"dividends\":{\"rows\":[{\"exOrEffDate\":\"09/28/2026\",\"type\":\"Cash\",\"amount\":\"$1.823\",\"paymentDate\":\"09/30/2026\",\"currency\":\"USD\"}]}}}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var failed = new VanguardDividendClient(uri -> { throw new IOException("issuer unavailable"); }, CLOCK);
        var client = new DividendFetchClient("", null, CLOCK, http, false,
                new DividendFinMindTransport("", CLOCK, http), failed);
        var observations = client.fetchObservations("VOO", "美股", 2);
        assertThat(observations.getFirst().source()).isEqualTo("VANGUARD_ISSUER");
        assertThat(observations.getFirst().status()).isEqualTo(FetchStatus.FAILED);
        assertThat(observations).anySatisfy(row -> { assertThat(row.source()).isEqualTo("NASDAQ.stocks"); assertThat(row.events()).hasSize(1); });
        assertThat(client.fetch("VOO", "美股", 2).source()).isEqualTo("NASDAQ.stocks");
    }
}
