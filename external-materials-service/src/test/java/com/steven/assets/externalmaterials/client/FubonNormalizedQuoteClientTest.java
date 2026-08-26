package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient.BatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FubonNormalizedQuoteClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-08-21T05:00:00Z"), ZoneOffset.UTC);
    private static final String PROVIDER_TIME = "2026-08-21T05:00:00Z";

    @TempDir Path tempDir;

    @Test
    void postsOnlyExactNormalizedEndpointAndMapsProviderTimeWithoutDoubleConversion() throws Exception {
        Path token = tokenFile();
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<String> sentToken = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        FubonNormalizedQuoteClient.Transport transport = (uri, secret, requestBody, timeout) -> {
            endpoint.set(uri);
            sentToken.set(secret);
            body.set(requestBody);
            assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(30));
            return new FubonNormalizedQuoteClient.RawResponse(200, successBody("2330"));
        };
        FubonNormalizedQuoteClient client = new FubonNormalizedQuoteClient(
                "http://adapter:8080", token.toString(), transport, NOW);

        var result = client.fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(BatchStatus.SUCCESS);
        assertThat(endpoint.get().toString())
                .isEqualTo("http://adapter:8080/internal/market-data/tw-quotes");
        assertThat(sentToken.get()).isEqualTo("test-shared-token");
        assertThat(MAPPER.readTree(body.get()).path("codes").get(0).asText()).isEqualTo("2330");
        var observation = result.observations().get(0);
        assertThat(observation.providerUpdatedAt()).isEqualTo(Instant.parse(PROVIDER_TIME));
        assertThat(observation.tradingDate().toString()).isEqualTo("2026-08-21");
        assertThat(observation.result().source()).isEqualTo("FUBON_INTRADAY");
        assertThat(observation.result().volume()).isEqualTo(Long.MAX_VALUE);
        assertThat(observation.result().price().toPlainString()).isEqualTo("9999999999.9999999999");
        assertThat(observation.result().openPrice().toPlainString()).isEqualTo("0.1");
    }

    @Test
    void missingEmptyUnreadableOrInvalidConfigIsMisconfiguredWithZeroHttp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FubonNormalizedQuoteClient.Transport transport = (uri, secret, body, timeout) -> {
            calls.incrementAndGet();
            throw new AssertionError("HTTP must not be called");
        };

        var missing = new FubonNormalizedQuoteClient(
                "http://adapter:8080", tempDir.resolve("missing").toString(), transport, NOW);
        assertThat(missing.fetch(List.of("2330")).status()).isEqualTo(BatchStatus.MISCONFIGURED);

        Path empty = tempDir.resolve("empty");
        Files.writeString(empty, "   ");
        var emptyClient = new FubonNormalizedQuoteClient(
                "http://adapter:8080", empty.toString(), transport, NOW);
        assertThat(emptyClient.fetch(List.of("2330")).status()).isEqualTo(BatchStatus.MISCONFIGURED);

        var invalidBase = new FubonNormalizedQuoteClient(
                "http://adapter:8080/forbidden", tokenFile().toString(), transport, NOW);
        assertThat(invalidBase.fetch(List.of("2330")).status()).isEqualTo(BatchStatus.MISCONFIGURED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void partialFailureKeepsValidSymbolAndRejectsBadSymbolLocally() throws Exception {
        Path token = tokenFile();
        ObjectNode root = responseRoot();
        root.withArray("quotes").add(successRow("2330"));
        ObjectNode failed = root.withArray("quotes").addObject();
        failed.put("stockCode", "2317");
        failed.put("status", "FAILURE");
        failed.put("reason", "TRIAL_QUOTE_REJECTED");
        failed.putNull("quote");
        var client = client(token, new FubonNormalizedQuoteClient.RawResponse(200, root.toString()));

        var result = client.fetch(List.of("2330", "2317"));

        assertThat(result.status()).isEqualTo(BatchStatus.PARTIAL_FAILURE);
        assertThat(result.observations()).hasSize(1);
        assertThat(result.rejected()).isEqualTo(1);
    }

    @Test
    void strictDecimalVolumeAndTimeMatrixRejectsEachMalformedSuccessRow() throws Exception {
        Path token = tokenFile();
        List<java.util.function.Consumer<ObjectNode>> corruptions = List.of(
                quote -> quote.put("actualPrice", 100.1),
                quote -> quote.put("actualPrice", "1E+2"),
                quote -> quote.put("actualPrice", "10000000000.0000000000"),
                quote -> quote.put("actualPrice", "0.00000000001"),
                quote -> quote.put("volume", -1),
                quote -> quote.put("volume", new java.math.BigInteger("9223372036854775808")),
                quote -> quote.put("tradingDate", "2026-08-20"),
                quote -> quote.put("updatedAt", "2026-08-21T05:00:31Z"),
                quote -> quote.put("source", "TWSE"),
                quote -> quote.put("closed", true));

        for (java.util.function.Consumer<ObjectNode> corruption : corruptions) {
            ObjectNode root = responseRoot();
            ObjectNode row = successRow("2330");
            corruption.accept((ObjectNode) row.get("quote"));
            root.withArray("quotes").add(row);
            var result = client(token, new FubonNormalizedQuoteClient.RawResponse(200, root.toString()))
                    .fetch(List.of("2330"));
            assertThat(result.status()).isEqualTo(BatchStatus.PARTIAL_FAILURE);
            assertThat(result.observations()).isEmpty();
        }
    }

    @Test
    void volumeLowerBoundZeroIsAcceptedAsValidObservation() throws Exception {
        Path token = tokenFile();
        ObjectNode root = responseRoot();
        ObjectNode row = successRow("2330");
        ((ObjectNode) row.get("quote")).put("volume", 0);
        root.withArray("quotes").add(row);

        var result = client(token, new FubonNormalizedQuoteClient.RawResponse(200, root.toString()))
                .fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(BatchStatus.SUCCESS);
        assertThat(result.observations()).hasSize(1);
        assertThat(result.observations().get(0).result().volume()).isEqualTo(0L);
    }

    @Test
    void completeOptionalBookUsesBookTimestampWithoutChangingActualPriceObservation() throws Exception {
        ObjectNode root = responseRoot();
        ObjectNode quote = (ObjectNode) successRow("2330").get("quote");
        quote.put("updatedAt", "2026-08-21T04:59:58Z");
        ObjectNode book = quote.putObject("orderBook");
        book.put("bookUpdatedAt", "2026-08-21T04:59:59.123456Z");
        book.put("averagePrice", "100.15");
        book.put("turnoverYi", "42.26");
        book.put("innerVolumeLots", 9);
        book.put("outerVolumeLots", 11);
        var levels = book.putArray("levels");
        for (int level = 1; level <= 5; level++) {
            ObjectNode row = levels.addObject();
            row.put("level", level);
            row.put("bidPrice", "100." + (6 - level));
            row.put("bidVolumeLots", level);
            row.put("askPrice", "101." + level);
            row.put("askVolumeLots", level + 10);
        }
        ObjectNode wrapper = root.withArray("quotes").addObject();
        wrapper.put("stockCode", "2330");
        wrapper.put("status", "SUCCESS");
        wrapper.putNull("reason");
        wrapper.set("quote", quote);

        var result = client(tokenFile(), new FubonNormalizedQuoteClient.RawResponse(200, root.toString()))
                .fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(BatchStatus.SUCCESS);
        assertThat(result.observations()).singleElement().satisfies(observation ->
                assertThat(observation.providerUpdatedAt()).isEqualTo(Instant.parse("2026-08-21T04:59:58Z")));
        assertThat(result.orderBooks()).containsOnlyKeys("2330");
        var snapshot = result.orderBooks().get("2330");
        assertThat(snapshot.source()).isEqualTo("FUBON_BOOKS");
        assertThat(snapshot.sourceTime()).isEqualTo(Instant.parse("2026-08-21T04:59:59.123456Z"));
        assertThat(snapshot.levels()).hasSize(5);
        assertThat(snapshot.bidTotalLots()).isEqualTo(15L);
        assertThat(snapshot.askTotalLots()).isEqualTo(65L);
    }

    @Test
    void malformedOptionalBookDoesNotRejectItsValidActualPrice() throws Exception {
        ObjectNode root = responseRoot();
        ObjectNode row = successRow("2330");
        ObjectNode book = ((ObjectNode) row.get("quote")).putObject("orderBook");
        book.put("bookUpdatedAt", "2026-08-21T05:00:00Z");
        ObjectNode half = book.putArray("levels").addObject();
        half.put("level", 1);
        half.put("bidPrice", "100");
        half.putNull("bidVolumeLots");
        half.putNull("askPrice");
        half.putNull("askVolumeLots");
        root.withArray("quotes").add(row);

        var result = client(tokenFile(), new FubonNormalizedQuoteClient.RawResponse(200, root.toString()))
                .fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(BatchStatus.SUCCESS);
        assertThat(result.observations()).hasSize(1);
        assertThat(result.orderBooks()).isEmpty();
    }

    @Test
    void zeroLotsOrUnorderedFubonBookIsExcludedWithoutRejectingActualPrice() throws Exception {
        for (boolean zeroLots : List.of(true, false)) {
            ObjectNode root = responseRoot();
            ObjectNode quote = (ObjectNode) successRow("2330").get("quote");
            ObjectNode book = quote.putObject("orderBook");
            book.put("bookUpdatedAt", "2026-08-21T05:00:00Z");
            var levels = book.putArray("levels");
            for (int level = 1; level <= 5; level++) {
                ObjectNode row = levels.addObject();
                row.put("level", level);
                row.put("bidPrice", zeroLots ? "100." + (6 - level) : "100");
                row.put("bidVolumeLots", zeroLots && level == 3 ? 0 : level);
                row.put("askPrice", "101." + level);
                row.put("askVolumeLots", level);
            }
            ObjectNode wrapper = root.withArray("quotes").addObject();
            wrapper.put("stockCode", "2330");
            wrapper.put("status", "SUCCESS");
            wrapper.set("quote", quote);

            var result = client(tokenFile(), new FubonNormalizedQuoteClient.RawResponse(200, root.toString()))
                    .fetch(List.of("2330"));

            assertThat(result.observations()).hasSize(1);
            assertThat(result.orderBooks()).isEmpty();
        }
    }

    @Test
    void futureOrWrongTaiwanDateBookIsExcludedWithoutRejectingActualPrice() throws Exception {
        for (String bookTime : List.of("2026-08-21T05:00:00.000001Z", "2026-08-20T05:00:00Z")) {
            ObjectNode root = responseRoot();
            ObjectNode quote = (ObjectNode) successRow("2330").get("quote");
            ObjectNode book = quote.putObject("orderBook");
            book.put("bookUpdatedAt", bookTime);
            var levels = book.putArray("levels");
            for (int level = 1; level <= 5; level++) {
                ObjectNode row = levels.addObject();
                row.put("level", level);
                row.put("bidPrice", "100." + (6 - level));
                row.put("bidVolumeLots", level);
                row.put("askPrice", "101." + level);
                row.put("askVolumeLots", level);
            }
            ObjectNode wrapper = root.withArray("quotes").addObject();
            wrapper.put("stockCode", "2330");
            wrapper.put("status", "SUCCESS");
            wrapper.set("quote", quote);

            var result = client(tokenFile(), new FubonNormalizedQuoteClient.RawResponse(200, root.toString()))
                    .fetch(List.of("2330"));

            assertThat(result.observations()).hasSize(1);
            assertThat(result.orderBooks()).isEmpty();
        }
    }

    @Test
    void non200MalformedCoverageAndDuplicateRowsRejectWholeBatch() throws Exception {
        Path token = tokenFile();
        assertThat(client(token, new FubonNormalizedQuoteClient.RawResponse(503, "{}"))
                .fetch(List.of("2330")).status()).isEqualTo(BatchStatus.SERVICE_UNAVAILABLE);
        assertThat(client(token, new FubonNormalizedQuoteClient.RawResponse(200, "not-json"))
                .fetch(List.of("2330")).status()).isEqualTo(BatchStatus.INVALID_RESPONSE);

        ObjectNode duplicate = responseRoot();
        duplicate.withArray("quotes").add(successRow("2330"));
        duplicate.withArray("quotes").add(successRow("2330"));
        assertThat(client(token, new FubonNormalizedQuoteClient.RawResponse(200, duplicate.toString()))
                .fetch(List.of("2330")).status()).isEqualTo(BatchStatus.INVALID_RESPONSE);
    }

    @Test
    void codeCountAndNormalizedDuplicatesFailBeforeHttp() throws Exception {
        Path token = tokenFile();
        AtomicInteger calls = new AtomicInteger();
        var client = new FubonNormalizedQuoteClient(
                "http://adapter:8080", token.toString(), (uri, secret, body, timeout) -> {
                    calls.incrementAndGet();
                    return new FubonNormalizedQuoteClient.RawResponse(200, "{}");
                }, NOW);

        assertThat(client.fetch(List.of("2330", " 2330 ")).status()).isEqualTo(BatchStatus.INVALID_REQUEST);
        assertThat(client.fetch(java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> String.format("%04d", i)).toList()).status())
                .isEqualTo(BatchStatus.INVALID_REQUEST);
        assertThat(calls).hasValue(0);
    }

    private FubonNormalizedQuoteClient client(
            Path token,
            FubonNormalizedQuoteClient.RawResponse response) {
        return new FubonNormalizedQuoteClient(
                "http://adapter:8080", token.toString(), (uri, secret, body, timeout) -> response, NOW);
    }

    private Path tokenFile() throws Exception {
        Path token = tempDir.resolve("internal-service-token");
        Files.writeString(token, "test-shared-token\n");
        return token;
    }

    private static String successBody(String code) throws Exception {
        ObjectNode root = responseRoot();
        root.withArray("quotes").add(successRow(code));
        return MAPPER.writeValueAsString(root);
    }

    private static ObjectNode responseRoot() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("batchId", "fixture-batch");
        root.putArray("quotes");
        return root;
    }

    private static ObjectNode successRow(String code) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("stockCode", code);
        row.put("status", "SUCCESS");
        row.putNull("reason");
        ObjectNode quote = row.putObject("quote");
        quote.put("stockCode", code);
        quote.put("stockName", "台積電");
        quote.put("market", "台股");
        quote.put("actualPrice", "9999999999.9999999999");
        quote.put("previousClose", "9999999999.0");
        quote.put("openPrice", "0.1");
        quote.put("highPrice", "9999999999.9999999999");
        quote.put("lowPrice", "0.1");
        quote.put("buyPrice", "9999999999.8");
        quote.put("sellPrice", "9999999999.9");
        quote.put("volume", Long.MAX_VALUE);
        quote.put("updatedAt", PROVIDER_TIME);
        quote.put("tradingDate", "2026-08-21");
        quote.put("source", "FUBON_INTRADAY");
        quote.put("closed", false);
        quote.put("quoteStatus", "LIVE");
        return row;
    }
}
